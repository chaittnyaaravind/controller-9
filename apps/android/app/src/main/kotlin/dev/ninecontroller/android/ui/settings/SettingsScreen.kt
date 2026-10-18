package dev.ninecontroller.android.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.navigation.NavController
import dev.ninecontroller.android.BuildConfig
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: NineApp, nav: NavController) {
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    var confirm by remember { mutableStateOf<String?>(null) }
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(f) }

    @Composable fun toggle(label: String, value: Boolean, on: (Boolean) -> Unit) =
        Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, on) }

    Page("Settings", "Controller-9", nav) {
        Card {
            Micro("Feel")
            toggle("Vibration", s.hapticsEnabled) { v -> set { it.copy(hapticsEnabled = v) } }
            if (!app.haptics.available) Text("This phone has no vibration motor.", color = Nine.dim)
            Micro("Vibration strength ${(s.hapticStrength * 100).toInt()}%")
            Slider(s.hapticStrength.toFloat(), { v -> set { it.copy(hapticStrength = v.toDouble()) } }, enabled = s.hapticsEnabled)
            toggle("Keep screen on while playing", s.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            toggle("Show latency on the controller", s.showStats) { v -> set { it.copy(showStats = v) } }
        }
        Card {
            Micro("Motion sensor rate")
            Row { listOf(100, 200).forEach { hz -> TextButton(onClick = { set { it.copy(sensorHz = hz) } }) { Text("$hz Hz", color = if (s.sensorHz == hz) Nine.accent else Nine.muted) } } }
            Text("200 Hz is smoother; 100 Hz uses less battery.", color = Nine.dim)
        }
        Card {
            Micro("Privacy")
            Text("Controller-9 works only on your local network. There is no account, no cloud service and no analytics. Your controller identity never leaves this phone and is excluded from backups.", color = Nine.muted)
            TextButton(onClick = { confirm = "hosts" }) { Text("Forget all computers", color = Nine.bad) }
            TextButton(onClick = { confirm = "identity" }) { Text("Reset controller identity", color = Nine.bad) }
        }
        Card {
            Micro("About")
            Text("Version ${BuildConfig.VERSION_NAME} · protocol 1", color = Nine.muted)
            Text("Open-source components: Kotlin, Jetpack Compose and AndroidX (Apache 2.0), Bouncy Castle (MIT), ZXing (Apache 2.0), kotlinx (Apache 2.0).", color = Nine.dim)
        }
    }

    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (what == "hosts") "Forget all computers?" else "Reset identity?") },
            text = { Text(if (what == "hosts") "You will need to pair again." else "Every computer will treat this phone as new and it must pair again.") },
            confirmButton = { Button(onClick = {
                app.connection.disconnect()
                if (what == "hosts") app.hosts.clear() else { app.identity.reset(); app.hosts.clear() }
                confirm = null
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Nine.text) } }, containerColor = Nine.s1)
    }
}
