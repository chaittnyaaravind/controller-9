package dev.ninecontroller.android

import dev.ninecontroller.android.input.FeelEffects
import dev.ninecontroller.android.input.Haptics.Cue
import dev.ninecontroller.android.input.RumbleWaveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test fun heavyMotorPulsesAndLightMotorBuzzes() {
        val heavy = RumbleWaveform.render(1.0, 0.0, 80, 0.7)
        assertEquals(8, heavy.amplitudes.size)
        assertArrayEquals("25 Hz pulse: 20 ms on, 20 ms off", intArrayOf(255, 255, 0, 0, 255, 255, 0, 0), heavy.amplitudes)
        val light = RumbleWaveform.render(0.0, 1.0, 40, 0.7)
        assertTrue("steady", light.amplitudes.all { it == light.amplitudes[0] && it in 150..200 })
        val both = RumbleWaveform.render(1.0, 1.0, 40, 0.7)
        assertTrue("never silent while both motors run", both.amplitudes.all { it > 0 })
        assertEquals("strength scales", 127, RumbleWaveform.render(1.0, 0.0, 10, 0.35).amplitudes[0])
        assertArrayEquals("on/off actuators get the pulse pattern", longArrayOf(0, 20, 20, 20, 20), heavy.onOffTimings())
    }

    @Test fun pedalResistancePointAndEndStopFireOncePerPress() {
        val cues = ArrayList<Cue>()
        val f = FeelEffects({ cues += it })
        for (v in listOf(0.1, 0.4, 0.69, 0.72, 0.8, 0.75, 0.9, 1.0, 1.0, 0.95, 0.0)) f.trigger("gas", v)
        assertEquals(listOf(Cue.RESISTANCE, Cue.END_STOP), cues)
        cues.clear(); f.trigger("gas", 1.0) // a thumb-tapped preset trigger goes straight to full
        assertEquals(listOf(Cue.END_STOP), cues)
    }

    @Test fun steeringDetentAtCenterAndBumpAtLockAreRateLimited() {
        var t = 0L
        val cues = ArrayList<Cue>()
        val f = FeelEffects({ cues += it }, { t })
        f.steering(0.4); t += 200; f.steering(1.0) // full lock
        t += 200; f.steering(0.3); t += 200; f.steering(0.0) // back through center
        assertEquals(listOf(Cue.LOCK, Cue.DETENT), cues)
        // Tremor right at the center line can't buzz: one cue per 120 ms at most.
        cues.clear(); t += 200
        repeat(20) { i -> f.steering(if (i % 2 == 0) 0.01 else -0.01); t += 5 }
        assertTrue("tremor cues: ${cues.size}", cues.size <= 1)
    }
}
