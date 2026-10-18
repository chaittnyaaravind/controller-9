package dev.ninecontroller.android.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.data.SteeringCalibration
import dev.ninecontroller.android.input.MotionSensor
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
import dev.ninecontroller.protocol.Calibration
import dev.ninecontroller.protocol.Calibrator
import dev.ninecontroller.protocol.MotionMath
import dev.ninecontroller.protocol.MotionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Pure rules for the guided calibration (unit-tested in CalibrationMathTest). */
object CalibrationMath {
    const val HOLD_MS = 1500L
    /** Std dev allowed while holding an extreme (centre uses Calibration.steady, 1.5°). */
    const val EXTREME_SPREAD = 3.0
    /** A turn smaller than this is not a usable full-lock measurement. */
    const val MIN_TURN = 10.0

    sealed interface Extreme {
        data class Ok(val deltaDeg: Double, val reversed: Boolean) : Extreme
        data class Retry(val reason: String) : Extreme
    }

    /**
     * Evaluate a held extreme relative to [centerDeg]. [wantRight] is what we asked for; the
     * direction is checked against what the steering output would do with [motion].
     */
    fun extreme(centerDeg: Double, cal: Calibration?, wantRight: Boolean, motion: MotionSettings): Extreme {
        cal ?: return Extreme.Retry("Couldn’t read the motion sensor. Hold the phone upright, not flat.")
        if (cal.spreadDeg > EXTREME_SPREAD) return Extreme.Retry("The phone moved while holding (±${"%.1f".format(cal.spreadDeg)}°). Hold it still at full turn.")
        val delta = MotionMath.wrapDeg(cal.centerDeg - centerDeg)
        if (abs(delta) < MIN_TURN) return Extreme.Retry("That was only ${"%.0f".format(abs(delta))}° from centre. Turn further, then hold.")
        val out = MotionMath.shape(delta, motion.copy(deadzone = 0.0, lockDeg = 180.0, sensitivity = 1.0, curve = 1.0))
        return Extreme.Ok(delta, reversed = (out > 0) != wantRight)
    }
}

private enum class Step { CENTER, LEFT, RIGHT, DONE }

@Composable
fun CalibrationScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    val profiles by app.profiles.profiles.collectAsState()
    val profile = profiles.find { it.id == s.activeProfile }
    // Steering settings of the layout in use: controller presets share the tilt settings; profiles keep their own.
    val motion = if (s.padPreset != null) s.padMotion() else profile?.motion ?: MotionSettings()

    val sensor = remember { MotionSensor(context) }
    var angle by remember { mutableDoubleStateOf(Double.NaN) }
    var available by remember { mutableStateOf(true) }
    val calibrator = remember { arrayOfNulls<Calibrator>(1) }
    var step by remember { mutableStateOf(Step.CENTER) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var center by remember { mutableStateOf<Calibration?>(null) }
    var left by remember { mutableStateOf<Double?>(null) }
    var right by remember { mutableStateOf<Double?>(null) }
    var reversed by remember { mutableStateOf(false) }

    // This screen reads the sensor only; it never sends input. Leaving the controller screen
    // already released every control, so the computer sees a neutral controller throughout —
    // and the host neutralizes on its own if the connection drops.
    DisposableEffect(Unit) {
        available = sensor.start(100, { androidx.core.content.ContextCompat.getDisplayOrDefault(context).rotation }) { gx, gy, gz, _ ->
            MotionMath.wheelAngleDeg(gx, gy, gz)?.let { angle = it }
            calibrator[0]?.add(gx, gy, gz)
        }
        onDispose { sensor.stop() }
    }

    fun capture(then: (Calibration?) -> Unit) {
        busy = true; message = "Hold still…"
        calibrator[0] = Calibrator()
        scope.launch { delay(CalibrationMath.HOLD_MS); val c = calibrator[0]?.finish(); calibrator[0] = null; busy = false; message = null; then(c) }
    }

    val centerDeg = center?.centerDeg ?: s.calibrationDeg
    val delta = if (angle.isNaN()) null else MotionMath.wrapDeg(angle - centerDeg)
    val output = delta?.let { MotionMath.shape(it, motion) }

    Page("Steering calibration", "Calibrate", nav) {
        Text("Hold the phone like a wheel, in landscape. Three short holds measure where your centre is, how far you turn each way, and how steady your hands are.",
            style = NineType.small, color = Nine.muted)
        if (!available) Text("This phone has no usable motion sensor, so steering can’t be calibrated.", color = Nine.bad, style = NineType.small)

        Section("Live") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SteeringBar(output ?: 0.0)
                Row(Modifier.fillMaxWidth()) {
                    Metric(delta?.let { "%+.1f°".format(it) } ?: "—", "Angle from centre", Modifier.weight(1f))
                    Metric(output?.let { "%+.2f".format(it) } ?: "—", "Steering output", Modifier.weight(1f))
                    Metric(output?.let { if (it > 0.01) "Right" else if (it < -0.01) "Left" else "Centre" } ?: "—", "Direction", Modifier.weight(1f))
                }
                Text("Output uses ${if (s.padPreset != null) "the controller tilt settings" else "${profile?.name ?: "this profile"}’s motion settings"}: ${"%.0f".format(motion.lockDeg)}° lock, dead zone ${"%.0f".format(motion.deadzone * 100)}%." +
                    if (s.padPreset != null && !s.padTilt) " Tilt steering is off for controller layouts; turn it on in Settings to use it in game." else "",
                    style = NineType.small, color = Nine.dim)
            }
        }

        Section("Steps") {
            StepRow(1, "Neutral", "Hold the phone level at your resting position.", center?.let { "Centre ${"%+.1f".format(it.centerDeg)}° · steadiness ±${"%.1f".format(it.spreadDeg)}°" }, step == Step.CENTER)
            Divider()
            StepRow(2, "Full left", "Turn left as far as you would in a game, then hold.", left?.let { "%+.1f° from centre".format(it) }, step == Step.LEFT)
            Divider()
            StepRow(3, "Full right", "Turn right as far as you would, then hold.", right?.let { "%+.1f° from centre".format(it) }, step == Step.RIGHT)
        }
        message?.let { Text(it, style = NineType.small, color = if (busy) Nine.text else Nine.warn) }
        if (reversed) Text("The output moved the opposite way to your turn. If steering feels reversed in game, turn on Invert in the layout’s motion settings.", style = NineType.small, color = Nine.warn)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                Step.CENTER -> PrimaryButton(if (busy) "Measuring…" else "Measure neutral", {
                    capture { c ->
                        when {
                            c == null -> message = "Couldn’t read the motion sensor. Hold the phone upright, not flat."
                            !c.steady -> message = "The phone moved (±${"%.1f".format(c.spreadDeg)}°). Hold it still and try again."
                            else -> { center = c; step = Step.LEFT }
                        }
                    }
                }, enabled = !busy && available)
                Step.LEFT, Step.RIGHT -> PrimaryButton(if (busy) "Measuring…" else "Hold ${if (step == Step.LEFT) "left" else "right"} and measure", {
                    val wantRight = step == Step.RIGHT
                    capture { c ->
                        when (val r = CalibrationMath.extreme(center!!.centerDeg, c, wantRight, motion)) {
                            is CalibrationMath.Extreme.Retry -> message = r.reason
                            is CalibrationMath.Extreme.Ok -> {
                                reversed = reversed || r.reversed
                                if (wantRight) { right = r.deltaDeg; step = Step.DONE } else { left = r.deltaDeg; step = Step.RIGHT }
                            }
                        }
                    }
                }, enabled = !busy)
                Step.DONE -> Unit
            }
            if (step != Step.CENTER) SecondaryButton("Start over", { step = Step.CENTER; center = null; left = null; right = null; reversed = false; message = null }, enabled = !busy)
        }

        val c = center
        if (c != null) {
            val result = SteeringCalibration(c.centerDeg, left, right, c.spreadDeg, System.currentTimeMillis())
            Section("Measured", footer = "Only values measured on this phone just now are shown. Nothing is saved until you choose to.") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("%+.1f°".format(c.centerDeg - s.calibrationDeg), "Centre offset", Modifier.weight(1f))
                        Metric(result.lockDeg?.let { "%.0f°".format(it) } ?: "—", "Usable lock", Modifier.weight(1f))
                        Metric(result.suggestedDeadzone?.let { "%.1f%%".format(it * 100) } ?: "—", "Dead zone (3σ)", Modifier.weight(1f))
                    }
                    if (result.lockDeg == null) Text("Measure full left and full right to get the lock and dead zone.", style = NineType.small, color = Nine.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Save centre" + if (result.lockDeg != null) " and range" else "", {
                            scope.launch {
                                app.settings.update { st ->
                                    var n = st.copy(calibrationDeg = c.centerDeg, calibration = result)
                                    if (result.lockDeg != null && st.padPreset != null) n = n.copy(tiltLockDeg = result.lockDeg!!.coerceIn(10.0, 180.0), tiltDeadzone = result.suggestedDeadzone ?: st.tiltDeadzone)
                                    n
                                }
                                if (result.lockDeg != null && profile != null && s.padPreset == null) runCatching {
                                    app.profiles.save(profile.copy(motion = profile.motion.copy(lockDeg = result.lockDeg!!.coerceIn(10.0, 180.0), deadzone = result.suggestedDeadzone ?: profile.motion.deadzone)))
                                }
                                nav.popBackStack()
                            }
                        })
                    }
                }
            }
        }
        s.calibration?.let { saved ->
            Section("Last saved") {
                ListRow("Centre ${"%+.1f".format(saved.centerDeg)}°", detail = listOfNotNull(
                    saved.lockDeg?.let { "lock ${"%.0f".format(it)}°" }, "steadiness ±${fmt(saved.noiseDeg)}°",
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(saved.measuredAt)),
                ).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, how: String, result: String?, current: Boolean) =
    ListRow("$n  ·  $title", detail = result ?: how, detailColor = when { result != null -> Nine.accent; current -> Nine.text; else -> Nine.dim },
        trailing = { Text(if (result != null) "Measured" else if (current) "Now" else "", style = NineType.small, color = if (result != null) Nine.accent else Nine.muted) })

/** Horizontal steering bar, −1 … +1, centre tick. */
@Composable
private fun SteeringBar(v: Double) = Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "Steering output %+.2f".format(v) }) {
    val y = size.height / 2; val w = size.width
    drawLine(Nine.line2, Offset(0f, y), Offset(w, y), 4.dp.toPx(), StrokeCap.Round)
    drawLine(Nine.dim, Offset(w / 2, y - 10.dp.toPx()), Offset(w / 2, y + 10.dp.toPx()), 1.5.dp.toPx())
    val x = w / 2 + (v.toFloat().coerceIn(-1f, 1f) * (w / 2 - 8.dp.toPx()))
    drawLine(Nine.accent, Offset(w / 2, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
    drawCircle(Nine.text, 7.dp.toPx(), Offset(x, y))
}
