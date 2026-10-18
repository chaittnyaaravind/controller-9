import Foundation

/// Mirrors protocol/rust/src/motion.rs. Gravity is in screen coordinates (x right, y up).
public enum MotionMath {
    public static func wheelAngleDeg(_ gx: Double, _ gy: Double, _ gz: Double) -> Double? {
        let g = (gx * gx + gy * gy + gz * gz).squareRoot()
        let gxy = hypot(gx, gy)
        if g < 1e-6 || gxy < 0.35 * g { return nil }
        return atan2(gx, -gy) * 180 / .pi
    }
    public static func wrapDeg(_ d: Double) -> Double {
        var x = (d + 180).truncatingRemainder(dividingBy: 360)
        if x < 0 { x += 360 }
        return x - 180
    }
    public static func shape(_ deltaDeg: Double, _ m: MotionSettings) -> Double {
        var x = deltaDeg / m.lockDeg * m.sensitivity
        if m.invert { x = -x }
        x = min(max(x, -1), 1)
        let a = abs(x)
        if a <= m.deadzone { return 0 }
        let y = pow((a - m.deadzone) / (1 - m.deadzone), m.curve)
        return x < 0 ? -y : y
    }
    public static func radialDeadzone(_ x: Double, _ y: Double, _ dz: Double) -> (Double, Double) {
        let m = hypot(x, y)
        if m <= dz || m == 0 { return (0, 0) }
        let s = min((m - dz) / (1 - dz), 1) / m
        return (min(max(x * s, -1), 1), min(max(y * s, -1), 1))
    }
}

/// One Euro filter (Casiez, Roussel, Vogel — CHI 2012).
public struct OneEuro: Sendable {
    let minCutoff: Double, beta: Double, dCutoff: Double
    var prev: (x: Double, dx: Double, t: Double)?
    public init(minCutoff: Double, beta: Double, dCutoff: Double = 1) { self.minCutoff = minCutoff; self.beta = beta; self.dCutoff = dCutoff }
    static func alpha(_ cutoff: Double, _ dt: Double) -> Double { 1 / (1 + (1 / (2 * .pi * cutoff)) / dt) }
    public mutating func reset() { prev = nil }
    public mutating func filter(_ x: Double, _ t: Double) -> Double {
        guard let p = prev else { prev = (x, 0, t); return x }
        let dt = t > p.t ? t - p.t : 1.0 / 120
        let dx = (x - p.x) / dt
        let edx = p.dx + Self.alpha(dCutoff, dt) * (dx - p.dx)
        let cutoff = minCutoff + beta * abs(edx)
        let y = p.x + Self.alpha(cutoff, dt) * (x - p.x)
        prev = (y, edx, t)
        return y
    }
}

public struct Steering: Sendable {
    public let motion: MotionSettings
    public private(set) var centerDeg = 0.0
    var filter: OneEuro
    var lastDelta = 0.0
    public init(_ m: MotionSettings) { motion = m; filter = OneEuro(minCutoff: m.minCutoff, beta: m.beta) }
    public mutating func setCenter(_ deg: Double) { centerDeg = deg; filter.reset(); lastDelta = 0 }
    public mutating func update(_ gx: Double, _ gy: Double, _ gz: Double, t: Double) -> Double {
        let delta = MotionMath.wheelAngleDeg(gx, gy, gz).map { MotionMath.wrapDeg($0 - centerDeg) } ?? lastDelta
        lastDelta = delta
        return MotionMath.shape(filter.filter(delta, t), motion)
    }
}

public struct Calibration: Sendable { public let centerDeg: Double, spreadDeg: Double, samples: Int; public var steady: Bool { spreadDeg <= 1.5 } }

public struct Calibrator: Sendable {
    var s = 0.0, c = 0.0, samples: [Double] = []
    public init() {}
    public var count: Int { samples.count }
    public mutating func add(_ gx: Double, _ gy: Double, _ gz: Double) {
        guard let a = MotionMath.wheelAngleDeg(gx, gy, gz) else { return }
        s += sin(a * .pi / 180); c += cos(a * .pi / 180); samples.append(a)
    }
    public func finish() -> Calibration? {
        guard samples.count >= 10 else { return nil }
        let center = atan2(s, c) * 180 / .pi
        let v = samples.reduce(0) { $0 + pow(MotionMath.wrapDeg($1 - center), 2) } / Double(samples.count)
        return Calibration(centerDeg: center, spreadDeg: v.squareRoot(), samples: samples.count)
    }
}
