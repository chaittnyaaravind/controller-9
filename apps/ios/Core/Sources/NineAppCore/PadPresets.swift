import CoreGraphics
import Foundation
import NineKit

/// Controller-style layouts. Mirror of apps/android/.../pad/PadModel.kt + PadPresets.kt: same canvas,
/// same element geometry, same positional mapping. Interface layouts only, never hardware emulation
/// and never a vendor logo (docs/LAYOUTS.md).

/// Logical input an element sends — exactly what protocol v1 carries. Face buttons are positional:
/// a PlayStation Cross, an Xbox A and a Nintendo B (all at the bottom) send the same bit.
public enum PadAction: String, CaseIterable, Sendable {
    case south, east, west, north, bumperL = "bumper_l", bumperR = "bumper_r", back, start
    case stickLPress = "stick_l_press", stickRPress = "stick_r_press", guide, dpad
    case stickL = "stick_l", stickR = "stick_r", triggerL = "trigger_l", triggerR = "trigger_r"

    public var button: NineButton? {
        switch self {
        case .south: .a; case .east: .b; case .west: .x; case .north: .y; case .bumperL: .lb; case .bumperR: .rb
        case .back: .view; case .start: .menu; case .stickLPress: .ls; case .stickRPress: .rs; case .guide: .guide
        default: nil
        }
    }
    public var kind: ControlKind {
        switch self { case .dpad: .dpad; case .stickL, .stickR: .stick; case .triggerL, .triggerR: .trigger; default: .button }
    }
    /// Bind string understood by the profile model and the touch engine.
    public var bind: String {
        switch self { case .dpad: "dpad"; case .stickL: "left"; case .stickR: "right"; case .triggerL: "lt"; case .triggerR: "rt"; default: button!.id }
    }
}

/// Physical part of a controller face: decides drawing and touch behaviour.
public enum PadPart: Sendable { case face, bumper, trigger, stick, stickPress, dpad, center, touchpad, paddle }

/// Drawn symbol: generic shapes only.
public enum PadGlyph: Sendable { case text, cross, circle, square, triangle, home, plus, minus, menu, view, share, create, capture, mute, star }

public enum PadFamily: Sendable { case xbox, playstation, nintendo, retro }

/// One control on the 200 x 100 preset canvas (landscape front view), sizes in the same units.
public struct PadElement: Identifiable, Sendable {
    public let id: String
    public let part: PadPart
    /// Label printed on the real controller ("A", "Cross", "ZL", "Options").
    public let label: String
    public let cx: Double, cy: Double, w: Double, h: Double
    /// Action out of the box; nil when protocol v1 cannot carry this control.
    public let action: PadAction?
    public let glyph: PadGlyph
    /// Why the control does nothing (when `action` is nil).
    public let unavailable: String?
    /// Short label drawn on the control.
    public let short: String

    init(_ id: String, _ part: PadPart, _ label: String, _ cx: Double, _ cy: Double, _ w: Double, _ h: Double, _ action: PadAction?,
         _ glyph: PadGlyph = .text, _ unavailable: String? = nil, short: String? = nil) {
        self.id = id; self.part = part; self.label = label; self.cx = cx; self.cy = cy; self.w = w; self.h = h
        self.action = action; self.glyph = glyph; self.unavailable = unavailable; self.short = short ?? label
    }
    public var round: Bool { part == .face || part == .stick || part == .stickPress || (part == .center && w == h) }
}

public struct PadPreset: Identifiable, Sendable {
    public let id: String, name: String, tagline: String
    public let family: PadFamily
    public let symmetric: Bool
    public let elements: [PadElement]
    /// Facts the player should know about this layout on protocol v1.
    public let notes: [String]
    public func element(_ id: String) -> PadElement? { elements.first { $0.id == id } }
}

public enum PadGeometry {
    /// Positions stretch with the area (controls move toward the grips on wide phones); sizes use one
    /// scale so round controls stay round. With `fit` the canvas keeps its 2:1 shape (previews).
    public static func place(_ e: PadElement, _ size: CGSize, fit: Bool = false) -> CGRect {
        let w = Double(size.width), h = Double(size.height)
        let s = min(w / 200, h / 100)
        let (sx, sy) = fit ? (s, s) : (w / 200, h / 100)
        let ox = fit ? (w - 200 * s) / 2 : 0, oy = fit ? (h - 100 * s) / 2 : 0
        let cx = ox + e.cx * sx, cy = oy + e.cy * sy, hw = e.w * s / 2, hh = e.h * s / 2
        return CGRect(x: cx - hw, y: cy - hh, width: hw * 2, height: hh * 2)
    }
}

public enum PadPresets {
    static let noSend = "Protocol v1 has no input for this control, so it sends nothing."
    static let noTouchpad = "Protocol v1 has no touchpad input (neither touch nor click), so it sends nothing."
    static let unassignedPaddle = "Rear paddle. Protocol v1 has no paddle input, so it sends nothing."

    private static func shoulders(_ lt: String, _ rt: String, _ lb: String, _ rb: String) -> [PadElement] { [
        PadElement("trigger_l", .trigger, lt, 30, 9, 44, 14, .triggerL),
        PadElement("trigger_r", .trigger, rt, 170, 9, 44, 14, .triggerR),
        PadElement("bumper_l", .bumper, lb, 30, 24.5, 44, 12, .bumperL),
        PadElement("bumper_r", .bumper, rb, 170, 24.5, 44, 12, .bumperR),
    ] }

    /// Face diamond; labels are (top, right, bottom, left), actions always positional.
    private static func face(_ cx: Double, _ cy: Double, _ t: (String, PadGlyph), _ r: (String, PadGlyph), _ b: (String, PadGlyph), _ l: (String, PadGlyph)) -> [PadElement] {
        let o = 13.5, d = 14.0
        return [
            PadElement("face_n", .face, t.0, cx, cy - o, d, d, .north, t.1),
            PadElement("face_e", .face, r.0, cx + o, cy, d, d, .east, r.1),
            PadElement("face_s", .face, b.0, cx, cy + o, d, d, .south, b.1),
            PadElement("face_w", .face, l.0, cx - o, cy, d, d, .west, l.1),
        ]
    }

    private static func asymmetricBody(_ l3: String, _ r3: String) -> [PadElement] { [
        PadElement("stick_l", .stick, "Left stick", 36, 52, 34, 34, .stickL),
        PadElement("dpad", .dpad, "D-pad", 70, 80, 30, 30, .dpad),
        PadElement("stick_r", .stick, "Right stick", 130, 80, 30, 30, .stickR),
        PadElement("stick_l_press", .stickPress, "\(l3) (stick press)", 14, 84, 12, 12, .stickLPress, short: l3),
        PadElement("stick_r_press", .stickPress, "\(r3) (stick press)", 186, 84, 12, 12, .stickRPress, short: r3),
    ] }

    private static func symmetricBody(_ l3: String, _ r3: String) -> [PadElement] { [
        PadElement("dpad", .dpad, "D-pad", 36, 52, 32, 32, .dpad),
        PadElement("stick_l", .stick, "Left stick", 72, 80, 30, 30, .stickL),
        PadElement("stick_r", .stick, "Right stick", 128, 80, 30, 30, .stickR),
        PadElement("stick_l_press", .stickPress, "\(l3) (stick press)", 46, 88, 12, 12, .stickLPress, short: l3),
        PadElement("stick_r_press", .stickPress, "\(r3) (stick press)", 154, 88, 12, 12, .stickRPress, short: r3),
    ] }

    public static let xbox = PadPreset(
        id: "xbox", name: "Xbox-style", tagline: "Asymmetric sticks · A B X Y", family: .xbox, symmetric: false,
        elements: shoulders("LT", "RT", "LB", "RB") + face(164, 52, ("Y", .text), ("B", .text), ("A", .text), ("X", .text)) + asymmetricBody("LS", "RS") + [
            PadElement("view", .center, "View", 86, 50, 11, 11, .back, .view),
            PadElement("guide", .center, "Guide", 100, 38, 14, 14, .guide, .home),
            PadElement("menu", .center, "Menu", 114, 50, 11, 11, .start, .menu),
            PadElement("share", .center, "Share", 100, 58, 10, 7, nil, .share, noSend),
        ],
        notes: ["A, B, X and Y send the same bits as an Xbox pad's positions.", "Guide is sent; some hosts ignore it.", "Share has no protocol input."])

    public static let dualsense = PadPreset(
        id: "dualsense", name: "DualSense-style", tagline: "Symmetric sticks · touchpad · Create", family: .playstation, symmetric: true,
        elements: shoulders("L2", "R2", "L1", "R1") + face(164, 52, ("Triangle", .triangle), ("Circle", .circle), ("Cross", .cross), ("Square", .square)) + symmetricBody("L3", "R3") + [
            PadElement("touchpad", .touchpad, "Touchpad", 100, 38, 52, 24, nil, .text, noTouchpad),
            PadElement("create", .center, "Create", 64, 32, 8, 13, .back, .create),
            PadElement("options", .center, "Options", 136, 32, 8, 13, .start, .menu),
            PadElement("ps", .center, "PS button", 100, 60, 11, 11, .guide, .home, short: "PS"),
            PadElement("mute", .center, "Mute", 100, 72, 10, 5, nil, .mute, noSend),
        ],
        notes: ["Cross is the bottom button and sends the same bit as Xbox A.", "Create sends Back/View; Options sends Start/Menu.", "Touchpad, Mute, adaptive triggers and haptics are not in protocol v1."])

    public static let dualshock4 = PadPreset(
        id: "dualshock4", name: "DualShock 4-style", tagline: "Symmetric sticks · touchpad · Share", family: .playstation, symmetric: true,
        elements: shoulders("L2", "R2", "L1", "R1") + face(164, 52, ("Triangle", .triangle), ("Circle", .circle), ("Cross", .cross), ("Square", .square)) + symmetricBody("L3", "R3") + [
            PadElement("touchpad", .touchpad, "Touchpad", 100, 36, 46, 22, nil, .text, noTouchpad),
            PadElement("share", .center, "Share", 70, 30, 8, 12, .back, .share),
            PadElement("options", .center, "Options", 130, 30, 8, 12, .start, .menu),
            PadElement("ps", .center, "PS button", 100, 58, 11, 11, .guide, .home, short: "PS"),
        ],
        notes: ["Share sends Back/View; Options sends Start/Menu.", "Touchpad and light bar are not in protocol v1."])

    public static let switchPro = PadPreset(
        id: "switchpro", name: "Switch Pro-style", tagline: "Asymmetric sticks · Nintendo A B X Y", family: .nintendo, symmetric: false,
        elements: shoulders("ZL", "ZR", "L", "R") + face(164, 52, ("X", .text), ("A", .text), ("B", .text), ("Y", .text)) + asymmetricBody("LS", "RS") + [
            PadElement("minus", .center, "−", 80, 36, 10, 10, .back, .minus),
            PadElement("plus", .center, "+", 120, 36, 10, 10, .start, .plus),
            PadElement("capture", .center, "Capture", 88, 50, 9, 9.5, nil, .capture, noSend),
            PadElement("home", .center, "Home", 112, 50, 11, 11, .guide, .home),
        ],
        notes: ["Positional: Nintendo B (bottom) sends Xbox A's bit, and Nintendo A (right) sends Xbox B's bit, the same as Steam/SDL's default for a Pro Controller.",
                "ZL/ZR are analog here (the real ones are digital). Capture has no protocol input."])

    public static let eightBitDoPro2 = PadPreset(
        id: "8bitdo-pro2", name: "8BitDo Pro 2-style", tagline: "Symmetric retro · rear paddles", family: .retro, symmetric: true,
        elements: shoulders("L2", "R2", "L", "R") + face(164, 52, ("X", .text), ("A", .text), ("B", .text), ("Y", .text)) + symmetricBody("L3", "R3") + [
            PadElement("select", .center, "Select", 84, 44, 14, 7, .back, short: "SEL"),
            PadElement("start", .center, "Start", 116, 44, 14, 7, .start, short: "START"),
            PadElement("star", .center, "Star", 100, 34, 9, 9, nil, .star, noSend),
            PadElement("home", .center, "Home", 100, 58, 11, 11, .guide, .home),
            PadElement("paddle_l", .paddle, "P1 (rear paddle)", 22, 92, 20, 10, nil, .text, unassignedPaddle, short: "P1"),
            PadElement("paddle_r", .paddle, "P2 (rear paddle)", 178, 92, 20, 10, nil, .text, unassignedPaddle, short: "P2"),
        ],
        notes: ["Nintendo-style labels, positional mapping (B bottom sends Xbox A's bit).", "Rear paddles P1/P2 have no protocol input here. Star has none either."])

    public static let all = [xbox, dualsense, dualshock4, switchPro, eightBitDoPro2]

    /// Settings value for "use a controller layout": `pad:<presetId>` (same as Android).
    public static let prefix = "pad:"
    public static func selection(_ p: PadPreset) -> String { prefix + p.id }
    public static func from(selection s: String) -> PadPreset? {
        guard s.hasPrefix(prefix) else { return nil }
        return all.first { $0.id == String(s.dropFirst(prefix.count)) }
    }
}

extension PadPreset {
    /// The layout as a profile for the touch engine, fitted to a screen of `size`: one control per element
    /// that sends something, at the element's on-screen box (as fractions). Triggers are thumb-tapped.
    public func profile(for size: CGSize) -> Profile {
        let W = max(Double(size.width), 1), H = max(Double(size.height), 1)
        let controls = elements.compactMap { e -> ControlDef? in
            guard let a = e.action else { return nil }
            let r = PadGeometry.place(e, size)
            func clamp(_ v: Double) -> Double { min(max(v, 0), 1) }
            let x = clamp(Double(r.minX) / W), y = clamp(Double(r.minY) / H)
            return ControlDef(id: e.id, kind: a.kind, bind: a.bind, x: x, y: y,
                              w: min(max(Double(r.width) / W, 0.03), 1 - x), h: min(max(Double(r.height) / H, 0.03), 1 - y), label: String(e.short.prefix(12)))
        }
        var p = Profile(id: "pad-\(id)", name: name, controls: controls)
        p.orientation = .landscape
        return p
    }
}
