package dev.ninecontroller.android.input

import dev.ninecontroller.protocol.Button as Btn
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Touch-to-control maths shared by every layout. Mirrored exactly in
 * apps/ios/Core/Sources/NineAppCore/TouchMath.swift — keep constants and tests in step.
 */
object DpadMath {
    /** Thumb must travel this far from the centre (fraction of the radius) before a direction presses… */
    const val PRESS_R = 0.22
    /** …and stays pressed until it comes back inside this (no chatter while resting near the centre). */
    const val RELEASE_R = 0.16
    /** Half-width of a diagonal zone; cardinals get the rest (60° each), so diagonals are deliberate. */
    const val DIAGONAL_HALF_DEG = 15.0
    /** The current direction is kept until the thumb leaves its zone by this much (no flicker on a roll). */
    const val HYSTERESIS_DEG = 6.0

    /** Index 0 = right, counter-clockwise in 45° steps (y up). */
    val MASKS = intArrayOf(
        Btn.RIGHT.mask, Btn.RIGHT.mask or Btn.UP.mask, Btn.UP.mask, Btn.UP.mask or Btn.LEFT.mask,
        Btn.LEFT.mask, Btn.LEFT.mask or Btn.DOWN.mask, Btn.DOWN.mask, Btn.DOWN.mask or Btn.RIGHT.mask,
    )

    private fun halfWidth(k: Int) = if (k % 2 == 0) 45.0 - DIAGONAL_HALF_DEG else DIAGONAL_HALF_DEG
    private fun offDeg(a: Double, k: Int): Double { val d = abs(a - 45.0 * k) % 360; return minOf(d, 360 - d) }

    /**
     * Direction mask for a thumb at ([dx], [dy]) from the pad centre (y up), [radius] = half the pad,
     * given the mask sent last time ([prev], 0 = none).
     */
    fun resolve(dx: Double, dy: Double, radius: Double, prev: Int): Int {
        val r = hypot(dx, dy) / radius.coerceAtLeast(1e-9)
        if (r < (if (prev == 0) PRESS_R else RELEASE_R)) return 0
        val a = Math.toDegrees(atan2(dy, dx)).let { if (it < 0) it + 360 else it }
        val held = MASKS.indexOf(prev)
        if (held >= 0 && offDeg(a, held) <= halfWidth(held) + HYSTERESIS_DEG) return prev
        val k = (0 until 8).first { offDeg(a, it) <= halfWidth(it) }
        return MASKS[k]
    }
}
