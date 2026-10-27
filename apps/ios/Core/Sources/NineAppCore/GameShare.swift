import Foundation

/// A game setup shared phone-to-phone as a QR code (no server) — the same format as Android's
/// data/GameShare.kt: `c9game:1:<base64url>`, the payload being seven fields separated by U+001F
/// (name, accent, layout, tilt, aim, remaps, button modes). Decoding never trusts the input.
public enum GameShare {
    public static let prefix = "c9game:1:"
    static let sep: Character = "\u{1F}"
    static let maxField = 600
    public static let maxName = 32

    public struct Shared: Equatable, Sendable {
        public var name: String, accent: String, layout: String, tilt: Bool, aim: Bool, mapping: String, modes: String
        public init(name: String, accent: String, layout: String, tilt: Bool = false, aim: Bool = false, mapping: String = "", modes: String = "") {
            self.name = name; self.accent = accent; self.layout = layout; self.tilt = tilt; self.aim = aim; self.mapping = mapping; self.modes = modes
        }
    }

    public static func encode(_ s: Shared) -> String {
        let raw = [s.name, s.accent, s.layout, s.tilt ? "1" : "0", s.aim ? "1" : "0", s.mapping, s.modes].joined(separator: String(sep))
        let b64 = Data(raw.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
        return prefix + b64
    }

    /// The shared setup, or nil when `text` isn't a valid game code.
    public static func decode(_ text: String) -> Shared? {
        guard text.hasPrefix(prefix), text.count <= 2 * maxField else { return nil }
        var b = String(text.dropFirst(prefix.count)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        guard let d = Data(base64Encoded: b), let raw = String(data: d, encoding: .utf8) else { return nil }
        let f = raw.split(separator: sep, omittingEmptySubsequences: false).map(String.init)
        guard f.count == 7, f.allSatisfy({ $0.count <= maxField }) else { return nil }
        let name = String(f[0].trimmingCharacters(in: .whitespaces).prefix(maxName))
        guard !name.isEmpty, f[2].range(of: "^[a-z0-9:_-]{1,64}$", options: .regularExpression) != nil else { return nil }
        let accent = f[1].range(of: "^[A-Z]{3,12}$", options: .regularExpression) != nil ? f[1] : "PLATINUM"
        return Shared(name: name, accent: accent, layout: f[2], tilt: f[3] == "1", aim: f[4] == "1", mapping: f[5], modes: f[6])
    }
}
