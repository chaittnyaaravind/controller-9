package dev.ninecontroller.android.input

import dev.ninecontroller.protocol.AXIS_MAX
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.TRIGGER_MAX

/**
 * HID reports of an Xbox Wireless Controller (model 1914) over Bluetooth LE, so the computer's own
 * Bluetooth HID stack applies its Xbox mapping (docs/DIRECT_BLUETOOTH.md). Pure: no Android APIs.
 *
 * Input report 1 (16 bytes): lx, ly, rx, ry as u16 0..65535 (centre 32768, Y down), brake (LT) and
 * accelerator (RT) as 10-bit values in u16 slots, hat 1..8 (0 = centred), 15 buttons, share bit.
 * Output report 3 (8 bytes): motor enable bits, magnitudes 0..100 for LT, RT, left, right motors,
 * duration and start delay in 10 ms units, loop count.
 */
object XboxHidReport {
    const val INPUT_ID = 1
    const val OUTPUT_ID = 3
    const val INPUT_LEN = 16
    const val VENDOR_ID = 0x045E
    const val PRODUCT_ID = 0x0B13
    const val VERSION = 0x0509

    /** Xbox BLE button numbers (bit index) for each protocol button; the gaps are unused bits. */
    private val bits = mapOf(
        Button.A to 0, Button.B to 1, Button.X to 3, Button.Y to 4, Button.LB to 6, Button.RB to 7,
        Button.VIEW to 10, Button.MENU to 11, Button.GUIDE to 12, Button.LS to 13, Button.RS to 14,
    )

    val REPORT_MAP: ByteArray = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, // Generic Desktop, Game Pad, Application
        0x85, INPUT_ID,
        0x09, 0x01, 0xA1, 0x00, // left stick: X, Y
        0x09, 0x30, 0x09, 0x31, 0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x95, 0x02, 0x75, 0x10, 0x81, 0x02,
        0xC0,
        0x09, 0x01, 0xA1, 0x00, // right stick: Z, Rz
        0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x95, 0x02, 0x75, 0x10, 0x81, 0x02,
        0xC0,
        0x05, 0x02, 0x09, 0xC5, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x95, 0x01, 0x75, 0x0A, 0x81, 0x02, // LT (brake)
        0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x02, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x95, 0x01, 0x75, 0x0A, 0x81, 0x02, // RT (accelerator)
        0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x66, 0x14, 0x00, // hat
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x15, 0x00, 0x25, 0x00, 0x35, 0x00, 0x45, 0x00, 0x65, 0x00, 0x81, 0x03,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02, // 15 buttons
        0x15, 0x00, 0x25, 0x00, 0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x0C, 0x0A, 0xB2, 0x00, 0x15, 0x00, 0x25, 0x01, 0x95, 0x01, 0x75, 0x01, 0x81, 0x02, // share
        0x15, 0x00, 0x25, 0x00, 0x75, 0x07, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x0F, 0x09, 0x21, 0x85, OUTPUT_ID, 0xA1, 0x02, // PID: rumble output report
        0x09, 0x97, 0x15, 0x00, 0x25, 0x01, 0x75, 0x04, 0x95, 0x01, 0x91, 0x02,
        0x15, 0x00, 0x25, 0x00, 0x75, 0x04, 0x95, 0x01, 0x91, 0x03,
        0x09, 0x70, 0x15, 0x00, 0x25, 0x64, 0x75, 0x08, 0x95, 0x04, 0x91, 0x02,
        0x09, 0x50, 0x66, 0x01, 0x10, 0x55, 0x0E, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0x09, 0xA7, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0x65, 0x00, 0x55, 0x00,
        0x09, 0x7C, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0xC0,
        0xC0,
    )

    /** Input report 1 payload (without the report ID, which HOGP carries in the Report Reference). */
    fun input(s: InputState): ByteArray {
        val out = ByteArray(INPUT_LEN)
        fun u16(i: Int, v: Int) { out[i] = (v and 0xFF).toByte(); out[i + 1] = (v shr 8 and 0xFF).toByte() }
        u16(0, axis(s.lx)); u16(2, axis(-s.ly)); u16(4, axis(s.rx)); u16(6, axis(-s.ry))
        u16(8, trigger(s.lt)); u16(10, trigger(s.rt))
        out[12] = hat(s).toByte()
        var b = 0
        for ((button, bit) in bits) if (s.pressed(button)) b = b or (1 shl bit)
        u16(13, b)
        return out
    }

    /** Protocol axis -32767..32767 (Y already flipped to "down positive") → 0..65535, centre 32768. */
    fun axis(v: Int): Int = if (v == 0) 32768
        else ((v.coerceIn(-AXIS_MAX, AXIS_MAX).toLong() + AXIS_MAX) * 65535 / (2L * AXIS_MAX)).toInt()

    fun trigger(v: Int): Int = (v.coerceIn(0, TRIGGER_MAX).toLong() * 1023 / TRIGGER_MAX).toInt()

    /** 1 = up, clockwise to 8 = up-left; 0 = centred (null state). Opposite directions cancel. */
    fun hat(s: InputState): Int {
        val up = s.pressed(Button.UP) && !s.pressed(Button.DOWN)
        val down = s.pressed(Button.DOWN) && !s.pressed(Button.UP)
        val left = s.pressed(Button.LEFT) && !s.pressed(Button.RIGHT)
        val right = s.pressed(Button.RIGHT) && !s.pressed(Button.LEFT)
        return when {
            up && right -> 2; down && right -> 4; down && left -> 6; up && left -> 8
            up -> 1; right -> 3; down -> 5; left -> 7; else -> 0
        }
    }

    /** Rumble request from output report 3, as [Haptics.rumble] arguments. */
    data class Rumble(val low: Int, val high: Int, val ms: Int)

    /** Accepts the 8-byte payload or the same prefixed with report ID 3; null if malformed. */
    fun rumble(report: ByteArray): Rumble? {
        val r = if (report.size == 9 && report[0].toInt() == OUTPUT_ID) report.copyOfRange(1, 9) else report
        if (r.size < 8) return null
        val enable = r[0].toInt() and 0x0F
        fun mag(i: Int, bit: Int) = if (enable and bit != 0) (r[i].toInt() and 0xFF).coerceAtMost(100) else 0
        // Enable bits: 0 right (light) motor, 1 left (heavy) motor, 2 RT, 3 LT. A phone has one
        // actuator, so the trigger motors fold into the main two.
        val heavy = maxOf(mag(3, 0x2), mag(1, 0x8))
        val light = maxOf(mag(4, 0x1), mag(2, 0x4))
        val ms = (r[5].toInt() and 0xFF) * 10
        return Rumble(heavy * 65535 / 100, light * 65535 / 100, if (heavy == 0 && light == 0) 0 else maxOf(ms, 10))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
