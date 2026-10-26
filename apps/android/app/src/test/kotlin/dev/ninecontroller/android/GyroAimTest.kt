package dev.ninecontroller.android

import android.view.Surface
import dev.ninecontroller.android.input.GyroAimMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroAimTest {
    private val up = doubleArrayOf(0.0, 0.0, 9.81)          // phone flat, screen up
    private val right90 = GyroAimMath.viewAxes(Surface.ROTATION_90).first

    @Test fun stillPhoneSendsNothing() {
        assertEquals(0.0 to 0.0, GyroAimMath.stick(doubleArrayOf(0.0, 0.0, 0.0), up, right90, 1.0, false))
    }

    @Test fun tremorBelowDeadzoneIsIgnored() {
        assertEquals(0.0 to 0.0, GyroAimMath.stick(doubleArrayOf(0.02, -0.02, 0.03), up, right90, 1.0, false))
    }

    @Test fun turningLeftAimsLeftAndRightAimsRight() {
        val (lx, _) = GyroAimMath.stick(doubleArrayOf(0.0, 0.0, 1.0), up, right90, 1.0, false)
        val (rx, _) = GyroAimMath.stick(doubleArrayOf(0.0, 0.0, -1.0), up, right90, 1.0, false)
        assertEquals(-(1.0 - GyroAimMath.DEADZONE) / GyroAimMath.FULL_RATE, lx, 1e-9)
        assertTrue(rx > 0)
    }

    @Test fun yawFollowsGravityNotTheScreen() {
        // Same turn about the world vertical, phone held upright instead of flat: same aim.
        val upright = doubleArrayOf(9.81, 0.0, 0.0)
        val (x, _) = GyroAimMath.stick(doubleArrayOf(1.0, 0.0, 0.0), upright, right90, 1.0, false)
        assertEquals(-(1.0 - GyroAimMath.DEADZONE) / GyroAimMath.FULL_RATE, x, 1e-9)
    }

    @Test fun tippingTheFarEdgeUpAimsUpAndInvertFlipsIt() {
        // Landscape (rotation 90): screen-right is device −y.
        val w = doubleArrayOf(0.0, -1.0, 0.0)
        assertTrue(GyroAimMath.stick(w, up, right90, 1.0, false).second > 0)
        assertTrue(GyroAimMath.stick(w, up, right90, 1.0, true).second < 0)
    }

    @Test fun fastTurnsSaturateAndSensitivityScales() {
        assertEquals(1.0, GyroAimMath.stick(doubleArrayOf(0.0, 0.0, -20.0), up, right90, 1.0, false).first, 1e-9)
        val slow = GyroAimMath.stick(doubleArrayOf(0.0, 0.0, -0.5), up, right90, 1.0, false).first
        val fast = GyroAimMath.stick(doubleArrayOf(0.0, 0.0, -0.5), up, right90, 2.0, false).first
        assertEquals(slow * 2, fast, 1e-9)
    }
}
