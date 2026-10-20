// What macOS games see: lists controllers via GameController.framework (the API Apple Arcade,
// Steam-on-Mac titles and most native Mac games use) and prints every button/axis change.
// Build: swiftc -O -o /tmp/gc-probe tools/mac-gc-probe/main.swift   Run: /tmp/gc-probe [seconds]
import Foundation
import GameController

let seconds = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1]) ?? 20 : 20
GCController.shouldMonitorBackgroundEvents = true
func describe(_ c: GCController) -> String {
    let kind = c.extendedGamepad != nil ? "extendedGamepad" : c.microGamepad != nil ? "microGamepad" : "none"
    return "\"vendor\":\"\(c.vendorName ?? "?")\",\"category\":\"\(c.productCategory)\",\"profile\":\"\(kind)\",\"haptics\":\(c.haptics != nil)"
}
func watch(_ c: GCController) {
    print("{\"connected\":{\(describe(c))}}"); fflush(stdout)
    c.physicalInputProfile.valueDidChangeHandler = { _, element in
        if let b = element as? GCControllerButtonInput {
            print("{\"button\":\"\(b.localizedName ?? "?")\",\"pressed\":\(b.isPressed),\"value\":\(b.value)}")
        } else if let a = element as? GCControllerAxisInput, abs(a.value) > 0.9 {
            print("{\"axis\":\"\(a.localizedName ?? "?")\",\"value\":\(a.value)}")
        }
        fflush(stdout)
    }
}
NotificationCenter.default.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { n in
    if let c = n.object as? GCController { watch(c) }
}
NotificationCenter.default.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { n in
    if let c = n.object as? GCController { print("{\"disconnected\":{\(describe(c))}}"); fflush(stdout) }
}
GCController.controllers().forEach(watch)
print("{\"listening\":\(seconds),\"already\":\(GCController.controllers().count)}"); fflush(stdout)
DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { exit(0) }
RunLoop.main.run()
