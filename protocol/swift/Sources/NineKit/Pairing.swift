import Foundation

public enum Pairing {
    public static func qrProof(token: [UInt8], h: [UInt8]) -> [UInt8] { NineCrypto.hmac(token, Array("9ctl-qr".utf8), h) }
    public static func commitment(h: [UInt8], nb: [UInt8]) -> [UInt8] { NineCrypto.sha256(Array("9ctl-commit".utf8), h, nb) }
    public static func verifyCommitment(h: [UInt8], nb: [UInt8], c: [UInt8]) -> Bool { c.count == 32 && NineCrypto.constantTimeEqual(commitment(h: h, nb: nb), c) }
    public static func sasCode(h: [UInt8], na: [UInt8], nb: [UInt8]) -> UInt32 {
        let d = NineCrypto.sha256(Array("9ctl-sas".utf8), h, na, nb)
        return (UInt32(d[0]) << 24 | UInt32(d[1]) << 16 | UInt32(d[2]) << 8 | UInt32(d[3])) % 1_000_000
    }
    public static func formatCode(_ code: UInt32) -> String {
        let s = String(format: "%06u", code)
        return "\(s.prefix(3)) \(s.suffix(3))"
    }
    public static func hostId(_ staticPub: [UInt8]) -> String { NineCrypto.sha256(staticPub).prefix(8).map { String(format: "%02x", $0) }.joined() }
    public static func randomNonce() -> [UInt8] { NineCrypto.random(16) }
}

public struct PairURI: Equatable, Sendable {
    public var addr: String, port: UInt16, hostStatic: [UInt8], token: [UInt8], name: String
    /// Extra host addresses (hotspot, USB tethering, Ethernet…). SPEC §8.1.
    public var extra: [String] = []
    public var addresses: [String] { [addr] + extra }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~")
    private static func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? s }
    private static func b64url(_ b: [UInt8]) -> String {
        Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    private static func unb64url(_ s: String) -> [UInt8]? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t).map(Array.init)
    }

    public var uri: String {
        let base = "ninectl://pair?v=1&a=\(Self.enc(addr))&p=\(port)&k=\(Self.b64url(hostStatic))&t=\(Self.b64url(token))&n=\(Self.enc(name))"
        return extra.isEmpty ? base : base + "&x=" + extra.map(Self.enc).joined(separator: ",")
    }

    public init(addr: String, port: UInt16, hostStatic: [UInt8], token: [UInt8], name: String, extra: [String] = []) {
        self.addr = addr; self.port = port; self.hostStatic = hostStatic; self.token = token; self.name = name; self.extra = extra
    }

    public init?(parsing s: String) {
        guard s.hasPrefix("ninectl://pair?") else { return nil }
        var m: [String: String] = [:]
        for kv in s.dropFirst("ninectl://pair?".count).split(separator: "&") {
            guard let i = kv.firstIndex(of: "=") else { return nil }
            m[String(kv[..<i])] = String(kv[kv.index(after: i)...])
        }
        guard m["v"] == "1", let a = m["a"]?.removingPercentEncoding, let p = m["p"].flatMap({ UInt16($0) }),
              let k = m["k"].flatMap(Self.unb64url), k.count == 32, let t = m["t"].flatMap(Self.unb64url), t.count == 16 else { return nil }
        let extra = (m["x"] ?? "").split(separator: ",").compactMap { String($0).removingPercentEncoding }.prefix(8)
        self.init(addr: a, port: p, hostStatic: k, token: t, name: m["n"]?.removingPercentEncoding ?? "", extra: Array(extra))
    }
}
