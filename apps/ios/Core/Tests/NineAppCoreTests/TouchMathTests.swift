import CoreGraphics
import Foundation
import NineKit
import Testing
@testable import NineAppCore

/// Same cases as apps/android/app/src/test/kotlin/dev/ninecontroller/android/TouchMathTest.kt.
private let L = NineButton.left.mask, U = NineButton.up.mask, R = NineButton.right.mask, D = NineButton.down.mask
private func at(_ deg: Double, _ r: Double, prev: UInt32 = 0) -> UInt32 {
    DpadMath.resolve(dx: r * cos(deg * .pi / 180), dy: r * sin(deg * .pi / 180), radius: 1, prev: prev)
}

@Test func slightlyOffCardinalStaysCardinal() {
    #expect(at(153, 0.8) == L && at(207, 0.8) == L)
    #expect(at(63, 0.8) == U && at(-27, 0.8) == R && at(242, 0.8) == D)
}

@Test func diagonalsAreDeliberate() {
    #expect(at(45, 0.8) == R | U && at(135, 0.8) == U | L)
    #expect(at(222, 0.8) == L | D && at(313, 0.8) == D | R)
}

@Test func heldDirectionDoesNotFlickerOnARoll() {
    #expect(at(145, 0.8, prev: L) == L)
    #expect(at(140, 0.8, prev: L) == U | L)
    #expect(at(152, 0.8, prev: U | L) == U | L)
}

@Test func centreDeadZoneHasHysteresis() {
    #expect(at(180, 0.20) == 0)
    #expect(at(180, 0.25) == L)
    #expect(at(180, 0.18, prev: L) == L)
    #expect(at(180, 0.12, prev: L) == 0)
}

@Test func fingerOnAControlAlwaysWins() {
    let boxes: [(id: String, rect: CGRect)] = [
        ("stick", CGRect(x: 0, y: 0, width: 300, height: 300)),
        ("small", CGRect(x: 290, y: 140, width: 30, height: 30)),
    ]
    #expect(TouchHit.hit(boxes, CGPoint(x: 150, y: 150), minTarget: 132) == "stick")
    #expect(TouchHit.hit(boxes, CGPoint(x: 318, y: 150), minTarget: 132) == "small")
    #expect(TouchHit.hit(boxes, CGPoint(x: 295, y: 155), minTarget: 132) == "small")
    #expect(TouchHit.hit(boxes, CGPoint(x: 360, y: 290), minTarget: 0) == nil)
}

@Test func nearMissGoesToNearestEdgeNotNearestCentre() {
    let boxes: [(id: String, rect: CGRect)] = [
        ("tall", CGRect(x: 100, y: 0, width: 10, height: 300)),
        ("small", CGRect(x: 140, y: 10, width: 20, height: 20)),
    ]
    #expect(TouchHit.hit(boxes, CGPoint(x: 122, y: 20), minTarget: 60) == "tall")
}
