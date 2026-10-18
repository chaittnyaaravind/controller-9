import NineAppCore
import NineKit
import SwiftUI
import UIKit

@MainActor @Observable
final class Visuals {
    var pressed: [String: Bool] = [:]
    var stick: [String: CGPoint] = [:]
    var value: [String: Double] = [:]
    var dpad: [String: UInt32] = [:]
    var steering = 0.0
    var toggles: [String: Bool] = [:]
}

struct ControllerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var visuals = Visuals()
    @State private var motion = MotionInput()
    @State private var engine: ControllerEngine?
    @State private var calibration: String?
    @State private var calibrator: Calibrator?
    @State private var lastAngle = Double.nan

    var body: some View {
        if let profile = model.activeProfile {
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    Nine.bg.ignoresSafeArea()
                    ForEach(profile.controls) { c in
                        ControlShape(c: c, visuals: visuals)
                            .frame(width: c.w * geo.size.width, height: c.h * geo.size.height)
                            .position(x: (c.x + c.w / 2) * geo.size.width, y: (c.y + c.h / 2) * geo.size.height)
                            .accessibilityElement()
                            .accessibilityLabel(label(c))
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAction { press(c) }
                    }
                    if let engine {
                        TouchSurface(profile: profile, engine: engine, visuals: visuals, haptics: model.haptics) { recenter() }
                            .accessibilityHidden(true)
                    }
                    statusStrip(profile)
                }
            }
            .ignoresSafeArea()
            .statusBarHidden()
            .persistentSystemOverlays(.hidden)
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { start(profile) }
            .onDisappear { stop() }
            .alert("Calibrate steering", isPresented: .constant(calibration != nil)) {
                Button("Try again") { runCalibration() }
                Button("Cancel", role: .cancel) { calibration = nil; calibrator = nil; engine?.setCalibrating(false) }
            } message: { Text(calibration ?? "") }
        }
    }

    private func label(_ c: ControlDef) -> String {
        switch c.kind {
        case .button: return "\(c.label ?? c.bind.uppercased()) button"
        case .stick: return "\(c.bind) stick"
        case .dpad: return "Direction pad"
        case .trigger: return "\(c.label ?? c.bind.uppercased()) trigger"
        case .pedal: return "\(c.label ?? c.bind.uppercased()) pedal"
        case .steering: return "Steering indicator"
        }
    }

    /// VoiceOver activation: a short press of a button.
    private func press(_ c: ControlDef) {
        guard c.kind == .button, let b = NineButton(id: c.bind), let engine else { return }
        engine.setButtons(c.id, b.mask)
        Task { try? await Task.sleep(nanoseconds: 120_000_000); engine.setButtons(c.id, 0) }
    }

    private func statusStrip(_ p: Profile) -> some View {
        let (text, color): (String, Color) = {
            switch model.connection.state {
            case let .connected(h, s, w, link): return ("P\(s + 1) · \(h) · \(link.label)", w == nil ? Nine.accent : Nine.warn)
            case .reconnecting: return ("Reconnecting…", Nine.warn)
            case .connecting: return ("Connecting…", Nine.warn)
            default: return ("Offline", Nine.bad)
            }
        }()
        return HStack(spacing: 12) {
            Circle().fill(color).frame(width: 7, height: 7)
            Micro(text, color: Nine.text)
            if model.settings.showStats, case .connected = model.connection.state { Micro("\(fmt(model.connection.stats.rttP50)) ms", color: Nine.dim) }
            if p.motion.enabled { Button { runCalibration() } label: { Micro("Center", color: Nine.text) } }
            NavigationLink { LayoutEditorView(profileId: p.id) } label: { Micro("Edit", color: Nine.text) }
            Button { dismiss() } label: { Micro("Exit", color: Nine.text) }
        }
        .padding(.horizontal, 14).padding(.vertical, 6)
        .background(Nine.s1.opacity(0.85), in: Capsule()).padding(.top, 8)
    }

    private func start(_ p: Profile) {
        AppDelegate.lock(p.orientation == .landscape ? .landscape : .portrait)
        UIApplication.shared.isIdleTimerDisabled = true
        model.connection.setProfileName(p.name)
        if case .idle = model.connection.state, let id = model.settings.lastHost, let h = model.hosts.first(where: { $0.id == id }) { model.connection.connect(h) }
        if case .failed = model.connection.state, let id = model.settings.lastHost, let h = model.hosts.first(where: { $0.id == id }) { model.connection.connect(h) }
        let conn = model.connection
        let e = ControllerEngine { s, f in conn.submit(s, f) }
        e.configure(p.motion)
        engine = e
        if p.motion.enabled {
            var steering = Steering(p.motion)
            steering.setCenter(model.settings.calibrationDeg)
            let orientation = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.effectiveGeometry.interfaceOrientation ?? .landscapeRight
            let vis = visuals
            motion.start(hz: model.settings.sensorHz, orientation: orientation) { gx, gy, gz, t in
                if let a = MotionMath.wheelAngleDeg(gx, gy, gz) { Task { @MainActor in lastAngle = a } }
                Task { @MainActor in calibrator?.add(gx, gy, gz) }
                let v = steering.update(gx, gy, gz, t: t)
                e.setMotion(v)
                Task { @MainActor in vis.steering = v }
            }
            Task { while engine != nil { model.connection.sensorHz = motion.measuredHz; try? await Task.sleep(nanoseconds: 1_000_000_000) } }
        }
    }

    private func stop() {
        motion.stop()
        engine?.releaseAll()
        engine = nil
        UIApplication.shared.isIdleTimerDisabled = false
        AppDelegate.lock(.all)
    }

    private func recenter() { if !lastAngle.isNaN { model.settings.calibrationDeg = lastAngle; restartMotion() } }
    private func restartMotion() { if let p = model.activeProfile { motion.stop(); engine = nil; start(p) } }

    private func runCalibration() {
        engine?.setCalibrating(true)
        calibrator = Calibrator()
        calibration = "Hold the phone at your neutral steering position…"
        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            let cal = calibrator?.finish()
            calibrator = nil
            engine?.setCalibrating(false)
            if let cal, cal.steady { model.settings.calibrationDeg = cal.centerDeg; calibration = nil; restartMotion() }
            else if let cal { calibration = String(format: "The phone moved during calibration (±%.1f°). Hold it still and try again.", cal.spreadDeg) }
            else { calibration = "Couldn’t read the motion sensor. Hold the phone upright, not flat." }
        }
    }
}

/// Transparent multi-touch layer: each finger owns the control it first touched.
struct TouchSurface: UIViewRepresentable {
    let profile: Profile
    let engine: ControllerEngine
    let visuals: Visuals
    let haptics: Haptics
    let recenter: () -> Void

    func makeUIView(context: Context) -> SurfaceView { let v = SurfaceView(); v.isMultipleTouchEnabled = true; v.backgroundColor = .clear; update(v); return v }
    func updateUIView(_ v: SurfaceView, context: Context) { update(v) }
    private func update(_ v: SurfaceView) { v.profile = profile; v.engine = engine; v.visuals = visuals; v.haptics = haptics; v.recenter = recenter }

    final class SurfaceView: UIView {
        var profile: Profile?; var engine: ControllerEngine?; var visuals: Visuals?; var haptics: Haptics?; var recenter: (() -> Void)?
        private var owner: [ObjectIdentifier: ControlDef] = [:]
        private var origin: [ObjectIdentifier: CGPoint] = [:]

        private func rect(_ c: ControlDef) -> CGRect { CGRect(x: c.x * bounds.width, y: c.y * bounds.height, width: c.w * bounds.width, height: c.h * bounds.height) }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            for t in touches {
                let p = t.location(in: self)
                guard let c = profile?.controls.last(where: { rect($0).contains(p) }) else { continue }
                owner[ObjectIdentifier(t)] = c; origin[ObjectIdentifier(t)] = p
                handle(c, p, origin: p, down: true)
            }
        }
        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            for t in touches { if let c = owner[ObjectIdentifier(t)] { handle(c, t.location(in: self), origin: origin[ObjectIdentifier(t)]!, down: false) } }
        }
        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }

        private func end(_ touches: Set<UITouch>) {
            for t in touches {
                guard let c = owner.removeValue(forKey: ObjectIdentifier(t)) else { continue }
                origin[ObjectIdentifier(t)] = nil
                release(c)
            }
        }

        private func handle(_ c: ControlDef, _ p: CGPoint, origin o: CGPoint, down: Bool) {
            guard let engine, let visuals else { return }
            let r = rect(c)
            switch c.kind {
            case .button:
                guard down, let b = NineButton(id: c.bind) else { return }
                if c.toggle {
                    let on = !(visuals.toggles[c.id] ?? false); visuals.toggles[c.id] = on
                    engine.setButtons(c.id, on ? b.mask : 0); visuals.pressed[c.id] = on
                } else { engine.setButtons(c.id, b.mask); visuals.pressed[c.id] = true }
                if c.haptic { haptics?.tap() }
            case .stick:
                let radius = min(r.width, r.height) / 2
                var dx = Double((p.x - o.x) / radius), dy = Double(-(p.y - o.y) / radius)
                let m = max(hypot(dx, dy), 1e-9)
                if m > 1 { dx /= m; dy /= m }
                let (x, y) = MotionMath.radialDeadzone(dx, dy, profile?.sticks.deadzone ?? 0.08)
                engine.setStick(c.bind, x, y); visuals.stick[c.id] = CGPoint(x: dx, y: dy); visuals.pressed[c.id] = true
            case .dpad:
                let dx = Double(p.x - r.midX), dy = Double(r.midY - p.y)
                var mask: UInt32 = 0
                if hypot(dx, dy) >= Double(min(r.width, r.height)) * 0.12 {
                    var a = atan2(dy, dx) * 180 / .pi; if a < 0 { a += 360 }
                    let sectors: [UInt32] = [NineButton.right.mask, NineButton.right.mask | NineButton.up.mask, NineButton.up.mask, NineButton.up.mask | NineButton.left.mask,
                                             NineButton.left.mask, NineButton.left.mask | NineButton.down.mask, NineButton.down.mask, NineButton.down.mask | NineButton.right.mask]
                    mask = sectors[Int((a + 22.5) / 45) % 8]
                }
                if mask != visuals.dpad[c.id] ?? 0, mask != 0, c.haptic { haptics?.tap() }
                engine.setButtons(c.id, mask); visuals.dpad[c.id] = mask
            case .trigger, .pedal:
                let v = min(max((Double((p.y - r.minY) / r.height) - 0.08) / 0.84, 0), 1)
                engine.setTrigger(c.id, c.bind, c.kind == .pedal ? max(v, 0.02) : v); visuals.value[c.id] = v
                if down, c.haptic { haptics?.tap() }
            case .steering:
                if down { recenter?(); haptics?.tap() }
            }
        }

        private func release(_ c: ControlDef) {
            guard let engine, let visuals else { return }
            switch c.kind {
            case .button: if !c.toggle { engine.setButtons(c.id, 0); visuals.pressed[c.id] = false }
            case .stick: engine.setStick(c.bind, 0, 0); visuals.stick[c.id] = .zero; visuals.pressed[c.id] = false
            case .dpad: engine.setButtons(c.id, 0); visuals.dpad[c.id] = 0
            case .trigger, .pedal: engine.setTrigger(c.id, c.bind, 0); visuals.value[c.id] = 0
            case .steering: break
            }
        }
    }
}

/// Draws one control. Pressed state uses the single accent.
struct ControlShape: View {
    let c: ControlDef
    let visuals: Visuals?

    var body: some View {
        let pressed = visuals?.pressed[c.id] ?? false
        ZStack {
            switch c.kind {
            case .button:
                RoundedRectangle(cornerRadius: min(c.w, c.h) > 0.2 ? 18 : 999).fill(pressed ? Nine.accent : Nine.s2)
                    .overlay(RoundedRectangle(cornerRadius: min(c.w, c.h) > 0.2 ? 18 : 999).strokeBorder(Nine.line2, lineWidth: 1.5))
                Text(c.label ?? c.bind.uppercased()).font(.system(size: 13, weight: .medium)).foregroundStyle(pressed ? Nine.accentInk : Nine.muted)
            case .stick:
                GeometryReader { g in
                    let r = min(g.size.width, g.size.height) / 2
                    let off = visuals?.stick[c.id] ?? .zero
                    ZStack {
                        Circle().fill(Nine.s1).overlay(Circle().strokeBorder(Nine.line2, lineWidth: 1.5))
                        Circle().strokeBorder(Nine.line, lineWidth: 1.5).frame(width: r, height: r)
                        Circle().fill(pressed ? Nine.text : Nine.s2).overlay(Circle().strokeBorder(Nine.line2, lineWidth: 1.5))
                            .frame(width: r * 0.72, height: r * 0.72).offset(x: off.x * r * 0.62, y: -off.y * r * 0.62)
                    }.frame(width: 2 * r, height: 2 * r).position(x: g.size.width / 2, y: g.size.height / 2)
                }
            case .dpad:
                GeometryReader { g in
                    let s = min(g.size.width, g.size.height), arm = s / 3
                    let m = visuals?.dpad[c.id] ?? 0
                    ZStack {
                        ForEach([(NineButton.up, CGPoint(x: 1, y: 0)), (.left, CGPoint(x: 0, y: 1)), (.right, CGPoint(x: 2, y: 1)), (.down, CGPoint(x: 1, y: 2))], id: \.0.rawValue) { b, at in
                            RoundedRectangle(cornerRadius: 8).fill(m & b.mask != 0 ? Nine.accent : Nine.s2)
                                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Nine.line2, lineWidth: 1.5))
                                .frame(width: arm, height: arm).position(x: at.x * arm + arm / 2, y: at.y * arm + arm / 2)
                        }
                    }.frame(width: s, height: s).position(x: g.size.width / 2, y: g.size.height / 2)
                }
            case .trigger, .pedal:
                let v = visuals?.value[c.id] ?? 0
                let cr: CGFloat = c.kind == .pedal ? 22 : 14
                GeometryReader { g in
                    ZStack(alignment: .top) {
                        RoundedRectangle(cornerRadius: cr).fill(Nine.s1)
                        RoundedRectangle(cornerRadius: cr).fill(Nine.accent.opacity(0.85)).frame(height: g.size.height * v)
                        RoundedRectangle(cornerRadius: cr).strokeBorder(Nine.line2, lineWidth: 1.5)
                        VStack { Spacer(); Text(c.label ?? c.bind.uppercased()).font(.system(size: 13, weight: .medium)).foregroundStyle(Nine.muted).padding(.bottom, 8) }
                    }
                }
            case .steering:
                let s = visuals?.steering ?? 0
                GeometryReader { g in
                    ZStack {
                        Capsule().fill(Nine.line).frame(height: 4)
                        Circle().fill(Nine.text).frame(width: 12, height: 12).offset(x: s * g.size.width / 2 * 0.9)
                        Rectangle().fill(Nine.dim).frame(width: 1.5, height: 16)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .opacity(c.opacity)
    }
}
