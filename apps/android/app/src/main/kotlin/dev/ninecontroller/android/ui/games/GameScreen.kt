package dev.ninecontroller.android.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.Game
import dev.ninecontroller.android.data.GameStore
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.Choice
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.layouts.mappingFor
import dev.ninecontroller.android.ui.pad.PadDiagram
import dev.ninecontroller.android.ui.theme.Accent
import dev.ninecontroller.android.ui.theme.LocalAccent
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import dev.ninecontroller.android.data.AppSettings
import kotlinx.coroutines.launch

/**
 * Set up one game: name, colour, controller layout, tilt steering. The screen previews the
 * colour live, so the player sees the controller they'll get before saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameScreen(app: NineApp, nav: NavController, id: String) {
    val existing = remember(id) { app.games.get(id) }
    val settings by app.settings.flow.collectAsState(AppSettings())
    val profiles by app.profiles.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var accent by remember { mutableStateOf(Accent.of(existing?.accent ?: Accent.CHAMPAGNE.name)) }
    var layout by remember { mutableStateOf(existing?.layout ?: "pad:xbox") }
    var tilt by remember { mutableStateOf(existing?.tilt ?: false) }
    var aim by remember { mutableStateOf(existing?.aim ?: false) }
    val preset = PadPresets.fromSelection(layout)

    CompositionLocalProvider(LocalAccent provides accent) {
        Page(if (existing == null) "New game" else existing.name, "Game", nav) {
            // Live preview: the controller in this game's light.
            Box(Modifier.fillMaxWidth().background(accent.surface, Nine.card).border(1.dp, accent.color.copy(alpha = 0.28f), Nine.card).padding(16.dp)) {
                if (preset != null) PadDiagram(settings.mappingFor(preset), Modifier.fillMaxWidth().aspectRatio(2.1f), accent = accent)
                else Text(profiles.find { it.id == layout }?.name ?: "Custom layout", style = NineType.title, modifier = Modifier.padding(24.dp))
            }

            Section("Name") {
                OutlinedTextField(name, { name = it.take(Game.MAX_NAME) }, singleLine = true, placeholder = { Text("Asphalt Legends", color = Nine.dim) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp), textStyle = NineType.body, shape = Nine.small,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent.color, unfocusedBorderColor = Nine.line2, cursorColor = accent.color, focusedTextColor = Nine.text, unfocusedTextColor = Nine.text))
            }

            Section("Colour") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Accent.entries.forEach { a ->
                        val on = a == accent
                        Box(Modifier.size(44.dp).semantics { contentDescription = a.label; selected = on }
                            .border(2.dp, if (on) a.color else androidx.compose.ui.graphics.Color.Transparent, CircleShape).padding(5.dp)
                            .background(a.color, CircleShape).clickable(role = Role.RadioButton) { accent = a })
                    }
                }
            }

            Section("Controller layout") {
                FlowRow(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadPresets.all.forEach { p -> val sel = PadPresets.selection(p); Choice(p.name.removeSuffix("-style"), layout == sel, { layout = sel }) }
                    profiles.forEach { p -> Choice(p.name, layout == p.id, { layout = p.id }) }
                }
            }

            Section(null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tilt to steer", style = NineType.body)
                        Text("Turn the phone like a wheel; it moves the left stick.", style = NineType.small, color = Nine.muted)
                    }
                    Switch(tilt, { tilt = it }, enabled = preset != null,
                        colors = SwitchDefaults.colors(checkedTrackColor = accent.color, checkedThumbColor = accent.ink, uncheckedTrackColor = Nine.s3, uncheckedThumbColor = Nine.muted, uncheckedBorderColor = Nine.line2))
                }
                dev.ninecontroller.android.ui.Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gyro aim", style = NineType.body)
                        Text("Turn the phone to aim, like a DualSense or Switch Pro. Adds to the right stick.", style = NineType.small, color = Nine.muted)
                    }
                    Switch(aim, { aim = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent.color, checkedThumbColor = accent.ink, uncheckedTrackColor = Nine.s3, uncheckedThumbColor = Nine.muted, uncheckedBorderColor = Nine.line2))
                }
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (existing == null) "Save and play" else "Save", {
                    val g = Game(existing?.id ?: app.games.uniqueId(name), name.trim().ifEmpty { "My game" }, accent.name, layout, tilt && preset != null, aim)
                    app.games.save(g)
                    scope.launch { if (existing == null || settings.activeGame == g.id) app.play(g); nav.popBackStack() }
                })
                if (existing != null && existing.id != GameStore.ANY) SecondaryButton("Delete", {
                    app.games.delete(existing.id)
                    scope.launch {
                        if (settings.activeGame == existing.id) app.games.get(GameStore.ANY)?.let { app.play(it) }
                        nav.popBackStack()
                    }
                }, color = Nine.bad)
            }
            Micro("Colour lights the whole app while this game is chosen.", Modifier.padding(top = 4.dp), color = Nine.dim)
        }
    }
}
