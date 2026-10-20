// Posts one tagged F18 key press (harmless) the same way the backend does — proves the tap swallows.
import CoreGraphics
let src = CGEventSource(stateID: .hidSystemState)
for down in [true, false] {
    let e = CGEvent(keyboardEventSource: src, virtualKey: 79, keyDown: down)!
    e.setIntegerValueField(.eventSourceUserData, value: 0x4339); e.post(tap: .cghidEventTap)
}
