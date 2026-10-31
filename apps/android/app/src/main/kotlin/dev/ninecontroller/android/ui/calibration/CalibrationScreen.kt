package dev.ninecontroller.android.ui.calibration

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.input.MotionSensor
import dev.ninecontroller.android.input.SteeringRunner
import dev.ninecontroller.android.ui.Divider
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.Metric
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import dev.ninecontroller.protocol.FLAG_CALIBRATING
import dev.ninecontroller.protocol.HeldPose
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.PoseCapture
import dev.ninecontroller.protocol.RangeResult
import dev.ninecontroller.protocol.SteeringCalibrationV2
import dev.ninecontroller.protocol.SteeringCalibrator
import dev.ninecontroller.protocol.axisFromUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class Step { NEUTRAL, LEFT, RIGHT, DONE }

/** Each hold: ~240 samples at 200 Hz. */
private const val HOLD_MS = 1200L

/**
 * Guided steering calibration relative to the player's own grip (docs/STEERING.md): neutral, full
 * left, full right. Three separate readouts, so animation can never hide bad data: the local sensor
 * measurement (wheel), the normalized output (bar), and — only with "Send to computer" on — the exact
 * axis value in the frames sent, plus how fresh the computer's replies are.
 */
@Composable
fun CalibrationScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    val hid by app.hid.state.collectAsState()
    val profiles by app.profiles.profiles.collectAsState()
    val profile = profiles.find { it.id == s.activeProfile }
    // Steering settings of the layout in use: controller presets share the tilt settings; profiles keep their own.
    val motion = if (s.padPreset != null) s.padMotion() else profile?.motion ?: MotionSettings()

    val runner = remember { SteeringRunner(MotionSensor(context)) }
    var available by remember { mutableStateOf(true) }
    var step by remember { mutableStateOf(Step.NEUTRAL) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var neutral by remember { mutableStateOf<SteeringCalibrationV2?>(null) }
    var left by remember { mutableStateOf<HeldPose?>(null) }
    var result by remember { mutableStateOf<SteeringCalibrationV2?>(null) }
    var axisTilt by remember { mutableStateOf<Double?>(null) }
    var send by remember { mutableStateOf(false) }
    var sentAxis by remember { mutableStateOf<Int?>(null) }
    // Live values, read once per frame (no extra smoothing on top of the steering filter).
    var angle by remember { mutableDoubleStateOf(Double.NaN) }
    var output by remember { mutableDoubleStateOf(0.0) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }

    val connected = (hid as? dev.ninecontroller.android.net.HidGamepad.State.Connected)?.subscribed == true
    val sending = send && connected
    // Read on the sensor thread.
    val flags = remember { intArrayOf(0, 0) } // [0] sending, [1] calibrating
    flags[0] = if (sending) 1 else 0; flags[1] = if (busy) FLAG_CALIBRATING else 0

    // Reads the sensor only, unless "Send to computer" is on; then the steering value goes out on the
    // layout's steering axis, exactly as in game. Leaving the screen (or switching it off) sends neutral.
    DisposableEffect(motion, s.sensorHz) {
        available = runner.start(s.sensorHz, result ?: neutral ?: s.steering, motion) { v ->
            if (flags[0] == 1) {
                val a = axisFromUnit(v)
                app.hid.submit(if (motion.axis == "rx") InputState(rx = a) else InputState(lx = a))
                sentAxis = a
            }
        }
        onDispose { runner.stop(); app.hid.submit(InputState.NEUTRAL) }
    }
    LaunchedEffect(sending) { if (!sending) { app.hid.submit(InputState.NEUTRAL); sentAxis = null } }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { now = it }; angle = runner.angleDeg; output = runner.output } }

    fun hold(then: (HeldPose?) -> Unit) {
        busy = true; message = "Hold still…"
        runner.beginCapture()
        scope.launch { delay(HOLD_MS); val h = runner.endCapture(); busy = false; message = null; then(h) }
    }
    val cal = result ?: neutral ?: s.steering

    Page("Steering", "Calibrate", nav) {
        Text("Hold the phone the way you play — tilted, rolled, however it’s comfortable. Steering is measured from that grip, not from the screen being upright.",
            style = NineType.small, color = Nine.muted)
        if (!available) Text("This phone has no usable motion sensor, so steering can’t be calibrated. Steering stays centered.", color = Nine.bad, style = NineType.small)
        if (s.legacySteering && cal == null) Text("Your earlier calibration used the old method (absolute screen angle) and isn’t used any more. Measure your grip again.", color = Nine.warn, style = NineType.small)

        Section("Live") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Wheel(angle, cal, motion)
                OutputBar(output, motion.deadzone, sentAxis?.let { it / 32767.0 })
                Row(Modifier.fillMaxWidth()) {
                    Metric(if (angle.isNaN()) "—" else "%+.1f°".format(angle), "From your neutral", Modifier.weight(1f))
                    Metric("%+.2f".format(output), "Steering output", Modifier.weight(1f))
                    Metric(when { angle.isNaN() -> "—"; output > 0.0 -> "Right"; output < 0.0 -> "Left"; else -> "Center" }, "Direction", Modifier.weight(1f))
                }
                Text("Sensor: ${runner.source} · ${if (runner.hz > 0) "${fmt(runner.hz, 0)} Hz measured" else "starting"}" +
                    (cal?.let { c -> if (c.hasRange) " · range ${c.leftDeg!!.toInt()}° left / ${c.rightDeg!!.toInt()}° right" else " · range ${motion.lockDeg.toInt()}° each way (not measured)" } ?: " · no neutral yet"),
                    style = NineType.small, color = Nine.dim)
            }
            Divider()
            ListRow("Send to computer while testing", detail = when {
                !connected -> "Not connected — preview only"
                !send -> "Off — the computer sees a centered controller"
                else -> "Sending ${if (motion.axis == "rx") "right" else "left"} stick X = ${sentAxis ?: 0}"
            }, detailColor = if (sending) LocalAccent.current.color else Nine.muted, trailing = {
                Switch(send, { send = it }, enabled = connected, colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current.color, checkedThumbColor = LocalAccent.current.ink))
            })
        }

        Section("Steps") {
            StepRow(1, "Neutral", "Hold your normal playing grip still.", neutral?.let { "Steady to ±${"%.1f".format(it.noiseDeg)}°" }, step == Step.NEUTRAL)
            Divider()
            StepRow(2, "Full left", "Turn left as far as you would in a game, then hold.", left?.let { "Held" }, step == Step.LEFT)
            Divider()
            StepRow(3, "Full right", "Turn right as far as you would, then hold.", result?.let { "${it.leftDeg!!.toInt()}° left · ${it.rightDeg!!.toInt()}° right" }, step == Step.RIGHT)
        }
        message?.let { Text(it, style = NineType.small, color = if (busy) Nine.text else Nine.warn) }

        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                Step.NEUTRAL -> PrimaryButton(if (busy) "Measuring…" else "Measure neutral", {
                    hold { h ->
                        when {
                            h == null -> message = "Couldn’t read the motion sensor. Steering stays centered."
                            h.spreadDeg > PoseCapture.NEUTRAL_MAX_SPREAD -> message = "The phone moved (±${"%.1f".format(h.spreadDeg)}°). Rest your elbows or wrists, hold still, and try again."
                            else -> {
                                val n = SteeringCalibrationV2.neutralOnly(h, System.currentTimeMillis(), runner.source)
                                neutral = n; result = null; left = null
                                runner.recalibrate(n) // the live readout is now relative to this grip
                                step = Step.LEFT
                            }
                        }
                    }
                }, enabled = !busy && available)
                Step.LEFT -> PrimaryButton(if (busy) "Measuring…" else "Hold left and measure", {
                    hold { h -> if (h == null) message = "Couldn’t read the motion sensor." else { left = h; step = Step.RIGHT } }
                }, enabled = !busy)
                Step.RIGHT -> PrimaryButton(if (busy) "Measuring…" else "Hold right and measure", {
                    hold { h ->
                        val n = neutral; val l = left
                        if (h == null || n == null || l == null) { message = "Couldn’t read the motion sensor."; return@hold }
                        when (val r = SteeringCalibrator.range(n, l, h)) {
                            is RangeResult.Retry -> { message = r.reason; left = null; step = Step.LEFT }
                            is RangeResult.Ok -> { result = r.cal; axisTilt = r.axisToScreenNormalDeg; runner.recalibrate(r.cal); step = Step.DONE }
                        }
                    }
                }, enabled = !busy)
                Step.DONE -> Unit
            }
            if (step != Step.NEUTRAL) SecondaryButton("Start over", {
                step = Step.NEUTRAL; neutral = null; left = null; result = null; axisTilt = null; message = null; runner.recalibrate(s.steering)
            }, enabled = !busy)
        }

        val c = result ?: neutral
        if (c != null) {
            Section("Measured", footer = "Only values measured on this phone just now. Nothing is saved until you choose to.") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("±${"%.1f".format(c.noiseDeg)}°", "Hand wobble", Modifier.weight(1f))
                        Metric(c.leftDeg?.let { "${it.toInt()}°" } ?: "—", "Full left", Modifier.weight(1f))
                        Metric(c.rightDeg?.let { "${it.toInt()}°" } ?: "—", "Full right", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Metric(c.suggestedDeadzone?.let { "%.1f%%".format(it * 100) } ?: "—", "Dead zone (3σ)", Modifier.weight(1f))
                        Metric(axisTilt?.let { "${it.toInt()}°" } ?: "—", "Turn axis vs screen", Modifier.weight(1f))
                        Metric(if (c.hasRange && abs(c.leftDeg!! - c.rightDeg!!) >= 5) "Uneven" else if (c.hasRange) "Even" else "—", "Left vs right", Modifier.weight(1f))
                    }
                    if (!c.hasRange) Text("Measure full left and full right to use your own range; until then ${motion.lockDeg.toInt()}° each way is full lock.", style = NineType.small, color = Nine.dim)
                    PrimaryButton(if (c.hasRange) "Save grip and range" else "Save neutral grip", {
                        scope.launch {
                            val dz = c.suggestedDeadzone
                            app.settings.update { st -> if (dz != null && st.padPreset != null) st.copy(steering = c, tiltDeadzone = dz) else st.copy(steering = c) }
                            if (dz != null && profile != null && s.padPreset == null) runCatching { app.profiles.save(profile.copy(motion = profile.motion.copy(deadzone = dz))) }
                            nav.popBackStack()
                        }
                    })
                }
            }
        }
        s.steering?.let { saved ->
            Section("Saved") {
                ListRow(if (saved.hasRange) "${saved.leftDeg!!.toInt()}° left · ${saved.rightDeg!!.toInt()}° right" else "Neutral grip only", detail = listOfNotNull(
                    "wobble ±${fmt(saved.noiseDeg)}°", saved.source.takeIf { it.isNotBlank() },
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(saved.measuredAt)),
                ).joinToString(" · "))
            }
        }
        if (s.padPreset != null && !s.padTilt) Text("Tilt steering is off for controller layouts; turn it on in Settings to steer in game.", style = NineType.small, color = Nine.dim)
    }
}

@Composable
private fun StepRow(n: Int, title: String, how: String, result: String?, current: Boolean) =
    ListRow("$n  ·  $title", detail = result ?: how, detailColor = when { result != null -> LocalAccent.current.color; current -> Nine.text; else -> Nine.dim },
        trailing = { Text(if (result != null) "Done" else if (current) "Now" else "", style = NineType.small, color = if (result != null) LocalAccent.current.color else Nine.muted) })

/**
 * The wheel turns with the measured angle (local sensor, not the transmitted value). The arc behind it
 * is the usable range: center tick, the left and right limits, and the dead zone around center.
 */
@Composable
private fun Wheel(angle: Double, cal: SteeringCalibrationV2?, m: MotionSettings) {
    val accentColor = LocalAccent.current.color
    val leftDeg = cal?.leftDeg ?: m.lockDeg
    val rightDeg = cal?.rightDeg ?: m.lockDeg
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {
        contentDescription = if (angle.isNaN()) "Steering wheel: no measurement" else "Steering wheel at %+.0f degrees, range %.0f left to %.0f right".format(angle, leftDeg, rightDeg)
    }) {
        val r = minOf(size.width, size.height) / 2 - 16.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2 + 6.dp.toPx())
        val ring = r + 9.dp.toPx()
        val tl = Offset(c.x - ring, c.y - ring); val box = Size(2 * ring, 2 * ring)
        // Range arc (0° = top, clockwise = right), dead zone band, limit and center ticks.
        drawArc(Nine.line2, (-90 - leftDeg).toFloat(), (leftDeg + rightDeg).toFloat(), false, tl, box, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        val dz = m.deadzone
        if (dz > 0) drawArc(Nine.faint, (-90 - leftDeg * dz).toFloat(), ((leftDeg + rightDeg) * dz).toFloat(), false, tl, box, style = Stroke(8.dp.toPx()))
        fun tick(deg: Double, color: Color, len: Float) = rotate(deg.toFloat(), c) {
            drawLine(color, Offset(c.x, c.y - ring - 3.dp.toPx()), Offset(c.x, c.y - ring - 3.dp.toPx() - len), 2.dp.toPx(), StrokeCap.Round)
        }
        tick(0.0, accentColor, 10.dp.toPx())
        tick(-leftDeg, Nine.muted, 7.dp.toPx()); tick(rightDeg, Nine.muted, 7.dp.toPx())
        // The wheel itself.
        val measured = !angle.isNaN()
        val ink = if (measured) Nine.text else Nine.dim
        val spoke = ink.copy(alpha = 0.35f)
        rotate(if (measured) angle.toFloat().coerceIn(-180f, 180f) else 0f, c) {
            drawCircle(ink, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(spoke, r * 0.22f, c, style = Stroke(2.dp.toPx()))
            drawLine(spoke, Offset(c.x - r, c.y), Offset(c.x - r * 0.22f, c.y), 2.dp.toPx())
            drawLine(spoke, Offset(c.x + r * 0.22f, c.y), Offset(c.x + r, c.y), 2.dp.toPx())
            drawLine(spoke, Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y + r), 2.dp.toPx())
            drawCircle(if (measured) accentColor else Nine.dim, 5.dp.toPx(), Offset(c.x, c.y - r)) // top marker
        }
    }
}

/** Normalized output −1…+1 (local) with the dead zone shaded; a ring marks the value actually sent. */
@Composable
private fun OutputBar(v: Double, deadzone: Double, sent: Double?) { val accentColor = LocalAccent.current.color; Canvas(Modifier.fillMaxWidth().height(30.dp).semantics {
    contentDescription = "Steering output %+.2f".format(v) + (sent?.let { ", sent to computer %+.2f".format(it) } ?: "")
}) {
    val y = size.height / 2; val w = size.width; val pad = 10.dp.toPx(); val half = w / 2 - pad
    drawLine(Nine.line2, Offset(pad, y), Offset(w - pad, y), 4.dp.toPx(), StrokeCap.Round)
    if (deadzone > 0) drawLine(Nine.faint, Offset(w / 2 - half * deadzone.toFloat(), y), Offset(w / 2 + half * deadzone.toFloat(), y), 10.dp.toPx())
    drawLine(Nine.dim, Offset(w / 2, y - 10.dp.toPx()), Offset(w / 2, y + 10.dp.toPx()), 1.5.dp.toPx())
    val x = w / 2 + v.toFloat().coerceIn(-1f, 1f) * half
    drawLine(accentColor, Offset(w / 2, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
    drawCircle(Nine.text, 6.dp.toPx(), Offset(x, y))
    sent?.let { drawCircle(accentColor, 10.dp.toPx(), Offset(w / 2 + it.toFloat().coerceIn(-1f, 1f) * half, y), style = Stroke(1.5.dp.toPx())) }
} }
