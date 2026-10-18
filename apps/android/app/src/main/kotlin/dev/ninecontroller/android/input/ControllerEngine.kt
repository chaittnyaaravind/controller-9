package dev.ninecontroller.android.input

import dev.ninecontroller.protocol.FLAG_CALIBRATING
import dev.ninecontroller.protocol.FLAG_MOTION
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.axisFromUnit
import dev.ninecontroller.protocol.triggerFromUnit

/**
 * Merges every touch control and motion steering into one protocol InputState.
 * Thread-safe: touch (main thread) and motion (sensor thread) both write here.
 */
class ControllerEngine(private val onChange: (InputState, Int) -> Unit) {
    private val buttons = HashMap<String, Int>()
    private val triggers = HashMap<String, Pair<String, Double>>()
    private var left = 0.0 to 0.0
    private var right = 0.0 to 0.0
    private var motion = 0.0
    private var motionAxis = "lx"
    private var motionOn = false
    private var calibrating = false
    @Volatile var state = InputState.NEUTRAL; private set
    @Volatile var flags = 0; private set

    @Synchronized fun configure(m: MotionSettings) { motionAxis = m.axis; motionOn = m.enabled; if (!m.enabled) motion = 0.0; publish() }
    @Synchronized fun setButtons(controlId: String, mask: Int) { if (mask == 0) buttons.remove(controlId) else buttons[controlId] = mask; publish() }
    @Synchronized fun setStick(side: String, x: Double, y: Double) { if (side == "right") right = x to y else left = x to y; publish() }
    @Synchronized fun setTrigger(controlId: String, which: String, v: Double) { if (v <= 0.0) triggers.remove(controlId) else triggers[controlId] = which to v; publish() }
    @Synchronized fun setMotion(v: Double) { motion = v; publish() }
    @Synchronized fun setCalibrating(c: Boolean) { calibrating = c; publish() }

    /** Release everything (lifecycle pause, layout change, disconnect). */
    @Synchronized fun releaseAll() {
        buttons.clear(); triggers.clear(); left = 0.0 to 0.0; right = 0.0 to 0.0; motion = 0.0
        publish()
    }

    private fun publish() {
        var lx = left.first; val ly = left.second; var rx = right.first; val ry = right.second
        if (motionOn) { if (motionAxis == "rx") rx = (rx + motion) else lx = (lx + motion) }
        val s = InputState(
            buttons = buttons.values.fold(0) { a, b -> a or b },
            lx = axisFromUnit(lx), ly = axisFromUnit(ly), rx = axisFromUnit(rx), ry = axisFromUnit(ry),
            lt = triggerFromUnit(triggers.values.filter { it.first == "lt" }.maxOfOrNull { it.second } ?: 0.0),
            rt = triggerFromUnit(triggers.values.filter { it.first == "rt" }.maxOfOrNull { it.second } ?: 0.0),
        )
        val f = (if (motionOn) FLAG_MOTION else 0) or (if (calibrating) FLAG_CALIBRATING else 0)
        if (s != state || f != flags) { state = s; flags = f; onChange(s, f) }
    }
}
