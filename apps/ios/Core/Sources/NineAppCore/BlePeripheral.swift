import CoreBluetooth
import Foundation
import NineKit

/// BLE GATT binding, phone side (SPEC §8.3) with Core Bluetooth. The iPhone is the GATT server; the
/// computer is the central. iOS cannot advertise service data, so the host hint travels in the local
/// name (`C9-xxxxxxxx`). Apple limits: advertising data is reduced in the background, so the link is
/// offered only while the app is in the foreground (the controller screen keeps it there).
/// STATUS: compiles (macOS + iOS SDKs); NOT yet run between an iPhone and a host (UNVERIFIED).
public final class BlePeripheral: NSObject, CBPeripheralManagerDelegate, DatagramLink, @unchecked Sendable {
    public static let service = CBUUID(string: LinkConst.bleService)
    static let controlUp = CBUUID(string: LinkConst.bleControlUp)
    static let controlDown = CBUUID(string: LinkConst.bleControlDown)
    static let inputUp = CBUUID(string: LinkConst.bleInputUp)
    static let inputDown = CBUUID(string: LinkConst.bleInputDown)

    private var manager: CBPeripheralManager!
    private let queue = DispatchQueue(label: "c9.ble", qos: .userInteractive)
    private var cu: CBMutableCharacteristic!, iu: CBMutableCharacteristic!
    private var central: CBCentral?
    private var subscribed = Set<CBUUID>()
    private var hint: [UInt8] = []
    private var ready: CheckedContinuation<Bool, Never>?
    private var poweredOn: CheckedContinuation<CBManagerState, Never>?
    private var controlIn = Reassembler(max: 65535), inputIn = Reassembler(max: 256)
    private var controlSeg = Segmenter(), inputSeg = Segmenter()
    /// Pending notifications: control first, then ordered input messages (a new INPUT supersedes older queued INPUTs).
    private var controlQueue: [[UInt8]] = []
    private var inputQueue: [[UInt8]] = []
    private var inputSegments: [[UInt8]] = []
    public private(set) var control: MessageControlLink!
    public var onReceive: (@Sendable ([UInt8]) -> Void)?
    public var onDisconnect: (@Sendable () -> Void)?

    public override init() {
        super.init()
        control = MessageControlLink(send: { [weak self] f in self?.enqueueControl(f) }, onCancel: { [weak self] in self?.stop() })
        manager = CBPeripheralManager(delegate: self, queue: queue)
    }

    /// Why BLE can't be offered now, or nil.
    public func unavailableReason() -> String? {
        switch manager.state {
        case .poweredOn: return nil
        case .poweredOff: return "Bluetooth is off"
        case .unauthorized: return "Bluetooth permission not granted"
        case .unsupported: return "This device can't act as a Bluetooth LE peripheral"
        default: return nil // unknown/resetting: try
        }
    }

    private var mtu: Int { (central?.maximumUpdateValueLength ?? 20) + 3 }

    /// Advertise for `timeout` seconds; true once a host connected and subscribed to both notify characteristics.
    public func awaitHost(hint: [UInt8], timeout: TimeInterval) async -> Bool {
        if manager.state != .poweredOn {
            let st = await withCheckedContinuation { (k: CheckedContinuation<CBManagerState, Never>) in
                queue.async { if self.manager.state == .poweredOn { k.resume(returning: CBManagerState.poweredOn) } else { self.poweredOn = k } }
            }
            if st != CBManagerState.poweredOn { return false }
        }
        self.hint = hint
        return await withCheckedContinuation { k in
            queue.async {
                self.ready = k
                self.setUpService()
                self.manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.service], CBAdvertisementDataLocalNameKey: LinkConst.bleName(hint: hint)])
                self.queue.asyncAfter(deadline: .now() + timeout) { if let r = self.ready { self.ready = nil; self.manager.stopAdvertising(); r.resume(returning: false) } }
            }
        }
    }

    private func setUpService() {
        manager.removeAllServices()
        cu = CBMutableCharacteristic(type: Self.controlUp, properties: [.notify], value: nil, permissions: [.readable])
        iu = CBMutableCharacteristic(type: Self.inputUp, properties: [.notify], value: nil, permissions: [.readable])
        let cd = CBMutableCharacteristic(type: Self.controlDown, properties: [.write], value: nil, permissions: [.writeable])
        let idn = CBMutableCharacteristic(type: Self.inputDown, properties: [.writeWithoutResponse], value: nil, permissions: [.writeable])
        let svc = CBMutableService(type: Self.service, primary: true)
        svc.characteristics = [cu, cd, iu, idn]
        manager.add(svc)
    }

    public func stop() {
        queue.async {
            self.manager.stopAdvertising()
            self.manager.removeAllServices()
            self.central = nil
            self.control.finish()
        }
    }

    // MARK: delegate

    public func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        if let k = poweredOn, p.state != .unknown, p.state != .resetting { poweredOn = nil; k.resume(returning: p.state) }
        if p.state != .poweredOn, central != nil { central = nil; control.finish(ClientError.closed); onDisconnect?() }
    }

    public func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didSubscribeTo ch: CBCharacteristic) {
        if central == nil { central = c }
        guard c.identifier == central?.identifier else { return }
        subscribed.insert(ch.uuid)
        if subscribed.contains(Self.controlUp), subscribed.contains(Self.inputUp), let r = ready {
            ready = nil
            manager.stopAdvertising()
            r.resume(returning: true)
        }
    }

    public func peripheralManager(_ p: CBPeripheralManager, central c: CBCentral, didUnsubscribeFrom ch: CBCharacteristic) {
        guard c.identifier == central?.identifier else { return }
        subscribed.remove(ch.uuid)
        if subscribed.isEmpty { central = nil; control.finish(ClientError.closed); onDisconnect?() }
    }

    public func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.central.identifier == central?.identifier {
            guard let v = r.value.map(Array.init) else { continue }
            switch r.characteristic.uuid {
            case Self.controlDown: if let m = controlIn.push(v), !m.isEmpty { control.deliver(m) }
            case Self.inputDown: if let m = inputIn.push(v) { onReceive?(m) }
            default: break
            }
        }
        if let first = requests.first, first.characteristic.properties.contains(.write) { p.respond(to: first, withResult: .success) }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { pump() }

    // MARK: sending

    private func enqueueControl(_ frame: [UInt8]) {
        queue.async { self.controlQueue += self.controlSeg.segment(frame, attMtu: self.mtu); self.pump() }
    }

    public func send(_ d: [UInt8]) {
        queue.async {
            if d.count > 3, d[3] == PacketType.input.rawValue { self.inputQueue.removeAll { $0.count > 3 && $0[3] == PacketType.input.rawValue } }
            self.inputQueue.append(d)
            self.pump()
        }
    }

    /// Runs on `queue`. `updateValue` returns false when Core Bluetooth's queue is full; we resume in
    /// `peripheralManagerIsReady(toUpdateSubscribers:)`.
    private func pump() {
        guard let c = central else { return }
        while true {
            if let v = controlQueue.first {
                guard manager.updateValue(Data(v), for: cu, onSubscribedCentrals: [c]) else { return }
                controlQueue.removeFirst(); continue
            }
            if inputSegments.isEmpty, !inputQueue.isEmpty { inputSegments = inputSeg.segment(inputQueue.removeFirst(), attMtu: mtu) }
            if let v = inputSegments.first {
                guard manager.updateValue(Data(v), for: iu, onSubscribedCentrals: [c]) else { return }
                inputSegments.removeFirst(); continue
            }
            return
        }
    }

    public func close() { stop() }
}
