import NineAppCore
import NineKit
import SwiftUI
import UniformTypeIdentifiers

struct ProfileFile: FileDocument {
    static let readableContentTypes = [UTType.json]
    let data: Data
    init(_ p: Profile) { data = p.jsonData() }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct ProfilesView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false
    @State private var exporting: Profile?
    @State private var message: String?
    @State private var deleting: Profile?
    @State private var customised: String?

    var body: some View {
        List {
            Section {
                ForEach(PadPresets.all) { pad in
                    let inUse = model.settings.activeProfile == PadPresets.selection(pad)
                    VStack(alignment: .leading, spacing: 10) {
                        PadPreview(preset: pad).padding(.vertical, 4)
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(pad.name).font(.headline)
                                Text(pad.tagline).font(.subheadline).foregroundStyle(Nine.muted)
                            }
                            Spacer()
                            if inUse { Micro("In use", color: Nine.accent) }
                            else { Button("Use") { model.settings.activeProfile = PadPresets.selection(pad) }.buttonStyle(SecondaryButton()) }
                        }
                        // Move and resize each button on its own: an editable copy fitted to this screen.
                        Button("Customise positions and sizes") { customise(pad) }
                            .buttonStyle(.borderless).font(.subheadline).foregroundStyle(Nine.text)
                        NavigationLink("Turbo and toggle buttons") { ButtonModesView(pad: pad) }
                            .font(.subheadline).foregroundStyle(Nine.text)
                        DisclosureGroup("How it maps") {
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(pad.notes, id: \.self) { Text($0).font(.footnote).foregroundStyle(Nine.muted) }
                            }.padding(.top, 4)
                        }.font(.subheadline).tint(Nine.muted)
                    }
                    .listRowBackground(Nine.s1)
                }
            } header: { Text("Controller layouts") } footer: {
                Text("Familiar controller faces. Buttons are positional: the bottom face button always sends A, whatever it is called.")
            }
            Section("Custom layouts") {
            ForEach(model.profiles) { p in
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text(p.name).font(.headline); Spacer(); if p.id == model.settings.activeProfile { Micro("In use", color: Nine.accent) } }
                    Text("\(p.controls.count) controls\(p.motion.enabled ? " · motion steering (\(Int(p.motion.lockDeg))° lock)" : "")").foregroundStyle(Nine.muted)
                    HStack(spacing: 16) {
                        if p.id != model.settings.activeProfile { Button("Use") { model.settings.activeProfile = p.id } }
                        NavigationLink("Edit layout") { LayoutEditorView(profileId: p.id) }
                        NavigationLink("Motion") { MotionView(profileId: p.id) }
                    }.buttonStyle(.borderless).foregroundStyle(Nine.text)
                }
                .listRowBackground(Nine.s1)
                .swipeActions {
                    if model.profiles.count > 1 { Button("Delete", role: .destructive) { deleting = p } }
                    Button("Export") { exporting = p }
                    Button("Duplicate") { _ = try? model.store.duplicate(p); model.reloadProfiles() }
                }
            }
            }
            Section {
                Button("Import a layout") { importing = true }
                Button("Restore presets") { model.restorePresets(); message = "Presets restored." }
                if let message { Text(message).foregroundStyle(Nine.muted) }
            }.listRowBackground(Nine.s1)
        }
        .scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("Layouts")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { r in
            message = {
                do {
                    let url = try r.get()
                    let ok = url.startAccessingSecurityScopedResource(); defer { if ok { url.stopAccessingSecurityScopedResource() } }
                    let p = try model.store.importProfile(Data(contentsOf: url).prefix(256 * 1024))
                    model.reloadProfiles()
                    return "Imported “\(p.name)”."
                } catch { return "Couldn’t import: \(error.localizedDescription)" }
            }()
        }
        .fileExporter(isPresented: .constant(exporting != nil), document: exporting.map(ProfileFile.init), contentType: .json,
                      defaultFilename: "\(exporting?.id ?? "layout").controller9.json") { r in
            message = (try? r.get()) != nil ? "Exported." : "Export cancelled."
            exporting = nil
        }
        .confirmationDialog("Delete “\(deleting?.name ?? "")”?", isPresented: .constant(deleting != nil), titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let d = deleting { model.store.deleteProfile(d.id); model.reloadProfiles(); if model.settings.activeProfile == d.id, let f = model.profiles.first { model.settings.activeProfile = f.id } }
                deleting = nil
            }
            Button("Keep", role: .cancel) { deleting = nil }
        }
        .navigationDestination(item: $customised) { id in LayoutEditorView(profileId: id) }
    }

    /// Copies a controller layout into an editable one fitted to this screen (landscape), makes it the
    /// layout in use and opens the editor.
    private func customise(_ pad: PadPreset) {
        let b = UIScreen.main.bounds
        let fitted = pad.profile(for: CGSize(width: max(b.width, b.height), height: min(b.width, b.height)))
        let ids = Set(model.profiles.map(\.id))
        let id = (1...).lazy.map { n in n == 1 ? "\(pad.id)-custom" : "\(pad.id)-custom-\(n)" }.first { !ids.contains($0) }!
        // Keep the layout's toggle and turbo buttons (modes move to the new id).
        var p = ButtonModes.apply(model.settings.modes(for: pad), to: Profile(id: id, name: "\(pad.name) · custom", controls: fitted.controls))
        p.orientation = .landscape
        do {
            try model.save(p)
            model.settings.copyModes(from: pad.id, to: id)
            model.settings.activeProfile = id
            // The game being played now uses the customised layout, so Play doesn't switch back to the stock one.
            if let i = model.games.firstIndex(where: { $0.id == model.game.id }) { model.games[i].layout = id; model.game = model.games[i] }
            customised = id
        } catch { message = "Couldn’t copy the layout: \(error.localizedDescription)" }
    }
}

struct LayoutEditorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let profileId: String
    @State private var controls: [ControlDef] = []
    @State private var selected: String?
    @State private var error: String?
    /// The control as it was when the current drag began; drags apply their total translation to it.
    @State private var dragStart: ControlDef?

    private static func snap(_ v: Double) -> Double { (v * 200).rounded() / 200 }
    /// Drag translation as a fraction of the layout size.
    private static func frac(_ t: CGSize, _ size: CGSize) -> (Double, Double) {
        (Double(t.width / max(size.width, 1)), Double(t.height / max(size.height, 1)))
    }
    private func origin(_ c: ControlDef) -> ControlDef {
        if let s = dragStart, s.id == c.id { return s }
        dragStart = c
        return c
    }
    private static func binds(_ k: ControlKind) -> [String] {
        switch k {
        case .button: return NineButton.allCases.map(\.id)
        case .stick: return ["left", "right"]
        case .dpad: return ["dpad"]
        case .trigger, .pedal: return ["lt", "rt"]
        case .steering: return ["motion"]
        }
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                Nine.bg.ignoresSafeArea().onTapGesture { selected = nil }
                ForEach($controls) { $c in
                    let sel = c.id == selected
                    ControlShape(c: c, visuals: nil)
                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(sel ? Nine.accent : Nine.line2, lineWidth: sel ? 2 : 1))
                        .overlay(alignment: .bottomTrailing) {
                            if sel {
                                RoundedRectangle(cornerRadius: 6).fill(Nine.accent).frame(width: 28, height: 28)
                                    .gesture(DragGesture().onChanged { g in
                                        let s = origin(c), (dx, dy) = Self.frac(g.translation, geo.size)
                                        c.w = Self.snap(min(max(s.w + dx, 0.04), 1 - c.x))
                                        c.h = Self.snap(min(max(s.h + dy, 0.04), 1 - c.y))
                                    }.onEnded { _ in dragStart = nil }).accessibilityLabel("Resize handle")
                            }
                        }
                        .frame(width: c.w * geo.size.width, height: c.h * geo.size.height)
                        .position(x: (c.x + c.w / 2) * geo.size.width, y: (c.y + c.h / 2) * geo.size.height)
                        .onTapGesture { selected = c.id }
                        .gesture(DragGesture().onChanged { g in
                            selected = c.id
                            let s = origin(c), (dx, dy) = Self.frac(g.translation, geo.size)
                            c.x = Self.snap(min(max(s.x + dx, 0), 1 - c.w))
                            c.y = Self.snap(min(max(s.y + dy, 0), 1 - c.h))
                        }.onEnded { _ in dragStart = nil })
                        .accessibilityLabel("\(c.kind.rawValue) \(c.label ?? c.bind). Drag to move.")
                }
                toolbar
                if let i = controls.firstIndex(where: { $0.id == selected }) { properties($controls[i]).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing).padding(12) }
                if let error { Text(error).foregroundStyle(Nine.bad).frame(maxHeight: .infinity, alignment: .bottom).padding() }
            }
        }
        .toolbar(.hidden, for: .navigationBar).statusBarHidden()
        .onAppear { controls = model.profiles.first { $0.id == profileId }?.controls ?? []; AppDelegate.lock(.landscape) }
    }

    private var toolbar: some View {
        HStack(spacing: 14) {
            Menu { ForEach(ControlKind.allCases, id: \.self) { k in Button(k.rawValue.capitalized) { add(k) } } } label: { Micro("Add", color: Nine.text) }
            Button { controls = model.profiles.first { $0.id == profileId }?.controls ?? []; selected = nil } label: { Micro("Reset", color: Nine.text) }
            Button { dismiss() } label: { Micro("Cancel", color: Nine.text) }
            Button("Save") {
                guard var p = model.profiles.first(where: { $0.id == profileId }) else { return }
                p.controls = controls
                do { try model.save(p); dismiss() } catch { self.error = error.localizedDescription }
            }.buttonStyle(PrimaryButton())
        }.padding(.horizontal, 14).padding(.vertical, 6).background(Nine.s1, in: Capsule()).padding(.top, 8)
    }

    private func add(_ k: ControlKind) {
        var n = 1; while controls.contains(where: { $0.id == "\(k.rawValue)-\(n)" }) { n += 1 }
        let sq = k == .button || k == .stick || k == .dpad
        controls.append(ControlDef(id: "\(k.rawValue)-\(n)", kind: k, bind: Self.binds(k)[0], x: 0.42, y: 0.4, w: sq ? 0.12 : 0.14, h: sq ? 0.22 : 0.4))
        selected = controls.last?.id
    }

    private func properties(_ c: Binding<ControlDef>) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Micro(c.wrappedValue.kind.rawValue, color: Nine.accent)
            Picker("Action", selection: c.bind) { ForEach(Self.binds(c.wrappedValue.kind), id: \.self) { Text($0) } }
            TextField("Label", text: Binding(get: { c.wrappedValue.label ?? "" }, set: { c.wrappedValue.label = $0.isEmpty ? nil : String($0.prefix(12)) }))
                .padding(8).background(Nine.s2, in: RoundedRectangle(cornerRadius: 8))
            Micro("Opacity \(Int(c.wrappedValue.opacity * 100))%")
            Slider(value: c.opacity, in: 0.1...1)
            if c.wrappedValue.kind == .button { Toggle("Toggle (latch)", isOn: c.toggle) }
            Toggle("Haptic click", isOn: c.haptic)
            Button("Remove control", role: .destructive) { controls.removeAll { $0.id == c.wrappedValue.id }; selected = nil }
        }
        .padding(14).frame(width: 260)
        .background(Nine.s1, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Nine.line2))
    }
}

struct MotionView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let profileId: String
    @State private var m = MotionSettings()
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                Toggle("Steer by tilting the phone", isOn: $m.enabled)
                Picker("Steer with", selection: $m.axis) { Text("Left stick").tag("lx"); Text("Right stick").tag("rx") }
            }
            Section("Response") {
                slider("Steering lock", $m.lockDeg, 10...180, "\(Int(m.lockDeg))°")
                slider("Sensitivity", $m.sensitivity, 0.25...4, String(format: "%.2f×", m.sensitivity))
                slider("Dead zone", $m.deadzone, 0...0.3, "\(Int(m.deadzone * 100))%")
                slider("Response curve", $m.curve, 0.5...3, String(format: "%.2f", m.curve))
                slider("Smoothing cutoff", $m.minCutoff, 0.5...20, String(format: "%.1f Hz", m.minCutoff))
                Toggle("Invert direction", isOn: $m.invert)
                Canvas { ctx, size in
                    var path = Path()
                    for i in 0...100 {
                        let d = (Double(i) / 100 * 2 - 1) * m.lockDeg * 1.2
                        let pt = CGPoint(x: Double(i) / 100 * size.width, y: size.height / 2 - MotionMath.shape(d, m) * size.height / 2 * 0.92)
                        if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                    }
                    ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: size.height / 2)); $0.addLine(to: CGPoint(x: size.width, y: size.height / 2)) }, with: .color(Nine.line))
                    ctx.stroke(path, with: .color(Nine.text), lineWidth: 2)
                }.frame(height: 140).accessibilityLabel("Steering response curve")
            }
            Section {
                Button("Save") {
                    guard var p = model.profiles.first(where: { $0.id == profileId }) else { return }
                    p.motion = m
                    do { try model.save(p); dismiss() } catch { self.error = error.localizedDescription }
                }
                if let error { Text(error).foregroundStyle(Nine.bad) }
                Text("Calibrate the center from the controller screen (“Center”). More smoothing (lower cutoff) adds a little delay.").font(.footnote).foregroundStyle(Nine.dim)
            }
        }
        .scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("Motion steering")
        .onAppear { m = model.profiles.first { $0.id == profileId }?.motion ?? MotionSettings() }
    }

    private func slider(_ label: String, _ v: Binding<Double>, _ r: ClosedRange<Double>, _ shown: String) -> some View {
        VStack(alignment: .leading) { HStack { Text(label); Spacer(); Text(shown).monospacedDigit().foregroundStyle(Nine.muted) }; Slider(value: v, in: r).accessibilityLabel(label) }
    }
}

struct ConnectionView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Form {
            Section("Active link") {
                if case let .connected(_, _, _, link) = model.connection.state { Text("\(link.label) — \(model.connection.stats.linkDetail)") } else { Text("Not connected").foregroundStyle(Nine.muted) }
                Text("Controller-9 never needs the Internet. It picks the best local link and moves to the next one if it fails, releasing every button first.").font(.footnote).foregroundStyle(Nine.dim)
                if let s = model.connection.stats.lastSwitch { Text("Last switch: \(s)").font(.footnote).foregroundStyle(Nine.dim) }
            }
            Section("Links") {
                if model.connection.links.isEmpty { Text("Connect to a computer to see link details.").foregroundStyle(Nine.muted) }
                ForEach(model.connection.links) { l in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack { Circle().fill(l.available ? Nine.accent : Nine.dim).frame(width: 7, height: 7); Text(l.kind.label); Spacer(); Text(l.score.map { "score \(Int($0))" } ?? "unavailable").foregroundStyle(Nine.dim) }
                        if let r = l.reason { Text(r).font(.footnote).foregroundStyle(Nine.muted) }
                        if l.attempts > 0 { Text("\(l.successes)/\(l.attempts) recent connects ok").font(.footnote).foregroundStyle(Nine.dim) }
                    }.accessibilityElement(children: .combine)
                }
            }
            Section("Preferred link") {
                Picker("Preferred link", selection: Binding(get: { model.settings.linkOverride ?? "auto" }, set: { model.settings.linkOverride = $0 })) {
                    Text("Automatic").tag("auto")
                    ForEach([LinkKind.lan, .stream, .ble], id: \.self) { Text($0.label).tag($0.rawValue) }
                }
                Text("Used whenever available; otherwise the automatic order applies.").font(.footnote).foregroundStyle(Nine.dim)
            }
        }.scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("Connection")
    }
}

struct DiagnosticsView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        let s = model.connection.stats
        Form {
            Section("State") { Text(String(describing: model.connection.state)) }
            Section("Link") {
                LabeledContent("RTT p50", value: "\(fmt(s.rttP50)) ms"); LabeledContent("RTT p95", value: "\(fmt(s.rttP95)) ms")
                LabeledContent("Send rate", value: "\(fmt(s.sendHz, 0)) Hz"); LabeledContent("Sensor rate", value: "\(fmt(s.sensorHz, 0)) Hz")
                LabeledContent("Frames sent", value: "\(s.sent)"); LabeledContent("Reconnects", value: "\(s.reconnects)")
                LabeledContent("Last reconnect", value: s.lastReconnectMs.map { "\($0) ms" } ?? "—")
                LabeledContent("Watchdog neutralizations", value: "\(s.watchdogTrips)")
            }
            Section("What these mean") {
                Text("RTT is the encrypted ping round trip between this phone and the computer. It covers the network and host only — not the screen, game or display delay.").font(.footnote).foregroundStyle(Nine.dim)
            }
        }.scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("Diagnostics")
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var confirm: String?
    var body: some View {
        @Bindable var model = model
        Form {
            Section("Feel") {
                Toggle("Vibration", isOn: $model.settings.hapticsEnabled)
                if !model.haptics.supportsRumble { Text("Game rumble needs a device with Core Haptics; button clicks still work.").font(.footnote).foregroundStyle(Nine.dim) }
                VStack(alignment: .leading, spacing: 6) {
                    LabeledContent("Vibration strength", value: "\(Int((model.settings.hapticStrength * 100).rounded()))%")
                    Slider(value: $model.settings.hapticStrength, in: 0...1).accessibilityLabel("Vibration strength")
                }.disabled(!model.settings.hapticsEnabled)
                Toggle("Light behind the controls", isOn: $model.settings.glowOn)
                Toggle("Show latency on the controller", isOn: $model.settings.showStats)
                Picker("Motion sensor rate", selection: $model.settings.sensorHz) { Text("100 Hz").tag(100); Text("200 Hz").tag(200) }
            }
            Section {
                Toggle("Gyro aim", isOn: $model.settings.gyroAimOn)
                if model.settings.gyroAimOn {
                    Picker("Sensitivity", selection: $model.settings.aimSensitivityValue) {
                        Text("Low").tag(0.7); Text("Medium").tag(1.2); Text("High").tag(2.0); Text("Max").tag(3.0)
                    }.pickerStyle(.segmented)
                    Toggle("Invert up and down", isOn: $model.settings.aimInvert)
                }
            } header: { Text("Aim") } footer: {
                Text("Turn the phone to aim: how fast it turns is added to the right stick, like a DualSense or Switch Pro.")
            }
            Section("Lock the controller") {
                // iOS doesn't let apps pin themselves; Guided Access is the system's own lock.
                Text("Keep swipes from leaving the game: turn on Guided Access in Settings → Accessibility → Guided Access, then triple-click the side button on the controller screen. Triple-click again to unlock.")
                    .font(.footnote).foregroundStyle(Nine.muted)
            }
            Section("Privacy") {
                Text("Controller-9 works only on your local network. No account, no cloud service, no analytics. Your controller identity stays in this iPhone’s Keychain and is never synced or backed up.").font(.footnote).foregroundStyle(Nine.muted)
                Button("Forget all computers", role: .destructive) { confirm = "hosts" }
                Button("Reset controller identity", role: .destructive) { confirm = "identity" }
            }
            Section("About") {
                Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?") · protocol 1").foregroundStyle(Nine.muted)
            }
        }
        .scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("Settings")
        .confirmationDialog(confirm == "hosts" ? "Forget all computers?" : "Reset identity? Every computer must pair again.", isPresented: .constant(confirm != nil), titleVisibility: .visible) {
            Button("Confirm", role: .destructive) {
                model.connection.disconnect()
                if confirm == "identity" { IdentityStore.reset() }
                model.store.saveHosts([])
                confirm = nil
            }
            Button("Cancel", role: .cancel) { confirm = nil }
        }
    }
}

/// Turbo and toggle per button of a controller layout (ButtonModes), the same choices as Android.
struct ButtonModesView: View {
    @Environment(AppModel.self) private var model
    let pad: PadPreset

    var body: some View {
        let modes = model.settings.modes(for: pad)
        let buttons = pad.elements.filter { $0.action?.kind == .button && [.face, .bumper, .center, .stickPress].contains($0.part) }
        Form {
            Section {
                ForEach(buttons) { e in
                    Picker(e.label, selection: Binding(get: { modes[e.id] ?? .normal },
                                                       set: { model.settings.setMode($0, for: e.id, in: pad) })) {
                        ForEach(ButtonModes.Mode.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                }
            } footer: {
                Text("Turbo fires about 12 times a second while you hold the button. Toggle latches: tap to hold, tap again to let go.")
            }
        }
        .scrollContentBackground(.hidden).background(Nine.bg).navigationTitle("\(pad.name) buttons")
    }
}
