import Foundation
import NineKit

/// Core Motion attitude → the body→world pose NineKit steering expects (docs/STEERING.md).
/// Apple documents the attitude quaternion's direction loosely, so it is checked against Core Motion's
/// own gravity (device frame, pointing down): body→world must map it onto world down (0, 0, −1) in the
/// .xArbitraryZVertical frame. If neither q nor its conjugate does, the gravity pose is used instead.
public enum MotionPose {
    public static let maxGravityErrorDeg = 10.0

    public static func make(attitude q: Quat, gravity g: Vec3) -> Pose? {
        guard let q = q.normalized(), let gn = g.normalized() else { return nil }
        let down = Vec3(0, 0, -1)
        if q.rotate(gn).angleDeg(down) <= maxGravityErrorDeg { return .orientation(q) }
        if q.conj.rotate(gn).angleDeg(down) <= maxGravityErrorDeg { return .orientation(q.conj) }
        return .gravity(gn)
    }
}

/// One controller session's steering: relative to the player's grip, never to "screen upright".
/// Thread-safe: `update` runs on the motion queue, `recenter` on the main thread.
public final class SteeringSession: @unchecked Sendable {
    public enum Event: Equatable, Sendable {
        /// A new neutral was stored. `regrip`: the grip moved over 30°, so the measured range may no longer fit.
        case neutralSet(SteeringCalibrationV2, regrip: Bool)
        /// The phone moved while the neutral was captured (RMS spread in degrees); nothing was changed.
        case unsteady(Double)
    }
    public static let captureS = 1.0
    public static let minSamples = 20

    private let lock = NSLock()
    private var steering: RelativeSteering
    private var capture: PoseCapture?
    private var captureEnd = 0.0
    private var captureRequested = false
    private let source: String
    private let clock: @Sendable () -> Int64
    private let onEvent: @Sendable (Event) -> Void

    /// With no stored calibration, the first steady second of play becomes the neutral automatically.
    public init(calibration: SteeringCalibrationV2?, motion: MotionSettings, source: String = "device motion",
                clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                onEvent: @escaping @Sendable (Event) -> Void) {
        steering = RelativeSteering(calibration?.validated(), motion)
        self.source = source; self.clock = clock; self.onEvent = onEvent
        captureRequested = steering.calibration == nil
    }

    public var calibration: SteeringCalibrationV2? { lock.withLock { steering.calibration } }
    public var angleDeg: Double? { lock.withLock { steering.angleDeg } }
    public var capturing: Bool { lock.withLock { capture != nil || captureRequested } }

    /// Hold still for `captureS`: the grip at that moment becomes neutral (axis and ranges are kept).
    public func recenter() { lock.withLock { captureRequested = true } }

    /// Steering in [−1, 1]. While a neutral is captured, steering keeps using the previous one.
    public func update(_ p: Pose, t: Double) -> Double {
        var event: Event?
        let out: Double = lock.withLock {
            if captureRequested { captureRequested = false; capture = PoseCapture(minSamples: Self.minSamples); captureEnd = t + Self.captureS }
            if var c = capture {
                c.add(p)
                capture = c
                if t >= captureEnd {
                    capture = nil
                    let held = c.finish()
                    if let held, held.spreadDeg <= PoseCapture.neutralMaxSpread {
                        let (cal, regrip) = SteeringCalibrator.recenter(steering.calibration, held, at: clock(), source: source)
                        steering.recalibrate(cal, t: t)
                        event = .neutralSet(cal, regrip: regrip)
                    } else {
                        event = .unsteady(held?.spreadDeg ?? .infinity)
                        if steering.calibration == nil { captureRequested = true } // keep trying until there is a neutral
                    }
                }
            }
            return steering.update(p, t: t)
        }
        if let event { onEvent(event) }
        return out
    }
}
