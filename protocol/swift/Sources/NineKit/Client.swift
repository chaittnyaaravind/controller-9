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

/// Reliable, ordered CONTROL frames over some link (SPEC §8).
public protocol ControlLink: AnyObject, Sendable {
    func sendFrame(_ frame: [UInt8]) async throws
    func receiveFrame() async throws -> [UInt8]
    func cancel()
}

/// IP binding: `len:u16be || frame` over a TCP NWConnection.
final class TCPControlLink: ControlLink, @unchecked Sendable {
    let conn: NWConnection
    init(_ c: NWConnection) { conn = c }
    func sendFrame(_ frame: [UInt8]) async throws { try await ControlConnection.writeFrame(conn, frame) }
    func receiveFrame() async throws -> [UInt8] { try await ControlConnection.readFrame(conn) }
    func cancel() { conn.cancel() }
}

/// Message-based links (stream binding, BLE): frames are delivered whole by the link.
public final class MessageControlLink: ControlLink, @unchecked Sendable {
    private let sender: @Sendable ([UInt8]) async throws -> Void
    private let onCancel: @Sendable () -> Void
    private var stream: AsyncThrowingStream<[UInt8], Error>
    private var iterator: AsyncThrowingStream<[UInt8], Error>.AsyncIterator
    private let continuation: AsyncThrowingStream<[UInt8], Error>.Continuation
    public init(send: @escaping @Sendable ([UInt8]) async throws -> Void, onCancel: @escaping @Sendable () -> Void) {
        sender = send; self.onCancel = onCancel
        var c: AsyncThrowingStream<[UInt8], Error>.Continuation!
        stream = AsyncThrowingStream { c = $0 }
        continuation = c
        iterator = stream.makeAsyncIterator()
    }
    /// A complete CONTROL frame arrived from the host.
    public func deliver(_ frame: [UInt8]) { continuation.yield(frame) }
    public func finish(_ error: Error? = nil) { if let error { continuation.finish(throwing: error) } else { continuation.finish() } }
    public func sendFrame(_ frame: [UInt8]) async throws { try await sender(frame) }
    public func receiveFrame() async throws -> [UInt8] {
        guard let f = try await iterator.next() else { throw ClientError.closed }
        return f
    }
    public func cancel() { finish(); onCancel() }
}

/// Encrypted control channel over any [ControlLink]. Mirrors hosts/core/src/client.rs.
public final class ControlConnection: @unchecked Sendable {
    private let link: ControlLink
    private let transport: Transport
    public let host: HostHello
    public var hostStatic: [UInt8] { transport.remoteStatic }
    public var handshakeHash: [UInt8] { transport.handshakeHash }
    /// Numeric host address for the IP binding (empty on other links).
    public let remoteHost: String
    /// Interface kinds the IP path uses (from NWPath), for link labelling.
    public let pathKinds: [String]

    private init(link: ControlLink, transport: Transport, host: HostHello, remoteHost: String, pathKinds: [String]) {
        self.link = link; self.transport = transport; self.host = host; self.remoteHost = remoteHost; self.pathKinds = pathKinds
    }

    public static func connect(host: String, port: UInt16, identity: KeyPair, hello: ControllerHello, timeout: TimeInterval = 3) async throws -> ControlConnection {
        try await connect(endpoint: .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!), identity: identity, hello: hello, timeout: timeout)
    }

    /// IP binding to any endpoint, including a Bonjour service endpoint from NWBrowser.
    public static func connect(endpoint: NWEndpoint, identity: KeyPair, hello: ControllerHello, timeout: TimeInterval = 3) async throws -> ControlConnection {
        let conn = try await openTCP(endpoint, timeout: timeout)
        let kinds = conn.currentPath.map { p in [NWInterface.InterfaceType.wiredEthernet, .wifi, .other, .cellular].filter { p.usesInterfaceType($0) }.map { "\($0)" } } ?? []
        return try await handshake(TCPControlLink(conn), identity: identity, hello: hello, remoteHost: resolvedHost(conn), pathKinds: kinds)
    }

    /// Any binding: handshake over an already-established control link.
    public static func connect(over link: ControlLink, identity: KeyPair, hello: ControllerHello) async throws -> ControlConnection {
        try await handshake(link, identity: identity, hello: hello, remoteHost: "", pathKinds: [])
    }

    static func openTCP(_ endpoint: NWEndpoint, timeout: TimeInterval) async throws -> NWConnection {
        let tcp = NWProtocolTCP.Options(); tcp.noDelay = true; tcp.connectionTimeout = max(1, Int(timeout))
        let conn = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
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
            conn.start(queue: DispatchQueue(label: "c9.control"))
        }
        return conn
    }

    private static func handshake(_ link: ControlLink, identity: KeyPair, hello: ControllerHello, remoteHost: String, pathKinds: [String]) async throws -> ControlConnection {
        let hs = Handshake(initiator: true, s: identity)
        try await link.sendFrame(try hs.writeMessage(Array(try JSONEncoder().encode(InitHello(proto: [1])))))
        let host = try JSONDecoder().decode(HostHello.self, from: Data(try hs.readMessage(try await link.receiveFrame())))
        try await link.sendFrame(try hs.writeMessage(Array(try JSONEncoder().encode(hello))))
        return ControlConnection(link: link, transport: try hs.intoTransport(), host: host, remoteHost: remoteHost, pathKinds: pathKinds)
    }

    /// The numeric address actually connected to (works for Bonjour endpoints too).
    static func resolvedHost(_ c: NWConnection) -> String {
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

    public func send(_ m: Control) async throws { try await link.sendFrame(try transport.encrypt(m.json())) }
    public func recv() async throws -> Control { try Control.parse(try transport.decrypt(try await link.receiveFrame())) }
    public func close() { Task { try? await send(.bye(reason: nil)); link.cancel() } }
    public func cancel() { link.cancel() }

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

    /// Opens a session. A host without a virtual-pad driver follows with error("backend_unavailable");
    /// the caller's control-reader loop surfaces it.
    public func open(resumeSlot: Int?, profile: String?) async throws -> SessionInfo {
        try await send(.open(resumeSlot: resumeSlot, profile: profile))
        switch try await recv() {
        case let .session(slot, sid, port, kIn, kOut, hb, ihb, to):
            guard let ki = Data(base64Encoded: kIn), let ko = Data(base64Encoded: kOut), ki.count == 32, ko.count == 32 else { throw ClientError.protocolError("bad session keys") }
            return SessionInfo(slot: slot, sessionId: sid, udpPort: port, kIn: Array(ki), kOut: Array(ko), heartbeatMs: hb, idleHeartbeatMs: ihb, timeoutMs: to)
        case .pairRequired: throw ClientError.refused("not_paired")
        case let .error(c, _): throw ClientError.refused(c)
        case let o: throw ClientError.protocolError("unexpected \(o)")
        }
    }

    static func writeFrame(_ c: NWConnection, _ b: [UInt8]) async throws {
        precondition(!b.isEmpty && b.count <= 65535)
        try await sendRaw(c, Data([UInt8(b.count >> 8), UInt8(b.count & 0xFF)] + b))
    }
    static func sendRaw(_ c: NWConnection, _ data: Data) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            c.send(content: data, completion: .contentProcessed { e in if let e { k.resume(throwing: e) } else { k.resume() } })
        }
    }
    static func readExact(_ c: NWConnection, _ n: Int) async throws -> [UInt8] {
        try await withCheckedThrowingContinuation { k in
            c.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, _, e in
                if let e { k.resume(throwing: e) } else if let data, data.count == n { k.resume(returning: Array(data)) } else { k.resume(throwing: ClientError.closed) }
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

/// INPUT datagrams over some link (SPEC §6.1).
public protocol DatagramLink: AnyObject, Sendable {
    func send(_ d: [UInt8])
    var onReceive: (@Sendable ([UInt8]) -> Void)? { get set }
    func close()
}

/// IP binding: UDP with the lowest-latency Wi-Fi access category.
final class UDPLink: DatagramLink, @unchecked Sendable {
    private let conn: NWConnection
    var onReceive: (@Sendable ([UInt8]) -> Void)?
    init(host: String, port: UInt16) {
        let p = NWParameters.udp
        p.serviceClass = .interactiveVoice
        conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: p)
        conn.start(queue: DispatchQueue(label: "c9.udp", qos: .userInteractive))
        loop()
    }
    private func loop() {
        conn.receiveMessage { [weak self] data, _, _, err in
            guard let self, err == nil else { return }
            if let d = data { self.onReceive?(Array(d)) }
            self.loop()
        }
    }
    func send(_ d: [UInt8]) { conn.send(content: Data(d), completion: .idempotent) }
    func close() { conn.cancel() }
}

/// Stream binding (SPEC §8.2) over one TCP connection (port 47902) — for networks that block UDP.
public final class StreamBinding: DatagramLink, @unchecked Sendable {
    private let conn: NWConnection
    public let control: MessageControlLink
    public var onReceive: (@Sendable ([UInt8]) -> Void)?
    private let lock = NSLock()
    private var reader = RecordReader()

    private init(_ conn: NWConnection) {
        self.conn = conn
        control = MessageControlLink(send: { f in try await ControlConnection.sendRaw(conn, Data(Record.control(f).encode())) }, onCancel: { conn.cancel() })
        loop()
    }

    public static func connect(host: String, port: UInt16 = LinkConst.streamTCPPort, timeout: TimeInterval = 3) async throws -> StreamBinding {
        StreamBinding(try await ControlConnection.openTCP(.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!), timeout: timeout))
    }

    private func loop() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, done, err in
            guard let self else { return }
            if let d = data, !d.isEmpty {
                let recs: [Record] = self.lock.withLock {
                    self.reader.push(Array(d))
                    var out: [Record] = []
                    while let r = try? self.reader.next() { out.append(r) }
                    return out
                }
                for r in recs {
                    switch r {
                    case let .control(f): self.control.deliver(f)
                    case let .datagram(g): self.onReceive?(g)
                    }
                }
            }
            if err != nil || done { self.control.finish(ClientError.closed); return }
            self.loop()
        }
    }

    public func send(_ d: [UInt8]) { conn.send(content: Data(Record.datagram(d).encode()), completion: .idempotent) }
    public func close() { control.finish(); conn.cancel() }
}

final class Once: @unchecked Sendable {
    private var done = false; private let lock = NSLock()
    func run(_ f: () -> Void) { lock.lock(); defer { lock.unlock() }; if !done { done = true; f() } }
}

/// Input channel over any [DatagramLink].
public final class InputChannel: @unchecked Sendable {
    private let link: DatagramLink
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

    /// IP binding: UDP to the host's input port.
    public convenience init(host: String, info: SessionInfo) { self.init(link: UDPLink(host: host, port: info.udpPort), info: info) }

    public init(link: DatagramLink, info: SessionInfo) {
        self.link = link; self.info = info
        link.onReceive = { [weak self] d in self?.handle(d) }
    }

    public func nowUs() -> UInt64 { (DispatchTime.now().uptimeNanoseconds - epoch) / 1000 }

    private func seal(_ type: PacketType, _ payload: [UInt8]) -> [UInt8] {
        lock.withLock { counter += 1; return Datagram.seal(key: info.kIn, header: Header(type: type, sessionId: info.sessionId, counter: counter), payload: payload) }
    }

    public func sendInput(_ s: InputState, flags: UInt16) {
        link.send(seal(.input, InputFrame(tUs: nowUs(), state: s, flags: flags).encode()))
        lock.withLock { sent += 1 }
    }

    public func ping() {
        let t = nowUs()
        let id: UInt32 = lock.withLock { let i = nextPing; nextPing += 1; pings[i] = t; if pings.count > 64, let k = pings.keys.min() { pings[k] = nil }; return i }
        link.send(seal(.ping, Ping(tUs: t, id: id).encode()))
    }

    public func bye() { link.send(seal(.bye, [])) }
    public func close() { link.close() }

    private func handle(_ d: [UInt8]) {
        guard let (h, pt) = try? Datagram.open(key: info.kOut, d) else { return }
        guard lock.withLock({ freshIn.accept(h.counter) }) else { return }
        switch h.type {
        case .pong:
            if let p = try? Pong.decode(pt), lock.withLock({ pings.removeValue(forKey: p.id) }) != nil { rttMs.push(Double(nowUs() - p.tUs) / 1000) }
        case .feedback: if let f = try? Feedback.decode(pt) { onFeedback?(f) }
        default: break
        }
    }
}
