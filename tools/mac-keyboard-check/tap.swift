// Test rig for keyboard & mouse mode: an active session event tap that records and SWALLOWS every
// event carrying Controller-9's tag (user-data field = 0x4339), so a test never types or clicks into
// the user's apps. Untagged events (the real keyboard and mouse) pass through untouched.
// Usage: tap <seconds>  — prints "ready", then one JSON line per tagged event.
import CoreGraphics
import Foundation

let mark: Int64 = 0x4339
let secs = CommandLine.arguments.count > 1 ? Double(CommandLine.arguments[1])! : 15
let t0 = CFAbsoluteTimeGetCurrent()
var tapRef: CFMachPort?
let types: [CGEventType] = [.keyDown, .keyUp, .flagsChanged, .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp,
                            .mouseMoved, .leftMouseDragged, .rightMouseDragged]
let mask = types.reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }

let callback: CGEventTapCallBack = { _, type, event, _ in
    if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
        if let t = tapRef { CGEvent.tapEnable(tap: t, enable: true) }
        return Unmanaged.passUnretained(event)
    }
    guard event.getIntegerValueField(.eventSourceUserData) == mark else { return Unmanaged.passUnretained(event) }
    var o: [String: Any] = ["t": (CFAbsoluteTimeGetCurrent() - t0).rounded(toPlaces: 3), "type": Int(type.rawValue)]
    switch type {
    case .keyDown, .keyUp: o["key"] = event.getIntegerValueField(.keyboardEventKeycode); o["down"] = type == .keyDown
    // Modifiers (Shift) arrive as flagsChanged; "down" is whether the Shift flag is now set.
    case .flagsChanged: o["key"] = event.getIntegerValueField(.keyboardEventKeycode); o["down"] = event.flags.contains(.maskShift)
    case .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp: o["button"] = (type == .leftMouseDown || type == .leftMouseUp) ? "left" : "right"; o["down"] = type == .leftMouseDown || type == .rightMouseDown
    default: o["dx"] = event.getIntegerValueField(.mouseEventDeltaX); o["dy"] = event.getIntegerValueField(.mouseEventDeltaY)
    }
    if let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]), let s = String(data: d, encoding: .utf8) { print(s); fflush(stdout) }
    return nil // swallow: never reaches an app
}
extension Double { func rounded(toPlaces p: Int) -> Double { let m = pow(10.0, Double(p)); return (self * m).rounded() / m } }

guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                  eventsOfInterest: mask, callback: callback, userInfo: nil) else {
    print("{\"error\":\"tapCreate failed (Accessibility permission?)\"}"); exit(1)
}
tapRef = tap
let src = CFMachPortCreateRunLoopSource(nil, tap, 0)
CFRunLoopAddSource(CFRunLoopGetCurrent(), src, .commonModes)
CGEvent.tapEnable(tap: tap, enable: true)
print("ready"); fflush(stdout)
CFRunLoopRunInMode(.defaultMode, secs, false)
