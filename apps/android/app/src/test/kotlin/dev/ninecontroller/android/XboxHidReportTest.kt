package dev.ninecontroller.android

import dev.ninecontroller.android.input.XboxHidReport
import dev.ninecontroller.protocol.AXIS_MAX
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.TRIGGER_MAX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XboxHidReportTest {
    private fun u16(r: ByteArray, i: Int) = (r[i].toInt() and 0xFF) or (r[i + 1].toInt() and 0xFF shl 8)
    private fun pressed(vararg b: Button) = InputState(buttons = b.fold(0) { a, x -> a or x.mask })

    /** Walks the descriptor's short items and sums report bits per (report ID, main item kind). */
    private fun reportBits(): Map<Pair<Int, String>, Int> {
        val d = XboxHidReport.REPORT_MAP
        val bits = HashMap<Pair<Int, String>, Int>()
        var size = 0; var count = 0; var id = 0; var depth = 0; var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val len = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            var v = 0L
            for (k in 0 until len) v = v or ((d[i + 1 + k].toLong() and 0xFF) shl (8 * k))
            when (prefix and 0xFC) {
                0x74 -> size = v.toInt(); 0x94 -> count = v.toInt(); 0x84 -> id = v.toInt()
                0xA0 -> depth++; 0xC0 -> depth--
                0x80 -> bits.merge(id to "input", size * count, Int::plus)
                0x90 -> bits.merge(id to "output", size * count, Int::plus)
            }
            i += 1 + len
        }
        assertEquals("collections balanced", 0, depth)
        return bits
    }

    @Test fun descriptorMatchesReportSizes() {
        val bits = reportBits()
        assertEquals(XboxHidReport.INPUT_LEN * 8, bits[XboxHidReport.INPUT_ID to "input"])
        assertEquals(64, bits[XboxHidReport.OUTPUT_ID to "output"])
        assertEquals(setOf(XboxHidReport.INPUT_ID to "input", XboxHidReport.OUTPUT_ID to "output"), bits.keys)
    }

    @Test fun neutralIsCentredAndReleased() {
        val r = XboxHidReport.input(InputState.NEUTRAL)
        assertEquals(16, r.size)
        for (i in 0..3) assertEquals(32768, u16(r, i * 2))
        assertEquals(0, u16(r, 8)); assertEquals(0, u16(r, 10))
        assertEquals("hat centred", 0, r[12].toInt())
        assertEquals(0, u16(r, 13)); assertEquals(0, r[15].toInt())
    }

    @Test fun sticksSpanFullRangeAndYPointsDown() {
        val r = XboxHidReport.input(InputState(lx = AXIS_MAX, ly = AXIS_MAX, rx = -AXIS_MAX, ry = -AXIS_MAX))
        assertEquals(65535, u16(r, 0))
        assertEquals("protocol up = HID minimum", 0, u16(r, 2))
        assertEquals(0, u16(r, 4))
        assertEquals("protocol down = HID maximum", 65535, u16(r, 6))
    }

    @Test fun triggersAreTenBit() {
        val r = XboxHidReport.input(InputState(lt = TRIGGER_MAX, rt = TRIGGER_MAX / 2))
        assertEquals(1023, u16(r, 8))
        assertEquals(511, u16(r, 10))
    }

    @Test fun buttonsUseXboxBluetoothNumbering() {
        fun bits(vararg b: Button) = u16(XboxHidReport.input(pressed(*b)), 13)
        assertEquals(1 shl 0, bits(Button.A)); assertEquals(1 shl 1, bits(Button.B))
        assertEquals(1 shl 3, bits(Button.X)); assertEquals(1 shl 4, bits(Button.Y))
        assertEquals(1 shl 6, bits(Button.LB)); assertEquals(1 shl 7, bits(Button.RB))
        assertEquals(1 shl 10, bits(Button.VIEW)); assertEquals(1 shl 11, bits(Button.MENU))
        assertEquals(1 shl 12, bits(Button.GUIDE)); assertEquals(1 shl 13, bits(Button.LS)); assertEquals(1 shl 14, bits(Button.RS))
        assertEquals("D-pad goes to the hat, not buttons", 0, bits(Button.UP, Button.LEFT))
    }

    @Test fun dpadBecomesHat() {
        fun hat(vararg b: Button) = XboxHidReport.input(pressed(*b))[12].toInt()
        assertEquals(1, hat(Button.UP)); assertEquals(2, hat(Button.UP, Button.RIGHT)); assertEquals(3, hat(Button.RIGHT))
        assertEquals(4, hat(Button.DOWN, Button.RIGHT)); assertEquals(5, hat(Button.DOWN)); assertEquals(6, hat(Button.DOWN, Button.LEFT))
        assertEquals(7, hat(Button.LEFT)); assertEquals(8, hat(Button.UP, Button.LEFT))
        assertEquals("opposites cancel", 0, hat(Button.UP, Button.DOWN))
    }

    @Test fun rumbleOutputReport() {
        // SDL's Xbox Bluetooth rumble: enable all motors, LT 0, RT 0, left 100, right 50, 2.55 s.
        val r = XboxHidReport.rumble(byteArrayOf(0x0F, 0, 0, 100, 50, 0xFF.toByte(), 0, 0))!!
        assertEquals(65535, r.low); assertEquals(32767, r.high); assertEquals(2550, r.ms)
        assertEquals("with report ID prefix", r, XboxHidReport.rumble(byteArrayOf(3, 0x0F, 0, 0, 100, 50, 0xFF.toByte(), 0, 0)))
        assertEquals("disabled motors are ignored", 0, XboxHidReport.rumble(byteArrayOf(0x00, 0, 0, 100, 100, 10, 0, 0))!!.ms)
        assertNull(XboxHidReport.rumble(byteArrayOf(1, 2)))
    }
}
