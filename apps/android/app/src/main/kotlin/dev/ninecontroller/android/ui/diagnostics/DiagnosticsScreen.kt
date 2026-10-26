package dev.ninecontroller.android.ui.diagnostics

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ninecontroller.android.ui.fmt
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.Stat
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine

@Composable
fun DiagnosticsScreen(app: NineApp, nav: NavController) {
    val s by app.connection.stats.collectAsState()
    val c by app.connection.state.collectAsState()
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.nanoTime()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); kotlinx.coroutines.delay(250) } }
    fun ago(ns: Long) = if (ns <= 0L || c !is ConnState.Connected) "—" else "${(now - ns) / 1_000_000} ms ago"
    Page("Diagnostics", "Advanced", nav) {
        Section("Session") {
            ListRow("State", detail = when (val st = c) {
                is ConnState.Connected -> "Connected · player ${st.slot + 1} on ${st.hostName}"
                is ConnState.Connecting -> "Connecting to ${st.hostName}"
                is ConnState.Reconnecting -> "Reconnecting · attempt ${st.attempt}: ${st.reason}"
                is ConnState.ConfirmCode -> "Waiting for code confirmation"
                is ConnState.Failed -> "Failed · ${st.message}"
                ConnState.Idle -> "Idle"
            })
            (c as? ConnState.Connected)?.let {
                dev.ninecontroller.android.ui.Divider()
                ListRow(if (it.also.isEmpty()) "Link" else "Links (same input on each)", detail = it.linksLabel)
                it.backendWarning?.let { w -> dev.ninecontroller.android.ui.Divider(); ListRow("Computer", detail = "No virtual controller: $w", detailColor = Nine.warn) }
                dev.ninecontroller.android.ui.Divider()
                ListRow("Connected for", detail = if (app.connection.connectedAtNs > 0) "${(now - app.connection.connectedAtNs) / 1_000_000_000} s" else "—")
            }
            dev.ninecontroller.android.ui.Divider()
            ListRow("Last host reply (freshness)", detail = ago(app.connection.lastAckNs))
            dev.ninecontroller.android.ui.Divider()
            ListRow("Last input frame sent", detail = ago(app.connection.lastSendNs))
            dev.ninecontroller.android.ui.Divider()
            ListRow("Last disconnect reason", detail = s.lastDisconnect ?: "None this session")
        }
        if (s.paths.isNotEmpty()) Section("Per link") {
            s.paths.forEachIndexed { i, p ->
                if (i > 0) dev.ninecontroller.android.ui.Divider()
                ListRow(p.kind.label + if (i == 0) " · fastest" else "", detail = "RTT ${fmt(p.rttP50)} / ${fmt(p.rttP95)} ms · reply ${p.replyAgeMs} ms ago · ${p.detail}" +
                    (if (p.dropped > 0) " · ${p.dropped} dropped under congestion" else ""),
                    detailColor = if (p.replyAgeMs > 1000) Nine.warn else Nine.muted)
            }
        }
        Card {
            Row(Modifier.fillMaxWidth()) { Stat(fmt(s.rttP50), "RTT p50 ms", Modifier.weight(1f)); Stat(fmt(s.rttP95), "RTT p95 ms", Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth()) { Stat(fmt(s.sendHz, 0), "Send Hz", Modifier.weight(1f)); Stat(fmt(s.sensorHz, 0), "Sensor Hz", Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth()) { Stat(s.sent.toString(), "Frames sent", Modifier.weight(1f)); Stat("${s.reconnects}", "Reconnects", Modifier.weight(1f)) }
            Text("Last reconnect took ${s.lastReconnectMs?.let { "$it ms" } ?: "—"}. Watchdog neutralizations: ${s.watchdogTrips}.", color = Nine.dim)
            s.lastSwitch?.let { Text("Last link switch: $it", color = Nine.dim) }
        }
        Section(null) { ListRow("Links", detail = "Usable links, scores and preference", onClick = { nav.navigate("connection") }) }
        Card {
            Micro("What these mean")
            Text("RTT is the encrypted ping round trip between this phone and the computer. It covers the network and host only — not the screen, game or display delay.", color = Nine.dim)
            Text("Send Hz counts input frames per second: changes are sent immediately, plus a heartbeat every 50 ms while you hold a control.", color = Nine.dim)
            Text("Sensor Hz is the measured motion-sensor rate while steering is on.", color = Nine.dim)
        }
    }
}

@androidx.compose.runtime.Composable
fun ConnectionScreen(app: NineApp, nav: NavController) {
    val links by app.connection.links.collectAsState()
    val c by app.connection.state.collectAsState()
    val settings by app.settings.flow.collectAsState(dev.ninecontroller.android.data.AppSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val perms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { }
    Page("How your phone connects", "Connection", nav) {
        Card {
            Micro("Active link")
            Text((c as? ConnState.Connected)?.linksLabel ?: "Not connected", color = Nine.text)
            Text("Controller-9 never needs the Internet. In Automatic it sends the same input over every local link at once; the computer uses the first copy and ignores the rest, so one link dropping doesn’t interrupt play. If every link stops, the computer releases every control within 250 ms.", color = Nine.dim)
        }
        Micro("Links")
        if (links.isEmpty()) Card { Text("Connect to a computer to see link details.", color = Nine.muted) }
        links.forEach { l ->
            Card {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    dev.ninecontroller.android.ui.StatusDot(if (l.available) LocalAccent.current.color else Nine.dim)
                    Text("  ${l.kind.label}", modifier = Modifier.weight(1f), color = Nine.text)
                    Text(l.score?.let { "score ${it.toInt()}" } ?: "unavailable", color = Nine.dim)
                }
                l.reason?.let { Text(it, color = Nine.muted) }
                if (l.attempts > 0) Text("${l.successes}/${l.attempts} recent connects ok" + (l.rttP95?.let { " · RTT p95 ${fmt(it)} ms" } ?: ""), color = Nine.dim)
            }
        }
        Micro("Preferred link")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            dev.ninecontroller.android.net.LinkChoices.all.forEach { o ->
                val label = dev.ninecontroller.android.net.LinkChoices.label(o)
                androidx.compose.material3.FilterChip(selected = settings.linkOverride == o, onClick = { scope.launch { app.settings.update { it.copy(linkOverride = o) } } }, label = { Text(label) })
            }
        }
        Text("A fixed link is used whenever it's available; otherwise the automatic order applies.", color = Nine.dim)
        if (app.connection.ble.unavailableReason() == "Bluetooth permission not granted") {
            androidx.compose.material3.Button(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 31) perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
            }, colors = dev.ninecontroller.android.ui.theme.primaryButtonColors()) { Text("Allow Bluetooth fallback") }
            Text("Lets the computer reach this phone over Bluetooth LE when Wi-Fi is off. Used only while you play.", color = Nine.dim)
        }
    }
}
