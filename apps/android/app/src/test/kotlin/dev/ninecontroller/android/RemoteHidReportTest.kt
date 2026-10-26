package dev.ninecontroller.android

import dev.ninecontroller.android.input.RemoteHidReport as R
import dev.ninecontroller.android.input.XboxHidReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteHidReportTest {
    @Test fun keyboardReportIsModifierReservedAndSixKeys() {
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), R.keyboard(R.SHIFT, 0x04))
        assertEquals(8, R.keyboard(0).size)
    }

    @Test fun mouseClampsToSignedBytes() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 3), R.mouse(1, 500, -500, 3))
    }

    @Test fun consumerIsLittleEndian() {
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), R.consumer(R.VOLUME_UP))
    }

    @Test fun typedCharactersMapToUsKeys() {
        assertEquals(0 to 0x04, R.keyFor('a'))
        assertEquals(R.SHIFT to 0x04, R.keyFor('A'))
        assertEquals(0 to 0x27, R.keyFor('0'))
        assertEquals(R.SHIFT to 0x1F, R.keyFor('@'))
        assertNull(R.keyFor('é'))
    }

    @Test fun extraCollectionsAreBalancedAndUseTheirOwnReportIds() {
        val map = XboxHidReport.REPORT_MAP + R.REPORT_MAP_EXTRA
        // Every Collection (0xA1) has an End Collection (0xC0), walking items by their size prefix.
        var i = 0; var depth = 0; val ids = mutableListOf<Int>()
        while (i < map.size) {
            val prefix = map[i].toInt() and 0xFF
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            if (prefix and 0xFC == 0xA0) depth++
            if (prefix == 0xC0) depth--
            if (prefix == 0x85) ids += map[i + 1].toInt()
            i += 1 + size
        }
        assertEquals(0, depth)
        assertEquals(listOf(XboxHidReport.INPUT_ID, XboxHidReport.OUTPUT_ID, R.KEYBOARD_ID, R.MOUSE_ID, R.CONSUMER_ID), ids)
    }
}
