import NineAppCore
import NineKit
import SwiftUI
import UIKit

@main
struct NineControllerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView().environment(model).preferredColorScheme(.dark).tint(Nine.accent)
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

    init() {
        settings = store.settings
        connection = ConnectionManager(store: store, deviceName: UIDevice.current.name,
                                       appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0")
        if store.profiles().isEmpty { restorePresets() }
        reloadProfiles()
        let h = haptics
        connection.onRumble = { low, high, ms in Task { @MainActor in h.rumble(low: low, high: high, ms: ms) } }
        applyHaptics()
    }

    var hosts: [PairedHost] { store.hosts }
    var activeProfile: Profile? { profiles.first { $0.id == settings.activeProfile } ?? profiles.first }

    func reloadProfiles() { profiles = store.profiles() }
    func restorePresets() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "profiles") ?? []
        store.installPresets(from: urls)
        reloadProfiles()
    }
    func save(_ p: Profile) throws { try store.save(p); reloadProfiles() }
    private func applyHaptics() { haptics.enabled = settings.hapticsEnabled; haptics.strength = settings.hapticStrength }
}
