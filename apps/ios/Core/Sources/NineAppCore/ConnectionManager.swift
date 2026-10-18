import Foundation
import Network
import NineKit
import Observation

public enum ConnState: Equatable, Sendable {
    case idle
    case connecting(String)
    case confirmCode(host: String, code: String)
    case connected(host: String, slot: Int, warning: String?, link: LinkKind)
    case reconnecting(host: String, attempt: Int, reason: String)
    case failed(String)
}

public struct LinkStatsUi: Equatable, Sendable {
    public var rttP50: Double?, rttP95: Double?, sendHz = 0.0, sent: UInt64 = 0, sensorHz = 0.0, reconnects = 0, lastReconnectMs: Int?
    public var lastSwitch: String?, watchdogTrips = 0, linkDetail = ""
    public init() {}
}

public struct LinkReport: Equatable, Sendable, Identifiable {
    public var id: String { kind.rawValue }
    public let kind: LinkKind, available: Bool, reason: String?, score: Double?, attempts: Int, successes: Int
}

/// Connection manager with pluggable links and the shared policy (docs/CONNECTION_MANAGER.md).
/// One link at a time; a failed link is retried last; a healthy link is replaced only by a clearly
/// better one; every transition goes through the host's slot takeover (neutral first).
@MainActor @Observable
public final class ConnectionManager {
    public private(set) var state: ConnState = .idle
    public private(set) var stats = LinkStatsUi()
    public private(set) var links: [LinkReport] = []
    public var sensorHz = 0.0
    public var onRumble: (@Sendable (Int, Int, Int) -> Void)?
    public let ble = BlePeripheral()

    private let store: FileStore
    private let deviceName: String
    private let appVersion: String
    private var want: PairedHost?
    private var lastSlot: Int?
    private var profileName: String?
    private var sessionTask: Task<Void, Never>?
    private var codeContinuation: CheckedContinuation<Bool, Never>?
    private var active: ActiveLink?
    private let pump = SenderPump()
    @ObservationIgnored private var hist: [LinkKind: (attempts: Int, successes: Int, lastFailure: Date?, disconnects: [Date], rttP95: Double?)] = [:]
    @ObservationIgnored private var switchedAt = Date()
    @ObservationIgnored private var betterSince: Date?
    @ObservationIgnored private var monitor: Task<Void, Never>?

    public init(store: FileStore, deviceName: String, appVersion: String) {
        self.store = store; self.deviceName = deviceName; self.appVersion = appVersion
        monitor = Task { [weak self] in while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000_000); self?.evaluate() } }
    }

    private var hello: ControllerHello { ControllerHello(name: String(deviceName.prefix(40)), platform: "ios", app: appVersion) }

    public nonisolated func submit(_ s: InputState, _ f: UInt16) { pump.submit(s, f) }
    public func setPaused(_ p: Bool) { pump.setPaused(p) }
    public func setProfileName(_ n: String) { profileName = n }

    // MARK: candidates

    private var override: LinkKind? { store.settings.linkOverride.flatMap(LinkKind.init(rawValue:)) }

    private func candidates(_ h: PairedHost) -> [(LinkStats, String?)] {
        let ipOK = !h.allAddrs.isEmpty
        return LinkKind.allCases.map { k in
            let reason: String?
            switch k {
            case .ble: reason = ble.unavailableReason() ?? (h.offers("ble") ? nil : "\(h.name) doesn't offer Bluetooth")
            case .stream: reason = !ipOK ? "No address known for \(h.name)" : (h.offers("stream") ? nil : "\(h.name) doesn't offer TCP-only mode")
            // iOS doesn't let apps enumerate interfaces by name: one IP candidate ("lan") races every address
            // over whatever path exists (Wi-Fi, Personal Hotspot, USB); the actual path is labelled after connecting.
            case .lan: reason = ipOK ? nil : "No address known for \(h.name)"
            default: reason = "Detected after connecting (iOS reports the path, not the interface)"
            }
            let r = hist[k] ?? (0, 0, nil, [], nil)
            var st = LinkStats(kind: k, available: reason == nil, rttP95Ms: r.rttP95)
            st.attempts = r.attempts; st.successes = r.successes
            st.disconnectsPerMin = Double(r.disconnects.filter { $0 > Date().addingTimeInterval(-60) }.count)
            st.lastFailureAgeS = r.lastFailure.map { Date().timeIntervalSince($0) }
            return (st, reason)
        }
    }

    private func publish(_ h: PairedHost) {
        links = candidates(h).map { s, r in LinkReport(kind: s.kind, available: s.available, reason: r, score: LinkPolicy.score(s), attempts: s.attempts, successes: s.successes) }
    }

    // MARK: pairing

    public func pair(qr: PairURI) {
        Task {
            state = .connecting(qr.name.isEmpty ? qr.addr : qr.name)
            do {
                let tmp = PairedHost(id: Pairing.hostId(qr.hostStatic), name: qr.name, pub: "", addr: qr.addr, port: qr.port, pairedAt: "", addrs: qr.extra)
                let c = try await Transports.ip(tmp, id: IdentityStore.keyPair(), hello: hello, timeout: 2)
                defer { c.close() }
                try await c.pairQr(qr)
                connect(remember(c, port: qr.port, extra: qr.extra))
            } catch { state = .failed(error.localizedDescription) }
        }
    }

    /// Numeric comparison over IP (`endpoint`) or, with `endpoint == nil`, over Bluetooth (no network).
    public func pairWithCode(endpoint: NWEndpoint?, name: String) {
        Task {
            state = .connecting(name)
            do {
                let c: ControlConnection
                if let endpoint {
                    c = try await ControlConnection.connect(endpoint: endpoint, identity: IdentityStore.keyPair(), hello: hello)
                } else {
                    if let r = ble.unavailableReason() { throw ClientError.protocolError(r) }
                    guard await ble.awaitHost(hint: [0, 0, 0, 0], timeout: 30) else { throw ClientError.protocolError("No computer in pairing mode connected over Bluetooth") }
                    c = try await ControlConnection.connect(over: ble.control, identity: IdentityStore.keyPair(), hello: hello)
                }
                defer { c.close() }
                _ = try await c.pairCode { code in
                    await withCheckedContinuation { k in
                        Task { @MainActor in self.codeContinuation = k; self.state = .confirmCode(host: c.host.name, code: Pairing.formatCode(code)) }
                    }
                }
                connect(remember(c, port: endpoint.map(Self.port(of:)) ?? defaultTCPPort, extra: []))
            } catch { state = .failed(error.localizedDescription) }
        }
    }

    public func confirmCode(_ matches: Bool) { codeContinuation?.resume(returning: matches); codeContinuation = nil }

    static func port(of ep: NWEndpoint) -> UInt16 { if case let .hostPort(_, p) = ep { return p.rawValue }; return defaultTCPPort }

    private func remember(_ c: ControlConnection, port: UInt16, extra: [String]) -> PairedHost {
        var seen = Set<String>()
        let addrs = (extra + c.host.addrs).filter { seen.insert($0).inserted }
        let h = PairedHost(id: Pairing.hostId(c.hostStatic), name: c.host.name, pub: Data(c.hostStatic).base64EncodedString(),
                           addr: c.remoteHost.isEmpty ? (addrs.first ?? "") : c.remoteHost, port: port, pairedAt: now(), addrs: addrs, links: c.host.links)
        store.upsertHost(h)
        return h
    }

    // MARK: session

    public func connect(_ host: PairedHost) {
        want = host
        var s = store.settings; s.lastHost = host.id; store.saveSettings(s)
        startSession(host, attempt: 0, reason: "", failed: nil, preferred: nil)
    }

    public func disconnect() {
        want = nil
        sessionTask?.cancel()
        pump.stop(sendBye: true)
        active?.close(sendBye: true)
        active = nil
        state = .idle
    }

    private func restart(_ reason: String, healthy: Bool, to: LinkKind? = nil) {
        guard let h = want else { return }
        let failed = active?.kind
        if !healthy, let f = failed { var r = hist[f] ?? (0, 0, nil, [], nil); r.lastFailure = Date(); r.disconnects.append(Date()); hist[f] = r }
        let old = active
        if to == nil { pump.stop(sendBye: false); active = nil; old?.close(sendBye: false) }
        startSession(h, attempt: 1, reason: reason, failed: failed, preferred: to, keep: to == nil ? nil : old)
    }

    private func startSession(_ host: PairedHost, attempt: Int, reason: String, failed: LinkKind?, preferred: LinkKind?, keep: ActiveLink? = nil) {
        sessionTask?.cancel()
        sessionTask = Task {
            var n = attempt
            let t0 = Date()
            while !Task.isCancelled, want?.id == host.id {
                let h = store.hosts.first { $0.id == host.id } ?? host
                publish(h)
                let order: [LinkKind]
                if let p = preferred { order = [p] } else {
                    switch LinkPolicy.decide(candidates(h).map(\.0), PolicyState(current: failed, currentHealthy: false, overrideKind: override)) {
                    case let .failover(o): order = o
                    case let .switchTo(k): order = [k]
                    case .stay: order = failed.map { [$0] } ?? []
                    case .unavailable: order = []
                    }
                }
                if order.isEmpty {
                    n += 1
                    state = .reconnecting(host: h.name, attempt: n, reason: "No usable link: " + Set(candidates(h).compactMap(\.1)).joined(separator: "; "))
                }
                for kind in order where !Task.isCancelled {
                    state = n == 0 ? .connecting("\(h.name) via \(kind.label)") : .reconnecting(host: h.name, attempt: n, reason: "trying \(kind.label)")
                    var r = hist[kind] ?? (0, 0, nil, [], nil); r.attempts += 1; hist[kind] = r
                    do {
                        let (link, info) = try await Transports.open(kind, host: h, id: IdentityStore.keyPair(), hello: hello, ble: ble, resume: lastSlot, profile: profileName)
                        guard Data(link.conn.hostStatic).base64EncodedString() == h.pub else {
                            link.close(sendBye: false); want = nil
                            state = .failed("The computer that answered is not \(h.name) (its identity key changed). Pair again only if you trust it.")
                            return
                        }
                        var ok = hist[link.kind] ?? (0, 0, nil, [], nil); ok.successes += 1; hist[link.kind] = ok
                        lastSlot = info.slot
                        var seen = Set<String>()
                        var updated = h; updated.lastUsed = now()
                        updated.addrs = ((h.addrs ?? []) + link.conn.host.addrs).filter { seen.insert($0).inserted }
                        if !link.conn.host.links.isEmpty { updated.links = link.conn.host.links }
                        store.upsertHost(updated)
                        keep?.close(sendBye: false)
                        active = link
                        switchedAt = Date(); betterSince = nil
                        if n > 0 { stats.reconnects += 1; stats.lastReconnectMs = Int(Date().timeIntervalSince(t0) * 1000) }
                        if let f = failed, f != link.kind { stats.lastSwitch = "\(f.label) → \(link.kind.label): \(reason)" }
                        stats.linkDetail = link.detail
                        state = .connected(host: link.conn.host.name, slot: info.slot, warning: nil, link: link.kind)
                        startLink(link)
                        return
                    } catch ClientError.refused("not_paired") {
                        store.removeHost(h.id); want = nil; state = .failed("\(h.name) no longer knows this phone. Pair again."); return
                    } catch ClientError.refused("slots_full") {
                        want = nil; state = .failed("\(h.name) already has four controllers connected."); return
                    } catch {
                        var f = hist[kind] ?? (0, 0, nil, [], nil); f.lastFailure = Date(); hist[kind] = f
                        n += 1
                        state = .reconnecting(host: h.name, attempt: n, reason: "\(kind.label): \(error.localizedDescription)")
                    }
                }
                if preferred != nil, let k = keep { active = k; return } // planned switch failed: stay
                try? await Task.sleep(nanoseconds: UInt64(min(100 << min(n, 4), 2000)) * 1_000_000)
            }
        }
    }

    /// Planned switch check, once a second.
    private func evaluate() {
        guard let a = active, let h = want else { return }
        var r = hist[a.kind] ?? (0, 0, nil, [], nil); r.rttP95 = a.channel.rttMs.percentile(95); hist[a.kind] = r
        publish(h)
        let cands = candidates(h).map(\.0)
        let cur = cands.first { $0.kind == a.kind }.flatMap(LinkPolicy.score)
        let best = LinkPolicy.rank(cands).first
        let bs = cands.first { $0.kind == best }.flatMap(LinkPolicy.score)
        if let best, best != a.kind, let cur, let bs, bs - cur >= LinkPolicy.switchMargin { betterSince = betterSince ?? Date() } else { betterSince = nil }
        let st = PolicyState(current: a.kind, currentHealthy: true, sinceSwitchS: Date().timeIntervalSince(switchedAt),
                             betterForS: betterSince.map { Date().timeIntervalSince($0) } ?? 0, overrideKind: override)
        if case let .switchTo(k) = LinkPolicy.decide(cands, st), !(k.isIP && a.kind.isIP) { restart("better link available", healthy: true, to: k) }
    }

    private func startLink(_ link: ActiveLink) {
        let rumble = onRumble
        link.channel.onFeedback = { f in rumble?(Int(f.low), Int(f.high), Int(f.ms)) }
        let c = link.conn
        pump.start(link.channel, deadMs: link.kind == .ble ? 1500 : 1000) { [weak self] reason in
            Task { @MainActor in guard let self, self.active === link else { return }; self.stats.watchdogTrips += 1; self.restart(reason, healthy: false) }
        } stats: { [weak self] st in
            Task { @MainActor in
                guard let self else { return }
                self.stats.rttP50 = st.0; self.stats.rttP95 = st.1; self.stats.sendHz = st.2; self.stats.sent = st.3; self.stats.sensorHz = self.sensorHz
                try? await c.send(.stats(rttP50Ms: st.0.map(Float.init), rttP95Ms: st.1.map(Float.init), sensorHz: self.sensorHz > 0 ? Float(self.sensorHz) : nil, sendHz: Float(st.2), batteryPct: nil))
            }
        }
        Task { [weak self] in
            do {
                while true {
                    switch try await c.recv() {
                    case let .rumble(l, h, ms): rumble?(l, h, ms)
                    case let .error(code, msg) where code == "backend_unavailable":
                        await MainActor.run { if case let .connected(hn, s, _, k) = self?.state { self?.state = .connected(host: hn, slot: s, warning: msg ?? code, link: k) } }
                    case let .bye(reason):
                        await MainActor.run {
                            guard let self, self.active === link else { return }
                            if reason == "unpaired" { if let w = self.want { self.store.removeHost(w.id) }; self.want = nil; self.pump.stop(sendBye: false); self.active = nil; link.close(sendBye: false); self.state = .failed("This phone was removed on the computer.") }
                            else if reason?.hasPrefix("replaced") != true { self.restart(reason ?? "host closed", healthy: false) }
                        }
                        return
                    default: break
                    }
                }
            } catch { await MainActor.run { if self?.active === link { self?.restart(error.localizedDescription, healthy: false) } } }
        }
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

    func start(_ ch: InputChannel, deadMs: UInt64 = 1000, onDead: @escaping @Sendable (String) -> Void, stats: @escaping @Sendable ((Double?, Double?, Double, UInt64)) -> Void) {
        cond.lock(); channel = ch; generation += 1; let gen = generation; version &+= 1; cond.unlock() // resend state on the new link
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
                if now - lastPong > deadMs * 1_000_000 { onDead("no response for \((now - lastPong) / 1_000_000) ms"); return }
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
