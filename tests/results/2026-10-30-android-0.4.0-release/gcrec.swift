import GameController
import Foundation
// Records what macOS GameController sees from the first extended gamepad: one line per change,
// "<unix-seconds> <element> <value>", for <seconds> (argv[1], default 60). Used as the oracle for the
// phone's Bluetooth Xbox-controller mode, the same way a game reads a real Xbox pad.
let secs = Double(CommandLine.arguments.dropFirst().first ?? "60") ?? 60
var hooked = false
func now() -> String { String(format: "%.3f", Date().timeIntervalSince1970) }
func hook(_ c: GCController) {
    guard !hooked, let g = c.extendedGamepad else { return }; hooked = true
    print("# controller \(c.vendorName ?? "?") product=\(c.productCategory)"); fflush(stdout)
    var last: [String: Float] = [:]
    g.valueChangedHandler = { g, _ in
        var cur: [String: Float] = [
            "A": g.buttonA.value, "B": g.buttonB.value, "X": g.buttonX.value, "Y": g.buttonY.value,
            "LB": g.leftShoulder.value, "RB": g.rightShoulder.value, "LT": g.leftTrigger.value, "RT": g.rightTrigger.value,
            "View": g.buttonOptions?.value ?? 0, "Menu": g.buttonMenu.value, "Guide": g.buttonHome?.value ?? 0,
            "LS": g.leftThumbstickButton?.value ?? 0, "RS": g.rightThumbstickButton?.value ?? 0,
            "up": g.dpad.up.value, "down": g.dpad.down.value, "left": g.dpad.left.value, "right": g.dpad.right.value,
            "lx": g.leftThumbstick.xAxis.value, "ly": g.leftThumbstick.yAxis.value, "rx": g.rightThumbstick.xAxis.value, "ry": g.rightThumbstick.yAxis.value]
        for (k, v) in cur where abs((last[k] ?? 0) - v) > 0.02 { print(now(), k, String(format: "%.3f", v)) }
        fflush(stdout); last = cur; cur.removeAll()
    }
}
NotificationCenter.default.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { n in hook(n.object as! GCController) }
GCController.shouldMonitorBackgroundEvents = true
DispatchQueue.main.asyncAfter(deadline: .now() + 1) { GCController.controllers().forEach(hook); if !hooked { print("# no controller yet"); fflush(stdout) } }
DispatchQueue.main.asyncAfter(deadline: .now() + secs) { exit(0) }
RunLoop.main.run()
