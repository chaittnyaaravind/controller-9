package dev.ninecontroller.android.ui.controller

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.input.ControllerEngine
import dev.ninecontroller.android.input.MotionSensor
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.ui.StatusDot
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors
import dev.ninecontroller.protocol.Button as Btn
import dev.ninecontroller.protocol.Calibrator
import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import dev.ninecontroller.protocol.MotionMath
import dev.ninecontroller.protocol.Orientation
import dev.ninecontroller.protocol.Steering
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/** Live per-control visual state (what the user is touching). */
data class Visual(val pressed: Boolean = false, val x: Double = 0.0, val y: Double = 0.0, val value: Double = 0.0, val mask: Int = 0)

@Composable
fun ControllerScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsState(AppSettings())
    val profiles by app.profiles.profiles.collectAsState()
    val conn by app.connection.state.collectAsState()
    val stats by app.connection.stats.collectAsState()
    val profile = profiles.find { it.id == settings.activeProfile } ?: profiles.firstOrNull() ?: return

    val engine = remember { ControllerEngine { s, f -> app.connection.submit(s, f) } }
    val visuals = remember { mutableStateMapOf<String, Visual>() }
    val toggles = remember { mutableStateMapOf<String, Boolean>() }
    var steeringValue by remember { mutableDoubleStateOf(0.0) }
    var calibrating by remember { mutableStateOf(false) }
    var calibrationMsg by remember { mutableStateOf<String?>(null) }
    val sensor = remember { MotionSensor(context) }
    val lastAngle = remember { doubleArrayOf(Double.NaN) }
    val calibrator = remember { arrayOfNulls<Calibrator>(1) }

    app.haptics.enabled = settings.hapticsEnabled && profile.haptics.enabled
    app.haptics.strength = settings.hapticStrength * profile.haptics.strength / 0.7

    // Window: orientation, immersive, keep-screen-on — restored on exit.
    DisposableEffect(profile.orientation, settings.keepScreenOn) {
        activity.requestedOrientation = if (profile.orientation == Orientation.LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (settings.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Connect to the last computer automatically.
    LaunchedEffect(Unit) {
        app.connection.setProfileName(profile.name)
        if (app.connection.state.value is ConnState.Idle || app.connection.state.value is ConnState.Failed) {
            // Read the stored settings: the collected `settings` is still the default on first composition.
            val last = app.settings.flow.first().lastHost ?: app.hosts.hosts.value.firstOrNull()?.id
            last?.let { app.hosts.byId(it) }?.let { app.connection.connect(it) }
        }
    }

    // Motion steering: only while this screen is visible and the profile enables it.
    DisposableEffect(profile.id, profile.motion, settings.calibrationDeg, settings.sensorHz) {
        engine.configure(profile.motion)
        if (profile.motion.enabled) {
            val steering = Steering(profile.motion).apply { setCenter(settings.calibrationDeg) }
            val display = androidx.core.content.ContextCompat.getDisplayOrDefault(context)
            sensor.start(settings.sensorHz, { display.rotation }) { gx, gy, gz, t ->
                MotionMath.wheelAngleDeg(gx, gy, gz)?.let { lastAngle[0] = it }
                calibrator[0]?.add(gx, gy, gz)
                val v = steering.update(gx, gy, gz, t)
                engine.setMotion(v)
                steeringValue = v
            }
        }
        onDispose { sensor.stop(); engine.releaseAll() }
    }
    LaunchedEffect(Unit) { while (true) { app.connection.sensorHz = sensor.measuredHz; delay(1000) } }
    DisposableEffect(profile.id) { visuals.clear(); toggles.clear(); engine.releaseAll(); onDispose { } }

    fun runCalibration() {
        calibrating = true; calibrationMsg = "Hold the phone at your neutral steering position…"
        engine.setCalibrating(true)
        calibrator[0] = Calibrator()
        scope.launch {
            delay(1500)
            val cal = calibrator[0]?.finish()
            calibrator[0] = null
            engine.setCalibrating(false)
            calibrationMsg = when {
                cal == null -> "Couldn’t read the motion sensor. Hold the phone upright, not flat."
                !cal.steady -> "The phone moved during calibration (±${"%.1f".format(cal.spreadDeg)}°). Hold it still and try again."
                else -> { app.settings.update { it.copy(calibrationDeg = cal.centerDeg) }; null }
            }
            if (calibrationMsg == null) calibrating = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Nine.bg)) {
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        fun rect(c: ControlDef) = floatArrayOf((c.x * wPx).toFloat(), (c.y * hPx).toFloat(), ((c.x + c.w) * wPx).toFloat(), ((c.y + c.h) * hPx).toFloat())
        val controls = profile.controls

        // One multi-touch handler for the whole surface: each finger owns the control it landed on.
        Box(Modifier.fillMaxSize().pointerInput(profile) {
            val recenter = { val a = lastAngle[0]; if (!a.isNaN()) scope.launch { app.settings.update { it.copy(calibrationDeg = a) } }; Unit }
            val owner = HashMap<PointerId, ControlDef>()
            val origin = HashMap<PointerId, Offset>()
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    for (ch in ev.changes) {
                        val p = ch.position
                        if (ch.pressed && !ch.previousPressed) {
                            val hit = controls.lastOrNull { c -> val r = rect(c); p.x >= r[0] && p.x <= r[2] && p.y >= r[1] && p.y <= r[3] } ?: continue
                            owner[ch.id] = hit; origin[ch.id] = p
                            onTouch(hit, p, p, rect(hit), engine, visuals, toggles, app, profile.sticks.deadzone, down = true, recenter = recenter)
                            ch.consume()
                        } else if (ch.pressed) {
                            val c = owner[ch.id] ?: continue
                            onTouch(c, p, origin[ch.id]!!, rect(c), engine, visuals, toggles, app, profile.sticks.deadzone, down = false, recenter = recenter)
                            ch.consume()
                        } else {
                            val c = owner.remove(ch.id) ?: continue
                            origin.remove(ch.id)
                            release(c, engine, visuals, toggles)
                        }
                    }
                }
            }
        }) {
            controls.forEach { c ->
                val r = rect(c)
                val v = visuals[c.id] ?: Visual(pressed = toggles[c.id] == true)
                with(density) {
                    Box(Modifier.offset(r[0].toDp(), r[1].toDp()).size((r[2] - r[0]).toDp(), (r[3] - r[1]).toDp())
                        .semantics {
                            role = Role.Button
                            contentDescription = describe(c)
                            if (c.kind == ControlKind.BUTTON) onClick {
                                val m = Btn.fromId(c.bind)!!.mask
                                engine.setButtons(c.id, m); scope.launch { delay(120); engine.setButtons(c.id, 0) }; true
                            }
                        }) {
                        ControlVisual(c, v, steeringValue)
                    }
                }
            }
        }

        // Status strip (top centre, small) — never covers controls' hit areas meaningfully.
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Nine.s1.copy(alpha = 0.85f), Nine.pill).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (label, color) = when (val s = conn) {
                is ConnState.Connected -> "P${s.slot + 1} · ${s.hostName} · ${s.link.label}" to (if (s.backendWarning == null) Nine.accent else Nine.warn)
                is ConnState.Reconnecting -> "Reconnecting…" to Nine.warn
                is ConnState.Connecting -> "Connecting…" to Nine.warn
                else -> "Offline" to Nine.bad
            }
            StatusDot(color)
            Micro(label, color = Nine.text)
            if (settings.showStats && conn is ConnState.Connected) Micro("${fmt(stats.rttP50)} ms", color = Nine.dim)
            if (profile.motion.enabled) TextButton(onClick = { runCalibration() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Micro("Center", color = Nine.text) }
            TextButton(onClick = { nav.navigate("edit/${profile.id}") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Micro("Edit", color = Nine.text) }
            TextButton(onClick = { nav.popBackStack() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Micro("Exit", color = Nine.text) }
        }
    }

    if (calibrating) AlertDialog(
        onDismissRequest = {},
        title = { Text("Calibrate steering") },
        text = { Text(calibrationMsg ?: "") },
        confirmButton = { if (calibrationMsg?.startsWith("Hold the phone at") != true) Button(onClick = { runCalibration() }, colors = primaryButtonColors()) { Text("Try again") } },
        dismissButton = { TextButton(onClick = { calibrating = false; calibrator[0] = null; engine.setCalibrating(false) }) { Text("Cancel", color = Nine.text) } },
        containerColor = Nine.s1,
    )
}

private fun describe(c: ControlDef): String = when (c.kind) {
    ControlKind.BUTTON -> "${c.label ?: c.bind.uppercase()} button"
    ControlKind.STICK -> "${c.bind} stick"
    ControlKind.DPAD -> "Direction pad"
    ControlKind.TRIGGER -> "${c.label ?: c.bind.uppercase()} trigger"
    ControlKind.PEDAL -> "${c.label ?: c.bind.uppercase()} pedal"
    ControlKind.STEERING -> "Steering indicator. Tap to recenter."
}

private fun onTouch(
    c: ControlDef, p: Offset, origin: Offset, r: FloatArray, engine: ControllerEngine,
    visuals: MutableMap<String, Visual>, toggles: MutableMap<String, Boolean>, app: NineApp, stickDz: Double, down: Boolean,
    recenter: () -> Unit,
) {
    val w = r[2] - r[0]; val h = r[3] - r[1]
    when (c.kind) {
        ControlKind.BUTTON -> if (down) {
            val mask = Btn.fromId(c.bind)!!.mask
            if (c.toggle) {
                val on = !(toggles[c.id] ?: false); toggles[c.id] = on
                engine.setButtons(c.id, if (on) mask else 0); visuals[c.id] = Visual(pressed = on)
            } else { engine.setButtons(c.id, mask); visuals[c.id] = Visual(pressed = true) }
            if (c.haptic) app.haptics.click()
        }
        ControlKind.STICK -> {
            // Relative origin: wherever the thumb lands is centre. Radius = half the smaller side.
            val radius = minOf(w, h) / 2f
            val dx = ((p.x - origin.x) / radius).toDouble(); val dy = (-(p.y - origin.y) / radius).toDouble()
            val m = hypot(dx, dy).coerceAtLeast(1e-9)
            val (cx, cy) = if (m > 1) dx / m to dy / m else dx to dy
            val (x, y) = MotionMath.radialDeadzone(cx, cy, stickDz)
            engine.setStick(c.bind, x, y); visuals[c.id] = Visual(true, cx, cy)
        }
        ControlKind.DPAD -> {
            val cx = r[0] + w / 2; val cy = r[1] + h / 2
            val dx = p.x - cx; val dy = cy - p.y
            val mask = if (hypot(dx, dy) < minOf(w, h) * 0.12f) 0 else {
                val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
                val sector = ((a + 22.5) / 45).toInt() % 8 // 0=E,1=NE,2=N,...
                listOf(Btn.RIGHT.mask, Btn.RIGHT.mask or Btn.UP.mask, Btn.UP.mask, Btn.UP.mask or Btn.LEFT.mask,
                    Btn.LEFT.mask, Btn.LEFT.mask or Btn.DOWN.mask, Btn.DOWN.mask, Btn.DOWN.mask or Btn.RIGHT.mask)[sector]
            }
            val prev = visuals[c.id]?.mask ?: 0
            if (mask != prev && mask != 0 && c.haptic) app.haptics.click()
            engine.setButtons(c.id, mask); visuals[c.id] = Visual(mask != 0, mask = mask)
        }
        ControlKind.TRIGGER, ControlKind.PEDAL -> {
            // Depth from the top edge: deeper finger = more pressure. Small top band = 0 for clean release.
            val v = (((p.y - r[1]) / h - 0.08f) / 0.84f).coerceIn(0f, 1f).toDouble()
            engine.setTrigger(c.id, c.bind, if (c.kind == ControlKind.PEDAL) maxOf(v, 0.02) else v)
            visuals[c.id] = Visual(true, value = v)
            if (down && c.haptic) app.haptics.click()
        }
        ControlKind.STEERING -> if (down) { recenter(); app.haptics.click() }
    }
}

private fun release(c: ControlDef, engine: ControllerEngine, visuals: MutableMap<String, Visual>, toggles: Map<String, Boolean>) {
    when (c.kind) {
        ControlKind.BUTTON -> if (!c.toggle) { engine.setButtons(c.id, 0); visuals.remove(c.id) }
        ControlKind.STICK -> { engine.setStick(c.bind, 0.0, 0.0); visuals.remove(c.id) }
        ControlKind.DPAD -> { engine.setButtons(c.id, 0); visuals.remove(c.id) }
        ControlKind.TRIGGER, ControlKind.PEDAL -> { engine.setTrigger(c.id, c.bind, 0.0); visuals.remove(c.id) }
        ControlKind.STEERING -> Unit
    }
}
