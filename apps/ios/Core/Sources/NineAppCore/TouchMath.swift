import CoreGraphics
import Foundation
import NineKit

/// Touch-to-control maths. Mirror of apps/android/.../input/TouchMath.kt and PadGeometry.hit —
/// keep constants and tests in step.
public enum DpadMath {
    /// Thumb must travel this far from the centre (fraction of the radius) before a direction presses…
    public static let pressR = 0.22
    /// …and stays pressed until it comes back inside this (no chatter while resting near the centre).
    public static let releaseR = 0.16
    /// Half-width of a diagonal zone; cardinals get the rest (60° each), so diagonals are deliberate.
    public static let diagonalHalfDeg = 15.0
    /// The current direction is kept until the thumb leaves its zone by this much (no flicker on a roll).
    public static let hysteresisDeg = 6.0

    /// Index 0 = right, counter-clockwise in 45° steps (y up).
    public static let masks: [UInt32] = [
        NineButton.right.mask, NineButton.right.mask | NineButton.up.mask, NineButton.up.mask, NineButton.up.mask | NineButton.left.mask,
        NineButton.left.mask, NineButton.left.mask | NineButton.down.mask, NineButton.down.mask, NineButton.down.mask | NineButton.right.mask,
    ]

    private static func halfWidth(_ k: Int) -> Double { k % 2 == 0 ? 45 - diagonalHalfDeg : diagonalHalfDeg }
    private static func offDeg(_ a: Double, _ k: Int) -> Double { let d = abs(a - 45 * Double(k)).truncatingRemainder(dividingBy: 360); return min(d, 360 - d) }

    /// Direction mask for a thumb at (dx, dy) from the pad centre (y up), `radius` = half the pad,
    /// given the mask sent last time (`prev`, 0 = none).
    public static func resolve(dx: Double, dy: Double, radius: Double, prev: UInt32) -> UInt32 {
        let r = hypot(dx, dy) / max(radius, 1e-9)
        if r < (prev == 0 ? pressR : releaseR) { return 0 }
        var a = atan2(dy, dx) * 180 / .pi
        if a < 0 { a += 360 }
        if let held = masks.firstIndex(of: prev), offDeg(a, held) <= halfWidth(held) + hysteresisDeg { return prev }
        let k = (0..<8).first { offDeg(a, $0) <= halfWidth($0) } ?? 0
        return masks[k]
    }
}

public enum TouchHit {
    /// A control the finger is actually on always wins (overlapping boxes: closest centre). Only a finger
    /// that misses every control falls back to targets grown to `minTarget`, then to the nearest *edge*.
    /// Controls whose id is in `round` (face buttons, sticks) are the ellipse inside their box, so the
    /// box corners in the gaps of a face diamond belong to nobody.
    public static func hit(_ boxes: [(id: String, rect: CGRect)], _ p: CGPoint, minTarget: CGFloat, round: Set<String> = []) -> String? {
        func centreDist(_ r: CGRect) -> CGFloat { hypot(r.midX - p.x, r.midY - p.y) }
        func inside(_ r: CGRect, _ isRound: Bool) -> Bool {
            guard isRound else { return r.contains(p) }
            let nx = (p.x - r.midX) / max(r.width / 2, 1e-6), ny = (p.y - r.midY) / max(r.height / 2, 1e-6)
            return nx * nx + ny * ny <= 1
        }
        func edgeDist(_ r: CGRect, _ isRound: Bool) -> CGFloat {
            if isRound { return max(0, centreDist(r) - min(r.width, r.height) / 2) }
            return hypot(max(r.minX - p.x, 0, p.x - r.maxX), max(r.minY - p.y, 0, p.y - r.maxY))
        }
        if let on = boxes.filter({ inside($0.rect, round.contains($0.id)) }).min(by: { centreDist($0.rect) < centreDist($1.rect) }) { return on.id }
        func grown(_ r: CGRect) -> CGRect { r.insetBy(dx: -max(0, (minTarget - r.width) / 2), dy: -max(0, (minTarget - r.height) / 2)) }
        return boxes.filter { inside(grown($0.rect), round.contains($0.id)) }
            .min { edgeDist($0.rect, round.contains($0.id)) < edgeDist($1.rect, round.contains($1.id)) }?.id
    }
}
