import CoreGraphics
import Foundation
import NineKit
import Testing
@testable import NineAppCore

// Same cases as Android's PadLookTest.

@Test func customisedLayoutKeepsEveryPartAndGlyph() {
    for p in PadPresets.all {
        let id = "\(p.id)-custom-2"
        for c in p.profile(for: CGSize(width: 800, height: 360)).controls {
            let e = p.element(c.id)!, look = PadLook.element(c, size: CGSize(width: 100, height: 100), profileId: id)
            #expect(look?.part == e.part, "\(p.id) \(c.id)")
            #expect(look?.glyph == e.glyph, "\(p.id) \(c.id)")
            #expect(look?.action == e.action, "\(p.id) \(c.id)")
        }
    }
}

@Test func stretchedRoundButtonBecomesCapsuleKeepingGlyph() {
    let look = PadLook.element(ControlDef(id: "face_s", kind: .button, bind: "a", x: 0, y: 0, w: 0.2, h: 0.1, label: "Cross"), size: CGSize(width: 300, height: 100))
    #expect(look?.part == .center && look?.glyph == .cross && look?.round == false)
}

@Test func plainControlsGetGenericParts() {
    #expect(PadLook.element(ControlDef(id: "b1", kind: .button, bind: "a", x: 0, y: 0, w: 0.1, h: 0.1), size: CGSize(width: 90, height: 100))?.part == .face)
    let lb = PadLook.element(ControlDef(id: "b2", kind: .button, bind: "lb", x: 0, y: 0, w: 0.3, h: 0.1), size: CGSize(width: 300, height: 100))
    #expect(lb?.part == .bumper && lb?.short == "LB")
    #expect(PadLook.element(ControlDef(id: "t", kind: .trigger, bind: "rt", x: 0, y: 0, w: 0.3, h: 0.1), size: CGSize(width: 300, height: 100))?.action == .triggerR)
    #expect(PadLook.element(ControlDef(id: "d", kind: .dpad, bind: "dpad", x: 0, y: 0, w: 0.2, h: 0.2), size: CGSize(width: 200, height: 200))?.part == .dpad)
}

@Test func barFitsBetweenTheTriggers() {
    let low = CGRect(x: 800, y: 400, width: 100, height: 100)
    #expect(PadLook.topGap([CGRect(x: 0, y: 0, width: 500, height: 100), CGRect(x: 1900, y: 0, width: 500, height: 100), low], width: 2400, band: 130, margin: 20) == 1360)
    #expect(PadLook.topGap([CGRect(x: 0, y: 0, width: 500, height: 100), CGRect(x: 1700, y: 0, width: 700, height: 100)], width: 2400, band: 130, margin: 20) == 960)
    #expect(PadLook.topGap([CGRect(x: 0, y: 400, width: 500, height: 100), CGRect(x: 1100, y: 0, width: 200, height: 100)], width: 2400, band: 130, margin: 20) == nil)
}

@Test func pedalsAndSteeringKeepTheirOwnDrawing() {
    #expect(PadLook.element(ControlDef(id: "p", kind: .pedal, bind: "rt", x: 0, y: 0, w: 0.1, h: 0.3), size: CGSize(width: 100, height: 300)) == nil)
    #expect(PadLook.element(ControlDef(id: "s", kind: .steering, bind: "steer", x: 0, y: 0, w: 0.3, h: 0.1), size: CGSize(width: 300, height: 100)) == nil)
}
