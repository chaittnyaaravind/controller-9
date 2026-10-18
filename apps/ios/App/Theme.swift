import SwiftUI

/// Same tokens as the desktop companion and Android app.
enum Nine {
    static let bg = Color(red: 0x0B / 255, green: 0x0C / 255, blue: 0x0D / 255)
    static let s1 = Color(red: 0x11 / 255, green: 0x13 / 255, blue: 0x15 / 255)
    static let s2 = Color(red: 0x17 / 255, green: 0x1A / 255, blue: 0x1C / 255)
    static let text = Color(red: 0xEE / 255, green: 0xF0 / 255, blue: 0xF1 / 255)
    static let muted = text.opacity(0.62)
    static let dim = text.opacity(0.42)
    static let line = text.opacity(0.08)
    static let line2 = text.opacity(0.14)
    static let accent = Color(red: 0xC6 / 255, green: 0xF4 / 255, blue: 0x32 / 255)
    static let accentInk = Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x0A / 255)
    static let warn = Color(red: 0xF5 / 255, green: 0xB1 / 255, blue: 0x4C / 255)
    static let bad = Color(red: 0xF2 / 255, green: 0x67 / 255, blue: 0x5A / 255)
}

struct Micro: View {
    let text: String; var color: Color = Nine.muted
    init(_ t: String, color: Color = Nine.muted) { text = t; self.color = color }
    var body: some View { Text(text.uppercased()).font(.system(size: 11, weight: .medium)).tracking(0.7).foregroundStyle(color) }
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(Nine.s1, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Nine.line, lineWidth: 1))
    }
}
extension View { func card() -> some View { modifier(CardStyle()) } }

struct PrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.body.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 10)
            .background(Nine.accent, in: Capsule()).foregroundStyle(Nine.accentInk)
            .scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

func fmt(_ v: Double?, _ digits: Int = 1) -> String { v.map { String(format: "%.\(digits)f", $0) } ?? "—" }
