import Foundation
import NineKit
import Testing
@testable import NineAppCore

/// Phone held upright in landscape (home indicator on the right): the device's top points left, so
/// Core Motion's gravity (pointing down) is (−1, 0, 0) and "up" is its negation.
private let gravity = (-1.0, 0.0, 0.0)
private let up = (-gravity.0, -gravity.1, -gravity.2)
private let right = GyroAimMath.screenRight(.landscapeRight)

@Test func gyroTurningLeftAimsLeft() {
    // Turning left = positive rotation about world up, which is device +x when held like this.
    let (x, y) = GyroAimMath.stick(rate: (1.0, 0, 0), up: up, right: right, sensitivity: 1, invertY: false)
    #expect(x < -0.3 && y == 0)
    let (x2, _) = GyroAimMath.stick(rate: (-1.0, 0, 0), up: up, right: right, sensitivity: 1, invertY: false)
    #expect(x2 > 0.3)
}

@Test func gyroIgnoresTremorAndCapsFastTurns() {
    #expect(GyroAimMath.stick(rate: (0.02, 0.02, 0), up: up, right: right, sensitivity: 3, invertY: false) == (0, 0))
    let (x, _) = GyroAimMath.stick(rate: (20, 0, 0), up: up, right: right, sensitivity: 3, invertY: false)
    #expect(x == -1)
}

@Test func gyroInvertFlipsOnlyVertical() {
    let a = GyroAimMath.stick(rate: (0.5, -1.0, 0), up: up, right: right, sensitivity: 1, invertY: false)
    let b = GyroAimMath.stick(rate: (0.5, -1.0, 0), up: up, right: right, sensitivity: 1, invertY: true)
    #expect(a.0 == b.0 && a.1 == -b.1 && a.1 != 0)
}

@Test func gyroMatchesAndroidAxes() {
    // Android's ROTATION_90 / ROTATION_270 screen-right axes.
    #expect(GyroAimMath.screenRight(.landscapeRight) == (0, -1, 0) && GyroAimMath.screenRight(.landscapeLeft) == (0, 1, 0))
}

@Test func engineAddsAimToRightStick() {
    let e = ControllerEngine { _, _ in }
    e.setStick("right", 0.25, 0)
    e.setAim(0.5, -0.5)
    #expect(e.state.rx > 24000 && e.state.rx < 25000)   // 0.75 of full
    #expect(e.state.ry < -16000)
    e.releaseAll()
    #expect(e.state == .neutral)
}

@Test func settingsFromBeforeGyroAimStillDecode() throws {
    let old = #"{"activeProfile":"pad:xbox","hapticsEnabled":true,"hapticStrength":0.7,"sensorHz":100,"calibrationDeg":0,"showStats":true,"linkOverride":"auto"}"#
    let s = try JSONDecoder().decode(AppSettings.self, from: Data(old.utf8))
    #expect(s.activeProfile == "pad:xbox" && !s.gyroAimOn && s.aimSensitivityValue == 1.2 && !s.aimInvert)
}

// MARK: turbo and toggle buttons (same text format as Android's pad_modes)

@Test func buttonModesRoundTripLikeAndroid() {
    let m = ButtonModes.decode("face_s=turbo;bumper_r=toggle;face_n=normal;junk;=turbo;x=bogus")
    #expect(m == ["face_s": .turbo, "bumper_r": .toggle])
    #expect(ButtonModes.encode(m) == "bumper_r=toggle;face_s=turbo")
    var s = AppSettings()
    s.setMode(.turbo, for: "face_s", in: PadPresets.xbox)
    s.setMode(.normal, for: "face_s", in: PadPresets.xbox)
    s.setMode(.toggle, for: "bumper_l", in: PadPresets.xbox)
    #expect(s.padModes?["xbox"] == "bumper_l=toggle")
}

@Test func toggleModeMarksOnlyThatButton() {
    let p = ButtonModes.apply(["face_s": .toggle], to: PadPresets.xbox.profile(for: CGSize(width: 800, height: 360)))
    #expect(p.controls.first { $0.id == "face_s" }?.toggle == true)
    #expect(p.controls.filter(\.toggle).count == 1)
}

@Test func turboPulsesAndEndsReleased() async throws {
    final class Log: @unchecked Sendable { let lock = NSLock(); var v: [UInt32] = []; func add(_ x: UInt32) { lock.withLock { v.append(x) } } }
    let log = Log()
    let t = TurboPulser { _, m in log.add(m) }
    t.start("face_s", NineButton.a.mask)
    try await Task.sleep(nanoseconds: 450_000_000)
    t.stop("face_s")
    try await Task.sleep(nanoseconds: 100_000_000)
    let v = log.lock.withLock { log.v }
    let presses = zip(v, v.dropFirst()).filter { $0.0 == 0 && $0.1 != 0 }.count + (v.first != 0 ? 1 : 0)
    #expect(presses >= 4 && presses <= 7, "about 12 a second for 0.45 s: \(presses)")
    #expect(v.last == 0, "ends released")
}
