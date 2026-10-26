package dev.ninecontroller.android.input

/**
 * Keyboard, mouse and media-key reports that ride next to the gamepad in the same HID service when
 * "Remote keys" is on (docs/DIRECT_BLUETOOTH.md): the computer gets a trackpad, a keyboard and
 * volume / play keys from the phone with no app installed. Pure: no Android APIs.
 *
 * Report IDs: 1 gamepad in, 3 rumble out (XboxHidReport), 2 keyboard, 4 mouse, 5 consumer.
 */
object RemoteHidReport {
    const val KEYBOARD_ID = 2
    const val MOUSE_ID = 4
    const val CONSUMER_ID = 5

    /** Appended to the gamepad's report map: three more top-level collections. */
    val REPORT_MAP_EXTRA: ByteArray = bytes(
        // Keyboard: 8 modifier bits, a reserved byte, six key slots.
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD_ID,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Mouse: 3 buttons, relative X, Y, wheel (−127…127).
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, MOUSE_ID, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // Consumer control: one 16-bit usage (play/pause, volume, …).
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, CONSUMER_ID,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    // Modifier bits.
    const val CTRL = 0x01; const val SHIFT = 0x02; const val ALT = 0x04; const val GUI = 0x08

    // HID keyboard usages used by the remote's named keys.
    const val ENTER = 0x28; const val ESC = 0x29; const val BACKSPACE = 0x2A; const val TAB = 0x2B; const val SPACE = 0x2C
    const val RIGHT = 0x4F; const val LEFT = 0x50; const val DOWN = 0x51; const val UP = 0x52

    // Consumer usages.
    const val PLAY_PAUSE = 0xCD; const val NEXT = 0xB5; const val PREVIOUS = 0xB6
    const val MUTE = 0xE2; const val VOLUME_UP = 0xE9; const val VOLUME_DOWN = 0xEA

    fun keyboard(modifiers: Int, vararg keys: Int): ByteArray {
        val out = ByteArray(8)
        out[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> out[2 + i] = k.toByte() }
        return out
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray =
        byteArrayOf((buttons and 0x07).toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte())

    fun consumer(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte())

    /** (modifiers, usage) for a typed character on a US layout, or null if it has no key. */
    fun keyFor(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
        in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
        in '1'..'9' -> 0 to (0x1E + (c - '1'))
        '0' -> 0 to 0x27
        ' ' -> 0 to SPACE; '\n' -> 0 to ENTER; '\t' -> 0 to TAB
        '-' -> 0 to 0x2D; '_' -> SHIFT to 0x2D; '=' -> 0 to 0x2E; '+' -> SHIFT to 0x2E
        '[' -> 0 to 0x2F; '{' -> SHIFT to 0x2F; ']' -> 0 to 0x30; '}' -> SHIFT to 0x30
        '\\' -> 0 to 0x31; '|' -> SHIFT to 0x31; ';' -> 0 to 0x33; ':' -> SHIFT to 0x33
        '\'' -> 0 to 0x34; '"' -> SHIFT to 0x34; '`' -> 0 to 0x35; '~' -> SHIFT to 0x35
        ',' -> 0 to 0x36; '<' -> SHIFT to 0x36; '.' -> 0 to 0x37; '>' -> SHIFT to 0x37
        '/' -> 0 to 0x38; '?' -> SHIFT to 0x38
        '!' -> SHIFT to 0x1E; '@' -> SHIFT to 0x1F; '#' -> SHIFT to 0x20; '$' -> SHIFT to 0x21; '%' -> SHIFT to 0x22
        '^' -> SHIFT to 0x23; '&' -> SHIFT to 0x24; '*' -> SHIFT to 0x25; '(' -> SHIFT to 0x26; ')' -> SHIFT to 0x27
        else -> null
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
