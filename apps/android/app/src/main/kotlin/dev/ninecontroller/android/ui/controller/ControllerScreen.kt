package dev.ninecontroller.android.ui.controller

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import dev.ninecontroller.android.pad.PadGeometry
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.Pill
import dev.ninecontroller.android.ui.Tone
import dev.ninecontroller.android.ui.color
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.pad.Look
import dev.ninecontroller.android.ui.pad.drawElement
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.protocol.Button as Btn
import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.MotionMath
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.Orientation
import dev.ninecontroller.protocol.Profile
import dev.ninecontroller.protocol.PoseCapture
import dev.ninecontroller.protocol.SteeringCalibrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/** Live per-control visual state (what the user is touching). */
data class Visual(val pressed: Boolean = false, val x: Double = 0.0, val y: Double = 0.0, val value: Double = 0.0, val mask: Int = 0)

/**
 * What the surface shows: a controller preset (PadMapping) or a custom profile. Both feed the same
 * ControllerEngine through the same touch handlers, so input safety is shared.
 */
private class SurfaceSpec(
    val key: String,
    val name: String,
    val controls: List<ControlDef>,
    val pad: PadMapping?,
    val motion: MotionSettings,
    val stickDeadzone: Double,
    val orientation: Orientation,
    val profileId: String?,
    val hapticsEnabled: Boolean,
    val hapticStrength: Double,
)

@Composable
fun ControllerScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsState(null)
    val profiles by app.profiles.profiles.collectAsState()
    val conn by app.connection.state.collectAsState()
    val stats by app.connection.stats.collectAsState()
    val s = settings ?: return // wait for stored settings: never flash the wrong layout

    val spec = remember(s.activeProfile, s.padMappings, s.padTilt, s.tiltSensitivity, s.tiltDeadzone, s.tiltLockDeg, s.stickDeadzone, profiles) { surfaceFor(s, profiles) } ?: return

    val engine = remember { ControllerEngine(minPressMs = MIN_PRESS_MS) { st, f -> app.connection.submit(st, f) } }
    val visuals = remember { mutableStateMapOf<String, Visual>() }
    val toggles = remember { mutableStateMapOf<String, Boolean>() }
    var sent by remember { mutableStateOf(InputState.NEUTRAL) }
    var steeringValue by remember { mutableDoubleStateOf(0.0) }
    var recentering by remember { mutableStateOf(false) }
    var recenterMsg by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var showInputs by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var switcher by remember { mutableStateOf(false) }
    val runner = remember { dev.ninecontroller.android.input.SteeringRunner(MotionSensor(context)) }
    val feel = remember { dev.ninecontroller.android.input.FeelEffects(app.haptics::cue) }

    app.haptics.enabled = s.hapticsEnabled && spec.hapticsEnabled
    app.haptics.strength = s.hapticStrength * spec.hapticStrength / 0.7
    app.haptics.feel = s.feelEffects

    // Window: orientation, immersive, keep-screen-on — restored on exit. Controller presets are
    // landscape (two-thumb grip; motion steering assumes it); either landscape rotation works.
    DisposableEffect(spec.orientation, s.keepScreenOn) {
        activity.requestedOrientation = if (spec.orientation == Orientation.LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (s.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Connect to the last computer automatically.
    LaunchedEffect(Unit) {
        if (app.connection.state.value is ConnState.Idle || app.connection.state.value is ConnState.Failed) {
            val last = app.settings.flow.first().lastHost ?: app.hosts.hosts.value.firstOrNull()?.id
            last?.let { app.hosts.byId(it) }?.let { app.connection.connect(it) }
        }
    }
    LaunchedEffect(spec.name) { app.connection.setProfileName(spec.name) }

    // Motion steering, relative to the player's own grip (docs/STEERING.md). Only while this screen is
    // visible and the layout enables it. A recenter changes the reference inside the runner (blended, no
    // step), so this effect does not restart when the saved calibration changes.
    DisposableEffect(spec.key, spec.motion, s.sensorHz) {
        engine.configure(spec.motion)
        if (spec.motion.enabled) runner.start(s.sensorHz, s.steering, spec.motion) { v -> engine.setMotion(v); feel.steering(v); steeringValue = v }
        onDispose { runner.stop(); engine.releaseAll(); feel.reset() }
    }
    LaunchedEffect(Unit) { while (true) { app.connection.sensorHz = runner.hz; delay(1000) } }
    // Any layout change releases every control first (nothing stays held across a switch).
    DisposableEffect(spec.key) { visuals.clear(); toggles.clear(); engine.releaseAll(); onDispose { } }
    // Inspector shows what is actually sent, not what the screen thinks is pressed.
    LaunchedEffect(showInputs) { while (showInputs) { sent = engine.state; delay(50) } }
    LaunchedEffect(hint) { if (hint != null) { delay(2400); hint = null } }

    /**
     * Recenter: hold the grip still for a short window, estimate it robustly, accept it only if steady.
     * Steering keeps working during the hold (relative to the old center) and the new center blends in,
     * so the game never sees a jump. Never automatic during play — only on request, or once when a
     * steering session starts with no center at all.
     */
    fun recenter(auto: Boolean = false) {
        if (recentering || !spec.motion.enabled) return
        recentering = true
        recenterMsg = ((if (auto) "No center yet. " else "") + "Hold your normal grip still…") to Tone.WARN
        engine.setCalibrating(true)
        runner.beginCapture()
        scope.launch {
            delay(RECENTER_HOLD_MS)
            val held = runner.endCapture()
            engine.setCalibrating(false)
            recenterMsg = when {
                held == null -> "Couldn’t read the motion sensor, so steering stays centered." to Tone.BAD
                held.spreadDeg > PoseCapture.NEUTRAL_MAX_SPREAD -> "Too shaky to set the center (±${"%.1f".format(held.spreadDeg)}°). Rest your elbows or wrists, hold still, then tap Recenter." to Tone.WARN
                else -> {
                    val (cal, remeasure) = SteeringCalibrator.recenter(runner.calibration ?: s.steering, held, System.currentTimeMillis(), runner.source)
                    runner.recalibrate(cal)
                    app.settings.update { it.copy(steering = cal) }
                    (if (remeasure) "Center set. Your grip changed a lot: measure left and right again in Calibrate steering."
                     else "Center set. Steering now follows this grip.") to Tone.OK
                }
            }
            recentering = false
        }
    }
    LaunchedEffect(recenterMsg, recentering) { if (recenterMsg != null && !recentering) { delay(2800); recenterMsg = null } }
    // A steering session with no center at all: capture the player's grip once, automatically.
    LaunchedEffect(spec.key, spec.motion.enabled) { if (spec.motion.enabled && s.steering == null) { delay(800); recenter(auto = true) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Nine.bg)) {
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val minTarget = with(density) { Nine.target.toPx() }
        // Screen boxes for every control: presets use uniform sizing, profiles their own rects.
        val boxes: Map<String, PadGeometry.Box> = remember(spec.key, wPx, hPx, spec.controls) {
            spec.pad?.active?.associate { (e, _) -> e.id to PadGeometry.place(e, wPx, hPx) }
                ?: spec.controls.associate { c -> c.id to PadGeometry.Box((c.x * wPx).toFloat(), (c.y * hPx).toFloat(), ((c.x + c.w) * wPx).toFloat(), ((c.y + c.h) * hPx).toFloat()) }
        }
        val inert: Map<String, PadGeometry.Box> = remember(spec.key, wPx, hPx) {
            spec.pad?.let { m -> m.preset.elements.filter { m.actionOf(it) == null }.associate { it.id to PadGeometry.place(it, wPx, hPx) } } ?: emptyMap()
        }
        val byId = remember(spec.controls) { spec.controls.associateBy { it.id } }
        fun rect(id: String) = boxes.getValue(id).let { floatArrayOf(it.left, it.top, it.right, it.bottom) }

        // One multi-touch handler for the whole surface: each finger owns the control it landed on.
        Box(Modifier.fillMaxSize().pointerInput(spec.key, wPx, hPx) {
            val recenter = { recenter() }
            val owner = HashMap<PointerId, ControlDef>()
            val origin = HashMap<PointerId, Offset>()
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    for (ch in ev.changes) {
                        val p = ch.position
                        if (ch.pressed && !ch.previousPressed) {
                            val id = PadGeometry.hit(boxes, p.x, p.y, if (spec.pad != null) minTarget else 0f)
                            val hit = id?.let { byId[it] }
                            if (hit == null) {
                                // Tapping a control this protocol can't carry explains why instead of doing nothing silently.
                                inert.entries.firstOrNull { it.value.contains(p.x, p.y) }?.let { (eid, _) ->
                                    val e = spec.pad!!.preset.element(eid); hint = "${e.label}: ${e.unavailable}"
                                }
                                continue
                            }
                            owner[ch.id] = hit; origin[ch.id] = p
                            onTouch(hit, p, p, rect(hit.id), engine, visuals, toggles, app, spec.stickDeadzone, down = true, recenter = recenter, fullTriggers = spec.pad != null, feel = feel)
                            ch.consume()
                        } else if (ch.pressed) {
                            val c = owner[ch.id] ?: continue
                            onTouch(c, p, origin[ch.id]!!, rect(c.id), engine, visuals, toggles, app, spec.stickDeadzone, down = false, recenter = recenter, fullTriggers = spec.pad != null, feel = feel)
                            ch.consume()
                        } else {
                            val c = owner.remove(ch.id) ?: continue
                            origin.remove(ch.id)
                            release(c, engine, visuals, toggles, feel)
                        }
                    }
                }
            }
        }) {
            val pad = spec.pad
            if (pad != null) {
                val tm = rememberTextMeasurer()
                val u = minOf(wPx / 200f, hPx / 100f)
                Canvas(Modifier.fillMaxSize()) {
                    for (e in pad.preset.elements) {
                        val b = boxes[e.id] ?: inert.getValue(e.id)
                        val v = visuals[e.id] ?: Visual(pressed = toggles[e.id] == true)
                        drawElement(e, Rect(b.left, b.top, b.right, b.bottom), pad.actionOf(e),
                            Look(v.pressed, v.value.toFloat(), v.x.toFloat(), v.y.toFloat(), v.mask), tm, u)
                    }
                }
            }
            // Accessibility nodes (and the profile renderer): one box per control.
            spec.controls.forEach { c ->
                val b = boxes.getValue(c.id)
                val v = visuals[c.id] ?: Visual(pressed = toggles[c.id] == true)
                with(density) {
                    Box(Modifier.offset(b.left.toDp(), b.top.toDp()).size(b.width.toDp(), b.height.toDp())
                        .semantics {
                            role = Role.Button
                            contentDescription = describe(c, spec.pad)
                            stateDescription = if (v.pressed) "pressed" else "released"
                            if (c.kind == ControlKind.BUTTON) onClick {
                                val m = Btn.fromId(c.bind)!!.mask
                                engine.setButtons(c.id, m); scope.launch { delay(120); engine.setButtons(c.id, 0) }; true
                            }
                        }) {
                        if (pad == null) ControlVisual(c, v, steeringValue)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- top bar (between the shoulders)
        val barMax = with(density) { (wPx * if (spec.pad != null) 0.47f else 0.64f).toDp() }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).widthIn(max = barMax),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Row(Modifier.heightIn(min = 36.dp).background(Nine.s1.copy(alpha = 0.92f), Nine.pill).border(1.dp, Nine.line, Nine.pill)
                    .clickable(role = Role.Button, onClickLabel = "Switch layout") { switcher = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = NineType.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 118.dp))
                    Text("  ▾", style = NineType.small, color = Nine.dim)
                }
                DropdownMenu(switcher, { switcher = false }, containerColor = Nine.s2) {
                    Micro("Controller layouts", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    PadPresets.all.forEach { p ->
                        val sel = PadPresets.selection(p)
                        DropdownMenuItem(text = { Text(p.name + if (sel == s.activeProfile) "  ·  active" else "", color = if (sel == s.activeProfile) Nine.accent else Nine.text) },
                            onClick = { switcher = false; scope.launch { app.settings.update { it.copy(activeProfile = sel) } } })
                    }
                    HorizontalDivider(color = Nine.line)
                    Micro("Custom profiles", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    profiles.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name + if (p.id == s.activeProfile) "  ·  active" else "", color = if (p.id == s.activeProfile) Nine.accent else Nine.text) },
                            onClick = { switcher = false; scope.launch { app.settings.update { it.copy(activeProfile = p.id) } } })
                    }
                }
            }
            val (label, tone) = when (val c = conn) {
                is ConnState.Connected -> "P${c.slot + 1} · ${(listOf(c.link) + c.also).joinToString("+") { shortLink(it) }}" + (if (s.showStats) " · ${fmt(stats.rttP50, 0)} ms" else "") to (if (c.backendWarning == null) Tone.OK else Tone.WARN)
                is ConnState.Reconnecting -> "Reconnecting" to Tone.WARN
                is ConnState.Connecting -> "Connecting" to Tone.WARN
                is ConnState.ConfirmCode -> "Confirm code" to Tone.WARN
                is ConnState.Failed -> "Offline" to Tone.BAD
                ConnState.Idle -> "Offline" to Tone.BAD
            }
            Pill(label, tone, Modifier.weight(1f, fill = false))
            BarButton("Inputs", active = showInputs) { showInputs = !showInputs }
            if (spec.motion.enabled) BarButton("Recenter", active = recentering) { recenter() }
            if (spec.profileId != null) BarButton("Edit") { nav.navigate("edit/${spec.profileId}") }
            BarButton("Exit") { nav.popBackStack() }
        }

        // ---------------------------------------------------------------- inspector / hints (non-interactive)
        val inspector = if (showInputs) describeSent(sent, spec.pad) else null
        val warn = (conn as? ConnState.Connected)?.backendWarning
        if (inspector != null || hint != null || recenterMsg != null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 50.dp).widthIn(max = with(density) { (wPx * 0.44f).toDp() })
                .background(Nine.s1.copy(alpha = 0.94f), Nine.small).border(1.dp, Nine.line, Nine.small).padding(horizontal = 12.dp, vertical = 8.dp)) {
                androidx.compose.foundation.layout.Column {
                    recenterMsg?.let { (m, tone) -> Text(m, style = NineType.small, color = tone.color(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (hint != null) Text(hint!!, style = NineType.small, color = Nine.warn)
                    if (inspector != null) {
                        Micro("Sending now", color = Nine.dim)
                        Text(inspector, style = NineType.mono, color = Nine.text)
                        if (warn != null) Text("Computer receives input; games can’t see a controller there yet.", style = NineType.small, color = Nine.warn)
                    }
                }
            }
        }
    }

}

/** Shortest press reported to the computer: a fast tap still spans several frames on every link. */
private const val MIN_PRESS_MS = 40L

/** Long enough for ~150 samples at 200 Hz, short enough to feel instant. */
private const val RECENTER_HOLD_MS = 900L

@Composable
private fun BarButton(text: String, active: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.heightIn(min = 36.dp).background(if (active) Nine.accentSoft else Nine.s1.copy(alpha = 0.92f), Nine.pill)
        .border(1.dp, if (active) Nine.accent.copy(alpha = 0.6f) else Nine.line, Nine.pill)
        .semantics { if (active) stateDescription = "on" }
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        // Single line, never wraps: a narrow bar truncates the pill first (it has the weight), not the buttons.
        Text(text.uppercase(), style = NineType.label, color = Nine.text, maxLines = 1, softWrap = false)
    }

/** Compact link names for the in-game bar; the full label is on Home and Diagnostics. */
internal fun shortLink(k: dev.ninecontroller.protocol.LinkKind) = when (k) {
    dev.ninecontroller.protocol.LinkKind.LAN -> "Wi-Fi"; dev.ninecontroller.protocol.LinkKind.BLE -> "BLE"
    dev.ninecontroller.protocol.LinkKind.USB -> "USB"; dev.ninecontroller.protocol.LinkKind.STREAM -> "TCP"
    else -> k.label
}

private fun surfaceFor(s: AppSettings, profiles: List<Profile>): SurfaceSpec? {
    s.padPreset?.let { preset ->
        val mapping = PadMapping.decode(preset, s.padMappings[preset.id]).first
        return SurfaceSpec(s.activeProfile, preset.name, mapping.controls(), mapping, s.padMotion(), s.stickDeadzone, Orientation.LANDSCAPE, null, true, 0.7)
    }
    val p = profiles.find { it.id == s.activeProfile } ?: profiles.firstOrNull() ?: return null
    return SurfaceSpec(p.id, p.name, p.controls, null, p.motion, p.sticks.deadzone, p.orientation, p.id, p.haptics.enabled, p.haptics.strength)
}

private fun describe(c: ControlDef, pad: PadMapping?): String {
    pad?.let { m -> val e = m.preset.element(c.id); return "${e.label}, sends ${m.actionOf(e)?.title ?: "nothing"}" }
    return when (c.kind) {
        ControlKind.BUTTON -> "${c.label ?: c.bind.uppercase()} button"
        ControlKind.STICK -> "${c.bind} stick"
        ControlKind.DPAD -> "Direction pad"
        ControlKind.TRIGGER -> "${c.label ?: c.bind.uppercase()} trigger"
        ControlKind.PEDAL -> "${c.label ?: c.bind.uppercase()} pedal"
        ControlKind.STEERING -> "Steering indicator. Tap to recenter."
    }
}

/** Human-readable decode of the InputState actually being sent, with this layout's labels. */
internal fun describeSent(st: InputState, pad: PadMapping?): String {
    if (st.isNeutral) return "nothing — all released"
    val names = Btn.entries.filter { st.pressed(it) }.map { b ->
        pad?.active?.firstOrNull { (_, a) -> a.button == b }?.first?.let { if (it.part == dev.ninecontroller.android.pad.Part.DPAD) null else it.short }
            ?: when (b) { Btn.UP -> "↑"; Btn.DOWN -> "↓"; Btn.LEFT -> "←"; Btn.RIGHT -> "→"; else -> b.id.uppercase() }
    }
    val parts = ArrayList(names)
    fun axis(v: Int) = "%+.2f".format(v / 32767.0)
    fun trig(v: Int) = "${(v * 100.0 / 65535).toInt()}%"
    val lt = pad?.preset?.elements?.find { it.id == "trigger_l" }?.short ?: "LT"
    val rt = pad?.preset?.elements?.find { it.id == "trigger_r" }?.short ?: "RT"
    if (st.lt > 0) parts += "$lt ${trig(st.lt)}"
    if (st.rt > 0) parts += "$rt ${trig(st.rt)}"
    if (st.lx != 0 || st.ly != 0) parts += "L ${axis(st.lx)} ${axis(st.ly)}"
    if (st.rx != 0 || st.ry != 0) parts += "R ${axis(st.rx)} ${axis(st.ry)}"
    return parts.joinToString("  ·  ")
}

private fun onTouch(
    c: ControlDef, p: Offset, origin: Offset, r: FloatArray, engine: ControllerEngine,
    visuals: MutableMap<String, Visual>, toggles: MutableMap<String, Boolean>, app: NineApp, stickDz: Double, down: Boolean,
    recenter: () -> Unit, fullTriggers: Boolean, feel: dev.ninecontroller.android.input.FeelEffects,
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
            val prev = visuals[c.id]?.mask ?: 0
            // Cardinal-dominant zones with hysteresis: "left" means left, not left+up (input/TouchMath.kt).
            val mask = dev.ninecontroller.android.input.DpadMath.resolve((p.x - cx).toDouble(), (cy - p.y).toDouble(), minOf(w, h) / 2.0, prev)
            if (mask != prev && mask != 0 && c.haptic) app.haptics.click()
            engine.setButtons(c.id, mask); visuals[c.id] = Visual(mask != 0, mask = mask)
        }
        ControlKind.TRIGGER, ControlKind.PEDAL -> {
            // Preset triggers are thumb-tapped: full press. Profile triggers/pedals are analog by
            // depth from the top edge (deeper finger = more pressure; small top band = 0).
            val v = if (fullTriggers && c.kind == ControlKind.TRIGGER) 1.0
                else (((p.y - r[1]) / h - 0.08f) / 0.84f).coerceIn(0f, 1f).toDouble()
            engine.setTrigger(c.id, c.bind, if (c.kind == ControlKind.PEDAL) maxOf(v, 0.02) else v)
            if (c.haptic) feel.trigger(c.id, v)
            visuals[c.id] = Visual(true, value = v)
            if (down && c.haptic) app.haptics.click()
        }
        ControlKind.STEERING -> if (down) { recenter(); app.haptics.click() }
    }
}

private fun release(c: ControlDef, engine: ControllerEngine, visuals: MutableMap<String, Visual>, toggles: Map<String, Boolean>, feel: dev.ninecontroller.android.input.FeelEffects) {
    when (c.kind) {
        ControlKind.BUTTON -> if (!c.toggle) { engine.setButtons(c.id, 0); visuals.remove(c.id) }
        ControlKind.STICK -> { engine.setStick(c.bind, 0.0, 0.0); visuals.remove(c.id) }
        ControlKind.DPAD -> { engine.setButtons(c.id, 0); visuals.remove(c.id) }
        ControlKind.TRIGGER, ControlKind.PEDAL -> { engine.setTrigger(c.id, c.bind, 0.0); feel.trigger(c.id, 0.0); visuals.remove(c.id) }
        ControlKind.STEERING -> Unit
    }
}
