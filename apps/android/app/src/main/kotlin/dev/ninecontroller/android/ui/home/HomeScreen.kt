package dev.ninecontroller.android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.Stat
import dev.ninecontroller.android.ui.StatusDot
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.ButtonPadding
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors

@Composable
fun HomeScreen(app: NineApp, nav: NavController) {
    val state by app.connection.state.collectAsState()
    val stats by app.connection.stats.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()
    val profiles by app.profiles.profiles.collectAsState()
    val settings by app.settings.flow.collectAsState(AppSettings())
    val active = profiles.find { it.id == settings.activeProfile } ?: profiles.firstOrNull()

    Page("Your controller", "Controller-9", null) {
        Card {
            val (label, color) = when (val s = state) {
                is ConnState.Connected -> "Player ${s.slot + 1} on ${s.hostName} · ${s.link.label}" to (if (s.backendWarning == null) Nine.accent else Nine.warn)
                is ConnState.Connecting -> "Connecting to ${s.hostName}${if (s.via.isNotEmpty()) " via ${s.via}" else ""}…" to Nine.warn
                is ConnState.Reconnecting -> "Reconnecting to ${s.hostName}…" to Nine.warn
                is ConnState.ConfirmCode -> "Confirm the code" to Nine.warn
                is ConnState.Failed -> s.message to Nine.bad
                ConnState.Idle -> (if (hosts.isEmpty()) "No computer paired yet" else "Not connected") to Nine.dim
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(color); Text(label, style = MaterialTheme.typography.titleMedium)
            }
            (state as? ConnState.Connected)?.backendWarning?.let {
                Text("The computer receives input but can’t create a game controller yet: $it", color = Nine.warn, style = MaterialTheme.typography.bodySmall)
            }
            if (state is ConnState.Connected) Row(Modifier.fillMaxWidth()) {
                Stat(fmt(stats.rttP50), "RTT ms", Modifier.weight(1f))
                Stat(fmt(stats.rttP95), "p95 ms", Modifier.weight(1f))
                Stat(fmt(stats.sendHz, 0), "Send Hz", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { nav.navigate("play") }, enabled = active != null, colors = primaryButtonColors(), contentPadding = ButtonPadding) { Text("Play") }
                if (state is ConnState.Connected || state is ConnState.Reconnecting) OutlinedButton(onClick = { app.connection.disconnect() }) { Text("Disconnect") }
            }
        }

        Micro("Computers")
        if (hosts.isEmpty()) {
            Card {
                Text("Pair with the computer you want to play on. Open Controller-9 on the computer and choose “Pair a phone”.", color = Nine.muted)
                Button(onClick = { nav.navigate("add") }, colors = primaryButtonColors()) { Text("Add a computer") }
            }
        } else {
            hosts.forEach { h ->
                Card(Modifier.clickable(role = Role.Button, onClickLabel = "Connect to ${h.name}") { app.connection.connect(h) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium)
                            Text("${h.addr}:${h.port}", fontFamily = Nine.mono, color = Nine.dim, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (settings.lastHost == h.id) "Last used" else "Connect", color = Nine.muted)
                    }
                }
            }
            TextButton(onClick = { nav.navigate("add") }) { Text("Add another computer", color = Nine.text) }
        }

        Micro("Layout")
        Card(Modifier.clickable(role = Role.Button) { nav.navigate("profiles") }) {
            Text(active?.name ?: "No layout", style = MaterialTheme.typography.titleMedium)
            Text(active?.let { "${it.controls.size} controls" + if (it.motion.enabled) " · motion steering" else "" } ?: "", color = Nine.muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { nav.navigate("connection") }) { Text("Connection", color = Nine.text) }
            TextButton(onClick = { nav.navigate("diagnostics") }) { Text("Diagnostics", color = Nine.text) }
            TextButton(onClick = { nav.navigate("settings") }) { Text("Settings", color = Nine.text) }
        }
    }
}
