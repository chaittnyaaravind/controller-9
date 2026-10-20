import Foundation
import NineKit
import Testing
@testable import NineAppCore

final class Events: @unchecked Sendable { var v: [SteeringSession.Event] = [] }

private func motion() -> MotionSettings { var m = MotionSettings(); m.enabled = true; m.lockDeg = 30; m.deadzone = 0; m.curve = 1; return m }
/// A grip tilted 35° back plus `turn` degrees about the screen normal (positive = right).
private func pose(_ turn: Double, tilt: Double = 35) -> Quat {
    Quat.axisAngle(Vec3(1, 0, 0), tilt) * Quat.axisAngle(.screenClockwise, turn)
}

@Test func tiltedGripBecomesNeutralAutomatically() {
    let ev = Events()
    let s = SteeringSession(calibration: nil, motion: motion(), clock: { 1 }) { ev.v.append($0) }
    var t = 0.0, out = 1.0
    while t < 1.2 { out = s.update(.orientation(pose(0)), t: t); t += 0.01 }
    guard case let .neutralSet(cal, regrip)? = ev.v.first else { Issue.record("no neutral: \(ev.v)"); return }
    #expect(!regrip && cal.kind == "orientation")
    #expect(abs(out) < 1e-6) // the tilted grip itself is straight ahead
    while t < 2.0 { out = s.update(.orientation(pose(15)), t: t); t += 0.01 }
    #expect(abs(out - 0.5) < 0.02) // 15° of a 30° lock, relative to the grip
}

@Test func unsteadyCaptureChangesNothingAndRetries() {
    let ev = Events()
    let s = SteeringSession(calibration: nil, motion: motion()) { ev.v.append($0) }
    var t = 0.0
    while t < 1.05 { _ = s.update(.orientation(pose(8 * sin(t * 40))), t: t); t += 0.01 }
    guard case .unsteady? = ev.v.first else { Issue.record("expected unsteady: \(ev.v)"); return }
    #expect(s.calibration == nil && s.capturing)
    while t < 2.2 { _ = s.update(.orientation(pose(0)), t: t); t += 0.01 }
    #expect(s.calibration != nil)
}

@Test func recenterKeepsRangesAndBlends() {
    let ev = Events()
    var cal = SteeringCalibrationV2(neutral: HeldPose(.orientation(pose(0)), spreadDeg: 0.3, samples: 50), at: 0, source: "t")
    cal.leftDeg = 40; cal.rightDeg = 20
    let s = SteeringSession(calibration: cal, motion: motion()) { ev.v.append($0) }
    #expect(!s.capturing)
    var t = 0.0
    while t < 0.5 { _ = s.update(.orientation(pose(10)), t: t); t += 0.01 }
    #expect(abs(s.update(.orientation(pose(10)), t: t) - 0.5) < 0.02) // right range is 20°
    s.recenter()
    while t < 1.6 { _ = s.update(.orientation(pose(10)), t: t); t += 0.01 }
    let first = s.update(.orientation(pose(10)), t: t)
    #expect(first > 0.05) // blending from the old output, not a step to zero
    while t < 2.2 { t += 0.01; _ = s.update(.orientation(pose(10)), t: t) }
    #expect(abs(s.update(.orientation(pose(10)), t: t)) < 0.01)
    #expect(s.calibration?.leftDeg == 40 && s.calibration?.rightDeg == 20)
    guard case .neutralSet(_, regrip: false)? = ev.v.last else { Issue.record("\(ev.v)"); return }
}

@Test func attitudeHandednessIsCheckedAgainstGravity() {
    let bodyToWorld = pose(20, tilt: 50)
    let gBody = bodyToWorld.conj.rotate(Vec3(0, 0, -1)) // what Core Motion reports as gravity in the device frame
    func same(_ p: Pose?) -> Bool { if case let .orientation(q)? = p { return abs(abs(q.dot(bodyToWorld)) - 1) < 1e-9 }; return false }
    #expect(same(MotionPose.make(attitude: bodyToWorld, gravity: gBody)))
    #expect(same(MotionPose.make(attitude: bodyToWorld.conj, gravity: gBody)))
    #expect(MotionPose.make(attitude: Quat.axisAngle(Vec3(0, 1, 0), 90), gravity: Vec3(0, 0, -1)) == .gravity(Vec3(0, 0, -1)))
    #expect(MotionPose.make(attitude: Quat(0, 0, 0, 0), gravity: gBody) == nil)
}

@Test func settingsWithoutSteeringStillDecode() throws {
    let old = #"{"activeProfile":"racing","hapticsEnabled":true,"hapticStrength":0.7,"sensorHz":100,"calibrationDeg":12,"showStats":true}"#
    let s = try JSONDecoder().decode(AppSettings.self, from: Data(old.utf8))
    #expect(s.steering == nil && s.calibrationDeg == 12)
}
