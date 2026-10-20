package dev.ninecontroller.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.BuildConfig
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.ui.Choice
import dev.ninecontroller.android.ui.Divider
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import dev.ninecontroller.protocol.LinkKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: NineApp, nav: NavController) {
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    var confirm by remember { mutableStateOf<String?>(null) }
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(f) }

    Page("Settings", "Controller-9", nav) {
        Section("Controls", footer = "Stick and tilt settings apply to the five controller layouts. Custom profiles keep their own (Layouts → Manage profiles).") {
            SliderRow("Stick dead zone", "${(s.stickDeadzone * 100).toInt()}%", s.stickDeadzone.toFloat(), 0f..0.3f) { v -> set { it.copy(stickDeadzone = v.toDouble()) } }
            Divider()
            ToggleRow("Tilt steering", "Turn the phone like a wheel to move the left stick", s.padTilt) { v -> set { it.copy(padTilt = v) } }
            if (s.padTilt) {
                Divider()
                SliderRow("Tilt sensitivity", "%.2f×".format(s.tiltSensitivity), s.tiltSensitivity.toFloat(), 0.25f..3f) { v -> set { it.copy(tiltSensitivity = v.toDouble()) } }
                Divider()
                SliderRow("Tilt dead zone", "${(s.tiltDeadzone * 100).toInt()}%", s.tiltDeadzone.toFloat(), 0f..0.2f) { v -> set { it.copy(tiltDeadzone = v.toDouble()) } }
                Divider()
                SliderRow("Steering lock", "${s.tiltLockDeg.toInt()}°", s.tiltLockDeg.toFloat(), 10f..90f) { v -> set { it.copy(tiltLockDeg = v.toDouble()) } }
            }
            Divider()
            ListRow("Calibrate steering", detail = s.steering?.let { c -> if (c.hasRange) "Your grip · ${c.leftDeg!!.toInt()}° left, ${c.rightDeg!!.toInt()}° right" else "Your grip · default range" } ?: if (s.legacySteering) "Old calibration — measure again" else "Not measured yet",
                onClick = { nav.navigate("calibrate") })
            Divider()
            ListRow("Layouts", detail = s.padPreset?.name ?: "Custom profile", onClick = { nav.navigate("layouts") })
        }

        Section("Connection", footer = "Automatic sends every input over Wi-Fi and Bluetooth LE at once, so the computer uses whichever arrives first and the controller keeps working if one link drops. One link uses a single link at a time and switches when it fails. A fixed link is for troubleshooting. USB is never used for input in Automatic.") {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dev.ninecontroller.android.net.LinkChoices.all.forEach { o ->
                    Choice(dev.ninecontroller.android.net.LinkChoices.label(o), s.linkOverride == o, { set { it.copy(linkOverride = o) } })
                }
            }
        }

        Section("Feel") {
            ToggleRow("Vibration", if (app.haptics.available) "Button clicks and game rumble (heavy and light motor, like a console pad)" else "This phone has no vibration motor", s.hapticsEnabled) { v -> set { it.copy(hapticsEnabled = v) } }
            if (s.hapticsEnabled) {
                Divider(); SliderRow("Vibration strength", "${(s.hapticStrength * 100).toInt()}%", s.hapticStrength.toFloat(), 0f..1f) { v -> set { it.copy(hapticStrength = v.toDouble()) } }
                Divider(); ToggleRow("Trigger and steering feel", "A click at the pedal’s resistance point and at full press, a light detent at steering center and a bump at full lock. Made on this phone, not by the game.", s.feelEffects) { v -> set { it.copy(feelEffects = v) } }
            }
            Divider()
            ToggleRow("Keep screen on while playing", null, s.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            Divider()
            ToggleRow("Show latency on the controller", null, s.showStats) { v -> set { it.copy(showStats = v) } }
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motion sensor rate", style = NineType.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(100, 200).forEach { hz -> Choice("$hz Hz", s.sensorHz == hz, { set { it.copy(sensorHz = hz) } }) } }
                Text("200 Hz is smoother; 100 Hz uses less battery.", style = NineType.small, color = Nine.dim)
            }
        }

        Section("Advanced") {
            ListRow("Diagnostics", detail = "Latency, freshness, reconnects, disconnect reasons", onClick = { nav.navigate("diagnostics") })
            Divider()
            ListRow("Links", detail = "Which links are usable and why", onClick = { nav.navigate("connection") })
        }

        Section("Privacy", footer = "Controller-9 works only on your local network or Bluetooth. No account, no cloud service, no analytics. Your controller identity never leaves this phone and is excluded from backups.") {
            ListRow("Forget all computers", detailColor = Nine.bad, onClick = { confirm = "hosts" }, trailing = { Text("Forget", style = NineType.small, color = Nine.bad) })
            Divider()
            ListRow("Reset controller identity", onClick = { confirm = "identity" }, trailing = { Text("Reset", style = NineType.small, color = Nine.bad) })
        }

        Section("About") {
            ListRow("Controller-9 ${BuildConfig.VERSION_NAME}", detail = "Protocol 1 · Kotlin, Jetpack Compose and AndroidX (Apache 2.0), Bouncy Castle (MIT), ZXing (Apache 2.0), kotlinx (Apache 2.0)")
        }
    }

    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (what == "hosts") "Forget all computers?" else "Reset identity?") },
            text = { Text(if (what == "hosts") "You will need to pair again." else "Every computer will treat this phone as new and it must pair again.") },
            confirmButton = { PrimaryButton("Confirm", {
                app.connection.disconnect()
                if (what == "hosts") app.hosts.clear() else { app.identity.reset(); app.hosts.clear() }
                confirm = null
            }) },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Nine.text) } }, containerColor = Nine.s1)
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, value: Boolean, on: (Boolean) -> Unit) =
    ListRow(title, detail = detail, trailing = {
        Switch(value, on, colors = SwitchDefaults.colors(checkedTrackColor = Nine.accent, checkedThumbColor = Nine.accentInk, uncheckedTrackColor = Nine.s3, uncheckedThumbColor = Nine.muted, uncheckedBorderColor = Nine.line2))
    })

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, on: (Float) -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = NineType.body, modifier = Modifier.weight(1f))
            Text(value, style = NineType.mono, color = Nine.muted)
        }
        Slider(v.coerceIn(range.start, range.endInclusive), on, valueRange = range, modifier = Modifier.heightIn(min = Nine.target).semantics { contentDescription = title; stateDescription = value },
            colors = SliderDefaults.colors(thumbColor = Nine.text, activeTrackColor = Nine.accent, inactiveTrackColor = Nine.s3))
    }
