package dev.ninecontroller.protocol

import kotlin.math.roundToInt

/** Button bit indices (SPEC §6.3). */
enum class Button(val bit: Int, val id: String) {
    A(0, "a"), B(1, "b"), X(2, "x"), Y(3, "y"), LB(4, "lb"), RB(5, "rb"), VIEW(6, "view"), MENU(7, "menu"),
    LS(8, "ls"), RS(9, "rs"), UP(10, "up"), DOWN(11, "down"), LEFT(12, "left"), RIGHT(13, "right"), GUIDE(14, "guide");

    val mask: Int get() = 1 shl bit

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): Button? = byId[id]
    }
}

const val AXIS_MAX = 32767
const val TRIGGER_MAX = 65535
const val VALID_BUTTONS = 0x7FFF
const val VALID_FLAGS = 0x7
const val FLAG_MOTION = 1
const val FLAG_CALIBRATING = 2
const val FLAG_PAUSED = 4

/** Complete controller state. Y axes positive up; axes -32767..32767; triggers 0..65535 (held as Int). */
data class InputState(
    val buttons: Int = 0,
    val lx: Int = 0, val ly: Int = 0, val rx: Int = 0, val ry: Int = 0,
    val lt: Int = 0, val rt: Int = 0,
) {
    val isNeutral: Boolean get() = this == NEUTRAL
    fun pressed(b: Button) = buttons and b.mask != 0

    companion object { val NEUTRAL = InputState() }
}

fun axisFromUnit(v: Double): Int = (v.coerceIn(-1.0, 1.0) * AXIS_MAX).roundToInt()
fun triggerFromUnit(v: Double): Int = (v.coerceIn(0.0, 1.0) * TRIGGER_MAX).roundToInt()

const val DEFAULT_TCP_PORT = 47901
const val DEFAULT_UDP_PORT = 47900
