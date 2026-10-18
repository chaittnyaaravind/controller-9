import Foundation

/// Button bit indices (SPEC §6.3).
public enum NineButton: Int, CaseIterable, Sendable {
    case a = 0, b, x, y, lb, rb, view, menu, ls, rs, up, down, left, right, guide
    public var mask: UInt32 { 1 << UInt32(rawValue) }
    public var id: String { ["a", "b", "x", "y", "lb", "rb", "view", "menu", "ls", "rs", "up", "down", "left", "right", "guide"][rawValue] }
    public init?(id: String) { guard let b = NineButton.allCases.first(where: { $0.id == id }) else { return nil }; self = b }
}

public let axisMax: Int16 = 32767
public let validButtons: UInt32 = 0x7FFF
public let validFlags: UInt16 = 0x7
public let flagMotion: UInt16 = 1
public let flagCalibrating: UInt16 = 2
public let flagPaused: UInt16 = 4
public let defaultTCPPort: UInt16 = 47901

/// Complete controller state. Y axes positive up; axes -32767...32767; triggers 0...65535.
public struct InputState: Equatable, Sendable {
    public var buttons: UInt32 = 0
    public var lx: Int16 = 0, ly: Int16 = 0, rx: Int16 = 0, ry: Int16 = 0
    public var lt: UInt16 = 0, rt: UInt16 = 0
    public init(buttons: UInt32 = 0, lx: Int16 = 0, ly: Int16 = 0, rx: Int16 = 0, ry: Int16 = 0, lt: UInt16 = 0, rt: UInt16 = 0) {
        self.buttons = buttons; self.lx = lx; self.ly = ly; self.rx = rx; self.ry = ry; self.lt = lt; self.rt = rt
    }
    public static let neutral = InputState()
    public var isNeutral: Bool { self == .neutral }
    public func pressed(_ b: NineButton) -> Bool { buttons & b.mask != 0 }
}

public func axis(fromUnit v: Double) -> Int16 { Int16((min(max(v, -1), 1) * 32767).rounded()) }
public func trigger(fromUnit v: Double) -> UInt16 { UInt16((min(max(v, 0), 1) * 65535).rounded()) }
