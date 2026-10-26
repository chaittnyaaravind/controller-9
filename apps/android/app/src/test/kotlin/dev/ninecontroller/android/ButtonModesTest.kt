package dev.ninecontroller.android

import dev.ninecontroller.android.pad.ButtonModes
import dev.ninecontroller.android.pad.ButtonModes.Mode
import dev.ninecontroller.android.pad.TurboPulser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ButtonModesTest {
    @Test fun roundTripsAndDropsNormal() {
        val m = mapOf("face_south" to Mode.TURBO, "bumper_l" to Mode.TOGGLE, "face_east" to Mode.NORMAL)
        val text = ButtonModes.encode(m)
        assertEquals("bumper_l=toggle;face_south=turbo", text)
        assertEquals(mapOf("face_south" to Mode.TURBO, "bumper_l" to Mode.TOGGLE), ButtonModes.decode(text))
    }

    @Test fun ignoresGarbage() {
        assertEquals(emptyMap<String, Mode>(), ButtonModes.decode(null))
        assertEquals(mapOf("a" to Mode.TURBO), ButtonModes.decode("a=turbo;;=toggle;b=warp;c"))
    }

    @Test fun turboPulsesThenAlwaysEndsReleased() {
        val calls = Collections.synchronizedList(ArrayList<Int>())
        val t = TurboPulser { _, mask -> calls += mask }
        t.start("a", 1)
        Thread.sleep(ButtonModes.TURBO_HALF_PERIOD_MS * 6)
        t.stop("a")
        Thread.sleep(ButtonModes.TURBO_HALF_PERIOD_MS * 2)
        assertTrue("several presses: $calls", calls.count { it == 1 } >= 2)
        assertEquals(0, calls.last())
        val n = calls.size
        Thread.sleep(ButtonModes.TURBO_HALF_PERIOD_MS * 3)
        assertEquals("no pulses after stop", n, calls.size)
    }
}
