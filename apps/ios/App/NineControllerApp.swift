import NineAppCore
import NineKit
import Network
import SwiftUI
import UIKit

@main
struct NineControllerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            // Choosing a game re-lights every screen: the root is rebuilt with the new tone.
            RootView().environment(model).preferredColorScheme(.dark).tint(Nine.accent).id(model.game.accent)
        }
        .onChange(of: phase) { _, p in model.connection.setPaused(p != .active) } // background: release everything
    }
}

/// Orientation lock used by the controller and editor screens.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var mask: UIInterfaceOrientationMask = .all
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { Self.mask }

    @MainActor static func lock(_ m: UIInterfaceOrientationMask) {
        mask = m
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: m)) { _ in }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}

@MainActor @Observable
final class AppModel {
    let store = FileStore()
    let connection: ConnectionManager
    let haptics = Haptics()
    let discovery = Discovery()
    var settings: AppSettings { didSet { store.saveSettings(settings); applyHaptics() } }
    var profiles: [Profile] = []
    var games: [Game] = GameStore.load() { didSet { GameStore.save(games) } }
    /// The game being played: its tone lights the app (Accent.current), its layout is the active one.
    var game: Game = Game.starters[0]

    init() {
        settings = store.settings
        connection = ConnectionManager(store: store, deviceName: UIDevice.current.name,
                                       appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0")
        if store.profiles().isEmpty { restorePresets() }
        reloadProfiles()
        #if DEBUG
        // CI screenshots: `-layout pad:xbox` selects a layout for this launch (tools/ios-ci/screens.sh).
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-layout"), i + 1 < args.count { settings.activeProfile = args[i + 1] }
        // Simulator tests: `-pair-uri <ninectl://pair?...>` pairs as if that QR code was scanned (the Simulator has no camera).
        if let i = args.firstIndex(of: "-pair-uri"), i + 1 < args.count, let uri = PairURI(parsing: args[i + 1]) { connection.pair(qr: uri) }
        #endif
        let h = haptics
        connection.onRumble = { low, high, ms in Task { @MainActor in h.rumble(low: low, high: high, ms: ms) } }
        connection.onHostChosen = { [weak self] id in Task { @MainActor in if self?.settings.lastHost != id { self?.settings.lastHost = id } } }
        applyHaptics()
        game = games.first { $0.id == GameStore.activeId } ?? games[0]
        Accent.current = game.accent
    }

    /// Make [g] the game being played: its tone and layout, in one step.
    func play(_ g: Game) {
        game = g; Accent.current = g.accent; GameStore.activeId = g.id
        settings.activeProfile = g.layout
    }

    var hosts: [PairedHost] { store.hosts }
    /// The controller-style layout in use (`pad:<id>`), or nil for a custom/preset profile.
    var activePad: PadPreset? { PadPresets.from(selection: settings.activeProfile) }
    /// The layout in use. A controller-style layout is fitted to the real screen size by the controller
    /// screen; this reference fit only serves names and counts.
    var activeProfile: Profile? {
        if let pad = activePad { return pad.profile(for: CGSize(width: 800, height: 360)) }
        return profiles.first { $0.id == settings.activeProfile } ?? profiles.first
    }

    func reloadProfiles() { profiles = store.profiles() }
    func restorePresets() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "profiles") ?? []
        store.installPresets(from: urls)
        reloadProfiles()
    }
    func save(_ p: Profile) throws { try store.save(p); reloadProfiles() }
    private func applyHaptics() { haptics.enabled = settings.hapticsEnabled; haptics.strength = settings.hapticStrength }
}
