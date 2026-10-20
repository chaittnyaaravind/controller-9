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
 *
 * [minPressMs]: a press is reported for at least this long, so even a very fast tap is present in
 * several transmitted frames on every link (SPEC §8.5) and can't vanish with one lost datagram. The
 * release is delayed only for taps shorter than this; [schedule] runs the deferred release.
 */
class ControllerEngine(
    private val minPressMs: Long = 0,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Long, () -> Unit) -> Unit = { ms, f -> releaseTimer.schedule(f, ms, java.util.concurrent.TimeUnit.MILLISECONDS) },
    private val onChange: (InputState, Int) -> Unit,
) {
    constructor(onChange: (InputState, Int) -> Unit) : this(0, onChange = onChange)
    private val buttons = HashMap<String, Int>()
    private val pressedAt = HashMap<String, Long>()
    private var generation = 0L
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
    @Synchronized fun setButtons(controlId: String, mask: Int) {
        if (mask != 0) {
            // A new press (or a re-press during a deferred release) starts a new minimum hold.
            if (buttons[controlId] == null || releasePending.remove(controlId)) pressedAt[controlId] = nowMs()
            buttons[controlId] = mask; publish(); return
        }
        val held = pressedAt[controlId]?.let { nowMs() - it } ?: Long.MAX_VALUE
        if (buttons.containsKey(controlId) && held < minPressMs) {
            val g = generation; val at = pressedAt[controlId]
            schedule(minPressMs - held) { synchronized(this) { if (g == generation && pressedAt[controlId] == at && releasePending.remove(controlId)) { buttons.remove(controlId); pressedAt.remove(controlId); publish() } } }
            releasePending += controlId
            return
        }
        buttons.remove(controlId); pressedAt.remove(controlId); releasePending.remove(controlId); publish()
    }
    private val releasePending = HashSet<String>()
    @Synchronized fun setStick(side: String, x: Double, y: Double) { if (side == "right") right = x to y else left = x to y; publish() }
    @Synchronized fun setTrigger(controlId: String, which: String, v: Double) { if (v <= 0.0) triggers.remove(controlId) else triggers[controlId] = which to v; publish() }
    @Synchronized fun setMotion(v: Double) { motion = v; publish() }
    @Synchronized fun setCalibrating(c: Boolean) { calibrating = c; publish() }

    /** Release everything (lifecycle pause, layout change, disconnect). */
    @Synchronized fun releaseAll() {
        generation++; pressedAt.clear(); releasePending.clear() // a lifecycle release is immediate, never deferred
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

private val releaseTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "c9-release").apply { isDaemon = true } }
