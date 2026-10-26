// Plays a 1.2 s rumble on the first connected controller through GameController haptics (the API Mac games use).
// Usage: swiftc -O -o rumble tools/mac-rumble/main.swift && ./rumble
import Foundation
import GameController
import CoreHaptics

GCController.shouldMonitorBackgroundEvents = true
var done = false
// Keep the engine and player alive while the pattern plays (a local would be freed at once,
// which stops the engine before anything is sent).
var keepEngine: CHHapticEngine?
var keepPlayer: CHHapticPatternPlayer?
func play(_ c: GCController) {
    guard let engine = c.haptics?.createEngine(withLocality: .default) else { print("no haptics on \(c.vendorName ?? "?")"); exit(2) }
    do {
        try engine.start()
        let e = CHHapticEvent(eventType: .hapticContinuous, parameters: [
            CHHapticEventParameter(parameterID: .hapticIntensity, value: 1.0),
            CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5)], relativeTime: 0, duration: 1.2)
        let player = try engine.makePlayer(with: try CHHapticPattern(events: [e], parameters: []))
        try player.start(atTime: 0)
        keepEngine = engine; keepPlayer = player
        print("rumble sent to \(c.vendorName ?? "?")")
    } catch { print("haptics error: \(error)"); exit(3) }
}
NotificationCenter.default.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { n in
    if !done, let c = n.object as? GCController { done = true; play(c) }
}
GCController.startWirelessControllerDiscovery {}
DispatchQueue.main.asyncAfter(deadline: .now() + 1) { if !done, let c = GCController.controllers().first { done = true; play(c) } }
DispatchQueue.main.asyncAfter(deadline: .now() + 4) { exit(done ? 0 : 1) }
RunLoop.main.run()
