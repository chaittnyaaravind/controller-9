import CoreMotion
import UIKit

/// Gravity in screen coordinates from Core Motion device motion (sensor fusion), only while playing.
final class MotionInput {
    private let manager = CMMotionManager()
    private let queue: OperationQueue = { let q = OperationQueue(); q.qualityOfService = .userInteractive; q.maxConcurrentOperationCount = 1; return q }()
    private(set) var measuredHz = 0.0
    var available: Bool { manager.isDeviceMotionAvailable }

    /// `orientation` is read on the main thread at start; the sink runs on a background queue.
    func start(hz: Int, orientation: UIInterfaceOrientation, sink: @escaping (Double, Double, Double, Double) -> Void) {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1.0 / Double(max(50, min(hz, 200)))
        var windowStart = 0.0, count = 0
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: queue) { [weak self] m, _ in
            guard let self, let m else { return }
            let g = m.gravity // device frame, unit g, pointing down
            let (sx, sy): (Double, Double)
            switch orientation {
            case .landscapeRight: (sx, sy) = (-g.y, g.x) // top of the phone at the left
            case .landscapeLeft: (sx, sy) = (g.y, -g.x)
            case .portraitUpsideDown: (sx, sy) = (-g.x, -g.y)
            default: (sx, sy) = (g.x, g.y)
            }
            sink(sx, sy, g.z, m.timestamp)
            count += 1
            if windowStart == 0 { windowStart = m.timestamp }
            if m.timestamp - windowStart >= 1 { self.measuredHz = Double(count) / (m.timestamp - windowStart); windowStart = m.timestamp; count = 0 }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates(); measuredHz = 0 }
}
