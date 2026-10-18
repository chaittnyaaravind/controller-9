import Foundation

/// Control-channel messages (SPEC §4); JSON with discriminator `t`.
public enum Control: Equatable, Sendable {
    case open(resumeSlot: Int?, profile: String?)
    case pairQr(proof: String)
    case pairCode
    case nonce(n: String)
    case codeOk, codeReject
    case stats(rttP50Ms: Float?, rttP95Ms: Float?, sensorHz: Float?, sendHz: Float?, batteryPct: Int?)
    case session(slot: Int, sessionId: UInt32, udpPort: UInt16, kIn: String, kOut: String, heartbeatMs: Int, idleHeartbeatMs: Int, timeoutMs: Int)
    case pairRequired(modes: [String])
    case commit(c: String)
    case reveal(n: String)
    case paired
    case pairFailed(reason: String)
    case rumble(low: Int, high: Int, ms: Int)
    case error(code: String, message: String?)
    case bye(reason: String?)
}

extension Control: Codable {
    enum K: String, CodingKey { case t, resumeSlot, profile, proof, n, rttP50Ms, rttP95Ms, sensorHz, sendHz, batteryPct, slot, sessionId, udpPort, kIn, kOut, heartbeatMs, idleHeartbeatMs, timeoutMs, modes, c, reason, low, high, ms, code, message }

    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: K.self)
        switch try c.decode(String.self, forKey: .t) {
        case "open": self = .open(resumeSlot: try c.decodeIfPresent(Int.self, forKey: .resumeSlot), profile: try c.decodeIfPresent(String.self, forKey: .profile))
        case "pair_qr": self = .pairQr(proof: try c.decode(String.self, forKey: .proof))
        case "pair_code": self = .pairCode
        case "nonce": self = .nonce(n: try c.decode(String.self, forKey: .n))
        case "code_ok": self = .codeOk
        case "code_reject": self = .codeReject
        case "stats": self = .stats(rttP50Ms: try c.decodeIfPresent(Float.self, forKey: .rttP50Ms), rttP95Ms: try c.decodeIfPresent(Float.self, forKey: .rttP95Ms),
                                    sensorHz: try c.decodeIfPresent(Float.self, forKey: .sensorHz), sendHz: try c.decodeIfPresent(Float.self, forKey: .sendHz),
                                    batteryPct: try c.decodeIfPresent(Int.self, forKey: .batteryPct))
        case "session": self = .session(slot: try c.decode(Int.self, forKey: .slot), sessionId: try c.decode(UInt32.self, forKey: .sessionId),
                                        udpPort: try c.decode(UInt16.self, forKey: .udpPort), kIn: try c.decode(String.self, forKey: .kIn), kOut: try c.decode(String.self, forKey: .kOut),
                                        heartbeatMs: try c.decode(Int.self, forKey: .heartbeatMs), idleHeartbeatMs: try c.decode(Int.self, forKey: .idleHeartbeatMs),
                                        timeoutMs: try c.decode(Int.self, forKey: .timeoutMs))
        case "pair_required": self = .pairRequired(modes: try c.decode([String].self, forKey: .modes))
        case "commit": self = .commit(c: try c.decode(String.self, forKey: .c))
        case "reveal": self = .reveal(n: try c.decode(String.self, forKey: .n))
        case "paired": self = .paired
        case "pair_failed": self = .pairFailed(reason: try c.decode(String.self, forKey: .reason))
        case "rumble": self = .rumble(low: try c.decode(Int.self, forKey: .low), high: try c.decode(Int.self, forKey: .high), ms: try c.decode(Int.self, forKey: .ms))
        case "error": self = .error(code: try c.decode(String.self, forKey: .code), message: try c.decodeIfPresent(String.self, forKey: .message))
        case "bye": self = .bye(reason: try c.decodeIfPresent(String.self, forKey: .reason))
        case let t: throw DecodingError.dataCorruptedError(forKey: .t, in: c, debugDescription: "unknown message type \(t)")
        }
    }

    public func encode(to e: Encoder) throws {
        var c = e.container(keyedBy: K.self)
        switch self {
        case let .open(r, p): try c.encode("open", forKey: .t); try c.encodeIfPresent(r, forKey: .resumeSlot); try c.encodeIfPresent(p, forKey: .profile)
        case let .pairQr(proof): try c.encode("pair_qr", forKey: .t); try c.encode(proof, forKey: .proof)
        case .pairCode: try c.encode("pair_code", forKey: .t)
        case let .nonce(n): try c.encode("nonce", forKey: .t); try c.encode(n, forKey: .n)
        case .codeOk: try c.encode("code_ok", forKey: .t)
        case .codeReject: try c.encode("code_reject", forKey: .t)
        case let .stats(a, b, s, h, bat):
            try c.encode("stats", forKey: .t); try c.encodeIfPresent(a, forKey: .rttP50Ms); try c.encodeIfPresent(b, forKey: .rttP95Ms)
            try c.encodeIfPresent(s, forKey: .sensorHz); try c.encodeIfPresent(h, forKey: .sendHz); try c.encodeIfPresent(bat, forKey: .batteryPct)
        case let .session(slot, sid, port, kIn, kOut, hb, ihb, to):
            try c.encode("session", forKey: .t); try c.encode(slot, forKey: .slot); try c.encode(sid, forKey: .sessionId); try c.encode(port, forKey: .udpPort)
            try c.encode(kIn, forKey: .kIn); try c.encode(kOut, forKey: .kOut); try c.encode(hb, forKey: .heartbeatMs); try c.encode(ihb, forKey: .idleHeartbeatMs); try c.encode(to, forKey: .timeoutMs)
        case let .pairRequired(m): try c.encode("pair_required", forKey: .t); try c.encode(m, forKey: .modes)
        case let .commit(x): try c.encode("commit", forKey: .t); try c.encode(x, forKey: .c)
        case let .reveal(n): try c.encode("reveal", forKey: .t); try c.encode(n, forKey: .n)
        case .paired: try c.encode("paired", forKey: .t)
        case let .pairFailed(r): try c.encode("pair_failed", forKey: .t); try c.encode(r, forKey: .reason)
        case let .rumble(l, h, ms): try c.encode("rumble", forKey: .t); try c.encode(l, forKey: .low); try c.encode(h, forKey: .high); try c.encode(ms, forKey: .ms)
        case let .error(code, m): try c.encode("error", forKey: .t); try c.encode(code, forKey: .code); try c.encodeIfPresent(m, forKey: .message)
        case let .bye(r): try c.encode("bye", forKey: .t); try c.encodeIfPresent(r, forKey: .reason)
        }
    }

    public func json() -> [UInt8] { Array(try! JSONEncoder().encode(self)) }
    public static func parse(_ b: [UInt8]) throws -> Control { try JSONDecoder().decode(Control.self, from: Data(b)) }
}

extension Control: CustomStringConvertible {
    /// Never prints session keys.
    public var description: String {
        if case let .session(slot, _, port, _, _, _, _, _) = self { return "session(slot: \(slot), udpPort: \(port), keys: <redacted>)" }
        return String(decoding: json(), as: UTF8.self) // no other message carries a secret
    }
}

public struct InitHello: Codable, Sendable { public var proto: [Int] }
public struct HostHello: Codable, Sendable { public var proto: Int; public var name: String; public var id: String; public var os: String }
public struct ControllerHello: Codable, Sendable {
    public var proto: Int; public var name: String; public var platform: String; public var app: String
    public init(name: String, platform: String, app: String) { proto = 1; self.name = name; self.platform = platform; self.app = app }
}
