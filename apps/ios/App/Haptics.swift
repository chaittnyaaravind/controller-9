import CoreHaptics
import UIKit

/// Button clicks (UIKit feedback) and game rumble (Core Haptics, where the device supports it).
@MainActor
final class Haptics {
    var enabled = true
    var strength = 0.7
    private let click = UIImpactFeedbackGenerator(style: .rigid)
    private var engine: CHHapticEngine?
    private var player: CHHapticPatternPlayer?
    let supportsRumble = CHHapticEngine.capabilitiesForHardware().supportsHaptics

    init() {
        click.prepare()
        if supportsRumble {
            engine = try? CHHapticEngine()
            engine?.isAutoShutdownEnabled = true
            engine?.resetHandler = { [weak self] in try? self?.engine?.start() }
        }
    }

    func tap() { guard enabled else { return }; click.impactOccurred(intensity: CGFloat(min(1, strength + 0.2))) }

    /// low/high: motor magnitudes 0...65535 from the game; ms 0 stops.
    func rumble(low: Int, high: Int, ms: Int) {
        guard enabled, supportsRumble, let engine else { return }
        try? player?.stop(atTime: CHHapticTimeImmediate)
        guard ms > 0, low > 0 || high > 0 else { return }
        let intensity = Float(Double(max(low, high)) / 65535 * strength)
        let sharpness = Float(Double(high) / Double(max(1, low + high)))
        let event = CHHapticEvent(eventType: .hapticContinuous,
                                  parameters: [.init(parameterID: .hapticIntensity, value: intensity), .init(parameterID: .hapticSharpness, value: sharpness)],
                                  relativeTime: 0, duration: Double(min(ms, 5000)) / 1000)
        do {
            try engine.start()
            player = try engine.makePlayer(with: CHHapticPattern(events: [event], parameters: []))
            try player?.start(atTime: CHHapticTimeImmediate)
        } catch {}
    }
}
