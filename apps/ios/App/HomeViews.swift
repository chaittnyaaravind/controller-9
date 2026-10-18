import NineAppCore
import NineKit
import SwiftUI

struct RootView: View {
    var body: some View {
        NavigationStack { HomeView() }.background(Nine.bg)
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Micro("Controller-9", color: Nine.accent)
                Text("Your controller").font(.system(size: 30, weight: .medium)).tracking(-0.6).accessibilityAddTraits(.isHeader)
                statusCard
                Micro("Computers")
                if model.hosts.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Pair with the computer you want to play on. Open Controller-9 there and choose “Pair a phone”.").foregroundStyle(Nine.muted)
                        NavigationLink("Add a computer") { AddHostView() }.buttonStyle(PrimaryButton())
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
                }
                Micro("Layout")
                NavigationLink { ProfilesView() } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(model.activeProfile?.name ?? "No layout").font(.headline).foregroundStyle(Nine.text)
                        if let p = model.activeProfile { Text("\(p.controls.count) controls\(p.motion.enabled ? " · motion steering" : "")").foregroundStyle(Nine.muted) }
                    }.card()
                }
                HStack(spacing: 18) {
                    NavigationLink("Diagnostics") { DiagnosticsView() }
                    NavigationLink("Settings") { SettingsView() }
                }.foregroundStyle(Nine.text).padding(.top, 4)
            }.padding(20)
        }.background(Nine.bg).toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder private var statusCard: some View {
        let (label, color): (String, Color) = {
            switch model.connection.state {
            case let .connected(h, slot, w): return ("Player \(slot + 1) on \(h)", w == nil ? Nine.accent : Nine.warn)
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
            if case let .connected(_, _, w?) = model.connection.state {
                Text("The computer receives input but can’t create a game controller yet: \(w)").font(.footnote).foregroundStyle(Nine.warn)
            }
            if case .connected = model.connection.state {
                HStack {
                    stat(fmt(model.connection.stats.rttP50), "RTT ms"); stat(fmt(model.connection.stats.rttP95), "p95 ms"); stat(fmt(model.connection.stats.sendHz, 0), "Send Hz")
                }
            }
            HStack(spacing: 10) {
                NavigationLink("Play") { ControllerView() }.buttonStyle(PrimaryButton()).disabled(model.activeProfile == nil)
                switch model.connection.state {
                case .connected, .reconnecting: Button("Disconnect") { model.connection.disconnect() }.foregroundStyle(Nine.text)
                default: EmptyView()
                }
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
                }.foregroundStyle(Nine.text)
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
