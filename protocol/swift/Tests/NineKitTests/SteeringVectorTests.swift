import Foundation
import Testing
@testable import NineKit

/// Reproduces protocol/vectors/steering.json from the Rust reference (protocol/rust/src/steering.rs).
struct SteeringVectorTests {
    private let v: [String: Any]
    private let tol: Double

    init() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../vectors/steering.json").standardized
        v = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        tol = v["tolerance"] as! Double
    }
    private func quat(_ a: Any?) -> Quat { let d = a as! [Double]; return Quat(d[0], d[1], d[2], d[3]) }
    private func vec(_ a: Any?) -> Vec3 { let d = a as! [Double]; return Vec3(d[0], d[1], d[2]) }
    private func cal(_ o: Any?) throws -> SteeringCalibrationV2 {
        let s = String(data: try JSONSerialization.data(withJSONObject: o!), encoding: .utf8)
        return try #require(SteeringCalibrationV2.decode(s))
    }

    @Test func twistAndGravity() {
        for c in v["twist"] as! [[String: Any]] {
            #expect(abs(SteeringMath.twistDeg(quat(c["r"]), vec(c["axis"])) - (c["out_deg"] as! Double)) <= tol)
        }
        for c in v["gravity"] as! [[String: Any]] {
            let got = SteeringMath.gravityTwistDeg(vec(c["g0"]), vec(c["g"]), vec(c["axis"]))
            if let want = c["out_deg"] as? Double { #expect(abs(got! - want) <= tol) } else { #expect(got == nil) }
        }
    }

    @Test func rangeCalibration() throws {
        let neutral = try cal(v["neutral"])
        for c in v["range"] as! [[String: Any]] {
            func held(_ o: Any?) -> HeldPose { let d = o as! [String: Any]; return HeldPose(.orientation(quat(d["q"])), spreadDeg: d["spread"] as! Double, samples: 50) }
            let e = c["expect"] as! [String: Any]
            let r = SteeringCalibrator.range(neutral, left: held(c["left"]), right: held(c["right"]))
            guard e["ok"] as! Bool else { if case .retry = r {} else { Issue.record("expected retry") }; continue }
            guard case let .ok(got, tilt) = r else { Issue.record("expected ok, got \(r)"); continue }
            #expect(abs(got.leftDeg! - (e["leftDeg"] as! Double)) <= tol)
            #expect(abs(got.rightDeg! - (e["rightDeg"] as! Double)) <= tol)
            for (w, g) in zip(e["axis"] as! [Double], got.axis) { #expect(abs(w - g) <= tol) }
            #expect(abs(tilt - (e["axisToScreenNormalDeg"] as! Double)) <= tol)
        }
    }

    @Test func neutralCapture() throws {
        let c = v["capture"] as! [String: Any]
        var p = PoseCapture(minSamples: c["minSamples"] as! Int)
        for s in c["samples"] as! [[Double]] { p.add(.orientation(Quat(s[0], s[1], s[2], s[3]))) }
        let h = try #require(p.finish())
        let e = c["expect"] as! [String: Any]
        guard case let .orientation(q) = h.pose else { Issue.record("not an orientation"); return }
        #expect(abs(abs(q.dot(quat(e["q"]))) - 1) <= tol) // q and −q are one rotation
        #expect(abs(h.spreadDeg - (e["spread"] as! Double)) <= tol)
        #expect(h.samples == e["samples"] as! Int)
    }

    @Test func driveWithRecenterAndWrap() throws {
        let d = v["drive"] as! [String: Any]
        let o = d["motion"] as! [String: Any]
        var m = MotionSettings()
        m.enabled = true; m.lockDeg = o["lockDeg"] as! Double; m.deadzone = o["deadzone"] as! Double; m.sensitivity = o["sensitivity"] as! Double
        m.curve = o["curve"] as! Double; m.invert = o["invert"] as! Bool; m.minCutoff = o["minCutoff"] as! Double; m.beta = o["beta"] as! Double
        var s = RelativeSteering(try cal(d["calibration"]), m)
        for (i, c) in (d["samples"] as! [[String: Any]]).enumerated() {
            let t = c["t"] as! Double
            if !(c["recenter"] is NSNull) { s.recalibrate(try cal(c["recenter"]), t: t) }
            let got = s.update(.orientation(quat(c["q"])), t: t)
            #expect(abs(got - (c["out"] as! Double)) <= tol, "drive sample \(i)")
            if let a = c["angle"] as? Double { #expect(abs(s.angleDeg! - a) <= tol, "angle \(i)") }
        }
    }

    @Test func calibrationDecoding() {
        for c in v["decode"] as! [[String: Any]] {
            #expect((SteeringCalibrationV2.decode(c["json"] as? String) != nil) == (c["valid"] as! Bool), "\(c["json"]!)")
        }
    }
}
