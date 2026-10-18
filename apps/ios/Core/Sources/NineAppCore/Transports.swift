import Foundation
import Network
import NineKit

/// One established link (control connection + input channel).
public final class ActiveLink: @unchecked Sendable {
    public let kind: LinkKind
    public let detail: String
    public let conn: ControlConnection
    public let channel: InputChannel
    init(kind: LinkKind, detail: String, conn: ControlConnection, channel: InputChannel) { self.kind = kind; self.detail = detail; self.conn = conn; self.channel = channel }
    public func close(sendBye: Bool) {
        if sendBye { channel.sendInput(.neutral, flags: 0); channel.bye(); conn.close() } else { conn.cancel() }
        channel.close()
    }
}

public enum LocalLinks {
    /// Classify the path a TCP connection used (iOS exposes interface types, not names, to apps).
    public static func classify(pathKinds: [String], remote: String) -> LinkKind {
        if remote.hasPrefix("172.20.10.") { return .hotspot } // iPhone Personal Hotspot subnet (Wi-Fi or USB)
        if pathKinds.contains("wiredEthernet") { return .ethernet }
        if pathKinds.contains("wifi") { return .lan }
        if pathKinds.contains("other") { return .usb }
        return .lan
    }
}

public enum Transports {
    /// IP binding: race every known address; the first completed handshake wins immediately and any
    /// later winners are closed.
    public static func ip(_ host: PairedHost, id: KeyPair, hello: ControllerHello, timeout: TimeInterval) async throws -> ControlConnection {
        let addrs = host.allAddrs
        guard !addrs.isEmpty else { throw ClientError.protocolError("No address known for \(host.name)") }
        let race = Race(count: addrs.count)
        return try await withCheckedThrowingContinuation { k in
            for a in addrs {
                Task {
                    do {
                        let c = try await ControlConnection.connect(host: a, port: host.port, identity: id, hello: hello, timeout: timeout)
                        if race.win() { k.resume(returning: c) } else { c.cancel() }
                    } catch {
                        if race.lose() { k.resume(throwing: error) }
                    }
                }
            }
        }
    }

    final class Race: @unchecked Sendable {
        private let lock = NSLock(); private var won = false; private var failures = 0; private let count: Int
        init(count: Int) { self.count = count }
        func win() -> Bool { lock.withLock { if won { return false }; won = true; return true } }
        /// True exactly once: when the last attempt fails and nobody won.
        func lose() -> Bool { lock.withLock { failures += 1; return !won && failures == count } }
    }

    public static func open(_ kind: LinkKind, host: PairedHost, id: KeyPair, hello: ControllerHello, ble: BlePeripheral, resume: Int?, profile: String?) async throws -> (ActiveLink, SessionInfo) {
        switch kind {
        case .stream:
            var last: Error = ClientError.timeout
            for a in host.allAddrs {
                do {
                    let sb = try await StreamBinding.connect(host: a, timeout: 1.5)
                    let c = try await ControlConnection.connect(over: sb.control, identity: id, hello: hello)
                    let info = try await c.open(resumeSlot: resume, profile: profile)
                    return (ActiveLink(kind: .stream, detail: "TCP \(a):\(LinkConst.streamTCPPort)", conn: c, channel: InputChannel(link: sb, info: info)), info)
                } catch { last = error }
            }
            throw last
        case .ble:
            if let r = ble.unavailableReason() { throw ClientError.protocolError(r) }
            let hint = stride(from: 0, to: 8, by: 2).compactMap { UInt8(host.id.dropFirst($0).prefix(2), radix: 16) }
            guard await ble.awaitHost(hint: hint, timeout: 8) else { throw ClientError.protocolError("No computer connected over Bluetooth within 8 s") }
            let c = try await ControlConnection.connect(over: ble.control, identity: id, hello: hello)
            let info = try await c.open(resumeSlot: resume, profile: profile)
            return (ActiveLink(kind: .ble, detail: "Bluetooth LE", conn: c, channel: InputChannel(link: ble, info: info)), info)
        default:
            let c = try await ip(host, id: id, hello: hello, timeout: 1.5)
            let info = try await c.open(resumeSlot: resume, profile: profile)
            let k = LocalLinks.classify(pathKinds: c.pathKinds, remote: c.remoteHost)
            return (ActiveLink(kind: k, detail: "\(c.remoteHost):\(host.port)", conn: c, channel: InputChannel(host: c.remoteHost, info: info)), info)
        }
    }
}
