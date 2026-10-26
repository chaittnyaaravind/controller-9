import SwiftUI

/// "Lit Hardware" tokens, shared with the Android app (docs/DESIGN_SYSTEM.md): a black instrument lit
/// by one premium tone that belongs to the game being played. Secondary text is the text colour at
/// lower opacity, never a separate grey; hairlines are tinted text at 8 / 14 %.
enum Nine {
    static let bg = Color(red: 0x0A / 255, green: 0x0B / 255, blue: 0x0D / 255)
    static let s1 = Color(red: 0x12 / 255, green: 0x14 / 255, blue: 0x18 / 255)
    static let s2 = Color(red: 0x19 / 255, green: 0x1C / 255, blue: 0x21 / 255)
    static let s3 = Color(red: 0x23 / 255, green: 0x27 / 255, blue: 0x2E / 255)
    static let text = Color(red: 0xF1 / 255, green: 0xF3 / 255, blue: 0xF5 / 255)
    static let muted = text.opacity(0.66)
    static let dim = text.opacity(0.46)
    static let line = text.opacity(0.08)
    static let line2 = text.opacity(0.14)
    /// The current game's tone (set by AppModel when a game is chosen).
    static var accent: Color { Accent.current.color }
    static var accentInk: Color { Accent.current.ink }
    static let warn = Color(red: 0xF5 / 255, green: 0xB1 / 255, blue: 0x4C / 255)
    static let bad = Color(red: 0xF2 / 255, green: 0x67 / 255, blue: 0x5A / 255)

    /// Geist does 90 % of the work; Geist Mono is for live numbers (both SIL OFL, bundled in App/Fonts).
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font { .custom("Geist", size: size).weight(weight) }
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font { .custom("Geist Mono", size: size).weight(weight) }
}

/// One precious-metal or gem tone per game, on a black instrument. [ink] is text on a solid fill.
enum Accent: String, CaseIterable, Identifiable, Codable {
    case platinum = "PLATINUM", champagne = "CHAMPAGNE", crimson = "CRIMSON", sapphire = "SAPPHIRE", emerald = "EMERALD", copper = "COPPER"
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var color: Color {
        switch self {
        case .platinum: Color(red: 0xE4 / 255, green: 0xE7 / 255, blue: 0xEC / 255)
        case .champagne: Color(red: 0xD9 / 255, green: 0xB9 / 255, blue: 0x79 / 255)
        case .crimson: Color(red: 0xE0 / 255, green: 0x39 / 255, blue: 0x4F / 255)
        case .sapphire: Color(red: 0x5B / 255, green: 0x8C / 255, blue: 0xFF / 255)
        case .emerald: Color(red: 0x34 / 255, green: 0xC0 / 255, blue: 0x8A / 255)
        case .copper: Color(red: 0xD9 / 255, green: 0x8A / 255, blue: 0x5B / 255)
        }
    }
    var ink: Color { self == .crimson ? .white : Color(red: 0x0E / 255, green: 0x0F / 255, blue: 0x12 / 255) }

    /// Process-wide current tone; AppModel keeps it in step with the chosen game.
    nonisolated(unsafe) static var current: Accent = .platinum
}

struct Micro: View {
    let text: String; var color: Color = Nine.muted
    init(_ t: String, color: Color = Nine.muted) { text = t; self.color = color }
    var body: some View { Text(text.uppercased()).font(Nine.sans(11, .medium)).tracking(0.8).foregroundStyle(color) }
}

/// Card: one radius (20), hairline, a faint top highlight; never a drop shadow on black.
struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(Nine.s1, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(LinearGradient(colors: [Nine.text.opacity(0.12), Nine.line], startPoint: .top, endPoint: .bottom), lineWidth: 1))
    }
}
extension View { func card() -> some View { modifier(CardStyle()) } }

struct PrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(Nine.sans(16, .medium)).padding(.horizontal, 22).padding(.vertical, 13)
            .background(Nine.accent, in: Capsule()).foregroundStyle(Nine.accentInk)
            .scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Secondary action: hairline pill, so it reads as a button without competing with the accent.
struct SecondaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(Nine.sans(16, .medium)).padding(.horizontal, 20).padding(.vertical, 13)
            .background(Nine.text.opacity(configuration.isPressed ? 0.1 : 0.05), in: Capsule())
            .overlay(Capsule().strokeBorder(Nine.line2, lineWidth: 1))
            .foregroundStyle(Nine.text)
            .scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

func fmt(_ v: Double?, _ digits: Int = 1) -> String { v.map { String(format: "%.\(digits)f", $0) } ?? "—" }
