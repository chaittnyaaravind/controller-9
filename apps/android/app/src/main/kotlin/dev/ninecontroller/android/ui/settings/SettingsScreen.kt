package dev.ninecontroller.android.ui.settings

import dev.ninecontroller.android.ui.theme.LocalAccent
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: NineApp, nav: NavController) {
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(f) }

    Page("Controls & feel", "Settings", nav) {
        Section("Controls", footer = "Stick and tilt settings apply to the five controller layouts. Custom profiles keep their own (Layouts → Manage profiles).") {
            SliderRow("Stick dead zone", "${(s.stickDeadzone * 100).toInt()}%", s.stickDeadzone.toFloat(), 0f..0.3f) { v -> set { it.copy(stickDeadzone = v.toDouble()) } }
            Divider()
            ToggleRow("Tilt steering", "Turn the phone like a wheel to move the left stick", s.padTilt) { v -> set { it.copy(padTilt = v) } }
            Divider(); ToggleRow("Gyro aim", "Turn the phone to aim: its turn speed is added to the right stick", s.gyroAim) { v -> set { it.copy(gyroAim = v) } }
            if (s.gyroAim) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low" to 0.7, "Medium" to 1.2, "High" to 2.0, "Max" to 3.0).forEach { (label, v) ->
                        dev.ninecontroller.android.ui.Choice(label, kotlin.math.abs(s.aimSensitivity - v) < 0.01, { set { it.copy(aimSensitivity = v) } })
                    }
                }
                ToggleRow("Invert aim up/down", null, s.aimInvertY) { v -> set { it.copy(aimInvertY = v) } }
            }
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

        if (android.os.Build.VERSION.SDK_INT >= 33) Section("Quick Settings") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ListRow("Add to Quick Settings", detail = "Turn the controller on or off from the notification panel", onClick = { requestTile(ctx) })
        }

        Section("Feel") {
            ToggleRow("Vibration", if (app.haptics.available) "Button clicks and game rumble (heavy and light motor, like a console pad)" else "This phone has no vibration motor", s.hapticsEnabled) { v -> set { it.copy(hapticsEnabled = v) } }
            if (s.hapticsEnabled) {
                Divider(); SliderRow("Vibration strength", "${(s.hapticStrength * 100).toInt()}%", s.hapticStrength.toFloat(), 0f..1f) { v -> set { it.copy(hapticStrength = v.toDouble()) } }
                Divider(); ToggleRow("Trigger and steering feel", "A click at the pedal’s resistance point and at full press, a light detent at steering center and a bump at full lock. Made on this phone, not by the game.", s.feelEffects) { v -> set { it.copy(feelEffects = v) } }
            }
        }

        Section("While playing") {
            ToggleRow("Silence notifications", if (dev.ninecontroller.android.ui.controller.ControllerQuiet.allowed(app)) "Only alarms can interrupt while the controller is open. Your usual setting comes back when you leave."
                else "Needs Do Not Disturb access — tap to allow it once in Android settings.", s.quietPlay && dev.ninecontroller.android.ui.controller.ControllerQuiet.allowed(app)) { v ->
                if (v && !dev.ninecontroller.android.ui.controller.ControllerQuiet.allowed(app)) dev.ninecontroller.android.ui.controller.ControllerQuiet.requestAccess(app)
                set { it.copy(quietPlay = v) }
            }
            Divider()
            ToggleRow("Lock the controller", "No notification panel, home or back swipes during a game. Android asks you to confirm “Pin” once; hold LOCKED on the controller to unlock.", s.lockOnPlay) { v -> set { it.copy(lockOnPlay = v) } }
            Divider()
            ToggleRow("Keep the screen on", null, s.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            Divider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motion sensor rate", style = NineType.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(100, 200).forEach { hz -> Choice("$hz Hz", s.sensorHz == hz, { set { it.copy(sensorHz = hz) } }) } }
                Text("200 Hz is smoother; 100 Hz uses less battery.", style = NineType.small, color = Nine.dim)
            }
        }

        Section("Privacy", footer = "Controller-9 talks only to the computer it is paired with over Bluetooth. No account, no internet, no analytics.") {
            ListRow("Paired computers", detail = "Managed in this phone\u2019s Bluetooth settings", onClick = {
                app.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }

        Section("About") {
            ListRow("Controller-9 ${BuildConfig.VERSION_NAME}", detail = "Kotlin, Jetpack Compose and AndroidX (Apache 2.0), Bouncy Castle (MIT), ZXing (Apache 2.0), kotlinx (Apache 2.0)")
        }
    }

}

@Composable
private fun ToggleRow(title: String, detail: String?, value: Boolean, on: (Boolean) -> Unit) =
    ListRow(title, detail = detail, trailing = {
        Switch(value, on, colors = SwitchDefaults.colors(checkedTrackColor = dev.ninecontroller.android.ui.theme.LocalAccent.current.color, checkedThumbColor = dev.ninecontroller.android.ui.theme.LocalAccent.current.ink, uncheckedTrackColor = Nine.s3, uncheckedThumbColor = Nine.muted, uncheckedBorderColor = Nine.line2))
    })

@Composable
private fun SliderRow(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, on: (Float) -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = NineType.body, modifier = Modifier.weight(1f))
            Text(value, style = NineType.mono, color = Nine.muted)
        }
        Slider(v.coerceIn(range.start, range.endInclusive), on, valueRange = range, modifier = Modifier.heightIn(min = Nine.target).semantics { contentDescription = title; stateDescription = value },
            colors = SliderDefaults.colors(thumbColor = Nine.text, activeTrackColor = LocalAccent.current.color, inactiveTrackColor = Nine.s3))
    }

/** Asks Android to add the Controller tile (net/ControllerTile.kt) to Quick Settings; the system shows its own prompt. */
@androidx.annotation.RequiresApi(33)
private fun requestTile(ctx: android.content.Context) {
    val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(android.content.ComponentName(ctx, dev.ninecontroller.android.net.ControllerTile::class.java), "Controller",
        android.graphics.drawable.Icon.createWithResource(ctx, dev.ninecontroller.android.R.drawable.c9_mark_small), ctx.mainExecutor) { }
}
