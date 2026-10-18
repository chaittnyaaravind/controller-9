import CryptoKit
import Foundation

public enum NineCryptoError: Error, Equatable { case badKey, decrypt, nonceExhausted }

enum NineCrypto {
    static func random(_ n: Int) -> [UInt8] {
        var b = [UInt8](repeating: 0, count: n)
        precondition(SecRandomCopyBytes(kSecRandomDefault, n, &b) == errSecSuccess, "OS random source unavailable")
        return b
    }
    static func sha256(_ parts: [UInt8]...) -> [UInt8] {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Array(h.finalize())
    }
    static func hmac(_ key: [UInt8], _ parts: [UInt8]...) -> [UInt8] {
        var m = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts { m.update(data: p) }
        return Array(m.finalize())
    }
    static func x25519Public(_ secret: [UInt8]) throws -> [UInt8] {
        Array(try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret).publicKey.rawRepresentation)
    }
    static func x25519(_ secret: [UInt8], _ remote: [UInt8]) throws -> [UInt8] {
        let k = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret)
        guard let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: remote) else { throw NineCryptoError.badKey }
        let shared = try k.sharedSecretFromKeyAgreement(with: pub).withUnsafeBytes { Array($0) }
        if shared.allSatisfy({ $0 == 0 }) { throw NineCryptoError.badKey }
        return shared
    }
    static func nonce(_ counter: UInt64) -> [UInt8] {
        var n = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { n[4 + i] = UInt8(truncatingIfNeeded: counter >> (8 * UInt64(i))) }
        return n
    }
    /// ChaCha20-Poly1305 (RFC 8439); returns ciphertext || tag.
    static func seal(_ key: [UInt8], _ nonce: [UInt8], _ aad: [UInt8], _ pt: [UInt8]) throws -> [UInt8] {
        let box = try ChaChaPoly.seal(pt, using: SymmetricKey(data: key), nonce: ChaChaPoly.Nonce(data: nonce), authenticating: aad)
        return Array(box.ciphertext) + Array(box.tag)
    }
    static func open(_ key: [UInt8], _ nonce: [UInt8], _ aad: [UInt8], _ ct: [UInt8]) throws -> [UInt8] {
        guard ct.count >= 16 else { throw NineCryptoError.decrypt }
        do {
            let box = try ChaChaPoly.SealedBox(nonce: ChaChaPoly.Nonce(data: nonce), ciphertext: ct.dropLast(16), tag: ct.suffix(16))
            return Array(try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad))
        } catch { throw NineCryptoError.decrypt }
    }
    static func constantTimeEqual(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var d: UInt8 = 0
        for i in 0..<a.count { d |= a[i] ^ b[i] }
        return d == 0
    }
}

extension Array where Element == UInt8 {
    public init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var out = [UInt8](); out.reserveCapacity(hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            out.append(b); i = j
        }
        self = out
    }
    public var hex: String { map { String(format: "%02x", $0) }.joined() }
}
