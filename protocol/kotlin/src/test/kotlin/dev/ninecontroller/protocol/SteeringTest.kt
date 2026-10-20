package dev.ninecontroller.protocol

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relative steering (Steering.kt). Poses are synthesised from known rotations: a phone at neutral grip
 * q0 that turns by r in its own frame is at q0 · r (SteeringMath.relative inverts exactly that).
 */
class SteeringTest {
    private val x = Vec3(1.0, 0.0, 0.0); private val y = Vec3(0.0, 1.0, 0.0); private val z = Vec3(0.0, 0.0, 1.0)
    private val wheel = Vec3.SCREEN_CLOCKWISE // + = turning right (clockwise seen by the player)

    /** A gamer's grip: landscape (rolled 90°), tilted back, yawed 20° and slightly rolled. */
    private val grip: Quat = Quat.axisAngle(z, 20.0) * Quat.axisAngle(x, 55.0) * Quat.axisAngle(y, 90.0) * Quat.axisAngle(z, 4.0)

    private fun turned(neutral: Quat, deg: Double, axis: Vec3 = wheel) = neutral * Quat.axisAngle(axis, deg)
    private fun held(q: Quat) = HeldPose(Pose.Orientation(q), 0.3, 50)
    private fun neutralCal(q: Quat = grip) = SteeringCalibrationV2.neutralOnly(held(q), 0, "test")
    /** No smoothing, so expected values are exact. */
    private fun motion(lock: Double = 45.0, dz: Double = 0.0) = MotionSettings(enabled = true, lockDeg = lock, deadzone = dz, minCutoff = 1e9, beta = 0.0)

    @Test fun tiltedNeutralIsZeroAfterRecenter() {
        val s = RelativeSteering(neutralCal(), motion())
        assertEquals(0.0, s.update(Pose.Orientation(grip), 0.0), 1e-9)
        assertEquals(0.0, s.angleDeg, 1e-6)
    }

    @Test fun leftIsNegativeRightIsPositiveAndBackIsZero() {
        val s = RelativeSteering(neutralCal(), motion(lock = 45.0))
        val r = s.update(Pose.Orientation(turned(grip, 20.0)), 0.00)
        val l = s.update(Pose.Orientation(turned(grip, -20.0)), 0.01)
        val n = s.update(Pose.Orientation(grip), 0.02)
        assertEquals(20.0 / 45, r, 1e-6)
        assertEquals(-20.0 / 45, l, 1e-6)
        assertEquals(0.0, n, 1e-6)
    }

    @Test fun absoluteWorldAngleIsNotTheOutput() {
        // The old method measured the screen's angle to gravity, so this tilted grip already read as a turn.
        val g = grip.conj().rotate(Vec3(0.0, 0.0, -1.0))
        val legacy = MotionMath.wheelAngleDeg(g.x, g.y, g.z)
        assertTrue(legacy == null || abs(legacy) > 25.0, "legacy absolute angle for the tilted grip: $legacy")
        assertEquals(0.0, RelativeSteering(neutralCal(), motion()).update(Pose.Orientation(grip), 0.0), 1e-9)
    }

    @Test fun calibratedUnequalRangesNormalizePerSide() {
        val cal = (SteeringCalibrator.range(neutralCal(), held(turned(grip, -25.0)), held(turned(grip, 40.0))) as RangeResult.Ok).cal
        assertEquals(25.0, cal.leftDeg!!, 1e-6); assertEquals(40.0, cal.rightDeg!!, 1e-6)
        assertTrue(cal.axisMeasured)
        assertTrue(cal.axisVec!!.angleDeg(wheel) < 1e-4)
        val s = RelativeSteering(cal, motion())
        assertEquals(1.0, s.update(Pose.Orientation(turned(grip, 40.0)), 0.0), 1e-6)
        assertEquals(-1.0, s.update(Pose.Orientation(turned(grip, -25.0)), 0.01), 1e-6)
        assertEquals(0.5, s.update(Pose.Orientation(turned(grip, 20.0)), 0.02), 1e-6)
        assertEquals(-0.5, s.update(Pose.Orientation(turned(grip, -12.5)), 0.03), 1e-6)
    }

    @Test fun measuredAxisFollowsHowThePlayerReallyTurns() {
        // This player's turns are about an axis tipped 25° from the screen normal (wrists, not a wheel).
        val tipped = Quat.axisAngle(x, 25.0).rotate(wheel).normalized()!!
        val cal = (SteeringCalibrator.range(neutralCal(), held(turned(grip, -30.0, tipped)), held(turned(grip, 30.0, tipped))) as RangeResult.Ok).cal
        assertTrue(cal.axisVec!!.angleDeg(tipped) < 1e-4)
        val s = RelativeSteering(cal, motion())
        assertEquals(0.5, s.update(Pose.Orientation(turned(grip, 15.0, tipped)), 0.0), 1e-6)
        // A tilt about the perpendicular axis barely steers.
        val pitch = s.update(Pose.Orientation(grip * Quat.axisAngle(x, 15.0)), 0.01)
        assertTrue(abs(pitch) < 0.2, "pitch leaked into steering: $pitch")
    }

    @Test fun inconsistentOrSmallTurnsAreRefused() {
        assertIs<RangeResult.Retry>(SteeringCalibrator.range(neutralCal(), held(turned(grip, -5.0)), held(turned(grip, 30.0))))
        // Left about the wheel axis, "right" as a pitch: not one steering motion.
        assertIs<RangeResult.Retry>(SteeringCalibrator.range(neutralCal(), held(turned(grip, -30.0)), held(grip * Quat.axisAngle(x, 30.0))))
        assertIs<RangeResult.Retry>(SteeringCalibrator.range(neutralCal(), HeldPose(Pose.Orientation(turned(grip, -30.0)), 6.0, 50), held(turned(grip, 30.0))))
    }

    @Test fun wrappingPastHalfATurnNeverFlipsTheSign() {
        val s = RelativeSteering(neutralCal(), motion(lock = 90.0))
        var prev = 0.0
        var t = 0.0
        for (deg in 0..200 step 2) {
            val v = s.update(Pose.Orientation(turned(grip, deg.toDouble())), t); t += 0.005
            assertTrue(v >= prev - 1e-9, "spike at $deg°: $prev -> $v")
            prev = v
        }
        assertEquals(1.0, prev, 1e-9)
    }

    @Test fun handTremorAtNeutralDoesNotDrift() {
        val s = RelativeSteering(neutralCal(), MotionSettings(enabled = true, lockDeg = 45.0, deadzone = 0.02))
        val rnd = Random(7)
        var t = 0.0; var maxOut = 0.0; var sum = 0.0
        repeat(2000) {
            val wobble = Quat.axisAngle(Vec3(rnd.nextDouble() - .5, rnd.nextDouble() - .5, rnd.nextDouble() - .5), rnd.nextDouble() * 0.6)
            val v = s.update(Pose.Orientation(grip * wobble), t); t += 0.005
            maxOut = maxOf(maxOut, abs(v)); sum += v
        }
        assertEquals(0.0, maxOut, 1e-9, "±0.6° tremor stays inside the 2% dead zone")
        assertEquals(0.0, sum, 1e-9)
    }

    @Test fun estimatorRejectsOutliersAndReportsSpread() {
        val c = PoseCapture()
        val rnd = Random(3)
        repeat(60) { c.add(Pose.Orientation(grip * Quat.axisAngle(Vec3(rnd.nextDouble(), rnd.nextDouble(), rnd.nextDouble()), rnd.nextDouble() * 0.4))) }
        c.add(Pose.Orientation(turned(grip, 25.0))) // one bump
        val h = assertNotNull(c.finish())
        val q = (h.pose as Pose.Orientation).q
        assertTrue((grip.conj() * q).angleDeg() < 0.3, "mean is the grip, not pulled by the bump")
        assertTrue(h.spreadDeg < 0.5)
        assertEquals(60, h.samples)
        assertNull(PoseCapture().apply { repeat(5) { add(Pose.Orientation(grip)) } }.finish(), "too few samples")
    }

    @Test fun recenterNeverStepsTheOutput() {
        val s = RelativeSteering(neutralCal(), motion(lock = 45.0))
        val newGrip = turned(grip, 18.0) // player settles into a new neutral, currently reading +0.4
        var t = 0.0
        repeat(10) { s.update(Pose.Orientation(newGrip), t); t += 0.005 }
        val before = s.update(Pose.Orientation(newGrip), t); t += 0.005
        assertEquals(0.4, before, 1e-6)
        val (cal, remeasure) = SteeringCalibrator.recenter(neutralCal(), held(newGrip), 1, "test")
        assertTrue(!remeasure)
        s.recalibrate(cal, t)
        var prev = before; var maxStep = 0.0
        repeat(80) {
            val v = s.update(Pose.Orientation(newGrip), t); t += 0.005
            maxStep = maxOf(maxStep, abs(v - prev)); prev = v
        }
        assertEquals(0.0, prev, 1e-9, "settles at the new neutral")
        assertTrue(maxStep < 0.03, "no jump injected into the game: largest per-sample step $maxStep")
    }

    @Test fun recenterKeepsTheMeasuredRangeAndFlagsBigGripChanges() {
        val full = (SteeringCalibrator.range(neutralCal(), held(turned(grip, -30.0)), held(turned(grip, 35.0))) as RangeResult.Ok).cal
        val (near, flagNear) = SteeringCalibrator.recenter(full, held(grip * Quat.axisAngle(x, 10.0)), 2, "test")
        assertEquals(30.0, near.leftDeg!!, 1e-9); assertEquals(35.0, near.rightDeg!!, 1e-9); assertTrue(!flagNear)
        val (_, flagFar) = SteeringCalibrator.recenter(full, held(grip * Quat.axisAngle(x, 50.0)), 3, "test")
        assertTrue(flagFar, "a 50° grip change asks for the range to be re-measured")
    }

    @Test fun invalidOrOutdatedCalibrationIsRefusedAndSteeringStaysNeutral() {
        val good = neutralCal().encode()
        assertTrue(good.contains("\"v\":2"), good)
        assertNotNull(SteeringCalibrationV2.decode(good))
        assertNull(SteeringCalibrationV2.decode(good.replace("\"v\":2", "\"v\":1")))
        assertNull(SteeringCalibrationV2.decode("{\"v\":2,\"kind\":\"orientation\",\"q\":[0,0,0,0],\"noiseDeg\":0.1,\"measuredAt\":1}"))
        assertNull(SteeringCalibrationV2.decode("{\"v\":2,\"kind\":\"orientation\",\"q\":[1,0,0,0],\"leftDeg\":3,\"rightDeg\":30,\"noiseDeg\":0.1,\"measuredAt\":1}"))
        assertNull(SteeringCalibrationV2.decode("not json"))
        assertNull(SteeringCalibrationV2.decode(null))
        // No calibration, or a pose from another sensor kind: neutral, never a guess.
        assertEquals(0.0, RelativeSteering(null, motion()).update(Pose.Orientation(turned(grip, 30.0)), 0.0))
        assertEquals(0.0, RelativeSteering(neutralCal(), motion()).update(Pose.Gravity(Vec3(0.0, -1.0, 0.0)), 0.0))
    }

    @Test fun gravityFallbackMeasuresScreenNormalTurns() {
        // Screen normal 30° from straight up: gravity keeps a component in the screen plane.
        val q0 = Quat.axisAngle(x, 30.0) * Quat.axisAngle(z, 90.0)
        fun g(q: Quat) = Pose.Gravity(q.conj().rotate(Vec3(0.0, 0.0, -1.0)))
        val cal = SteeringCalibrationV2.neutralOnly(HeldPose(g(q0), 0.2, 50), 0, "gravity")
        val s = RelativeSteering(cal, motion(lock = 45.0))
        assertEquals(0.5, s.update(g(turned(q0, 22.5)), 0.0), 1e-6)
        assertEquals(-0.5, s.update(g(turned(q0, -22.5)), 0.01), 1e-6)
        // Screen facing straight up: a wheel turn is invisible to gravity, so steering is neutral.
        val flat = Pose.Gravity(Vec3(0.0, 0.0, -1.0))
        val flatCal = SteeringCalibrationV2.neutralOnly(HeldPose(flat, 0.2, 50), 0, "gravity")
        assertEquals(0.0, RelativeSteering(flatCal, motion()).update(flat, 0.0))
    }

    @Test fun sameGripSameAngleWhateverTheScreenShows() {
        // No display-rotation input exists: a portrait calibration screen and a landscape controller
        // measure the same physical turn identically.
        val s = RelativeSteering(neutralCal(), motion())
        assertEquals(12.0, s.measure(Pose.Orientation(turned(grip, 12.0)))!!, 1e-6)
        assertEquals(-7.0, s.measure(Pose.Orientation(turned(grip, -7.0)))!!, 1e-6)
    }
}
