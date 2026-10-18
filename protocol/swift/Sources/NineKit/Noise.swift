import Foundation

/// Noise_XX_25519_ChaChaPoly_SHA256 — mirrors protocol/rust/src/noise.rs; pinned by vectors/noise_xx.json.
public enum NoiseError: Error, Equatable { case state, badLength, decrypt }

public struct KeyPair: Sendable {
    let secret: [UInt8]
    public let publicKey: [UInt8]
    public init(secret: [UInt8]) throws { self.secret = secret; publicKey = try NineCrypto.x25519Public(secret) }
    public static func generate() -> KeyPair { try! KeyPair(secret: NineCrypto.random(32)) }
    public var secretBytes: [UInt8] { secret }
    func dh(_ remote: [UInt8]) throws -> [UInt8] { try NineCrypto.x25519(secret, remote) }
}

func hkdf2(_ ck: [UInt8], _ ikm: [UInt8]) -> ([UInt8], [UInt8]) {
    let temp = NineCrypto.hmac(ck, ikm)
    let o1 = NineCrypto.hmac(temp, [1])
    let o2 = NineCrypto.hmac(temp, o1, [2])
    return (o1, o2)
}

public final class CipherState {
    private var k: [UInt8]?
    public private(set) var n: UInt64 = 0
    init(_ k: [UInt8]?) { self.k = k }
    func encrypt(ad: [UInt8], _ pt: [UInt8]) throws -> [UInt8] {
        guard let key = k else { return pt }
        if n == .max { throw NineCryptoError.nonceExhausted }
        defer { n += 1 }
        return try NineCrypto.seal(key, NineCrypto.nonce(n), ad, pt)
    }
    func decrypt(ad: [UInt8], _ ct: [UInt8]) throws -> [UInt8] {
        guard let key = k else { return ct }
        if n == .max { throw NineCryptoError.nonceExhausted }
        let pt = try NineCrypto.open(key, NineCrypto.nonce(n), ad, ct)
        n += 1
        return pt
    }
}

public final class Transport {
    let send: CipherState, recv: CipherState
    public let handshakeHash: [UInt8]
    public let remoteStatic: [UInt8]
    init(send: CipherState, recv: CipherState, h: [UInt8], rs: [UInt8]) { self.send = send; self.recv = recv; handshakeHash = h; remoteStatic = rs }
    public func encrypt(_ pt: [UInt8]) throws -> [UInt8] { try send.encrypt(ad: [], pt) }
    public func decrypt(_ ct: [UInt8]) throws -> [UInt8] { try recv.decrypt(ad: [], ct) }
}

public final class Handshake {
    static let protocolName = Array("Noise_XX_25519_ChaChaPoly_SHA256".utf8)
    static let prologue = Array("9ctl/1".utf8)
    private let initiator: Bool
    private let s: KeyPair
    private var cs = CipherState(nil)
    private var ck: [UInt8]
    private var h: [UInt8]
    private var e: KeyPair?
    private var fixedE: [UInt8]?
    private var rs: [UInt8]?
    private var re: [UInt8]?
    private var step = 0

    public init(initiator: Bool, s: KeyPair) {
        self.initiator = initiator; self.s = s
        h = Handshake.protocolName // exactly 32 bytes
        ck = h
        mixHash(Handshake.prologue)
    }

    /// Test vectors only.
    public func withFixedEphemeral(_ secret: [UInt8]) -> Handshake { fixedE = secret; return self }

    private func mixHash(_ d: [UInt8]) { h = NineCrypto.sha256(h, d) }
    private func mixKey(_ ikm: [UInt8]) { let (a, b) = hkdf2(ck, ikm); ck = a; cs = CipherState(b) }
    private func encryptAndHash(_ pt: [UInt8]) throws -> [UInt8] { let c = try cs.encrypt(ad: h, pt); mixHash(c); return c }
    private func decryptAndHash(_ ct: [UInt8]) throws -> [UInt8] { let p = try cs.decrypt(ad: h, ct); mixHash(ct); return p }
    private func genE() throws -> [UInt8] {
        let kp = try fixedE.map { try KeyPair(secret: $0) } ?? KeyPair.generate()
        fixedE = nil; e = kp
        return kp.publicKey
    }
    private var myTurn: Bool { initiator ? (step == 0 || step == 2) : step == 1 }

    public func writeMessage(_ payload: [UInt8]) throws -> [UInt8] {
        guard myTurn else { throw NoiseError.state }
        var out = [UInt8]()
        switch step {
        case 0:
            let ep = try genE(); mixHash(ep); out += ep
        case 1:
            let ep = try genE(); mixHash(ep); out += ep
            guard let r = re, let e else { throw NoiseError.state }
            mixKey(try e.dh(r))
            out += try encryptAndHash(s.publicKey)
            mixKey(try s.dh(r))
        default:
            guard let r = re else { throw NoiseError.state }
            out += try encryptAndHash(s.publicKey)
            mixKey(try s.dh(r))
        }
        out += try encryptAndHash(payload)
        step += 1
        return out
    }

    public func readMessage(_ msg: [UInt8]) throws -> [UInt8] {
        guard !myTurn, step <= 2 else { throw NoiseError.state }
        var off = 0
        func take(_ n: Int) throws -> [UInt8] {
            guard msg.count - off >= n else { throw NoiseError.badLength }
            defer { off += n }
            return Array(msg[off..<off + n])
        }
        switch step {
        case 0:
            let r = try take(32); mixHash(r); re = r
        case 1:
            let r = try take(32); mixHash(r); re = r
            guard let e else { throw NoiseError.state }
            mixKey(try e.dh(r))
            let remote = try decryptAndHash(try take(48)); rs = remote
            mixKey(try e.dh(remote))
        default:
            guard let e else { throw NoiseError.state }
            let remote = try decryptAndHash(try take(48)); rs = remote
            mixKey(try e.dh(remote))
        }
        let payload = try decryptAndHash(Array(msg[off...]))
        step += 1
        return payload
    }

    public func intoTransport() throws -> Transport {
        guard step == 3, let rs else { throw NoiseError.state }
        let (k1, k2) = hkdf2(ck, [])
        return initiator ? Transport(send: CipherState(k1), recv: CipherState(k2), h: h, rs: rs)
                         : Transport(send: CipherState(k2), recv: CipherState(k1), h: h, rs: rs)
    }
}
