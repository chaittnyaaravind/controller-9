import NineAppCore
import NineKit
import SwiftUI

/// Black glass (docs/DESIGN_SYSTEM.md): smoked-glass body lit from the top-left, a thin specular rim,
/// a soft dome shine; pressed = the game's tone in the rim, the label and a halo — never a flat fill.
enum Glass {
    static let smoke = Color(red: 0x15 / 255, green: 0x16 / 255, blue: 0x1A / 255)
    static let smokeDeep = Color(red: 0x07 / 255, green: 0x08 / 255, blue: 0x0A / 255)
    static func rim(_ lit: Bool) -> LinearGradient {
        lit ? LinearGradient(colors: [Nine.accent.opacity(0.95), Nine.accent, Nine.accent.opacity(0.55)], startPoint: .topLeading, endPoint: .bottomTrailing)
            : LinearGradient(colors: [.white.opacity(0.42), .white.opacity(0.07), .white.opacity(0.03), .white.opacity(0.16)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static func body(_ lit: Bool) -> RadialGradient {
        RadialGradient(colors: [smoke, smokeDeep], center: UnitPoint(x: 0.3, y: 0.22), startRadius: 0, endRadius: 80)
    }
}

/// One glass cap in any shape; [fill] 0…1 lights an analog trigger from the leading edge.
struct GlassCap<S: InsettableShape>: View {
    let shape: S
    var pressed = false
    var fill: Double = 0
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, r = min(w, h) / 2
            let lit = pressed || fill > 0.5
            ZStack {
                shape.fill(Color.black.opacity(0.6)).offset(y: r * 0.18).blur(radius: r * 0.12)
                if lit { shape.fill(Nine.accent.opacity(0.32)).blur(radius: r * 0.5).scaleEffect(1.35) }
                shape.fill(RadialGradient(colors: [Glass.smoke, Glass.smokeDeep], center: UnitPoint(x: 0.3, y: 0.22), startRadius: 0, endRadius: max(w, h) * 0.9))
                if pressed { shape.fill(RadialGradient(colors: [Nine.accent.opacity(0.28), .clear], center: UnitPoint(x: 0.5, y: 0.65), startRadius: 0, endRadius: r * 1.1)) }
                if fill > 0 {
                    shape.fill(LinearGradient(colors: [Nine.accent.opacity(0.1), Nine.accent.opacity(0.38)], startPoint: .leading, endPoint: .trailing))
                        .mask(alignment: .leading) { Rectangle().frame(width: w * fill) }
                }
                shape.strokeBorder(Glass.rim(lit), lineWidth: lit ? 1.6 : 1.1)
                // Dome shine and a small hot spot: glass, not plastic.
                // Wide caps (bumpers, triggers) get a thin highlight band, round ones a dome.
                let wide = w > h * 1.4
                Capsule().fill(LinearGradient(colors: [.white.opacity(wide ? 0.08 : 0.13), .clear], startPoint: .top, endPoint: .bottom))
                    .frame(width: wide ? w - h * 0.7 : w * 0.62, height: wide ? h * 0.22 : h * 0.34).offset(y: wide ? -h * 0.3 : -h * 0.27)
                Circle().fill(.white.opacity(0.32)).frame(width: r * 0.11, height: r * 0.11).offset(x: -w * 0.2, y: -h * 0.26)
            }
            .scaleEffect(pressed ? 0.965 : 1)
            .frame(width: w, height: h)
        }
    }
}

/// Draws one element of a controller-style layout (PadPresets). Generic shapes and the app's palette
/// only — never a vendor logo or colour.
struct PadElementView: View {
    let e: PadElement
    let visuals: Visuals?

    var body: some View {
        let pressed = visuals?.pressed[e.id] ?? false
        let inert = e.action == nil
        GeometryReader { g in
            let size = g.size, r = min(size.width, size.height) / 2
            ZStack {
                if inert {
                    shape.stroke(Nine.text.opacity(0.22), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    label(pressed: false, size: min(size.height * 0.42, 12))
                } else {
                    switch e.part {
                    case .face, .stickPress:
                        GlassCap(shape: Circle(), pressed: pressed)
                        glyph(pressed: pressed, size: r * (e.part == .face ? 0.9 : 0.75))
                    case .center:
                        if e.round { GlassCap(shape: Circle(), pressed: pressed) } else { GlassCap(shape: Capsule(), pressed: pressed) }
                        glyph(pressed: pressed, size: min(size.width, size.height) * 0.62)
                    case .bumper, .paddle:
                        GlassCap(shape: Capsule(), pressed: pressed)
                        label(pressed: pressed, size: size.height * 0.42)
                    case .trigger:
                        let v = visuals?.value[e.id] ?? 0
                        GlassCap(shape: RoundedRectangle(cornerRadius: size.height * 0.42), fill: v)
                        label(pressed: v > 0.45, size: size.height * 0.42)
                    case .stick:
                        stick(p: visuals?.stick[e.id] ?? .zero, live: pressed || (visuals?.stick[e.id] ?? .zero) != .zero, r: r)
                    case .dpad:
                        dpad(mask: visuals?.dpad[e.id] ?? 0, size: size)
                    case .touchpad:
                        GlassCap(shape: RoundedRectangle(cornerRadius: 12))
                        label(pressed: false, size: min(size.height * 0.2, 12))
                    }
                }
            }
            .frame(width: size.width, height: size.height)
        }
    }

    private var shape: AnyShape { e.round || e.part == .stick ? AnyShape(Circle()) : AnyShape(Capsule()) }

    private func ink(_ pressed: Bool) -> Color { pressed ? Nine.accent : Nine.text.opacity(0.92) }

    private func label(pressed: Bool, size: CGFloat) -> some View {
        Text(e.short).font(Nine.sans(max(size, 8), .semibold)).lineLimit(1).minimumScaleFactor(0.5).foregroundStyle(ink(pressed))
    }

    @ViewBuilder private func glyph(pressed: Bool, size: CGFloat) -> some View {
        if let name = Self.symbol(e.glyph) {
            Image(systemName: name).font(.system(size: max(size * 0.55, 7), weight: .semibold)).foregroundStyle(ink(pressed))
        } else {
            Text(e.short).font(Nine.sans(max(size * 0.62, 8), .semibold)).lineLimit(1).minimumScaleFactor(0.4).foregroundStyle(ink(pressed))
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

    /// Glass well with a gate ring and eight index marks; the knob is a glass cap; live = the tone.
    private func stick(p: CGPoint, live: Bool, r: CGFloat) -> some View {
        ZStack {
            Circle().fill(RadialGradient(colors: [Glass.smokeDeep, Glass.smoke.opacity(0.9)], center: .center, startRadius: 0, endRadius: r))
            Circle().strokeBorder(LinearGradient(colors: [.white.opacity(0.03), .white.opacity(0.22)], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1)
            Circle().stroke(live ? Nine.accent.opacity(0.75) : .white.opacity(0.07), lineWidth: live ? 1.6 : 1).frame(width: r * 1.6, height: r * 1.6)
            ForEach(0..<8, id: \.self) { i in
                Capsule().fill(live ? Nine.accent.opacity(0.6) : .white.opacity(0.14)).frame(width: 1.2, height: r * 0.08)
                    .offset(y: -r * 0.89).rotationEffect(.degrees(Double(i) * 45))
            }
            GlassCap(shape: Circle(), pressed: live).frame(width: r * 0.84, height: r * 0.84).offset(x: p.x * r * 0.56, y: -p.y * r * 0.56)
        }
    }

    /// Glass cross; the pressed arm takes the tone, its chevron lights.
    private func dpad(mask: UInt32, size: CGSize) -> some View {
        let s = min(size.width, size.height), arm = s / 3
        func lit(_ b: NineButton) -> Bool { mask & b.mask != 0 }
        func piece(_ on: Bool, _ dx: CGFloat, _ dy: CGFloat, _ symbol: String) -> some View {
            ZStack {
                GlassCap(shape: RoundedRectangle(cornerRadius: arm * 0.26), pressed: on)
                Image(systemName: symbol).font(.system(size: arm * 0.32, weight: .semibold)).foregroundStyle(on ? Nine.accent : Nine.muted)
            }
            .frame(width: arm, height: arm).offset(x: dx * arm, y: dy * arm)
        }
        return ZStack {
            Rectangle().fill(Glass.smokeDeep).frame(width: arm, height: arm)
            piece(lit(.up), 0, -1, "chevron.up"); piece(lit(.down), 0, 1, "chevron.down")
            piece(lit(.left), -1, 0, "chevron.left"); piece(lit(.right), 1, 0, "chevron.right")
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
