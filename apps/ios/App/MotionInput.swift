import CoreMotion
import NineAppCore
import NineKit

/// Body-frame orientation from Core Motion device motion (sensor fusion), only while playing.
/// The display orientation is never an input: steering is relative to the player's grip (docs/STEERING.md).
final class MotionInput {
    private let manager = CMMotionManager()
    private let queue: OperationQueue = { let q = OperationQueue(); q.qualityOfService = .userInteractive; q.maxConcurrentOperationCount = 1; return q }()
    private(set) var measuredHz = 0.0
    var available: Bool { manager.isDeviceMotionAvailable }
    /// Gyro aim: rotation rate (rad/s) and gravity (g, pointing down), device coordinates, every sample.
    /// Set on the main thread, read on the motion queue: guarded so leaving the screen can't race a sample.
    var onRate: (((Double, Double, Double), (Double, Double, Double)) -> Void)? {
        get { rateLock.withLock { _onRate } }
        set { rateLock.withLock { _onRate = newValue } }
    }
    private var _onRate: (((Double, Double, Double), (Double, Double, Double)) -> Void)?
    private let rateLock = NSLock()

    /// The sink runs on a background queue with the pose and the sample time in seconds.
    func start(hz: Int, sink: @escaping (Pose, Double) -> Void) {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1.0 / Double(max(50, min(hz, 200)))
        var windowStart = 0.0, count = 0
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: queue) { [weak self] m, _ in
            guard let self, let m else { return }
            let a = m.attitude.quaternion, g = m.gravity
            if let pose = MotionPose.make(attitude: Quat(a.w, a.x, a.y, a.z), gravity: Vec3(g.x, g.y, g.z)) { sink(pose, m.timestamp) }
            if let onRate = self.onRate { let r = m.rotationRate; onRate((r.x, r.y, r.z), (g.x, g.y, g.z)) }
            count += 1
            if windowStart == 0 { windowStart = m.timestamp }
            if m.timestamp - windowStart >= 1 { self.measuredHz = Double(count) / (m.timestamp - windowStart); windowStart = m.timestamp; count = 0 }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates(); measuredHz = 0; onRate = nil }
}
