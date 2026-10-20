package dev.ninecontroller.protocol

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.withSign

/*
 * Relative steering (docs/STEERING.md).
 *
 * Frames. Android rotation-vector sensors give the device orientation as a unit quaternion q that maps
 * device (body) coordinates to world coordinates: v_world = q · v_body · q*. Body axes: x to the right
 * of the screen in its natural (portrait) orientation, y up the screen, z out of the screen towards
 * the player. The display rotation is irrelevant here: everything below is in the phone's own frame,
 * so a calibration screen shown in portrait and a controller shown in landscape measure the same thing.
 *
 * Neutral. The player's own grip, captured as a mean quaternion q0 (any tilt, roll or yaw).
 * Relative rotation: r = q0* · q, expressed in the neutral body frame (if the phone turned by r in its
 * own frame, q = q0 · r). Steering angle = twist of r about the steering axis a (body frame, unit),
 * from the swing–twist decomposition: θ = 2·atan2(r_v · a, r_w) with r taken with r_w ≥ 0.
 * Positive θ = turning right.
 *
 * Axis. Without a measured range, a = (0, 0, −1): turning a wheel rotates the phone about its screen
 * normal, and a clockwise turn (seen by the player) is a negative rotation about +z. A full calibration
 * measures a from the held full-left and full-right poses: a = normalize(ρR − ρL), ρ = rotation vectors.
 */

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm: Double get() = sqrt(this dot this)
    fun normalized(): Vec3? = norm.let { n -> if (n < 1e-9 || n.isNaN()) null else this * (1 / n) }
    fun angleDeg(o: Vec3): Double = Math.toDegrees(acos(((this dot o) / (norm * o.norm)).coerceIn(-1.0, 1.0)))
    fun toList() = listOf(x, y, z)
    companion object { val SCREEN_CLOCKWISE = Vec3(0.0, 0.0, -1.0) }
}

data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    /** Hamilton product: (this · o) applies o first, then this. */
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )
    fun conj() = Quat(w, -x, -y, -z)
    infix fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z
    val vec: Vec3 get() = Vec3(x, y, z)
    fun normalized(): Quat? {
        val n = sqrt(this dot this)
        if (n < 1e-9 || n.isNaN()) return null
        return Quat(w / n, x / n, y / n, z / n)
    }
    /** Same rotation, w ≥ 0 (q and −q are the same rotation). */
    fun canonical() = if (w < 0) Quat(-w, -x, -y, -z) else this
    /** Rotation angle, 0..180°. */
    fun angleDeg(): Double = Math.toDegrees(2 * atan2(vec.norm, abs(w)))
    /** Rotation vector (axis · angle in radians), angle in 0..π. */
    fun rotationVector(): Vec3 {
        val c = canonical(); val s = c.vec.norm
        if (s < 1e-12) return Vec3(0.0, 0.0, 0.0)
        return c.vec * (2 * atan2(s, c.w) / s)
    }
    fun rotate(v: Vec3): Vec3 = (this * Quat(0.0, v.x, v.y, v.z) * conj()).vec
    fun toList() = listOf(w, x, y, z)

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)
        fun axisAngle(axis: Vec3, deg: Double): Quat {
            val a = axis.normalized() ?: return IDENTITY
            val h = Math.toRadians(deg) / 2
            return Quat(cos(h), a.x * sin(h), a.y * sin(h), a.z * sin(h))
        }
        /** Android TYPE_GAME_ROTATION_VECTOR / TYPE_ROTATION_VECTOR values: x, y, z[, w]. */
        fun fromRotationVector(v: FloatArray): Quat? {
            if (v.size < 3) return null
            val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
            val w = if (v.size >= 4) v[3].toDouble() else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
            return Quat(w, x, y, z).normalized()
        }
        fun fromList(l: List<Double>): Quat? = if (l.size == 4 && l.all { it.isFinite() }) Quat(l[0], l[1], l[2], l[3]).normalized() else null
    }
}

/** One motion sample: a full orientation (rotation-vector sensors) or gravity only (fallback sensors). */
sealed interface Pose {
    data class Orientation(val q: Quat) : Pose
    /** Gravity direction in body coordinates (pointing down). Rotation about the vertical is unobservable. */
    data class Gravity(val g: Vec3) : Pose
}

object SteeringMath {
    /** Twist of relative rotation [r] about unit [axis], degrees in (−180, 180]. */
    fun twistDeg(r: Quat, axis: Vec3): Double {
        val c = r.canonical()
        return MotionMath.wrapDeg(Math.toDegrees(2 * atan2(c.vec dot axis, c.w)))
    }

    /** Rotation from the neutral grip to [q], in the neutral body frame. */
    fun relative(neutral: Quat, q: Quat): Quat = neutral.conj() * q

    /**
     * Gravity-only steering angle about [axis]: when the phone turns by θ about a body axis, body-frame
     * gravity turns by −θ about it. Null when gravity is (nearly) along the axis — the turn is then
     * physically invisible to an accelerometer.
     */
    fun gravityTwistDeg(g0: Vec3, g: Vec3, axis: Vec3): Double? {
        val p0 = g0 - axis * (g0 dot axis); val p = g - axis * (g dot axis)
        if (p0.norm < 0.25 * g0.norm || p.norm < 0.25 * g.norm) return null
        return -Math.toDegrees(atan2(axis dot (p0 cross p), p0 dot p))
    }

    /** Sign-aligned mean of quaternions (exact enough for the small spreads a held phone has). */
    fun mean(qs: List<Quat>): Quat? {
        if (qs.isEmpty()) return null
        val ref = qs.first()
        var w = 0.0; var x = 0.0; var y = 0.0; var z = 0.0
        for (q in qs) { val s = if ((q dot ref) < 0) -1.0 else 1.0; w += s * q.w; x += s * q.x; y += s * q.y; z += s * q.z }
        return Quat(w, x, y, z).normalized()
    }
}

/** Result of holding the phone still for a short window. */
data class HeldPose(val pose: Pose, val spreadDeg: Double, val samples: Int)

/**
 * Collects a short window of samples while the player holds still and estimates the pose robustly:
 * mean, then samples further than 3σ (at least 1°) from it are dropped and the mean recomputed. The
 * spread is the RMS angular distance of the kept samples from the mean.
 */
class PoseCapture(private val minSamples: Int = 20) {
    private val qs = ArrayList<Quat>()
    private val gs = ArrayList<Vec3>()
    val count @Synchronized get() = qs.size + gs.size
    @Synchronized fun add(p: Pose) { when (p) { is Pose.Orientation -> qs += p.q; is Pose.Gravity -> gs += p.g } }

    @Synchronized fun finish(): HeldPose? = when {
        qs.size >= minSamples -> finishQ()
        gs.size >= minSamples -> finishG()
        else -> null
    }

    private fun finishQ(): HeldPose? {
        var m = SteeringMath.mean(qs) ?: return null
        fun dev(q: Quat) = (m.conj() * q).angleDeg()
        val rms = sqrt(qs.sumOf { dev(it).pow(2) } / qs.size)
        val kept = qs.filter { dev(it) <= maxOf(3 * rms, 1.0) }
        m = SteeringMath.mean(kept) ?: return null
        val spread = sqrt(kept.sumOf { dev(it).pow(2) } / kept.size)
        return HeldPose(Pose.Orientation(m), spread, kept.size)
    }

    private fun finishG(): HeldPose? {
        fun meanOf(l: List<Vec3>) = l.fold(Vec3(0.0, 0.0, 0.0)) { a, b -> a + (b.normalized() ?: b) }.normalized()
        var m = meanOf(gs) ?: return null
        fun dev(g: Vec3) = m.angleDeg(g)
        val rms = sqrt(gs.sumOf { dev(it).pow(2) } / gs.size)
        val kept = gs.filter { dev(it) <= maxOf(3 * rms, 1.0) }
        m = meanOf(kept) ?: return null
        val spread = sqrt(kept.sumOf { dev(it).pow(2) } / kept.size)
        return HeldPose(Pose.Gravity(m), spread, kept.size)
    }

    companion object {
        /** Neutral must be held this still (RMS). */
        const val NEUTRAL_MAX_SPREAD = 1.5
        const val EXTREME_MAX_SPREAD = 3.0
    }
}

/**
 * Steering calibration, schema version 2. Every field is measured on this phone; [axisMeasured] says
 * whether [axis] came from the player's own left/right turns or is the default screen-normal axis.
 */
@Serializable
data class SteeringCalibrationV2(
    val v: Int = VERSION,
    /** "orientation" (rotation-vector neutral in [q]) or "gravity" (gravity-only neutral in [g]). */
    val kind: String,
    val q: List<Double>? = null,
    val g: List<Double>? = null,
    val axis: List<Double> = Vec3.SCREEN_CLOCKWISE.toList(),
    val axisMeasured: Boolean = false,
    /** Comfortable full turn each way, degrees (positive), or null until measured. */
    val leftDeg: Double? = null,
    val rightDeg: Double? = null,
    /** RMS hand wobble while holding neutral, degrees. */
    val noiseDeg: Double,
    val measuredAt: Long,
    val source: String = "",
) {
    val neutral: Pose? get() = when (kind) {
        "orientation" -> q?.let(Quat::fromList)?.let { Pose.Orientation(it) }
        "gravity" -> g?.takeIf { it.size == 3 && it.all(Double::isFinite) }?.let { Vec3(it[0], it[1], it[2]).normalized() }?.let { Pose.Gravity(it) }
        else -> null
    }
    val axisVec: Vec3? get() = axis.takeIf { it.size == 3 && it.all(Double::isFinite) }?.let { Vec3(it[0], it[1], it[2]).normalized() }
    val hasRange: Boolean get() = leftDeg != null && rightDeg != null
    /** Dead zone that hides 3σ of the measured wobble, as a fraction of the smaller range. */
    val suggestedDeadzone: Double? get() = if (leftDeg != null && rightDeg != null) (3 * noiseDeg / minOf(leftDeg, rightDeg)).coerceIn(0.0, 0.1) else null

    /** Null when the record is from another schema version or fails any bound — callers treat that as "not calibrated". */
    fun validated(): SteeringCalibrationV2? {
        if (v != VERSION || neutral == null || axisVec == null || !noiseDeg.isFinite() || noiseDeg < 0) return null
        for (r in listOfNotNull(leftDeg, rightDeg)) if (!r.isFinite() || r < MIN_RANGE_DEG || r > 180.0) return null
        if ((leftDeg == null) != (rightDeg == null)) return null
        return this
    }

    /** Every field is written (the version above all), unlike control messages. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 2
        /** A turn smaller than this is not a usable full-lock measurement. */
        const val MIN_RANGE_DEG = 10.0
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        fun decode(s: String?): SteeringCalibrationV2? = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull()?.validated() }

        /** Neutral only: default axis, symmetric range from the layout's lock setting. */
        fun neutralOnly(held: HeldPose, at: Long, source: String): SteeringCalibrationV2 = when (val p = held.pose) {
            is Pose.Orientation -> SteeringCalibrationV2(kind = "orientation", q = p.q.toList(), noiseDeg = held.spreadDeg, measuredAt = at, source = source)
            is Pose.Gravity -> SteeringCalibrationV2(kind = "gravity", g = p.g.toList(), noiseDeg = held.spreadDeg, measuredAt = at, source = source)
        }
    }
}

/** Outcome of measuring the full left and right turns against a neutral grip. */
sealed interface RangeResult {
    data class Ok(val cal: SteeringCalibrationV2, val axisToScreenNormalDeg: Double) : RangeResult
    data class Retry(val reason: String) : RangeResult
}

object SteeringCalibrator {
    /** Turns about axes more than this apart are not one steering motion. */
    const val MAX_AXIS_DISAGREEMENT_DEG = 40.0

    /**
     * Measure the steering axis and both ranges from held [left] and [right] poses (orientation sensors),
     * or, with gravity-only sensors, both ranges about the default axis.
     */
    fun range(neutral: SteeringCalibrationV2, left: HeldPose, right: HeldPose): RangeResult {
        if (left.spreadDeg > PoseCapture.EXTREME_MAX_SPREAD || right.spreadDeg > PoseCapture.EXTREME_MAX_SPREAD)
            return RangeResult.Retry("The phone moved while holding a full turn (±${"%.1f".format(maxOf(left.spreadDeg, right.spreadDeg))}°). Hold it still at full turn.")
        val n = neutral.neutral ?: return RangeResult.Retry("Measure neutral first.")
        val lp = left.pose; val rp = right.pose
        return when {
            n is Pose.Orientation && lp is Pose.Orientation && rp is Pose.Orientation -> {
                val rl = SteeringMath.relative(n.q, lp.q).rotationVector()
                val rr = SteeringMath.relative(n.q, rp.q).rotationVector()
                if (Math.toDegrees(rl.norm) < SteeringCalibrationV2.MIN_RANGE_DEG || Math.toDegrees(rr.norm) < SteeringCalibrationV2.MIN_RANGE_DEG)
                    return RangeResult.Retry("That was only ${"%.0f".format(Math.toDegrees(minOf(rl.norm, rr.norm)))}° from neutral. Turn further, then hold.")
                val apart = rr.angleDeg(-rl)
                if (apart > MAX_AXIS_DISAGREEMENT_DEG)
                    return RangeResult.Retry("Left and right weren’t turned around the same axis (${"%.0f".format(apart)}° apart). Turn like a steering wheel, keeping the phone’s tilt.")
                val axis = (rr - rl).normalized() ?: return RangeResult.Retry("Couldn’t work out the steering axis. Try again.")
                val rightDeg = SteeringMath.twistDeg(SteeringMath.relative(n.q, rp.q), axis)
                val leftDeg = -SteeringMath.twistDeg(SteeringMath.relative(n.q, lp.q), axis)
                if (rightDeg < SteeringCalibrationV2.MIN_RANGE_DEG || leftDeg < SteeringCalibrationV2.MIN_RANGE_DEG)
                    return RangeResult.Retry("The turns were too small (${"%.0f".format(leftDeg)}° left, ${"%.0f".format(rightDeg)}° right). Turn further, then hold.")
                RangeResult.Ok(neutral.copy(axis = axis.toList(), axisMeasured = true, leftDeg = leftDeg, rightDeg = rightDeg), axis.angleDeg(Vec3.SCREEN_CLOCKWISE))
            }
            n is Pose.Gravity && lp is Pose.Gravity && rp is Pose.Gravity -> {
                val a = Vec3.SCREEN_CLOCKWISE
                val rd = SteeringMath.gravityTwistDeg(n.g, rp.g, a); val ld = SteeringMath.gravityTwistDeg(n.g, lp.g, a)?.let { -it }
                if (rd == null || ld == null) return RangeResult.Retry("This phone only has an accelerometer, which can’t see a turn while the screen faces up. Hold the phone more upright.")
                if (rd < SteeringCalibrationV2.MIN_RANGE_DEG || ld < SteeringCalibrationV2.MIN_RANGE_DEG)
                    return RangeResult.Retry("The turns were too small or reversed (${"%.0f".format(ld)}° left, ${"%.0f".format(rd)}° right). Turn further, then hold.")
                RangeResult.Ok(neutral.copy(leftDeg = ld, rightDeg = rd), 0.0)
            }
            else -> RangeResult.Retry("The motion sensor changed during calibration. Start over.")
        }
    }

    /**
     * Recenter: a new neutral, keeping the measured axis and ranges. The axis lives in the phone's own
     * frame and the ranges are angles relative to neutral, so they stay valid for any new neutral as long
     * as the player still turns the same way. Returns the new calibration and whether the grip moved so far
     * (more than 30°) that the left/right range should be re-measured.
     */
    fun recenter(old: SteeringCalibrationV2?, held: HeldPose, at: Long, source: String): Pair<SteeringCalibrationV2, Boolean> {
        val fresh = SteeringCalibrationV2.neutralOnly(held, at, source)
        if (old == null || old.kind != fresh.kind) return fresh to (old?.hasRange == true)
        val a = old.neutral; val b = held.pose
        val moved = when {
            a is Pose.Orientation && b is Pose.Orientation -> (a.q.conj() * b.q).angleDeg()
            a is Pose.Gravity && b is Pose.Gravity -> a.g.angleDeg(b.g)
            else -> 0.0
        }
        return old.copy(q = fresh.q, g = fresh.g, noiseDeg = held.spreadDeg, measuredAt = at, source = source) to (old.hasRange && moved > 30.0)
    }
}

/**
 * Turns poses into a steering value in [−1, 1] relative to a calibration. Thread-confined (sensor thread).
 *
 * Output: angle → (angle / measured range on that side) · sensitivity → invert → clamp → dead zone →
 * curve. Without measured ranges the layout's lock angle is used on both sides. A One Euro filter on
 * the angle removes hand tremor with little lag during fast turns. Recentering never steps the output:
 * the change between the old and the new reference fades out over [BLEND_S].
 */
class RelativeSteering(private var cal: SteeringCalibrationV2?, private val motion: MotionSettings) {
    private val filter = OneEuro(motion.minCutoff, motion.beta)
    private var unwrapped: Double? = null
    private var lastOut = 0.0
    private var blendFrom = 0.0
    private var blendStart = Double.NaN
    /** Last measured (filtered) angle from neutral, degrees; NaN when the pose can't be measured. */
    @Volatile var angleDeg = Double.NaN; private set
    val calibration get() = cal

    /** Raw angle of [p] from neutral about the steering axis, or null (no calibration / unusable pose). */
    fun measure(p: Pose): Double? {
        val c = cal ?: return null
        val axis = c.axisVec ?: return null
        val n = c.neutral
        return when {
            n is Pose.Orientation && p is Pose.Orientation -> SteeringMath.twistDeg(SteeringMath.relative(n.q, p.q), axis)
            n is Pose.Gravity && p is Pose.Gravity -> SteeringMath.gravityTwistDeg(n.g, p.g, axis)
            else -> null
        }
    }

    /** Normalized steering for a (filtered) angle. */
    fun normalize(deg: Double): Double {
        val c = cal
        val range = if (deg >= 0) c?.rightDeg ?: motion.lockDeg else c?.leftDeg ?: motion.lockDeg
        var x = deg / range * motion.sensitivity
        if (motion.invert) x = -x
        x = x.coerceIn(-1.0, 1.0)
        val a = abs(x)
        if (a <= motion.deadzone) return 0.0
        return ((a - motion.deadzone) / (1.0 - motion.deadzone)).pow(motion.curve).withSign(x)
    }

    /** Steering in [−1, 1] for pose [p] at time [t] (seconds, monotonic). Neutral (0) when not measurable. */
    fun update(p: Pose, t: Double): Double {
        val raw = measure(p)
        val target = if (raw == null) {
            unwrapped = null; filter.reset(); angleDeg = Double.NaN; 0.0
        } else {
            // Unwrap across ±180° so a turn past the end of the range stays saturated instead of flipping.
            val u = unwrapped?.let { prev -> (prev + MotionMath.wrapDeg(raw - prev)).let { if (abs(it) > 270) raw else it } } ?: raw
            unwrapped = u
            val f = filter.filter(u, t)
            angleDeg = f
            normalize(f)
        }
        val out = if (blendStart.isNaN()) target else {
            val k = ((t - blendStart) / BLEND_S).coerceIn(0.0, 1.0)
            if (k >= 1.0) blendStart = Double.NaN
            target + (blendFrom - target) * (1 - k)
        }
        lastOut = out.coerceIn(-1.0, 1.0)
        return lastOut
    }

    /** Switch to a new calibration (recenter) at time [t] without a step in the output. */
    fun recalibrate(c: SteeringCalibrationV2?, t: Double) {
        blendFrom = lastOut; blendStart = t
        cal = c; unwrapped = null; filter.reset()
    }

    companion object { const val BLEND_S = 0.25 }
}
