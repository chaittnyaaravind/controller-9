import Foundation
import Network
import NineKit
import Observation

public enum ConnState: Equatable, Sendable {
    case idle
    case connecting(String)
    case confirmCode(host: String, code: String)
    case connected(host: String, slot: Int, warning: String?)
    case reconnecting(host: String, attempt: Int, reason: String)
    case failed(String)
}

public struct LinkStats: Equatable, Sendable {
    public var rttP50: Double?, rttP95: Double?, sendHz = 0.0, sent: UInt64 = 0, sensorHz = 0.0, reconnects = 0, lastReconnectMs: Int?
    public init() {}
}

/// One connection to one host: pairing (QR / numeric comparison), pinned host identity, session,
/// sender thread (on change + heartbeat, ping 250 ms), rumble, and reconnect (dead after 1 s silence).
@MainActor @Observable
public final class ConnectionManager {
    public private(set) var state: ConnState = .idle
    public private(set) var stats = LinkStats()
    public var sensorHz = 0.0
    public var onRumble: (@Sendable (Int, Int, Int) -> Void)?

    private let store: FileStore
    private let deviceName: String
    private let appVersion: String
    private var want: PairedHost?
    private var lastSlot: Int?
    private var profileName: String?
    private var sessionTask: Task<Void, Never>?
    private var codeContinuation: CheckedContinuation<Bool, Never>?
    private var control: ControlConnection?
    private let pump = SenderPump()

    public init(store: FileStore, deviceName: String, appVersion: String) {
        self.store = store; self.deviceName = deviceName; self.appVersion = appVersion
    }

    private var hello: ControllerHello { ControllerHello(name: String(deviceName.prefix(40)), platform: "ios", app: appVersion) }

    public nonisolated func submit(_ s: InputState, _ f: UInt16) { pump.submit(s, f) }
    public func setPaused(_ p: Bool) { pump.setPaused(p) }
    public func setProfileName(_ n: String) { profileName = n }

    // MARK: pairing

    public func pair(qr: PairURI) {
        Task {
            state = .connecting(qr.name.isEmpty ? qr.addr : qr.name)
            do {
                let c = try await ControlConnection.connect(host: qr.addr, port: qr.port, identity: IdentityStore.keyPair(), hello: hello)
                defer { c.close() }
                try await c.pairQr(qr)
                connect(remember(c, port: qr.port))
            } catch { state = .failed(error.localizedDescription) }
        }
    }

    public func pairWithCode(endpoint: NWEndpoint, name: String) {
        Task {
            state = .connecting(name)
            do {
                let c = try await ControlConnection.connect(endpoint: endpoint, identity: IdentityStore.keyPair(), hello: hello)
                defer { c.close() }
                _ = try await c.pairCode { code in
                    await withCheckedContinuation { k in
                        Task { @MainActor in
                            self.codeContinuation = k
                            self.state = .confirmCode(host: c.host.name, code: Pairing.formatCode(code))
                        }
                    }
                }
                connect(remember(c, port: Self.port(of: endpoint)))
            } catch { state = .failed(error.localizedDescription) }
        }
    }

    public func confirmCode(_ matches: Bool) { codeContinuation?.resume(returning: matches); codeContinuation = nil }

    static func port(of ep: NWEndpoint) -> UInt16 { if case let .hostPort(_, p) = ep { return p.rawValue }; return defaultTCPPort }

    private func remember(_ c: ControlConnection, port: UInt16) -> PairedHost {
        let h = PairedHost(id: Pairing.hostId(c.hostStatic), name: c.host.name, pub: Data(c.hostStatic).base64EncodedString(), addr: c.remoteHost, port: port, pairedAt: now())
        store.upsertHost(h)
        return h
    }

    // MARK: session

    public func connect(_ host: PairedHost) {
        want = host
        var s = store.settings; s.lastHost = host.id; store.saveSettings(s)
        startSession(host, attempt: 0, reason: "")
    }

    public func disconnect() {
        want = nil
        sessionTask?.cancel()
        teardown(bye: true)
        state = .idle
    }

    private func restart(_ reason: String) {
        guard let h = want, case .connected = state else { return }
        teardown(bye: false)
        startSession(h, attempt: 1, reason: reason)
    }

    private func startSession(_ host: PairedHost, attempt: Int, reason: String) {
        sessionTask?.cancel()
        sessionTask = Task {
            var n = attempt
            let t0 = Date()
            while !Task.isCancelled, want == host {
                state = n == 0 ? .connecting(host.name) : .reconnecting(host: host.name, attempt: n, reason: reason)
                do {
                    try await openSession(host)
                    if n > 0 { stats.reconnects += 1; stats.lastReconnectMs = Int(Date().timeIntervalSince(t0) * 1000) }
                    return
                } catch ClientError.refused("not_paired") {
                    store.removeHost(host.id); want = nil; state = .failed("\(host.name) no longer knows this phone. Pair again."); return
                } catch ClientError.refused("slots_full") {
                    want = nil; state = .failed("\(host.name) already has four controllers connected."); return
                } catch let e as IdentityChanged {
                    want = nil; state = .failed(e.message); return
                } catch {
                    n += 1
                    state = .reconnecting(host: host.name, attempt: n, reason: error.localizedDescription)
                }
                try? await Task.sleep(nanoseconds: UInt64(min(100 << min(n, 4), 2000)) * 1_000_000)
            }
        }
    }

    struct IdentityChanged: Error { let message: String }

    private func openSession(_ host: PairedHost) async throws {
        let c = try await ControlConnection.connect(host: host.addr, port: host.port, identity: IdentityStore.keyPair(), hello: hello, timeout: 1.5)
        guard Data(c.hostStatic).base64EncodedString() == host.pub else {
            c.cancel()
            throw IdentityChanged(message: "The computer at \(host.addr) is not \(host.name) (its identity key changed). Pair again only if you trust it.")
        }
        let info = try await c.open(resumeSlot: lastSlot, profile: profileName)
        lastSlot = info.slot
        control = c
        let ch = InputChannel(host: c.remoteHost.isEmpty ? host.addr : c.remoteHost, info: info)
        let rumble = onRumble
        ch.onFeedback = { f in rumble?(Int(f.low), Int(f.high), Int(f.ms)) }
        var h = host; h.lastUsed = now(); store.upsertHost(h)
        state = .connected(host: c.host.name, slot: info.slot, warning: info.backendWarning)
        pump.start(ch) { [weak self] reason in Task { @MainActor in self?.restart(reason) } } stats: { [weak self] st in
            Task { @MainActor in
                guard let self else { return }
                self.stats.rttP50 = st.0; self.stats.rttP95 = st.1; self.stats.sendHz = st.2; self.stats.sent = st.3
                self.stats.sensorHz = self.sensorHz
                try? await c.send(.stats(rttP50Ms: st.0.map(Float.init), rttP95Ms: st.1.map(Float.init), sensorHz: self.sensorHz > 0 ? Float(self.sensorHz) : nil, sendHz: Float(st.2), batteryPct: nil))
            }
        }
        // Control reader
        Task { [weak self] in
            do {
                while true {
                    switch try await c.recv() {
                    case let .rumble(l, h, ms): rumble?(l, h, ms)
                    case let .error(code, msg) where code == "backend_unavailable":
                        await MainActor.run { if case let .connected(hn, s, _) = self?.state { self?.state = .connected(host: hn, slot: s, warning: msg ?? code) } }
                    case let .bye(reason):
                        await MainActor.run {
                            if reason == "unpaired" { if let w = self?.want { self?.store.removeHost(w.id) }; self?.want = nil; self?.teardown(bye: false); self?.state = .failed("This phone was removed on the computer.") }
                            else { self?.restart(reason ?? "host closed") }
                        }
                        return
                    default: break
                    }
                }
            } catch { await MainActor.run { if self?.control === c { self?.restart(error.localizedDescription) } } }
        }
    }

    private func teardown(bye: Bool) {
        pump.stop(sendBye: bye)
        if bye { control?.close() } else { control?.cancel() }
        control = nil
    }
}

/// Dedicated high-priority sender thread (input on change + heartbeats + pings) and link-health watchdog.
final class SenderPump: @unchecked Sendable {
    private let cond = NSCondition()
    private var latest = InputState.neutral, flags: UInt16 = 0, version: UInt64 = 0, paused = false
    private var channel: InputChannel?
    private var generation = 0

    func submit(_ s: InputState, _ f: UInt16) { cond.lock(); latest = s; flags = f; version &+= 1; cond.signal(); cond.unlock() }
    func setPaused(_ p: Bool) { cond.lock(); paused = p; version &+= 1; cond.signal(); cond.unlock() }

    func start(_ ch: InputChannel, onDead: @escaping @Sendable (String) -> Void, stats: @escaping @Sendable ((Double?, Double?, Double, UInt64)) -> Void) {
        cond.lock(); channel = ch; generation += 1; let gen = generation; cond.unlock()
        let t = Thread { [self] in
            let hbActive = UInt64(max(10, min(ch.info.heartbeatMs, 1000))) * 1_000_000
            let hbIdle = UInt64(max(50, min(ch.info.idleHeartbeatMs, 2000))) * 1_000_000
            var sentVersion: UInt64 = .max, lastSend: UInt64 = 0, lastPing: UInt64 = 0, lastStats = DispatchTime.now().uptimeNanoseconds
            var lastPong = lastStats, seenPongs: UInt64 = 0, windowStart = lastStats, windowSent = 0.0, sendHz = 0.0
            while true {
                cond.lock()
                guard generation == gen else { cond.unlock(); return }
                let s = paused ? InputState.neutral : latest, f = flags | (paused ? flagPaused : 0), v = version
                cond.unlock()
                let now = DispatchTime.now().uptimeNanoseconds
                let hb = s.isNeutral ? hbIdle : hbActive
                if v != sentVersion || now - lastSend >= hb { ch.sendInput(s, flags: f); sentVersion = v; lastSend = now; windowSent += 1 }
                if now - lastPing >= 250_000_000 { ch.ping(); lastPing = now }
                if ch.rttMs.total != seenPongs { seenPongs = ch.rttMs.total; lastPong = now }
                if now - lastPong > 1_000_000_000 { onDead("no response for \((now - lastPong) / 1_000_000) ms"); return }
                if now - windowStart >= 1_000_000_000 { sendHz = windowSent * 1e9 / Double(now - windowStart); windowStart = now; windowSent = 0 }
                if now - lastStats >= 2_000_000_000 { lastStats = now; stats((ch.rttMs.percentile(50), ch.rttMs.percentile(95), sendHz, ch.sent)) }
                cond.lock()
                if version == sentVersion && generation == gen {
                    let wait = min(lastSend + hb, lastPing + 250_000_000)
                    let ms = wait > now ? Double(wait - now) / 1e9 : 0.001
                    _ = cond.wait(until: Date(timeIntervalSinceNow: max(0.001, min(ms, 0.25))))
                }
                cond.unlock()
            }
        }
        t.qualityOfService = .userInteractive
        t.name = "ninectl-sender"
        t.start()
    }

    func stop(sendBye: Bool) {
        cond.lock(); let ch = channel; channel = nil; generation += 1; cond.signal(); cond.unlock()
        if sendBye { ch?.sendInput(.neutral, flags: 0); ch?.bye() }
        ch?.close()
    }
}
