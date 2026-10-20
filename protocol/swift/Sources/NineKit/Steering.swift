import Foundation

// Relative steering — Swift mirror of protocol/rust/src/steering.rs (docs/STEERING.md); must reproduce
// protocol/vectors/steering.json. Core Motion's attitude quaternion maps the device (body) frame to its
// reference frame, the same convention as Android's rotation vector: x right of the screen in portrait,
// y up the screen, z out of the screen. The interface orientation is never used.

public struct Vec3: Equatable, Sendable {
    public var x, y, z: Double
    public init(_ x: Double, _ y: Double, _ z: Double) { self.x = x; self.y = y; self.z = z }
    public static let screenClockwise = Vec3(0, 0, -1)
    public static func + (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }
    public static func - (a: Vec3, b: Vec3) -> Vec3 { Vec3(a.x - b.x, a.y - b.y, a.z - b.z) }
    public static func * (a: Vec3, k: Double) -> Vec3 { Vec3(a.x * k, a.y * k, a.z * k) }
    public static prefix func - (a: Vec3) -> Vec3 { a * -1 }
    public func dot(_ o: Vec3) -> Double { x * o.x + y * o.y + z * o.z }
    public func cross(_ o: Vec3) -> Vec3 { Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x) }
    public var norm: Double { dot(self).squareRoot() }
    public func normalized() -> Vec3? { let n = norm; return n >= 1e-9 && n.isFinite ? self * (1 / n) : nil }
    public func angleDeg(_ o: Vec3) -> Double { acos(max(-1, min(1, dot(o) / (norm * o.norm)))) * 180 / .pi }
    public var array: [Double] { [x, y, z] }
    public init?(_ a: [Double]) { guard a.count == 3, a.allSatisfy(\.isFinite) else { return nil }; self.init(a[0], a[1], a[2]) }
}

public struct Quat: Equatable, Sendable {
    public var w, x, y, z: Double
    public init(_ w: Double, _ x: Double, _ y: Double, _ z: Double) { self.w = w; self.x = x; self.y = y; self.z = z }
    public static let identity = Quat(1, 0, 0, 0)
    /// Hamilton product: a * b applies b first, then a.
    public static func * (a: Quat, o: Quat) -> Quat {
        Quat(a.w * o.w - a.x * o.x - a.y * o.y - a.z * o.z,
             a.w * o.x + a.x * o.w + a.y * o.z - a.z * o.y,
             a.w * o.y - a.x * o.z + a.y * o.w + a.z * o.x,
             a.w * o.z + a.x * o.y - a.y * o.x + a.z * o.w)
    }
    public var conj: Quat { Quat(w, -x, -y, -z) }
    public func dot(_ o: Quat) -> Double { w * o.w + x * o.x + y * o.y + z * o.z }
    public var vec: Vec3 { Vec3(x, y, z) }
    public func normalized() -> Quat? { let n = dot(self).squareRoot(); return n >= 1e-9 && n.isFinite ? Quat(w / n, x / n, y / n, z / n) : nil }
    public var canonical: Quat { w < 0 ? Quat(-w, -x, -y, -z) : self }
    public var angleDeg: Double { 2 * atan2(vec.norm, abs(w)) * 180 / .pi }
    public var rotationVector: Vec3 {
        let c = canonical, s = c.vec.norm
        return s < 1e-12 ? Vec3(0, 0, 0) : c.vec * (2 * atan2(s, c.w) / s)
    }
    public func rotate(_ v: Vec3) -> Vec3 { (self * Quat(0, v.x, v.y, v.z) * conj).vec }
    public static func axisAngle(_ axis: Vec3, _ deg: Double) -> Quat {
        guard let a = axis.normalized() else { return .identity }
        let h = deg * .pi / 360
        return Quat(cos(h), a.x * sin(h), a.y * sin(h), a.z * sin(h))
    }
    public var array: [Double] { [w, x, y, z] }
    public init?(_ a: [Double]) {
        guard a.count == 4, a.allSatisfy(\.isFinite), let q = Quat(a[0], a[1], a[2], a[3]).normalized() else { return nil }
        self = q
    }
}

/// One motion sample: a full orientation, or gravity only (points down, body frame).
public enum Pose: Equatable, Sendable { case orientation(Quat), gravity(Vec3) }

public enum SteeringMath {
    public static func twistDeg(_ r: Quat, _ axis: Vec3) -> Double {
        let c = r.canonical
        return MotionMath.wrapDeg(2 * atan2(c.vec.dot(axis), c.w) * 180 / .pi)
    }
    public static func relative(_ neutral: Quat, _ q: Quat) -> Quat { neutral.conj * q }
    public static func gravityTwistDeg(_ g0: Vec3, _ g: Vec3, _ axis: Vec3) -> Double? {
        let p0 = g0 - axis * g0.dot(axis), p = g - axis * g.dot(axis)
        if p0.norm < 0.25 * g0.norm || p.norm < 0.25 * g.norm { return nil }
        return -atan2(axis.dot(p0.cross(p)), p0.dot(p)) * 180 / .pi
    }
    public static func mean(_ qs: [Quat]) -> Quat? {
        guard let r = qs.first else { return nil }
        var m = Quat(0, 0, 0, 0)
        for q in qs { let s: Double = q.dot(r) < 0 ? -1 : 1; m = Quat(m.w + s * q.w, m.x + s * q.x, m.y + s * q.y, m.z + s * q.z) }
        return m.normalized()
    }
}

public struct HeldPose: Equatable, Sendable {
    public let pose: Pose, spreadDeg: Double, samples: Int
    public init(_ pose: Pose, spreadDeg: Double, samples: Int) { self.pose = pose; self.spreadDeg = spreadDeg; self.samples = samples }
}

/// Short still window → robust pose: mean, drop samples beyond max(3σ, 1°), mean again.
public struct PoseCapture: Sendable {
    public static let neutralMaxSpread = 1.5, extremeMaxSpread = 3.0
    private var qs: [Quat] = [], gs: [Vec3] = []
    private let minSamples: Int
    public init(minSamples: Int = 20) { self.minSamples = minSamples }
    public var count: Int { qs.count + gs.count }
    public mutating func add(_ p: Pose) { switch p { case .orientation(let q): qs.append(q); case .gravity(let g): gs.append(g) } }
    private static func rms(_ d: [Double]) -> Double { (d.map { $0 * $0 }.reduce(0, +) / Double(d.count)).squareRoot() }
    public func finish() -> HeldPose? {
        if qs.count >= minSamples {
            guard let m0 = SteeringMath.mean(qs) else { return nil }
            let lim = max(3 * Self.rms(qs.map { (m0.conj * $0).angleDeg }), 1)
            let kept = qs.filter { (m0.conj * $0).angleDeg <= lim }
            guard let m = SteeringMath.mean(kept) else { return nil }
            return HeldPose(.orientation(m), spreadDeg: Self.rms(kept.map { (m.conj * $0).angleDeg }), samples: kept.count)
        }
        if gs.count >= minSamples {
            func meanG(_ l: [Vec3]) -> Vec3? { l.reduce(Vec3(0, 0, 0)) { $0 + ($1.normalized() ?? $1) }.normalized() }
            guard let m0 = meanG(gs) else { return nil }
            let lim = max(3 * Self.rms(gs.map { m0.angleDeg($0) }), 1)
            let kept = gs.filter { m0.angleDeg($0) <= lim }
            guard let m = meanG(kept) else { return nil }
            return HeldPose(.gravity(m), spreadDeg: Self.rms(kept.map { m.angleDeg($0) }), samples: kept.count)
        }
        return nil
    }
}

/// Steering calibration, schema 2 — same JSON as Rust and Kotlin.
public struct SteeringCalibrationV2: Codable, Equatable, Sendable {
    public static let version = 2, minRangeDeg = 10.0
    public var v = 2
    public var kind: String
    public var q: [Double]?, g: [Double]?
    public var axis = Vec3.screenClockwise.array
    public var axisMeasured = false
    public var leftDeg: Double?, rightDeg: Double?
    public var noiseDeg: Double
    public var measuredAt: Int64
    public var source = ""

    enum CodingKeys: String, CodingKey { case v, kind, q, g, axis, axisMeasured, leftDeg, rightDeg, noiseDeg, measuredAt, source }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        v = try c.decodeIfPresent(Int.self, forKey: .v) ?? 2
        kind = try c.decode(String.self, forKey: .kind)
        q = try c.decodeIfPresent([Double].self, forKey: .q); g = try c.decodeIfPresent([Double].self, forKey: .g)
        axis = try c.decodeIfPresent([Double].self, forKey: .axis) ?? Vec3.screenClockwise.array
        axisMeasured = try c.decodeIfPresent(Bool.self, forKey: .axisMeasured) ?? false
        leftDeg = try c.decodeIfPresent(Double.self, forKey: .leftDeg); rightDeg = try c.decodeIfPresent(Double.self, forKey: .rightDeg)
        noiseDeg = try c.decode(Double.self, forKey: .noiseDeg)
        measuredAt = try c.decode(Int64.self, forKey: .measuredAt)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? ""
    }
    public init(neutral h: HeldPose, at: Int64, source: String) {
        switch h.pose { case .orientation(let x): kind = "orientation"; q = x.array; case .gravity(let x): kind = "gravity"; g = x.array }
        noiseDeg = h.spreadDeg; measuredAt = at; self.source = source
    }

    public var neutral: Pose? {
        switch kind {
        case "orientation": return q.flatMap(Quat.init).map(Pose.orientation)
        case "gravity": return g.flatMap(Vec3.init)?.normalized().map(Pose.gravity)
        default: return nil
        }
    }
    public var axisVec: Vec3? { Vec3(axis)?.normalized() }
    public var hasRange: Bool { leftDeg != nil && rightDeg != nil }
    public var suggestedDeadzone: Double? { guard let l = leftDeg, let r = rightDeg else { return nil }; return max(0, min(0.1, 3 * noiseDeg / min(l, r))) }
    public func validated() -> SteeringCalibrationV2? {
        guard v == Self.version, neutral != nil, axisVec != nil, noiseDeg.isFinite, noiseDeg >= 0, (leftDeg == nil) == (rightDeg == nil) else { return nil }
        for r in [leftDeg, rightDeg].compactMap({ $0 }) where !r.isFinite || r < Self.minRangeDeg || r > 180 { return nil }
        return self
    }
    public static func decode(_ s: String?) -> SteeringCalibrationV2? {
        guard let d = s?.data(using: .utf8) else { return nil }
        return (try? JSONDecoder().decode(Self.self, from: d))?.validated()
    }
    public func encode() -> String { String(data: (try? JSONEncoder().encode(self)) ?? Data(), encoding: .utf8) ?? "" }
}

public enum RangeResult: Equatable, Sendable { case ok(SteeringCalibrationV2, axisToScreenNormalDeg: Double), retry(String) }

public enum SteeringCalibrator {
    public static let maxAxisDisagreementDeg = 40.0

    public static func range(_ neutral: SteeringCalibrationV2, left: HeldPose, right: HeldPose) -> RangeResult {
        if left.spreadDeg > PoseCapture.extremeMaxSpread || right.spreadDeg > PoseCapture.extremeMaxSpread {
            return .retry("The phone moved while holding a full turn. Hold it still at full turn.")
        }
        let minR = SteeringCalibrationV2.minRangeDeg
        switch (neutral.neutral, left.pose, right.pose) {
        case let (.orientation(n)?, .orientation(l), .orientation(r)):
            let rl = SteeringMath.relative(n, l).rotationVector, rr = SteeringMath.relative(n, r).rotationVector
            if rl.norm * 180 / .pi < minR || rr.norm * 180 / .pi < minR { return .retry("Turn further, then hold.") }
            if rr.angleDeg(-rl) > maxAxisDisagreementDeg { return .retry("Left and right weren’t turned around the same axis. Turn like a steering wheel.") }
            guard let axis = (rr - rl).normalized() else { return .retry("Couldn’t work out the steering axis. Try again.") }
            let rd = SteeringMath.twistDeg(SteeringMath.relative(n, r), axis), ld = -SteeringMath.twistDeg(SteeringMath.relative(n, l), axis)
            if rd < minR || ld < minR { return .retry("The turns were too small. Turn further, then hold.") }
            var c = neutral; c.axis = axis.array; c.axisMeasured = true; c.leftDeg = ld; c.rightDeg = rd
            return .ok(c, axisToScreenNormalDeg: axis.angleDeg(.screenClockwise))
        case let (.gravity(n)?, .gravity(l), .gravity(r)):
            let a = Vec3.screenClockwise
            guard let rd = SteeringMath.gravityTwistDeg(n, r, a), let l0 = SteeringMath.gravityTwistDeg(n, l, a) else { return .retry("Hold the phone more upright.") }
            if rd < minR || -l0 < minR { return .retry("The turns were too small. Turn further, then hold.") }
            var c = neutral; c.leftDeg = -l0; c.rightDeg = rd
            return .ok(c, axisToScreenNormalDeg: 0)
        default: return .retry("The motion sensor changed during calibration. Start over.")
        }
    }

    /// New neutral keeping axis and ranges; true when the grip moved over 30° (re-measure the range).
    public static func recenter(_ old: SteeringCalibrationV2?, _ h: HeldPose, at: Int64, source: String) -> (SteeringCalibrationV2, Bool) {
        let fresh = SteeringCalibrationV2(neutral: h, at: at, source: source)
        guard let old, old.kind == fresh.kind else { return (fresh, old?.hasRange == true) }
        var moved = 0.0
        switch (old.neutral, h.pose) {
        case let (.orientation(a)?, .orientation(b)): moved = (a.conj * b).angleDeg
        case let (.gravity(a)?, .gravity(b)): moved = a.angleDeg(b)
        default: break
        }
        var c = old; c.q = fresh.q; c.g = fresh.g; c.noiseDeg = h.spreadDeg; c.measuredAt = at; c.source = source
        return (c, old.hasRange && moved > 30)
    }
}

/// Poses → steering in [−1, 1] relative to a calibration; recenter blends over `blendS`.
public struct RelativeSteering: Sendable {
    public static let blendS = 0.25
    public private(set) var calibration: SteeringCalibrationV2?
    private let motion: MotionSettings
    private var filter: OneEuro
    private var unwrapped: Double?
    private var lastOut = 0.0, blendFrom = 0.0
    private var blendStart: Double?
    /// Filtered angle from neutral; nil when not measurable.
    public private(set) var angleDeg: Double?

    public init(_ cal: SteeringCalibrationV2?, _ motion: MotionSettings) {
        calibration = cal; self.motion = motion; filter = OneEuro(minCutoff: motion.minCutoff, beta: motion.beta)
    }

    public func measure(_ p: Pose) -> Double? {
        guard let c = calibration, let axis = c.axisVec else { return nil }
        switch (c.neutral, p) {
        case let (.orientation(n)?, .orientation(q)): return SteeringMath.twistDeg(SteeringMath.relative(n, q), axis)
        case let (.gravity(n)?, .gravity(g)): return SteeringMath.gravityTwistDeg(n, g, axis)
        default: return nil
        }
    }

    public func normalize(_ deg: Double) -> Double {
        let range = (deg >= 0 ? calibration?.rightDeg : calibration?.leftDeg) ?? motion.lockDeg
        var x = deg / range * motion.sensitivity
        if motion.invert { x = -x }
        x = max(-1, min(1, x))
        let a = abs(x)
        if a <= motion.deadzone { return 0 }
        let y = pow((a - motion.deadzone) / (1 - motion.deadzone), motion.curve)
        return x < 0 ? -y : y
    }

    public mutating func update(_ p: Pose, t: Double) -> Double {
        let target: Double
        if let raw = measure(p) {
            var u = raw
            if let prev = unwrapped { u = prev + MotionMath.wrapDeg(raw - prev); if abs(u) > 270 { u = raw } }
            unwrapped = u
            let f = filter.filter(u, t)
            angleDeg = f
            target = normalize(f)
        } else { unwrapped = nil; filter.reset(); angleDeg = nil; target = 0 }
        var out = target
        if let t0 = blendStart {
            let k = max(0, min(1, (t - t0) / Self.blendS))
            if k >= 1 { blendStart = nil }
            out = target + (blendFrom - target) * (1 - k)
        }
        lastOut = max(-1, min(1, out))
        return lastOut
    }

    public mutating func recalibrate(_ c: SteeringCalibrationV2?, t: Double) {
        blendFrom = lastOut; blendStart = t; calibration = c; unwrapped = nil; filter.reset()
    }
}
