package dev.ninecontroller.android

import dev.ninecontroller.android.input.ControllerEngine
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.FLAG_MOTION
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerEngineTest {
    private val sent = ArrayList<Pair<InputState, Int>>()
    private val e = ControllerEngine { s, f -> sent += s to f }

    @Test fun buttonsFromSeveralControlsAreOred() {
        e.setButtons("a", Button.A.mask)
        e.setButtons("dpad", Button.UP.mask or Button.LEFT.mask)
        assertEquals(Button.A.mask or Button.UP.mask or Button.LEFT.mask, e.state.buttons)
        e.setButtons("a", 0)
        assertEquals(Button.UP.mask or Button.LEFT.mask, e.state.buttons)
    }

    @Test fun triggersTakeTheStrongestSourceAndSticksScale() {
        e.setTrigger("gas", "rt", 0.25); e.setTrigger("rt2", "rt", 1.0); e.setTrigger("brake", "lt", 0.5)
        assertEquals(65535, e.state.rt); assertEquals(32768, e.state.lt)
        e.setStick("left", -1.0, 0.5)
        assertEquals(-32767, e.state.lx); assertEquals(16384, e.state.ly)
    }

    @Test fun motionAddsToConfiguredAxisAndSetsFlag() {
        e.configure(MotionSettings(enabled = true, axis = "rx"))
        e.setMotion(0.5)
        assertEquals(16384, e.state.rx); assertEquals(0, e.state.lx)
        assertEquals(FLAG_MOTION, e.flags)
        e.configure(MotionSettings(enabled = false))
        assertEquals(0, e.state.rx); assertEquals(0, e.flags)
    }

    @Test fun releaseAllNeutralizesAndOnlyChangesAreEmitted() {
        e.setButtons("a", Button.A.mask); e.setTrigger("gas", "rt", 0.7)
        e.releaseAll()
        assertEquals(InputState.NEUTRAL, e.state)
        val n = sent.size
        e.releaseAll()
        assertEquals("no duplicate emission when nothing changed", n, sent.size)
    }
}
