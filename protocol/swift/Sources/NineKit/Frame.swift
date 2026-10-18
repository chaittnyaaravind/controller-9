import Foundation

public enum PacketType: UInt8, Sendable { case input = 1, ping, pong, feedback, bye }
public enum FrameError: Error, Equatable { case tooShort, tooLong, badMagic, badVersion, unknownType, auth, badLength, outOfRange }

struct LE {
    var b: [UInt8] = []
    mutating func u16(_ v: UInt16) { b += [UInt8(v & 0xFF), UInt8(v >> 8)] }
    mutating func i16(_ v: Int16) { u16(UInt16(bitPattern: v)) }
    mutating func u32(_ v: UInt32) { for i in 0..<4 { b.append(UInt8(truncatingIfNeeded: v >> (8 * UInt32(i)))) } }
    mutating func u64(_ v: UInt64) { for i in 0..<8 { b.append(UInt8(truncatingIfNeeded: v >> (8 * UInt64(i)))) } }
}
struct LEReader {
    let b: [UInt8]; var o = 0
    init(_ b: [UInt8]) { self.b = b }
    mutating func u16() -> UInt16 { defer { o += 2 }; return UInt16(b[o]) | UInt16(b[o + 1]) << 8 }
    mutating func i16() -> Int16 { Int16(bitPattern: u16()) }
    mutating func u32() -> UInt32 { var v: UInt32 = 0; for i in 0..<4 { v |= UInt32(b[o + i]) << (8 * UInt32(i)) }; o += 4; return v }
    mutating func u64() -> UInt64 { var v: UInt64 = 0; for i in 0..<8 { v |= UInt64(b[o + i]) << (8 * UInt64(i)) }; o += 8; return v }
}

public struct Header: Equatable, Sendable {
    public var type: PacketType; public var sessionId: UInt32; public var counter: UInt64
    public init(type: PacketType, sessionId: UInt32, counter: UInt64) { self.type = type; self.sessionId = sessionId; self.counter = counter }
    public static let length = 16
    public func encode() -> [UInt8] { var w = LE(b: [0x39, 0x43, 1, type.rawValue]); w.u32(sessionId); w.u64(counter); return w.b }
    public static func peek(_ d: [UInt8]) throws -> Header {
        guard d.count >= 32 else { throw FrameError.tooShort }
        guard d.count <= 256 else { throw FrameError.tooLong }
        guard d[0] == 0x39, d[1] == 0x43 else { throw FrameError.badMagic }
        guard d[2] == 1 else { throw FrameError.badVersion }
        guard let t = PacketType(rawValue: d[3]) else { throw FrameError.unknownType }
        var r = LEReader(Array(d[4..<16]))
        return Header(type: t, sessionId: r.u32(), counter: r.u64())
    }
}

public enum Datagram {
    public static func seal(key: [UInt8], header: Header, payload: [UInt8]) -> [UInt8] {
        let h = header.encode()
        return h + (try! NineCrypto.seal(key, NineCrypto.nonce(header.counter), h, payload))
    }
    public static func open(key: [UInt8], _ d: [UInt8]) throws -> (Header, [UInt8]) {
        let h = try Header.peek(d)
        do { return (h, try NineCrypto.open(key, NineCrypto.nonce(h.counter), Array(d[0..<16]), Array(d[16...]))) }
        catch { throw FrameError.auth }
    }
}

public struct InputFrame: Equatable, Sendable {
    public var tUs: UInt64; public var state: InputState; public var flags: UInt16
    public init(tUs: UInt64, state: InputState, flags: UInt16) { self.tUs = tUs; self.state = state; self.flags = flags }
    public func encode() -> [UInt8] {
        var w = LE(); w.u64(tUs); w.u32(state.buttons)
        w.i16(state.lx); w.i16(state.ly); w.i16(state.rx); w.i16(state.ry); w.u16(state.lt); w.u16(state.rt); w.u16(flags)
        return w.b
    }
    public static func decode(_ b: [UInt8]) throws -> InputFrame {
        guard b.count == 26 else { throw FrameError.badLength }
        var r = LEReader(b)
        let t = r.u64(); let buttons = r.u32()
        let s = InputState(buttons: buttons, lx: r.i16(), ly: r.i16(), rx: r.i16(), ry: r.i16(), lt: r.u16(), rt: r.u16())
        let flags = r.u16()
        if buttons & ~validButtons != 0 || flags & ~validFlags != 0 || [s.lx, s.ly, s.rx, s.ry].contains(Int16.min) { throw FrameError.outOfRange }
        return InputFrame(tUs: t, state: s, flags: flags)
    }
}

public struct Ping: Equatable, Sendable {
    public var tUs: UInt64; public var id: UInt32
    public func encode() -> [UInt8] { var w = LE(); w.u64(tUs); w.u32(id); return w.b }
    public static func decode(_ b: [UInt8]) throws -> Ping { guard b.count == 12 else { throw FrameError.badLength }; var r = LEReader(b); return Ping(tUs: r.u64(), id: r.u32()) }
}
public struct Pong: Equatable, Sendable {
    public var tUs: UInt64; public var id: UInt32; public var hostRxUs: UInt64; public var hostTxUs: UInt64
    public func encode() -> [UInt8] { var w = LE(); w.u64(tUs); w.u32(id); w.u64(hostRxUs); w.u64(hostTxUs); return w.b }
    public static func decode(_ b: [UInt8]) throws -> Pong { guard b.count == 28 else { throw FrameError.badLength }; var r = LEReader(b); return Pong(tUs: r.u64(), id: r.u32(), hostRxUs: r.u64(), hostTxUs: r.u64()) }
}
public struct Feedback: Equatable, Sendable {
    public var low: UInt16; public var high: UInt16; public var ms: UInt16
    public func encode() -> [UInt8] { var w = LE(); w.u16(low); w.u16(high); w.u16(ms); return w.b }
    public static func decode(_ b: [UInt8]) throws -> Feedback {
        guard b.count == 6 else { throw FrameError.badLength }
        var r = LEReader(b); let f = Feedback(low: r.u16(), high: r.u16(), ms: r.u16())
        if f.ms > 5000 { throw FrameError.outOfRange }
        return f
    }
}

/// Receiver freshness: only counters above the highest accepted are applied.
public struct Freshness: Sendable {
    public private(set) var highest: UInt64 = 0, accepted: UInt64 = 0, stale: UInt64 = 0, lost: UInt64 = 0
    public init() {}
    public mutating func accept(_ c: UInt64) -> Bool {
        if c <= highest { stale += 1; return false }
        if highest != 0 { lost += c - highest - 1 }
        highest = c; accepted += 1
        return true
    }
}
