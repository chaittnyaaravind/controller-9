import Foundation
import NineKit

/// How a button behaves when held, per element of a controller layout — same as Android's pad/ButtonModes.kt:
/// turbo fires repeatedly while held; toggle latches (tap to hold, tap again to let go).
/// Stored per layout as `elementId=turbo;elementId=toggle` (AppSettings.padModes), the format Android uses.
public enum ButtonModes {
    public enum Mode: String, CaseIterable, Sendable {
        case normal, turbo, toggle
        public var title: String { switch self { case .normal: "Normal"; case .turbo: "Turbo"; case .toggle: "Toggle" } }
        public var detail: String {
            switch self {
            case .normal: "Pressed while your thumb is on it"
            case .turbo: "Fires about 12 times a second while held"
            case .toggle: "Tap to hold, tap again to let go"
            }
        }
    }

    /// 40 ms down, 40 ms up: every press spans several frames on every link.
    public static let turboHalfPeriod: TimeInterval = 0.040

    public static func decode(_ text: String?) -> [String: Mode] {
        var out: [String: Mode] = [:]
        for part in (text ?? "").split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard kv.count == 2, !kv[0].isEmpty, let m = Mode(rawValue: kv[1]), m != .normal else { continue }
            out[kv[0]] = m
        }
        return out
    }

    public static func encode(_ modes: [String: Mode]) -> String {
        modes.filter { $0.value != .normal }.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value.rawValue)" }.joined(separator: ";")
    }

    /// The layout's controls with toggle buttons marked (turbo is run by TurboPulser on the controller screen).
    public static func apply(_ modes: [String: Mode], to p: Profile) -> Profile {
        var q = p
        q.controls = p.controls.map { c in var c = c; if c.kind == .button, modes[c.id] == .toggle { c.toggle = true }; return c }
        return q
    }
}

/// Drives turbo buttons: `set` gets the button's mask, then 0, alternately, until stopped; stopping always
/// ends released (the release runs on the pulse queue after any tick already in flight).
public final class TurboPulser: @unchecked Sendable {
    private let queue = DispatchQueue(label: "c9.turbo")
    private var running: [String: DispatchSourceTimer] = [:]
    private let set: @Sendable (String, UInt32) -> Void

    public init(set: @escaping @Sendable (String, UInt32) -> Void) { self.set = set }

    public func start(_ id: String, _ mask: UInt32) {
        queue.async { [self] in
            guard running[id] == nil else { return }
            var down = false
            let t = DispatchSource.makeTimerSource(queue: queue)
            // Little slack: the system may otherwise batch timer fires on a busy device and turbo stutters.
            t.schedule(deadline: .now(), repeating: ButtonModes.turboHalfPeriod, leeway: .milliseconds(2))
            t.setEventHandler { [set] in down.toggle(); set(id, down ? mask : 0) }
            running[id] = t
            t.resume()
        }
    }

    public func stop(_ id: String) {
        queue.async { [self] in running.removeValue(forKey: id)?.cancel(); set(id, 0) }
    }

    public func stopAll() { queue.async { [self] in for (id, t) in running { t.cancel(); set(id, 0) }; running = [:] } }
}
