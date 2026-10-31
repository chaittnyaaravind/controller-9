import CoreGraphics
import Foundation
import NineKit

/// How a custom layout's control is drawn: as the same black-glass part a controller layout uses, so a
/// customised layout looks like the layout it came from (glyphs, D-pad chevrons, stick wells), not a flat
/// stand-in. Mirror of apps/android/.../pad/PadLook.kt; same rules, same tests.
public enum PadLook {
    /// The part to draw for `c` in a box of `size`; nil for controls no controller has (pedals, steering).
    ///  - A control customised from a layout keeps that layout's part and glyph (same id and label; the
    ///    layout named by the start of `profileId`, "arcade-custom", wins when several layouts match).
    ///  - Otherwise: near-square buttons are round caps, long ones capsules.
    ///  - A round cap the player stretched into a long box becomes a capsule, glyph kept.
    public static func element(_ c: ControlDef, size: CGSize, profileId: String? = nil) -> PadElement? {
        guard [.button, .stick, .dpad, .trigger].contains(c.kind),
              let action = PadAction.allCases.first(where: { $0.kind == c.kind && $0.bind == c.bind }) else { return nil }
        let label = c.label ?? c.bind.uppercased()
        let w = Double(size.width), h = Double(size.height)
        let square = abs(w - h) < 0.25 * max(w, h)
        let presets = PadPresets.all.filter { profileId?.hasPrefix($0.id + "-") == true } + PadPresets.all.filter { profileId?.hasPrefix($0.id + "-") != true }
        let known = presets.lazy.flatMap(\.elements).first { $0.id == c.id && $0.short == label && $0.action?.kind == c.kind }
        let part: PadPart = known?.part ?? {
            switch c.kind {
            case .stick: .stick; case .dpad: .dpad; case .trigger: .trigger
            default: square ? .face : .bumper
            }
        }()
        let base = PadElement(c.id, part, known?.label ?? label, 0, 0, 1, 1, action, known?.glyph ?? .text, short: label)
        if base.round && part != .stick && !square { return PadElement(c.id, .center, base.label, 0, 0, 2, 1, action, base.glyph, short: label) }
        return base
    }

    /// Width of the clear gap, centred on a screen `width` wide, between the controls that reach into the
    /// top `band`, less `margin` on each side: the in-game bar fits there, never over a trigger. Controls
    /// spanning the centre are ignored (the bar has to sit somewhere). Nil when nothing is up there.
    public static func topGap(_ boxes: [CGRect], width: CGFloat, band: CGFloat, margin: CGFloat) -> CGFloat? {
        let mid = width / 2
        let up = boxes.filter { $0.minY < band && !($0.minX < mid && $0.maxX > mid) }
        guard !up.isEmpty else { return nil }
        let left = up.filter { $0.midX < mid }.map(\.maxX).max() ?? 0
        let right = up.filter { $0.midX >= mid }.map(\.minX).min() ?? width
        return max(0, 2 * min(mid - left, right - mid) - 2 * margin)
    }
}
