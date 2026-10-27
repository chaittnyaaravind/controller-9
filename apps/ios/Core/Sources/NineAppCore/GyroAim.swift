import Foundation

/// Gyro aim: how fast the phone turns becomes right-stick deflection, the way DualSense and Switch Pro
/// players aim. Same maths as Android's input/GyroAim.kt: yaw about gravity ("player space"), pitch about
/// the screen's horizontal axis, a small dead zone for hand tremor, full stick at 3 rad/s × sensitivity.
public enum GyroAimMath {
    public static let fullRate = 3.0
    public static let deadzone = 0.035

    /// Which way the screen's interface is turned (matches UIInterfaceOrientation without importing UIKit).
    public enum Screen: Sendable { case portrait, portraitUpsideDown, landscapeLeft, landscapeRight }

    /// Screen right in device coordinates (x right, y up, z out of the screen, as Core Motion reports).
    public static func screenRight(_ s: Screen) -> (Double, Double, Double) {
        switch s {
        case .portrait: return (1, 0, 0)
        case .portraitUpsideDown: return (-1, 0, 0)
        case .landscapeRight: return (0, -1, 0)   // device top to the left (home indicator on the right)
        case .landscapeLeft: return (0, 1, 0)
        }
    }

    /// Stick (x right, y up, each −1…1) from rotation rate `rate` (device rad/s) and `up` (device coordinates,
    /// any length). Core Motion's `gravity` points down, so callers pass its negation as `up`.
    public static func stick(rate w: (Double, Double, Double), up: (Double, Double, Double), right: (Double, Double, Double),
                             sensitivity: Double, invertY: Bool) -> (Double, Double) {
        let n = (up.0 * up.0 + up.1 * up.1 + up.2 * up.2).squareRoot()
        guard n > 1e-6 else { return (0, 0) }
        let yaw = -(w.0 * up.0 + w.1 * up.1 + w.2 * up.2) / n   // turning left is positive about "up"; aiming left is −x
        let pitch = w.0 * right.0 + w.1 * right.1 + w.2 * right.2 // tipping the far edge up aims up
        func shape(_ r: Double) -> Double {
            guard abs(r) >= deadzone else { return 0 }
            return (min((abs(r) - deadzone) * sensitivity / fullRate, 1)) * (r < 0 ? -1 : 1)
        }
        return (shape(yaw), shape(invertY ? -pitch : pitch))
    }
}
