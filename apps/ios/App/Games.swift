import Foundation

/// A game the player sets up once: its tone lights the app, and choosing it switches to its layout
/// (same model as Android's data/GameStore). Stored as JSON in UserDefaults.
struct Game: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var accent: Accent
    /// An AppSettings.activeProfile value: `pad:xbox`, or a custom profile id.
    var layout: String

    static let anyId = "any"
    static let starters: [Game] = [
        Game(id: anyId, name: "Any game", accent: .platinum, layout: "pad:xbox"),
        Game(id: "asphalt-legends", name: "Asphalt Legends", accent: .crimson, layout: "pad:xbox"),
        Game(id: "go-kart-racing", name: "Go Kart Racing", accent: .champagne, layout: "pad:xbox"),
    ]
}

enum GameStore {
    private static let key = "games.v1", activeKey = "games.active"
    static func load() -> [Game] {
        guard let d = UserDefaults.standard.data(forKey: key), let g = try? JSONDecoder().decode([Game].self, from: d), !g.isEmpty else { return Game.starters }
        return g
    }
    static func save(_ g: [Game]) { if let d = try? JSONEncoder().encode(g) { UserDefaults.standard.set(d, forKey: key) } }
    static var activeId: String {
        get { UserDefaults.standard.string(forKey: activeKey) ?? Game.anyId }
        set { UserDefaults.standard.set(newValue, forKey: activeKey) }
    }
}
