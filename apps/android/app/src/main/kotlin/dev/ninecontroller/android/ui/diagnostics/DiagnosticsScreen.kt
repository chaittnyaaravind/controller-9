package dev.ninecontroller.android.ui.diagnostics

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.Stat
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine

@Composable
fun DiagnosticsScreen(app: NineApp, nav: NavController) {
    val s by app.connection.stats.collectAsState()
    val c by app.connection.state.collectAsState()
    Page("Connection", "Diagnostics", nav) {
        Card {
            Micro("State"); Text(c.toString().substringBefore("("), color = Nine.text)
            (c as? ConnState.Connected)?.let { Text("Player ${it.slot + 1} on ${it.hostName}", color = Nine.muted); it.backendWarning?.let { w -> Text(w, color = Nine.warn) } }
            (c as? ConnState.Reconnecting)?.let { Text("Attempt ${it.attempt}: ${it.reason}", color = Nine.warn) }
        }
        Card {
            Row(Modifier.fillMaxWidth()) { Stat(fmt(s.rttP50), "RTT p50 ms", Modifier.weight(1f)); Stat(fmt(s.rttP95), "RTT p95 ms", Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth()) { Stat(fmt(s.sendHz, 0), "Send Hz", Modifier.weight(1f)); Stat(fmt(s.sensorHz, 0), "Sensor Hz", Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth()) { Stat(s.sent.toString(), "Frames sent", Modifier.weight(1f)); Stat("${s.reconnects}", "Reconnects", Modifier.weight(1f)) }
            Text("Last reconnect took ${s.lastReconnectMs?.let { "$it ms" } ?: "—"}.", color = Nine.dim)
        }
        Card {
            Micro("What these mean")
            Text("RTT is the encrypted ping round trip between this phone and the computer. It covers the network and host only — not the screen, game or display delay.", color = Nine.dim)
            Text("Send Hz counts input frames per second: changes are sent immediately, plus a heartbeat every 50 ms while you hold a control.", color = Nine.dim)
            Text("Sensor Hz is the measured motion-sensor rate while steering is on.", color = Nine.dim)
        }
    }
}
