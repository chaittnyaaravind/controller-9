import Foundation

/// Transport bindings (SPEC §8) — mirrors protocol/rust/src/link.rs.
public enum LinkConst {
    public static let recControl: UInt8 = 0x01
    public static let recDatagram: UInt8 = 0x02
    public static let streamTCPPort: UInt16 = 47902
    public static let bleService = "c9c90001-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    public static let bleControlUp = "c9c90002-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    public static let bleControlDown = "c9c90003-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    public static let bleInputUp = "c9c90004-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    public static let bleInputDown = "c9c90005-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    static let segFirst: UInt8 = 0x80
    static let segLast: UInt8 = 0x40
    /// iOS advertises the host hint in its local name: `C9-<8 hex digits>`.
    public static func bleName(hint: [UInt8]) -> String { "C9-" + hint.prefix(4).map { String(format: "%02x", $0) }.joined() }
}

public enum LinkError: Error, Equatable { case length, kind(UInt8) }

public enum Record: Equatable, Sendable {
    case control([UInt8]), datagram([UInt8])
    public func encode() -> [UInt8] {
        let (k, b): (UInt8, [UInt8]) = { switch self { case let .control(b): return (LinkConst.recControl, b); case let .datagram(b): return (LinkConst.recDatagram, b) } }()
        precondition(!b.isEmpty && b.count <= 65535)
        return [k, UInt8(b.count >> 8), UInt8(b.count & 0xFF)] + b
    }
}

public struct RecordReader: Sendable {
    var buf: [UInt8] = []
    public init() {}
    public mutating func push(_ b: [UInt8]) { buf += b }
    public mutating func next() throws -> Record? {
        guard buf.count >= 3 else { return nil }
        let kind = buf[0], len = Int(buf[1]) << 8 | Int(buf[2])
        guard len > 0 else { throw LinkError.length }
        guard kind == LinkConst.recControl || kind == LinkConst.recDatagram else { throw LinkError.kind(kind) }
        guard buf.count >= 3 + len else { return nil }
        let body = Array(buf[3..<3 + len])
        buf.removeFirst(3 + len)
        return kind == LinkConst.recControl ? .control(body) : .datagram(body)
    }
}

public struct Segmenter: Sendable {
    var counter: UInt8 = 0
    public init() {}
    public mutating func segment(_ msg: [UInt8], attMtu: Int) -> [[UInt8]] {
        let chunk = max(attMtu - 4, 1)
        let pieces: [[UInt8]] = msg.isEmpty ? [[]] : stride(from: 0, to: msg.count, by: chunk).map { Array(msg[$0..<min($0 + chunk, msg.count)]) }
        return pieces.enumerated().map { i, c in
            var hdr = counter & 0x3F
            counter = (counter &+ 1) & 0x3F
            if i == 0 { hdr |= LinkConst.segFirst }
            if i == pieces.count - 1 { hdr |= LinkConst.segLast }
            return [hdr] + c
        }
    }
}

public struct Reassembler: Sendable {
    var expect: UInt8?, partial: [UInt8]?, discarding = false
    let max: Int
    public private(set) var dropped: UInt64 = 0
    public init(max: Int) { self.max = max }
    public mutating func push(_ v: [UInt8]) -> [UInt8]? {
        guard let hdr = v.first else { return nil }
        let ctr = hdr & 0x3F
        let gap = expect.map { $0 != ctr } ?? false
        expect = (ctr + 1) & 0x3F
        if gap, partial != nil { partial = nil; dropped += 1 }
        if hdr & LinkConst.segFirst != 0 {
            if partial != nil { dropped += 1 }
            partial = []; discarding = false
        }
        guard var p = partial else {
            if !discarding { dropped += 1 }
            discarding = hdr & LinkConst.segLast == 0
            return nil
        }
        p += v.dropFirst()
        if p.count > max { partial = nil; dropped += 1; discarding = hdr & LinkConst.segLast == 0; return nil }
        if hdr & LinkConst.segLast != 0 { partial = nil; return p }
        partial = p
        return nil
    }
}
