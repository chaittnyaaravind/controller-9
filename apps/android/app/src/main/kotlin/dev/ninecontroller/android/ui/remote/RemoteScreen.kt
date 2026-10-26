package dev.ninecontroller.android.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.input.RemoteHidReport as R
import dev.ninecontroller.android.net.HidGamepad
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.theme.LocalAccent
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The phone as a trackpad, keyboard and media remote for the paired computer, over the same
 * Bluetooth controller link — nothing installed on the computer (input/RemoteHidReport.kt).
 */
@Composable
fun RemoteScreen(app: NineApp, nav: NavController) {
    val s by app.settings.flow.collectAsState(AppSettings())
    val hid by app.hid.state.collectAsState()
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val live = hid is HidGamepad.State.Connected && s.remoteKeys

    Page("Remote", "Trackpad · keys · media", nav) {
        if (!s.remoteKeys) {
            Section(null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Turn on remote keys", style = NineType.title)
                    Text("Your phone also becomes a trackpad, keyboard and media keys for the computer it’s paired with — no app on the computer. "
                        + "The computer must forget the controller and pair it again once, because the controller changes what it reports.", style = NineType.small, color = Nine.muted)
                    PrimaryButton("Turn on", {
                        scope.launch {
                            app.settings.update { it.copy(remoteKeys = true) }
                            app.hid.remoteKeys = true
                            if (app.hid.active) { app.hid.stop(); app.hid.start(); app.settings.update { it.copy(hidMode = true) } }
                        }
                    })
                }
            }
            return@Page
        }
        if (!live) Text("Connect the Bluetooth controller to use the remote (Home → Bluetooth controller).", style = NineType.small, color = Nine.warn)

        // ---------------------------------------------------------------- trackpad
        Micro("Trackpad", Modifier.padding(start = 4.dp, top = 4.dp))
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(accent.surface, Nine.s1)), Nine.card)
            .border(1.dp, accent.color.copy(alpha = 0.3f), Nine.card)
            .semantics { contentDescription = "Trackpad. Drag to move, tap to click, two fingers to scroll, hold to right-click." }
            .pointerInput(live) { if (live) trackpad(app.hid) },
            contentAlignment = Alignment.Center) {
            Text("Drag · tap · two-finger scroll · hold for right-click", style = NineType.small, color = Nine.dim)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Left click", Modifier.weight(1f)) { app.hid.sendMouse(1, 0, 0); app.hid.sendMouse(0, 0, 0) }
            Key("Right click", Modifier.weight(1f)) { app.hid.sendMouse(2, 0, 0); app.hid.sendMouse(0, 0, 0) }
        }

        // ---------------------------------------------------------------- media
        Micro("Media", Modifier.padding(start = 4.dp, top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("⏮", desc = "Previous track") { app.hid.tapConsumer(R.PREVIOUS) }
            Key("⏯", desc = "Play or pause", primary = true) { app.hid.tapConsumer(R.PLAY_PAUSE) }
            Key("⏭", desc = "Next track") { app.hid.tapConsumer(R.NEXT) }
            Key("−", desc = "Volume down") { app.hid.tapConsumer(R.VOLUME_DOWN) }
            Key("Mute", desc = "Mute") { app.hid.tapConsumer(R.MUTE) }
            Key("+", desc = "Volume up") { app.hid.tapConsumer(R.VOLUME_UP) }
        }

        // ---------------------------------------------------------------- keys
        Micro("Keys", Modifier.padding(start = 4.dp, top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Esc") { app.hid.tapKey(0, R.ESC) }
            Key("Tab") { app.hid.tapKey(0, R.TAB) }
            Key("↑", desc = "Up arrow") { app.hid.tapKey(0, R.UP) }
            Key("Enter") { app.hid.tapKey(0, R.ENTER) }
            Key("⌫", desc = "Delete") { app.hid.tapKey(0, R.BACKSPACE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("⌘ Space", desc = "Spotlight") { app.hid.tapKey(R.GUI, R.SPACE) }
            Key("←", desc = "Left arrow") { app.hid.tapKey(0, R.LEFT) }
            Key("↓", desc = "Down arrow") { app.hid.tapKey(0, R.DOWN) }
            Key("→", desc = "Right arrow") { app.hid.tapKey(0, R.RIGHT) }
            Key("⌘ Tab", desc = "Switch app") { app.hid.tapKey(R.GUI, R.TAB) }
        }

        // ---------------------------------------------------------------- typing
        var text by remember { mutableStateOf("") }
        Micro("Type", Modifier.padding(start = 4.dp, top = 10.dp))
        OutlinedTextField(text, { new ->
            // Everything typed goes straight to the computer; the field stays empty.
            new.forEach { c -> R.keyFor(c)?.let { (mod, key) -> app.hid.tapKey(mod, key) } }
            text = ""
        }, Modifier.fillMaxWidth(), placeholder = { Text("Type here to type on the computer", color = Nine.dim) }, enabled = live,
            textStyle = NineType.body, shape = Nine.small,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.color, unfocusedBorderColor = Nine.line2, cursorColor = accent.color, focusedTextColor = Nine.text, unfocusedTextColor = Nine.text))
        Text("US keyboard layout. Media keys work on macOS, Windows, Android TV and most TVs.", style = NineType.small, color = Nine.dim)
    }
}

@Composable
private fun RowScope.Key(label: String, modifier: Modifier = Modifier.weight(1f), desc: String = label, primary: Boolean = false, onTap: () -> Unit) {
    val a = LocalAccent.current
    Box(modifier.heightIn(min = 52.dp)
        .background(if (primary) a.color else Nine.s2, Nine.small)
        .border(1.dp, if (primary) a.color else Nine.line2, Nine.small)
        .semantics { contentDescription = desc }
        .clickable(role = Role.Button, onClick = onTap), contentAlignment = Alignment.Center) {
        Text(label, style = NineType.small, color = if (primary) a.ink else Nine.text, maxLines = 1)
    }
}

/**
 * Trackpad gestures: one finger moves (with acceleration), a quick tap clicks, a still hold
 * right-clicks, two fingers scroll. Movement is sent at most every 8 ms with the remainder kept,
 * so slow drags still move and fast ones don't flood the link.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackpad(hid: HidGamepad) = awaitEachGesture {
    val down = awaitFirstDown()
    val t0 = down.uptimeMillis
    var moved = 0f
    var fingers = 1
    var accX = 0f; var accY = 0f; var accW = 0f
    var lastSend = t0
    var rightClicked = false
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        fingers = maxOf(fingers, pressed.size)
        val dx = pressed.map { it.position.x - it.previousPosition.x }.average().toFloat()
        val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
        moved += hypot(dx, dy)
        ev.changes.forEach { it.consume() }
        if (pressed.size >= 2) accW += -dy / 14f
        else {
            val speed = hypot(dx, dy)
            val gain = 0.9f + (speed / 18f).coerceAtMost(2.2f) // faster swipes travel further
            accX += dx * gain; accY += dy * gain
        }
        val now = ev.changes.first().uptimeMillis
        if (!rightClicked && fingers == 1 && moved < 12f && now - t0 > 550) { hid.sendMouse(2, 0, 0); hid.sendMouse(0, 0, 0); rightClicked = true }
        if (now - lastSend >= 8) {
            val sx = accX.roundToInt().coerceIn(-127, 127); val sy = accY.roundToInt().coerceIn(-127, 127); val sw = accW.roundToInt().coerceIn(-127, 127)
            if (sx != 0 || sy != 0 || sw != 0) { hid.sendMouse(0, sx, sy, sw); accX -= sx; accY -= sy; accW -= sw; lastSend = now }
        }
    }
    val up = currentEvent.changes.first().uptimeMillis
    if (!rightClicked && moved < 12f && up - t0 < 250) {
        if (fingers >= 2) { hid.sendMouse(2, 0, 0); hid.sendMouse(0, 0, 0) } // two-finger tap: right-click, like a Mac trackpad
        else { hid.sendMouse(1, 0, 0); hid.sendMouse(0, 0, 0) }
    }
    if (abs(accX) >= 1 || abs(accY) >= 1) hid.sendMouse(0, accX.roundToInt(), accY.roundToInt())
}
