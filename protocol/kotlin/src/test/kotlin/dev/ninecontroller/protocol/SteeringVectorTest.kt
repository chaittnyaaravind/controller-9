package dev.ninecontroller.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reproduces protocol/vectors/steering.json from the Rust reference (protocol/rust/src/steering.rs). */
class SteeringVectorTest {
    private val v = Json.parseToJsonElement(File(System.getProperty("vectors.dir"), "steering.json").readText()).jsonObject
    private val tol = v["tolerance"]!!.jsonPrimitive.double
    private fun nums(e: JsonElement) = e.jsonArray.map { it.jsonPrimitive.double }
    private fun quat(e: JsonElement) = nums(e).let { Quat(it[0], it[1], it[2], it[3]) }
    private fun vec(e: JsonElement) = nums(e).let { Vec3(it[0], it[1], it[2]) }
    private fun near(want: Double, got: Double, what: String) = assertTrue(abs(want - got) <= tol, "$what: want $want got $got")
    private fun cal(e: JsonElement) = assertNotNull(SteeringCalibrationV2.decode(e.toString()), "calibration $e")

    @Test fun twistAndGravity() {
        for (c in v["twist"]!!.jsonArray.map { it.jsonObject }) near(c["out_deg"]!!.jsonPrimitive.double, SteeringMath.twistDeg(quat(c["r"]!!), vec(c["axis"]!!)), "twist")
        for (c in v["gravity"]!!.jsonArray.map { it.jsonObject }) {
            val got = SteeringMath.gravityTwistDeg(vec(c["g0"]!!), vec(c["g"]!!), vec(c["axis"]!!))
            val want = c["out_deg"]!!.jsonPrimitive.doubleOrNull
            if (want == null) assertNull(got) else near(want, got!!, "gravity twist")
        }
    }

    @Test fun rangeCalibration() {
        val neutral = cal(v["neutral"]!!)
        for (c in v["range"]!!.jsonArray.map { it.jsonObject }) {
            fun held(o: JsonObject) = HeldPose(Pose.Orientation(quat(o["q"]!!)), o["spread"]!!.jsonPrimitive.double, 50)
            val r = SteeringCalibrator.range(neutral, held(c["left"]!!.jsonObject), held(c["right"]!!.jsonObject))
            val e = c["expect"]!!.jsonObject
            if (!e["ok"]!!.jsonPrimitive.boolean) { assertIs<RangeResult.Retry>(r); continue }
            val ok = assertIs<RangeResult.Ok>(r)
            near(e["leftDeg"]!!.jsonPrimitive.double, ok.cal.leftDeg!!, "left"); near(e["rightDeg"]!!.jsonPrimitive.double, ok.cal.rightDeg!!, "right")
            nums(e["axis"]!!).zip(ok.cal.axis).forEach { (w, g) -> near(w, g, "axis") }
            near(e["axisToScreenNormalDeg"]!!.jsonPrimitive.double, ok.axisToScreenNormalDeg, "axis tilt")
        }
    }

    @Test fun neutralCapture() {
        val c = v["capture"]!!.jsonObject
        val p = PoseCapture(c["minSamples"]!!.jsonPrimitive.int)
        c["samples"]!!.jsonArray.forEach { p.add(Pose.Orientation(quat(it))) }
        val h = assertNotNull(p.finish())
        val e = c["expect"]!!.jsonObject
        val q = (h.pose as Pose.Orientation).q
        assertTrue(abs(abs(q dot quat(e["q"]!!)) - 1) <= tol, "neutral quaternion") // q and −q are one rotation
        near(e["spread"]!!.jsonPrimitive.double, h.spreadDeg, "spread")
        assertEquals(e["samples"]!!.jsonPrimitive.int, h.samples)
    }

    @Test fun driveWithRecenterAndWrap() {
        val d = v["drive"]!!.jsonObject
        val m = d["motion"]!!.jsonObject.let { o ->
            MotionSettings(enabled = true, lockDeg = o["lockDeg"]!!.jsonPrimitive.double, deadzone = o["deadzone"]!!.jsonPrimitive.double,
                sensitivity = o["sensitivity"]!!.jsonPrimitive.double, curve = o["curve"]!!.jsonPrimitive.double, invert = o["invert"]!!.jsonPrimitive.boolean,
                minCutoff = o["minCutoff"]!!.jsonPrimitive.double, beta = o["beta"]!!.jsonPrimitive.double)
        }
        val s = RelativeSteering(cal(d["calibration"]!!), m)
        d["samples"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { i, c ->
            val t = c["t"]!!.jsonPrimitive.double
            if (c["recenter"] !is JsonNull) s.recalibrate(cal(c["recenter"]!!), t)
            near(c["out"]!!.jsonPrimitive.double, s.update(Pose.Orientation(quat(c["q"]!!)), t), "drive sample $i")
            c["angle"]!!.jsonPrimitive.doubleOrNull?.let { near(it, s.angleDeg, "angle $i") }
        }
    }

    @Test fun calibrationDecoding() {
        for (c in v["decode"]!!.jsonArray.map { it.jsonObject }) {
            val json = c["json"]!!.jsonPrimitive.content
            assertEquals(c["valid"]!!.jsonPrimitive.boolean, SteeringCalibrationV2.decode(json) != null, json)
        }
    }
}
