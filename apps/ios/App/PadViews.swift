import NineAppCore
import NineKit
import SwiftUI

/// Draws one element of a controller-style layout (PadPresets). Generic shapes and the app's palette
/// only — never a vendor logo or colour. Pressed state uses the single accent.
struct PadElementView: View {
    let e: PadElement
    let visuals: Visuals?

    var body: some View {
        let pressed = visuals?.pressed[e.id] ?? false
        let inert = e.action == nil
        GeometryReader { g in
            let size = g.size, r = min(size.width, size.height) / 2
            ZStack {
                switch e.part {
                case .face, .stickPress:
                    Circle().fill(pressed ? Nine.accent : Nine.s2)
                    Circle().strokeBorder(Nine.line2, lineWidth: 1.5)
                    glyph(pressed: pressed, size: r * (e.part == .face ? 0.9 : 0.75))
                case .center:
                    shape.fill(pressed ? Nine.accent : Nine.s2)
                    shape.stroke(Nine.line2, lineWidth: 1)
                    glyph(pressed: pressed, size: min(size.width, size.height) * 0.62)
                case .bumper:
                    RoundedRectangle(cornerRadius: size.height * 0.45).fill(pressed ? Nine.accent : Nine.s2)
                    RoundedRectangle(cornerRadius: size.height * 0.45).strokeBorder(Nine.line2, lineWidth: 1.5)
                    label(pressed: pressed, size: size.height * 0.42)
                case .trigger:
                    let v = visuals?.value[e.id] ?? 0
                    RoundedRectangle(cornerRadius: size.height * 0.4).fill(Nine.s2)
                    RoundedRectangle(cornerRadius: size.height * 0.4).fill(Nine.accent)
                        .mask(alignment: .leading) { Rectangle().frame(width: size.width * v) }
                    RoundedRectangle(cornerRadius: size.height * 0.4).strokeBorder(Nine.line2, lineWidth: 1.5)
                    label(pressed: v > 0.5, size: size.height * 0.42)
                case .stick:
                    let p = visuals?.stick[e.id] ?? .zero
                    Circle().fill(Nine.s1)
                    Circle().strokeBorder(Nine.line2, lineWidth: 1.5)
                    Circle().strokeBorder(Nine.line, lineWidth: 1).padding(r * 0.3)
                    Circle().fill(pressed ? Nine.accent : Nine.s2)
                        .overlay(Circle().strokeBorder(Nine.line2, lineWidth: 1.5))
                        .frame(width: r * 0.95, height: r * 0.95)
                        .offset(x: p.x * r * 0.5, y: -p.y * r * 0.5)
                case .dpad:
                    dpad(mask: visuals?.dpad[e.id] ?? 0, size: size)
                case .touchpad:
                    RoundedRectangle(cornerRadius: 12).fill(Nine.s1)
                    RoundedRectangle(cornerRadius: 12).strokeBorder(Nine.line2, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    label(pressed: false, size: min(size.height * 0.2, 12))
                case .paddle:
                    RoundedRectangle(cornerRadius: size.height * 0.45).strokeBorder(Nine.line2, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    label(pressed: false, size: size.height * 0.42)
                }
            }
            .frame(width: size.width, height: size.height)
        }
        .opacity(inert ? 0.45 : 1)
    }

    private var shape: some Shape { RoundedRectangle(cornerRadius: e.round ? 999 : 6) }

    private func label(pressed: Bool, size: CGFloat) -> some View {
        Text(e.short).font(.system(size: max(size, 8), weight: .medium)).lineLimit(1).minimumScaleFactor(0.5)
            .foregroundStyle(pressed ? Nine.accentInk : Nine.muted)
    }

    @ViewBuilder private func glyph(pressed: Bool, size: CGFloat) -> some View {
        let color = pressed ? Nine.accentInk : Nine.muted
        if let name = Self.symbol(e.glyph) {
            Image(systemName: name).font(.system(size: max(size * 0.55, 7), weight: .semibold)).foregroundStyle(color)
        } else {
            Text(e.short).font(.system(size: max(size * 0.6, 8), weight: .semibold)).lineLimit(1).minimumScaleFactor(0.4).foregroundStyle(color)
        }
    }

    static func symbol(_ g: PadGlyph) -> String? {
        switch g {
        case .text: nil
        case .cross: "xmark"; case .circle: "circle"; case .square: "square"; case .triangle: "triangle"
        case .home: "house"; case .plus: "plus"; case .minus: "minus"; case .menu: "line.3.horizontal"
        case .view: "rectangle.on.rectangle"; case .share: "square.and.arrow.up"; case .create: "square.and.pencil"
        case .capture: "record.circle"; case .mute: "mic.slash"; case .star: "star"
        }
    }

    /// Cross-shaped D-pad; each pressed arm lights up.
    private func dpad(mask: UInt32, size: CGSize) -> some View {
        let s = min(size.width, size.height), arm = s / 3
        func lit(_ b: NineButton) -> Bool { mask & b.mask != 0 }
        func piece(_ on: Bool, _ dx: CGFloat, _ dy: CGFloat) -> some View {
            RoundedRectangle(cornerRadius: arm * 0.22).fill(on ? Nine.accent : Nine.s2)
                .overlay(RoundedRectangle(cornerRadius: arm * 0.22).strokeBorder(Nine.line2, lineWidth: 1.5))
                .frame(width: arm, height: arm).offset(x: dx * arm, y: dy * arm)
        }
        return ZStack {
            Rectangle().fill(Nine.s2).frame(width: arm, height: arm)
            piece(lit(.up), 0, -1); piece(lit(.down), 0, 1); piece(lit(.left), -1, 0); piece(lit(.right), 1, 0)
        }
        .frame(width: s, height: s)
    }
}

/// Static, fitted preview of a whole controller layout (Layouts screen).
struct PadPreview: View {
    let preset: PadPreset
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) {
                ForEach(preset.elements) { e in
                    let r = PadGeometry.place(e, g.size, fit: true)
                    PadElementView(e: e, visuals: nil).frame(width: r.width, height: r.height).position(x: r.midX, y: r.midY)
                }
            }
        }
        .aspectRatio(2, contentMode: .fit)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
