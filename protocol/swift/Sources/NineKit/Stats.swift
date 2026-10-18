import Foundation

/// Fixed-size ring with nearest-rank percentiles (same definition as the Rust host).
public final class Window: @unchecked Sendable {
    private var buf: [Double]
    private var len = 0, next = 0
    private let lock = NSLock()
    public private(set) var total: UInt64 = 0
    public init(capacity: Int) { buf = Array(repeating: 0, count: capacity) }
    public var count: Int { lock.withLock { len } }
    public func push(_ v: Double) {
        lock.withLock { buf[next] = v; next = (next + 1) % buf.count; len = min(len + 1, buf.count); total += 1 }
    }
    public func percentile(_ p: Double) -> Double? {
        lock.withLock {
            guard len > 0 else { return nil }
            let s = buf[0..<len].sorted()
            let rank = max(1, Int((p / 100 * Double(len)).rounded(.up)))
            return s[min(rank, len) - 1]
        }
    }
}
