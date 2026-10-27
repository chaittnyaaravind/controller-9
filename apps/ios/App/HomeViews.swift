import NineAppCore
import NineKit
import Network
import SwiftUI

struct RootView: View {
    var body: some View {
        NavigationStack {
            #if DEBUG
            if let screen = ScreenshotRoute.requested { ScreenshotRoute.view(screen) } else { HomeView() }
            #else
            HomeView()
            #endif
        }.background(Nine.bg)
    }
}

#if DEBUG
/// Debug builds only: `-screen <name>` opens one screen directly, so CI can capture every screen in the
/// iOS Simulator (tools/ios-ci/screens.sh). Compiled out of Release builds.
enum ScreenshotRoute {
    static let all = ["home", "add", "profiles", "editor", "motion", "connection", "diagnostics", "settings", "controller", "doctor"]
    static var requested: String? {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: "-screen"), i + 1 < a.count, all.contains(a[i + 1]) else { return nil }
        return a[i + 1]
    }
    @MainActor @ViewBuilder static func view(_ name: String) -> some View {
        switch name {
        case "add": AddHostView()
        case "profiles": ProfilesView()
        case "editor", "motion": ProfileRoute(name: name)
        case "connection": ConnectionView()
        case "diagnostics": DiagnosticsView()
        case "settings": SettingsView()
        case "controller": ControllerView()
        case "doctor": DoctorView()
        default: HomeView()
        }
    }
    private struct ProfileRoute: View {
        let name: String
        @Environment(AppModel.self) private var model
        var body: some View {
            if let id = model.activeProfile?.id {
                if name == "motion" { MotionView(profileId: id) } else { LayoutEditorView(profileId: id) }
            }
        }
    }
}
#endif

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var sharing: Game?
    @State private var scanning = false
    private var isConnected: Bool { if case .connected = model.connection.state { return true }; return false }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Image("Wordmark").renderingMode(.template).resizable().scaledToFit().frame(height: 24).foregroundStyle(Nine.text)
                    .accessibilityLabel("Controller-9").padding(.bottom, 6)
                hero
                Micro("Games").padding(.top, 10)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(model.games) { g in
                            Button { model.play(g) } label: { GameCard(game: g, active: g.id == model.game.id) }
                                .accessibilityLabel("Play \(g.name)").accessibilityAddTraits(g.id == model.game.id ? .isSelected : [])
                                .contextMenu { Button("Share by QR code") { sharing = g } }
                        }
                        // A friend's game, from the QR code on their phone (iPhone or Android).
                        Button { scanning = true } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("⌗").font(.title2).foregroundStyle(Nine.muted)
                                Text("Scan a game").font(.subheadline).foregroundStyle(Nine.text)
                            }.frame(width: 120, height: 92, alignment: .leading).padding(12)
                                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Nine.text.opacity(0.14), style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
                        }.accessibilityLabel("Scan a friend's game")
                    }
                }
                Micro("Connection").padding(.top, 10)
                statusCard
                Micro("Computers")
                if model.hosts.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Pair with the computer you want to play on. Open Controller-9 there and choose “Pair a phone”.").foregroundStyle(Nine.muted)
                        NavigationLink("Add a computer") { AddHostView() }.buttonStyle(SecondaryButton())
                    }.card()
                } else {
                    ForEach(model.hosts) { h in
                        Button { model.connection.connect(h) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(h.name).font(.headline).foregroundStyle(Nine.text)
                                    Text("\(h.addr):\(h.port)").font(.caption.monospaced()).foregroundStyle(Nine.dim)
                                }
                                Spacer()
                                Text(model.settings.lastHost == h.id ? "Last used" : "Connect").foregroundStyle(Nine.muted)
                            }.card()
                        }.accessibilityLabel("Connect to \(h.name)")
                    }
                    NavigationLink("Add another computer") { AddHostView() }.foregroundStyle(Nine.text)
                    // Paired but not connected: say why instead of leaving people guessing (replica/fixes.md, F2).
                    if !isConnected { NavigationLink("Why won’t it connect?") { DoctorView() }.foregroundStyle(Nine.text) }
                }
                Micro("Layout")
                NavigationLink { ProfilesView() } label: {
                    VStack(alignment: .leading, spacing: 14) {
                        if let pad = model.activePad { PadPreview(preset: pad) }
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(model.activeProfile?.name ?? "No layout").font(.headline).foregroundStyle(Nine.text)
                                if let pad = model.activePad { Text(pad.tagline).foregroundStyle(Nine.muted) }
                                else if let p = model.activeProfile { Text("\(p.controls.count) controls\(p.motion.enabled ? " · motion steering" : "")").foregroundStyle(Nine.muted) }
                            }
                            Spacer()
                            Text("Change").foregroundStyle(Nine.muted)
                        }
                    }.card()
                }
                HStack(spacing: 18) {
                    NavigationLink("Connection") { ConnectionView() }
                    NavigationLink("Diagnostics") { DiagnosticsView() }
                    NavigationLink("Settings") { SettingsView() }
                }.foregroundStyle(Nine.text).padding(.top, 4)
            }.padding(20)
        }.background(Nine.bg).toolbar(.hidden, for: .navigationBar)
        .sheet(item: $sharing) { ShareGameView(game: $0).presentationDetents([.medium, .large]) }
        .sheet(isPresented: $scanning) { ScanGameView().presentationDetents([.large]) }
    }

    /// The one hero: the game being played, its controller in its light, one big Play.
    private var hero: some View {
        VStack(alignment: .leading, spacing: 6) {
            Micro("Now playing", color: Nine.accent)
            Text(model.game.name).font(Nine.sans(40, .medium)).tracking(-1.4).lineLimit(2).accessibilityAddTraits(.isHeader)
            Text(model.activePad?.name ?? model.activeProfile?.name ?? "No layout").font(Nine.sans(13)).foregroundStyle(Nine.muted)
            if let pad = model.activePad { PadPreview(preset: pad).padding(.vertical, 10) }
            HStack(spacing: 10) {
                NavigationLink("Play") { ControllerView() }.buttonStyle(PrimaryButton()).disabled(model.activeProfile == nil)
                NavigationLink("Layout") { ProfilesView() }.buttonStyle(SecondaryButton())
            }.padding(.top, 6)
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [Nine.accent.opacity(0.07), Nine.s1], startPoint: .top, endPoint: .bottom), in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(LinearGradient(colors: [Nine.accent.opacity(0.45), Nine.line], startPoint: .top, endPoint: .bottom), lineWidth: 1))
    }

    @ViewBuilder private var statusCard: some View {
        let (label, color): (String, Color) = {
            switch model.connection.state {
            case let .connected(h, slot, w, link): return ("Player \(slot + 1) on \(h) · \(link.label)", w == nil ? Nine.accent : Nine.warn)
            case let .connecting(h): return ("Connecting to \(h)…", Nine.warn)
            case let .reconnecting(h, _, _): return ("Reconnecting to \(h)…", Nine.warn)
            case .confirmCode: return ("Confirm the code", Nine.warn)
            case let .failed(m): return (m, Nine.bad)
            case .idle: return (model.hosts.isEmpty ? "No computer paired yet" : "Not connected", Nine.dim)
            }
        }()
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) { Circle().fill(color).frame(width: 7, height: 7); Text(label).font(.headline) }
                .accessibilityElement(children: .combine)
            if case let .connected(_, _, w?, _) = model.connection.state {
                Text("The computer receives input but can’t create a game controller yet: \(w)").font(.footnote).foregroundStyle(Nine.warn)
            }
            if case .connected = model.connection.state {
                HStack {
                    stat(fmt(model.connection.stats.rttP50), "RTT ms"); stat(fmt(model.connection.stats.rttP95), "p95 ms"); stat(fmt(model.connection.stats.sendHz, 0), "Send Hz")
                }
            }
            switch model.connection.state {
            case .connected, .reconnecting: Button("Disconnect") { model.connection.disconnect() }.buttonStyle(SecondaryButton())
            default: EmptyView()
            }
        }.card()
    }

    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading) { Text(v).font(.title3.monospacedDigit()); Micro(l, color: Nine.dim) }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct AddHostView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var scanning = false
    @State private var manual = ""
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("On the computer, open Controller-9 and choose “Pair a phone”.").foregroundStyle(Nine.muted)
                if scanning {
                    QRScanner { text in
                        scanning = false
                        if let uri = PairURI(parsing: text) { model.connection.pair(qr: uri) } else { error = "That isn’t a Controller-9 pairing code." }
                    }
                    .aspectRatio(1, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 14))
                    Button("Cancel") { scanning = false }.foregroundStyle(Nine.text)
                } else {
                    Button("Scan the QR code") { scanning = true }.buttonStyle(PrimaryButton())
                }
                Micro("On this network")
                if model.discovery.hosts.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(model.discovery.error ?? "Looking for computers…").foregroundStyle(Nine.muted)
                        Text("Not listed? Some routers block discovery. Use the QR code or the address below.").font(.footnote).foregroundStyle(Nine.dim)
                    }.card()
                }
                ForEach(model.discovery.hosts) { h in
                    Button { error = nil; model.connection.pairWithCode(endpoint: h.endpoint, name: h.name) } label: {
                        HStack { VStack(alignment: .leading) { Text(h.name).font(.headline).foregroundStyle(Nine.text); Text(h.os ?? "computer").font(.caption).foregroundStyle(Nine.dim) }
                            Spacer(); Text("Pair with code").foregroundStyle(Nine.muted) }.card()
                    }.accessibilityLabel("Pair with \(h.name) using a code")
                }
                Micro("No network?")
                VStack(alignment: .leading, spacing: 8) {
                    Text("Pair over Bluetooth").font(.headline)
                    Text("Works with Wi-Fi off. Open “Pair a phone” on the computer (it needs Bluetooth), keep this screen open, then compare codes.").foregroundStyle(Nine.muted)
                    Button("Start Bluetooth pairing") { error = nil; model.connection.pairWithCode(endpoint: nil, name: "computer over Bluetooth") }
                        .buttonStyle(SecondaryButton()).padding(.top, 4)
                }.card()
                VStack(alignment: .leading, spacing: 8) {
                    Text("Personal Hotspot or USB").font(.headline)
                    Text("No router? Turn on this iPhone's Personal Hotspot and join it from the computer, or connect the iPhone by USB cable with Personal Hotspot on. Then scan the computer's QR code.").foregroundStyle(Nine.muted)
                }.card()
                Micro("Manual address")
                TextField("192.168.1.20 or 192.168.1.20:47901", text: $manual)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(12).background(Nine.s2, in: RoundedRectangle(cornerRadius: 8))
                Button("Pair with code") {
                    let parts = manual.split(separator: ":")
                    guard let a = parts.first, !a.isEmpty else { error = "Enter the address shown on the computer."; return }
                    let port = parts.count > 1 ? UInt16(parts[1]) ?? defaultTCPPort : defaultTCPPort
                    error = nil
                    model.connection.pairWithCode(endpoint: .hostPort(host: .init(String(a)), port: .init(rawValue: port)!), name: String(a))
                }.buttonStyle(SecondaryButton())
                if case let .failed(m) = model.connection.state { Text(m).foregroundStyle(Nine.bad) }
                if case let .connecting(h) = model.connection.state { Text("Connecting to \(h)…").foregroundStyle(Nine.muted) }
                if let error { Text(error).foregroundStyle(Nine.bad) }
            }.padding(20)
        }
        .background(Nine.bg).navigationTitle("Add a computer")
        .onAppear { model.discovery.start() }.onDisappear { model.discovery.stop() }
        .onChange(of: model.connection.state) { _, s in if case .connected = s { dismiss() } }
        .alert(codeTitle, isPresented: .constant(isConfirming)) {
            Button("They differ", role: .cancel) { model.connection.confirmCode(false) }
            Button("Codes match") { model.connection.confirmCode(true) }
        } message: { Text("\(codeValue)\n\nConfirm only if both screens match exactly. Then click Allow on the computer.") }
    }

    private var isConfirming: Bool { if case .confirmCode = model.connection.state { return true }; return false }
    private var codeTitle: String { if case let .confirmCode(h, _) = model.connection.state { return "Does \(h) show this code?" }; return "" }
    private var codeValue: String { if case let .confirmCode(_, c) = model.connection.state { return c }; return "" }
}

/// One game in the rail: its tone as a lit dot, its name; the chosen one carries its tone's rim.
struct GameCard: View {
    let game: Game
    let active: Bool
    var body: some View {
        VStack(alignment: .leading) {
            Circle().fill(game.accent.color).frame(width: 13, height: 13).shadow(color: game.accent.color.opacity(0.7), radius: 8)
            Spacer()
            Text(game.name).font(Nine.sans(13, .medium)).foregroundStyle(Nine.text).lineLimit(2).multilineTextAlignment(.leading)
        }
        .padding(14).frame(width: 140, height: 96, alignment: .leading)
        .background(active ? AnyShapeStyle(LinearGradient(colors: [game.accent.color.opacity(0.10), Nine.s1], startPoint: .top, endPoint: .bottom)) : AnyShapeStyle(Nine.s1), in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(active ? game.accent.color.opacity(0.7) : Nine.line, lineWidth: 1))
    }
}
