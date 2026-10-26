package dev.ninecontroller.android.ui.editor

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.ui.controller.ControlVisual
import dev.ninecontroller.android.ui.controller.Visual
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors
import dev.ninecontroller.protocol.Button as Btn
import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import dev.ninecontroller.protocol.ProfileException
import kotlin.math.roundToInt

private fun snap(v: Double) = (v * 200).roundToInt() / 200.0 // 0.5% grid

private fun bindsFor(k: ControlKind): List<String> = when (k) {
    ControlKind.BUTTON -> Btn.entries.map { it.id }
    ControlKind.STICK -> listOf("left", "right")
    ControlKind.DPAD -> listOf("dpad")
    ControlKind.TRIGGER, ControlKind.PEDAL -> listOf("lt", "rt")
    ControlKind.STEERING -> listOf("motion")
}

/** Drag to move, drag the corner handle to resize, tap to edit properties. Saves only valid layouts. */
@Composable
fun LayoutEditorScreen(app: NineApp, nav: NavController, profileId: String) {
    val activity = LocalContext.current as Activity
    val original = app.profiles.get(profileId) ?: return
    var controls by remember { mutableStateOf(original.controls) }
    var selected by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    fun update(id: String, f: (ControlDef) -> ControlDef) { controls = controls.map { if (it.id == id) f(it) else it } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Nine.bg).windowInsetsPadding(WindowInsets.displayCutout)) { // no control under the camera cutout (dead to touch); same area in editor and controller
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val d = LocalDensity.current
        // 5% guide grid
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { selected = null } })
        controls.forEach { c ->
            val sel = c.id == selected
            with(d) {
                Box(Modifier.offset((c.x * wPx).toFloat().toDp(), (c.y * hPx).toFloat().toDp())
                    .size((c.w * wPx).toFloat().toDp(), (c.h * hPx).toFloat().toDp())
                    .border(if (sel) 2.dp else 1.dp, if (sel) LocalAccent.current.color else Nine.line2, Nine.small)
                    .semantics { contentDescription = "${c.kind.name.lowercase()} ${c.label ?: c.bind}. Drag to move." }
                    .pointerInput(c.id) {
                        detectTapGestures { selected = c.id }
                    }
                    .pointerInput(c.id + "drag") {
                        detectDragGestures(onDragStart = { selected = c.id }) { ch, drag ->
                            ch.consume()
                            update(c.id) { it.copy(x = snap((it.x + drag.x / wPx).coerceIn(0.0, 1.0 - it.w)), y = snap((it.y + drag.y / hPx).coerceIn(0.0, 1.0 - it.h))) }
                        }
                    }) {
                    ControlVisual(c, Visual(), 0.0)
                    if (sel) Box(Modifier.align(Alignment.BottomEnd).size(28.dp).background(LocalAccent.current.color, Nine.small)
                        .semantics { contentDescription = "Resize handle" }
                        .pointerInput(c.id + "resize") {
                            detectDragGestures { ch, drag ->
                                ch.consume()
                                update(c.id) { it.copy(w = snap((it.w + drag.x / wPx).coerceIn(0.04, 1.0 - it.x)), h = snap((it.h + drag.y / hPx).coerceIn(0.04, 1.0 - it.y))) }
                            }
                        })
                }
            }
        }

        // Toolbar
        Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Nine.s1, Nine.pill).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro(original.name, color = Nine.text, modifier = Modifier.padding(horizontal = 8.dp))
            Box {
                TextButton(onClick = { addMenu = true }) { Micro("Add", color = Nine.text) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    ControlKind.entries.forEach { k ->
                        DropdownMenuItem(text = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = {
                            addMenu = false
                            val id = generateSequence(1) { it + 1 }.map { "${k.name.lowercase()}-$it" }.first { n -> controls.none { it.id == n } }
                            val sq = k == ControlKind.BUTTON || k == ControlKind.STICK || k == ControlKind.DPAD
                            controls = controls + ControlDef(id, k, bindsFor(k).first(), 0.42, 0.4, if (sq) 0.12 else 0.14, if (sq) 0.22 else 0.4)
                            selected = id
                        })
                    }
                }
            }
            TextButton(onClick = { controls = original.controls; selected = null }) { Micro("Reset", color = Nine.text) }
            TextButton(onClick = { nav.popBackStack() }) { Micro("Cancel", color = Nine.text) }
            Button(onClick = {
                try { app.profiles.save(original.copy(controls = controls)); nav.popBackStack() }
                catch (e: ProfileException) { error = e.message }
            }, colors = primaryButtonColors()) { Text("Save") }
        }

        error?.let { Text(it, color = Nine.bad, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) }

        // Properties panel for the selected control
        controls.find { it.id == selected }?.let { c ->
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(260.dp).background(Nine.s1, Nine.card).border(1.dp, Nine.line2, Nine.card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Micro(c.kind.name.lowercase(), color = LocalAccent.current.color)
                var bindMenu by remember(c.id) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { bindMenu = true }) { Text("Action: ${c.bind}", color = Nine.text) }
                    DropdownMenu(bindMenu, { bindMenu = false }) {
                        bindsFor(c.kind).forEach { b -> DropdownMenuItem(text = { Text(b) }, onClick = { bindMenu = false; update(c.id) { it.copy(bind = b) } }) }
                    }
                }
                OutlinedTextField(c.label ?: "", { t -> update(c.id) { it.copy(label = t.take(12).ifEmpty { null }) } }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Micro("Opacity ${(c.opacity * 100).roundToInt()}%")
                Slider(c.opacity.toFloat(), { v -> update(c.id) { it.copy(opacity = v.toDouble()) } }, valueRange = 0.1f..1f)
                if (c.kind == ControlKind.BUTTON) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Toggle (latch)", Modifier.weight(1f)); Switch(c.toggle, { v -> update(c.id) { it.copy(toggle = v) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic click", Modifier.weight(1f)); Switch(c.haptic, { v -> update(c.id) { it.copy(haptic = v) } })
                }
                TextButton(onClick = { controls = controls.filterNot { it.id == c.id }; selected = null }) { Text("Remove control", color = Nine.bad) }
            }
        }
    }
}
