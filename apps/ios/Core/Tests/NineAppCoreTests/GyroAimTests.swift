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
