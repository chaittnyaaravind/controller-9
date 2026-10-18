import Foundation
import Network

public enum ClientError: Error, Equatable, LocalizedError {
    case closed, protocolError(String), refused(String), wrongHost, timeout
    public var errorDescription: String? {
        switch self {
        case .closed: return "The computer closed the connection."
        case let .protocolError(m): return m
        case let .refused(code): return Self.describe(code)
        case .wrongHost: return "This QR code belongs to a different computer than the one that answered. Pairing stopped."
        case .timeout: return "The computer didn’t answer in time."
        }
    }
    static func describe(_ code: String) -> String {
        switch code {
        case "pairing_closed": return "Pairing isn’t open on the computer. Open “Pair a phone” there first."
        case "rate_limited": return "Too many pairing attempts. Wait a minute and try again."
        case "bad_proof": return "That QR code has expired or was already used. Make a new one on the computer."
        case "rejected": return "Pairing was declined."
        case "slots_full": return "All four player slots are in use."
        case "not_paired": return "The computer no longer knows this phone. Pair again."
        case "unsupported_version": return "The computer runs an incompatible version. Update both apps."
        default: return "The computer refused: \(code)"
        }
    }
}

public struct SessionInfo: Sendable {
    public let slot: Int, sessionId: UInt32, udpPort: UInt16, kIn: [UInt8], kOut: [UInt8]
    public let heartbeatMs: Int, idleHeartbeatMs: Int, timeoutMs: Int
    public var backendWarning: String?
}

/// Encrypted control channel over Network.framework TCP. Mirrors hosts/core/src/client.rs.
public final class ControlConnection: @unchecked Sendable {
    private let conn: NWConnection
    private let queue: DispatchQueue
    private let transport: Transport
    public let host: HostHello
    public var hostStatic: [UInt8] { transport.remoteStatic }
    public var handshakeHash: [UInt8] { transport.handshakeHash }
    public let remoteHost: String

    private init(conn: NWConnection, queue: DispatchQueue, transport: Transport, host: HostHello, remoteHost: String) {
        self.conn = conn; self.queue = queue; self.transport = transport; self.host = host; self.remoteHost = remoteHost
    }

    public static func connect(host: String, port: UInt16, identity: KeyPair, hello: ControllerHello, timeout: TimeInterval = 3) async throws -> ControlConnection {
        try await connect(endpoint: .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!), identity: identity, hello: hello, timeout: timeout)
    }

    /// Connect to any endpoint, including a Bonjour service endpoint from NWBrowser.
    public static func connect(endpoint: NWEndpoint, identity: KeyPair, hello: ControllerHello, timeout: TimeInterval = 3) async throws -> ControlConnection {
        let tcp = NWProtocolTCP.Options(); tcp.noDelay = true; tcp.connectionTimeout = Int(timeout)
        let conn = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
        let queue = DispatchQueue(label: "ninectl.control")
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            let once = Once()
            conn.stateUpdateHandler = { st in
                switch st {
                case .ready: once.run { k.resume() }
                case let .failed(e), let .waiting(e): once.run { conn.cancel(); k.resume(throwing: e) }
                case .cancelled: once.run { k.resume(throwing: ClientError.closed) }
                default: break
                }
            }
            conn.start(queue: queue)
        }
        let hs = Handshake(initiator: true, s: identity)
        try await writeFrame(conn, try hs.writeMessage(Array(try JSONEncoder().encode(InitHello(proto: [1])))))
        let host = try JSONDecoder().decode(HostHello.self, from: Data(try hs.readMessage(try await readFrame(conn))))
        try await writeFrame(conn, try hs.writeMessage(Array(try JSONEncoder().encode(hello))))
        return ControlConnection(conn: conn, queue: queue, transport: try hs.intoTransport(), host: host, remoteHost: resolvedHost(conn))
    }

    /// The numeric address actually connected to (works for Bonjour endpoints too); used for UDP and to remember the host.
    private static func resolvedHost(_ c: NWConnection) -> String {
        let ep = c.currentPath?.remoteEndpoint ?? c.endpoint
        if case let .hostPort(h, _) = ep {
            switch h {
            case let .ipv4(a): return "\(a)"
            case let .ipv6(a): return "\(a)".components(separatedBy: "%").first ?? "\(a)"
            case let .name(n, _): return n
            @unknown default: return "\(h)"
            }
        }
        return ""
    }

    public func send(_ m: Control) async throws { try await Self.writeFrame(conn, try transport.encrypt(m.json())) }
    public func recv() async throws -> Control { try Control.parse(try transport.decrypt(try await Self.readFrame(conn))) }
    public func close() { Task { try? await send(.bye(reason: nil)); conn.cancel() } }
    public func cancel() { conn.cancel() }

    public func pairQr(_ uri: PairURI) async throws {
        guard hostStatic == uri.hostStatic else { throw ClientError.wrongHost }
        try await send(.pairQr(proof: Data(Pairing.qrProof(token: uri.token, h: handshakeHash)).base64EncodedString()))
        switch try await recv() {
        case .paired: return
        case let .pairFailed(r): throw ClientError.refused(r)
        case let .error(c, _): throw ClientError.refused(c)
        case let o: throw ClientError.protocolError("unexpected \(o)")
        }
    }

    /// Numeric comparison; `confirm` shows the code and returns the user's verdict.
    public func pairCode(confirm: @Sendable (UInt32) async -> Bool) async throws -> UInt32 {
        try await send(.pairCode)
        guard case let .commit(cb64) = try await recv(), let c = Data(base64Encoded: cb64) else { throw ClientError.protocolError("expected commit") }
        let na = Pairing.randomNonce()
        try await send(.nonce(n: Data(na).base64EncodedString()))
        guard case let .reveal(nb64) = try await recv(), let nb = Data(base64Encoded: nb64), nb.count == 16 else { throw ClientError.protocolError("expected reveal") }
        guard Pairing.verifyCommitment(h: handshakeHash, nb: Array(nb), c: Array(c)) else {
            throw ClientError.protocolError("Host commitment mismatch — possible interception, pairing aborted")
        }
        let code = Pairing.sasCode(h: handshakeHash, na: na, nb: Array(nb))
        let ok = await confirm(code)
        try await send(ok ? .codeOk : .codeReject)
        guard ok else { throw ClientError.refused("rejected on controller") }
        switch try await recv() {
        case .paired: return code
        case let .pairFailed(r): throw ClientError.refused(r)
        case let o: throw ClientError.protocolError("unexpected \(o)")
        }
    }

    public func open(resumeSlot: Int?, profile: String?) async throws -> SessionInfo {
        try await send(.open(resumeSlot: resumeSlot, profile: profile))
        let m = try await recv()
        switch m {
        case let .session(slot, sid, port, kIn, kOut, hb, ihb, to):
            guard let ki = Data(base64Encoded: kIn), let ko = Data(base64Encoded: kOut), ki.count == 32, ko.count == 32 else { throw ClientError.protocolError("bad session keys") }
            // A host without a virtual-pad driver follows this with error("backend_unavailable"); the app's
            // control-reader loop surfaces it. (A timed read here could not be cancelled and would steal messages.)
            return SessionInfo(slot: slot, sessionId: sid, udpPort: port, kIn: Array(ki), kOut: Array(ko), heartbeatMs: hb, idleHeartbeatMs: ihb, timeoutMs: to)
        case .pairRequired: throw ClientError.refused("not_paired")
        case let .error(c, _): throw ClientError.refused(c)
        case let o: throw ClientError.protocolError("unexpected \(o)")
        }
    }

    static func writeFrame(_ c: NWConnection, _ b: [UInt8]) async throws {
        precondition(!b.isEmpty && b.count <= 65535)
        let data = Data([UInt8(b.count >> 8), UInt8(b.count & 0xFF)] + b)
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            c.send(content: data, completion: .contentProcessed { e in if let e { k.resume(throwing: e) } else { k.resume() } })
        }
    }
    static func readExact(_ c: NWConnection, _ n: Int) async throws -> [UInt8] {
        try await withCheckedThrowingContinuation { k in
            c.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, done, e in
                if let e { k.resume(throwing: e) } else if let data, data.count == n { k.resume(returning: Array(data)) } else { k.resume(throwing: ClientError.closed) }
                _ = done
            }
        }
    }
    static func readFrame(_ c: NWConnection) async throws -> [UInt8] {
        let l = try await readExact(c, 2)
        let n = Int(l[0]) << 8 | Int(l[1])
        guard n > 0 else { throw ClientError.protocolError("empty frame") }
        return try await readExact(c, n)
    }
}

final class Once: @unchecked Sendable {
    private var done = false; private let lock = NSLock()
    func run(_ f: () -> Void) { lock.lock(); defer { lock.unlock() }; if !done { done = true; f() } }
}

/// UDP input channel.
public final class InputChannel: @unchecked Sendable {
    private let conn: NWConnection
    public let info: SessionInfo
    private var counter: UInt64 = 0
    private var freshIn = Freshness()
    private let epoch = DispatchTime.now().uptimeNanoseconds
    private var pings: [UInt32: UInt64] = [:]
    private var nextPing: UInt32 = 1
    private let lock = NSLock()
    public let rttMs = Window(capacity: 256)
    public private(set) var sent: UInt64 = 0
    public var onFeedback: (@Sendable (Feedback) -> Void)?

    public init(host: String, info: SessionInfo) {
        self.info = info
        let p = NWParameters.udp
        p.serviceClass = .interactiveVoice // lowest-latency Wi-Fi access category (WMM voice)
        conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: info.udpPort)!, using: p)
        conn.start(queue: DispatchQueue(label: "ninectl.udp", qos: .userInteractive))
        receiveLoop()
    }

    public func nowUs() -> UInt64 { (DispatchTime.now().uptimeNanoseconds - epoch) / 1000 }

    private func seal(_ type: PacketType, _ payload: [UInt8]) -> [UInt8] {
        lock.withLock { counter += 1; return Datagram.seal(key: info.kIn, header: Header(type: type, sessionId: info.sessionId, counter: counter), payload: payload) }
    }

    public func sendInput(_ s: InputState, flags: UInt16) {
        conn.send(content: Data(seal(.input, InputFrame(tUs: nowUs(), state: s, flags: flags).encode())), completion: .idempotent)
        lock.withLock { sent += 1 }
    }

    public func ping() {
        let t = nowUs()
        let id: UInt32 = lock.withLock { let i = nextPing; nextPing += 1; pings[i] = t; if pings.count > 64, let k = pings.keys.min() { pings[k] = nil }; return i }
        conn.send(content: Data(seal(.ping, Ping(tUs: t, id: id).encode())), completion: .idempotent)
    }

    public func bye() { conn.send(content: Data(seal(.bye, [])), completion: .idempotent) }
    public func close() { conn.cancel() }

    private func receiveLoop() {
        conn.receiveMessage { [weak self] data, _, _, err in
            guard let self, err == nil else { return }
            if let d = data, let (h, pt) = try? Datagram.open(key: self.info.kOut, Array(d)) {
                let fresh = self.lock.withLock { self.freshIn.accept(h.counter) }
                if fresh {
                    switch h.type {
                    case .pong:
                        if let p = try? Pong.decode(pt), let _ = self.lock.withLock({ self.pings.removeValue(forKey: p.id) }) {
                            self.rttMs.push(Double(self.nowUs() - p.tUs) / 1000)
                        }
                    case .feedback: if let f = try? Feedback.decode(pt) { self.onFeedback?(f) }
                    default: break
                    }
                }
            }
            self.receiveLoop()
        }
    }
}
