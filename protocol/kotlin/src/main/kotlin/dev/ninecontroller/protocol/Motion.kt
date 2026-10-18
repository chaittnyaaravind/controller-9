package dev.ninecontroller.protocol

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.withSign

/** Mirrors protocol/rust/src/motion.rs. Input gravity is in screen coordinates (x right, y up). */
object MotionMath {
    fun wheelAngleDeg(gx: Double, gy: Double, gz: Double): Double? {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        val gxy = hypot(gx, gy)
        if (g < 1e-6 || gxy < 0.35 * g) return null
        return Math.toDegrees(atan2(gx, -gy))
    }

    fun wrapDeg(d: Double): Double {
        var x = (d + 180.0) % 360.0
        if (x < 0) x += 360.0
        return x - 180.0
    }

    fun shape(deltaDeg: Double, m: MotionSettings): Double {
        var x = deltaDeg / m.lockDeg * m.sensitivity
        if (m.invert) x = -x
        x = x.coerceIn(-1.0, 1.0)
        val a = abs(x)
        if (a <= m.deadzone) return 0.0
        return ((a - m.deadzone) / (1.0 - m.deadzone)).pow(m.curve).withSign(x)
    }

    fun radialDeadzone(x: Double, y: Double, dz: Double): Pair<Double, Double> {
        val m = hypot(x, y)
        if (m <= dz || m == 0.0) return 0.0 to 0.0
        val s = minOf((m - dz) / (1.0 - dz), 1.0) / m
        return (x * s).coerceIn(-1.0, 1.0) to (y * s).coerceIn(-1.0, 1.0)
    }
}

/** One Euro filter (Casiez, Roussel, Vogel — CHI 2012). */
class OneEuro(private val minCutoff: Double, private val beta: Double, private val dCutoff: Double = 1.0) {
    private var px = 0.0; private var pdx = 0.0; private var pt = 0.0; private var init = false
    private fun alpha(cutoff: Double, dt: Double) = 1.0 / (1.0 + (1.0 / (2.0 * PI * cutoff)) / dt)
    fun reset() { init = false }
    fun filter(x: Double, t: Double): Double {
        if (!init) { px = x; pdx = 0.0; pt = t; init = true; return x }
        val dt = if (t > pt) t - pt else 1.0 / 120.0
        val dx = (x - px) / dt
        val edx = pdx + alpha(dCutoff, dt) * (dx - pdx)
        val cutoff = minCutoff + beta * abs(edx)
        val y = px + alpha(cutoff, dt) * (x - px)
        px = y; pdx = edx; pt = t
        return y
    }
}

class Steering(val motion: MotionSettings) {
    var centerDeg = 0.0; private set
    private val filter = OneEuro(motion.minCutoff, motion.beta)
    private var lastDelta = 0.0

    fun setCenter(deg: Double) { centerDeg = deg; filter.reset(); lastDelta = 0.0 }

    /** Normalized steering in [-1, 1]. `t` is seconds (monotonic). */
    fun update(gx: Double, gy: Double, gz: Double, t: Double): Double {
        val a = MotionMath.wheelAngleDeg(gx, gy, gz)
        val delta = if (a != null) MotionMath.wrapDeg(a - centerDeg) else lastDelta
        lastDelta = delta
        return MotionMath.shape(filter.filter(delta, t), motion)
    }
}

data class Calibration(val centerDeg: Double, val spreadDeg: Double, val samples: Int) {
    /** The phone was held still enough for a trustworthy center. */
    val steady get() = spreadDeg <= 1.5
}

class Calibrator {
    private var s = 0.0; private var c = 0.0
    private val samples = ArrayList<Double>()
    val count get() = samples.size
    fun add(gx: Double, gy: Double, gz: Double) {
        val a = MotionMath.wheelAngleDeg(gx, gy, gz) ?: return
        s += sin(Math.toRadians(a)); c += cos(Math.toRadians(a)); samples += a
    }
    fun finish(): Calibration? {
        if (samples.size < 10) return null
        val center = Math.toDegrees(atan2(s, c))
        val v = samples.sumOf { MotionMath.wrapDeg(it - center).pow(2) } / samples.size
        return Calibration(center, sqrt(v), samples.size)
    }
}
