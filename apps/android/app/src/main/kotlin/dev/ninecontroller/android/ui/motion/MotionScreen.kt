package dev.ninecontroller.android.ui.motion

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.input.MotionSensor
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors
import dev.ninecontroller.protocol.MotionMath
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.ProfileException

@Composable
fun MotionScreen(app: NineApp, nav: NavController, profileId: String) {
    val context = LocalContext.current
    val profile = app.profiles.get(profileId) ?: return
    var m by remember { mutableStateOf(profile.motion) }
    var angle by remember { mutableDoubleStateOf(Double.NaN) }
    var error by remember { mutableStateOf<String?>(null) }
    val runner = remember { dev.ninecontroller.android.input.SteeringRunner(MotionSensor(context)) }
    val saved by app.settings.flow.collectAsState(dev.ninecontroller.android.data.AppSettings())
    // Live angle relative to the player's saved neutral grip (none saved: no angle, never a guess).
    DisposableEffect(saved.steering) {
        runner.start(60, saved.steering, profile.motion) { _ -> angle = runner.angleDeg }
        onDispose { runner.stop() }
    }

    @Composable fun slider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, fmtv: String, set: (Double) -> Unit) {
        Micro("$label  $fmtv")
        Slider(value.toFloat(), { set(it.toDouble()) }, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }

    Page(profile.name, "Motion steering", nav) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Steer by tilting the phone", Modifier.weight(1f)); Switch(m.enabled, { m = m.copy(enabled = it) })
            }
            Text("Sensor: ${runner.source.takeIf { it != "none" } ?: "not available on this phone"}", color = Nine.dim)
            Text(if (saved.steering == null) "Set your neutral grip first (Calibrate steering or Recenter on the controller)." else "Angle from your neutral grip: ${fmt(angle, 1, "°")}", color = Nine.muted)
        }
        Card {
            slider("Steering lock", m.lockDeg, 10f..180f, "${m.lockDeg.toInt()}°") { m = m.copy(lockDeg = it) }
            Text("Turn needed for full lock from your neutral grip, used until you measure your own left and right range.", color = Nine.dim)
            slider("Sensitivity", m.sensitivity, 0.25f..4f, "%.2f×".format(m.sensitivity)) { m = m.copy(sensitivity = it) }
            slider("Dead zone", m.deadzone, 0f..0.3f, "${(m.deadzone * 100).toInt()}%") { m = m.copy(deadzone = it) }
            slider("Response curve", m.curve, 0.5f..3f, "%.2f".format(m.curve)) { m = m.copy(curve = it) }
            slider("Smoothing", 20.0 - m.minCutoff, 0f..19.5f, "%.1f Hz cutoff".format(m.minCutoff)) { m = m.copy(minCutoff = 20.0 - it) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Invert direction", Modifier.weight(1f)); Switch(m.invert, { m = m.copy(invert = it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Steer with", Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { m = m.copy(axis = if (m.axis == "lx") "rx" else "lx") }) { Text(if (m.axis == "lx") "Left stick" else "Right stick", color = Nine.text) }
            }
        }
        Card { Micro("Response"); Curve(m, angle - 0.0) }
        Button(onClick = {
            try { app.profiles.save(profile.copy(motion = m)); nav.popBackStack() } catch (e: ProfileException) { error = e.message }
        }, colors = primaryButtonColors()) { Text("Save") }
        error?.let { Text(it, color = Nine.bad) }
        Text("Set the center from the controller screen (“Recenter”). Smoothing trades a little delay for steadier steering.", color = Nine.dim)
    }
}

@Composable
private fun Curve(m: MotionSettings, liveAngle: Double) {
    val accentColor = LocalAccent.current.color
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = "Steering response curve" }) {
        val w = size.width; val h = size.height
        drawLine(Nine.line, Offset(0f, h / 2), Offset(w, h / 2)); drawLine(Nine.line, Offset(w / 2, 0f), Offset(w / 2, h))
        val path = Path()
        for (i in 0..100) {
            val d = (i / 100.0 * 2 - 1) * m.lockDeg * 1.2
            val y = MotionMath.shape(d, m)
            val px = (i / 100f) * w; val py = (h / 2 - y * h / 2 * 0.92).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, Nine.text, style = Stroke(2.dp.toPx()))
        if (!liveAngle.isNaN()) {
            val d = liveAngle.coerceIn(-m.lockDeg * 1.2, m.lockDeg * 1.2)
            val px = ((d / (m.lockDeg * 1.2) + 1) / 2 * w).toFloat()
            drawCircle(accentColor, 5.dp.toPx(), Offset(px, (h / 2 - MotionMath.shape(d, m) * h / 2 * 0.92).toFloat()))
        }
    }
}
