import Foundation
import NineKit

/// Merges touch controls and motion steering into one protocol InputState (thread-safe).
public final class ControllerEngine: @unchecked Sendable {
    private let lock = NSLock()
    private var buttons: [String: UInt32] = [:]
    private var triggers: [String: (String, Double)] = [:]
    private var left = (0.0, 0.0), right = (0.0, 0.0), aim = (0.0, 0.0)
    private var motion = 0.0, motionAxis = "lx", motionOn = false, calibrating = false
    public private(set) var state = InputState.neutral
    public private(set) var flags: UInt16 = 0
    private let onChange: @Sendable (InputState, UInt16) -> Void

    public init(onChange: @escaping @Sendable (InputState, UInt16) -> Void) { self.onChange = onChange }

    public func configure(_ m: MotionSettings) { update { motionAxis = m.axis; motionOn = m.enabled; if !m.enabled { motion = 0 } } }
    public func setButtons(_ id: String, _ mask: UInt32) { update { if mask == 0 { buttons[id] = nil } else { buttons[id] = mask } } }
    public func setStick(_ side: String, _ x: Double, _ y: Double) { update { if side == "right" { right = (x, y) } else { left = (x, y) } } }
    public func setTrigger(_ id: String, _ which: String, _ v: Double) { update { if v <= 0 { triggers[id] = nil } else { triggers[id] = (which, v) } } }
    public func setMotion(_ v: Double) { update { motion = v } }
    public func setCalibrating(_ c: Bool) { update { calibrating = c } }
    /// Gyro aim, added to the right stick (same as Android's ControllerEngine.setAim).
    public func setAim(_ x: Double, _ y: Double) { update { aim = (x, y) } }
    public func releaseAll() { update { buttons = [:]; triggers = [:]; left = (0, 0); right = (0, 0); aim = (0, 0); motion = 0 } }

    private func update(_ f: () -> Void) {
        let emit: (InputState, UInt16)? = lock.withLock {
            f()
            var lx = left.0, rx = right.0 + aim.0
            if motionOn { if motionAxis == "rx" { rx += motion } else { lx += motion } }
            let s = InputState(buttons: buttons.values.reduce(0, |),
                               lx: axis(fromUnit: lx), ly: axis(fromUnit: left.1), rx: axis(fromUnit: rx), ry: axis(fromUnit: right.1 + aim.1),
                               lt: trigger(fromUnit: triggers.values.filter { $0.0 == "lt" }.map(\.1).max() ?? 0),
                               rt: trigger(fromUnit: triggers.values.filter { $0.0 == "rt" }.map(\.1).max() ?? 0))
            let fl: UInt16 = (motionOn ? flagMotion : 0) | (calibrating ? flagCalibrating : 0)
            if s == state && fl == flags { return nil }
            state = s; flags = fl
            return (s, fl)
        }
        if let (s, f) = emit { onChange(s, f) }
    }
}
