import CoreGraphics
import Foundation
import NineKit
import Testing
@testable import NineAppCore

/// Landscape safe areas: iPhone 15 (Dynamic Island inset), iPhone SE, a wide Android phone, an iPad mini.
private let screens = [CGSize(width: 734, height: 372), CGSize(width: 667, height: 375), CGSize(width: 800, height: 360), CGSize(width: 1133, height: 744)]

@Test func everyPresetIsAValidProfileOnEveryScreen() throws {
    for p in PadPresets.all { for s in screens { try p.profile(for: s).validate() } }
}

@Test func faceButtonsArePositional() {
    // Bottom button sends A whatever it is called: Xbox A, PlayStation Cross, Nintendo B.
    for p in PadPresets.all {
        #expect(p.element("face_s")?.action == .south && PadAction.south.bind == "a", "\(p.id)")
        #expect(p.element("face_e")?.action == .east && PadAction.east.bind == "b", "\(p.id)")
    }
    #expect(PadPresets.dualsense.element("face_s")?.label == "Cross")
    #expect(PadPresets.switchPro.element("face_s")?.label == "B")
}

/// True when the touch shapes (circle for round controls, rectangle otherwise) share area.
private func overlap(_ a: CGRect, _ ar: Bool, _ b: CGRect, _ br: Bool) -> Bool {
    func circleRect(_ c: CGRect, _ r: CGRect) -> Bool {
        let px = min(max(c.midX, r.minX), r.maxX), py = min(max(c.midY, r.minY), r.maxY)
        return hypot(c.midX - px, c.midY - py) < c.width / 2 - 0.5
    }
    switch (ar, br) {
    case (true, true): return hypot(a.midX - b.midX, a.midY - b.midY) < (a.width + b.width) / 2 - 0.5
    case (true, false): return circleRect(a, b)
    case (false, true): return circleRect(b, a)
    case (false, false): let o = a.intersection(b); return !o.isNull && o.width * o.height >= 1
    }
}

@Test func workingControlsNeverOverlap() {
    // Accuracy: a finger can only ever land on one control.
    for p in PadPresets.all {
        for s in screens {
            let els = p.elements.filter { $0.action != nil }
            for i in els.indices { for j in els.indices where j > i {
                let (a, b) = (els[i], els[j])
                #expect(!overlap(PadGeometry.place(a, s), a.round, PadGeometry.place(b, s), b.round), "\(p.id) on \(s): \(a.id) overlaps \(b.id)")
            } }
        }
    }
}

@Test func gapBetweenFaceButtonsTriggersNothing() {
    // The corner shared by the top and right face buttons' boxes is outside both circles.
    let s = CGSize(width: 734, height: 372), p = PadPresets.xbox
    let n = PadGeometry.place(p.element("face_n")!, s), e = PadGeometry.place(p.element("face_e")!, s)
    let gap = CGPoint(x: (n.maxX + e.minX) / 2, y: (n.maxY + e.minY) / 2)
    let boxes = [("face_n", n), ("face_e", e)].map { (id: $0.0, rect: $0.1) }
    #expect(TouchHit.hit(boxes, gap, minTarget: 0, round: ["face_n", "face_e"]) == nil)
    #expect(TouchHit.hit(boxes, CGPoint(x: n.midX, y: n.midY), minTarget: 0, round: ["face_n", "face_e"]) == "face_n")
}

@Test func roundControlsStayRound() {
    for p in PadPresets.all { for e in p.elements where e.round {
        let r = PadGeometry.place(e, CGSize(width: 800, height: 360))
        #expect(abs(r.width - r.height) < 0.5, "\(p.id).\(e.id)")
    } }
}

@Test func selectionRoundTrips() {
    for p in PadPresets.all { #expect(PadPresets.from(selection: PadPresets.selection(p))?.id == p.id) }
    #expect(PadPresets.from(selection: "racing") == nil)
}

@Test func chordsHoldTogetherAndReleaseIndependently() {
    // Reviews of other apps: "when pressing 2 buttons at the same time it wouldn't work sometimes".
    // Two fingers found by the real hit test reach the engine together; lifting one keeps the other.
    final class Box: @unchecked Sendable { var last = InputState.neutral }
    for p in PadPresets.all {
        let size = CGSize(width: 734, height: 372)
        let els = p.elements.filter { $0.action != nil }
        let boxes = els.map { (id: $0.id, rect: PadGeometry.place($0, size)) }
        let round = Set(els.filter(\.round).map(\.id))
        func finger(_ id: String) -> String? { let r = boxes.first { $0.id == id }!.rect; return TouchHit.hit(boxes, CGPoint(x: r.midX, y: r.midY), minTarget: 0, round: round) }
        #expect(finger("face_s") == "face_s" && finger("face_e") == "face_e", "\(p.id)")
        let box = Box(); let e = ControllerEngine { s, _ in box.last = s }
        e.setButtons("face_s", NineButton.a.mask); e.setButtons("face_e", NineButton.b.mask)
        #expect(e.state.buttons == NineButton.a.mask | NineButton.b.mask, "\(p.id): A+B together")
        e.setButtons("face_s", 0)
        #expect(e.state.buttons == NineButton.b.mask, "\(p.id): B held after A lifts")
        e.setButtons("face_e", 0)
        #expect(e.state == .neutral)
    }
}
