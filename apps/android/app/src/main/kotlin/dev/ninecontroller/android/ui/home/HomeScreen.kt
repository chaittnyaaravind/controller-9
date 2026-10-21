package dev.ninecontroller.android.ui.home

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.net.HidGamepad
import dev.ninecontroller.android.net.LinkStatsUi
import dev.ninecontroller.android.ui.Divider
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.Metric
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.StatusDot
import dev.ninecontroller.android.ui.Tone
import dev.ninecontroller.android.ui.Wordmark
import dev.ninecontroller.android.ui.color
import dev.ninecontroller.android.ui.fmt
import dev.ninecontroller.android.ui.layouts.mappingFor
import dev.ninecontroller.android.ui.pad.PadDiagram
import dev.ninecontroller.android.ui.theme.LocalReducedMotion
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import dev.ninecontroller.protocol.LinkKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** What the connection panel says and offers. Pure; see HomeLogicTest. */
object HomeLogic {
    enum class Act { PAIR, CONNECT, CANCEL, PLAY_DISCONNECT, RECONNECT, CONFIRM }

    data class Summary(val headline: String, val detail: String?, val tone: Tone, val act: Act, val busy: Boolean, val health: String?)

    /** Replies older than this mean the link is not healthy, even though the session still exists. */
    fun staleAfterMs(link: LinkKind) = if (link == LinkKind.BLE) 2000L else 1000L

    fun summarize(state: ConnState, stats: LinkStatsUi, ackAgeMs: Long?, hasHosts: Boolean, permissionProblem: String?): Summary = when (state) {
        is ConnState.Connected -> {
            val stale = ackAgeMs != null && ackAgeMs > staleAfterMs(state.link)
            val health = when {
                ackAgeMs == null -> null
                stale -> "No reply for ${"%.1f".format(ackAgeMs / 1000.0)} s — reconnecting if it continues"
                else -> "Healthy · last reply ${ackAgeMs} ms ago"
            }
            Summary(
                headline = "Connected to ${state.hostName}",
                detail = "Player ${state.slot + 1} · ${state.linksLabel}" + (state.linkDetail.takeIf { it.isNotBlank() && state.also.isEmpty() }?.let { " · $it" } ?: ""),
                tone = if (stale || state.backendWarning != null) Tone.WARN else Tone.OK,
                act = Act.PLAY_DISCONNECT, busy = false, health = health,
            )
        }
        is ConnState.Connecting -> Summary("Connecting to ${state.hostName}", state.via.takeIf { it.isNotEmpty() }?.let { "Trying $it" }, Tone.WARN, Act.CANCEL, true, null)
        is ConnState.Reconnecting -> Summary("Reconnecting to ${state.hostName}", "Attempt ${state.attempt} · ${state.reason}", Tone.WARN, Act.CANCEL, true, stats.lastDisconnect?.let { "Lost: $it" })
        is ConnState.ConfirmCode -> Summary("Confirm the pairing code", "Check that ${state.hostName} shows ${state.code}", Tone.WARN, Act.CONFIRM, false, null)
        is ConnState.Failed -> Summary("Not connected", state.message, Tone.BAD, if (hasHosts) Act.RECONNECT else Act.PAIR, false, null)
        ConnState.Idle -> when {
            !hasHosts -> Summary("No computer paired", "Open Controller-9 on your computer and choose “Pair a phone”.", Tone.IDLE, Act.PAIR, false, null)
            permissionProblem != null -> Summary("Not connected", permissionProblem, Tone.IDLE, Act.CONNECT, false, stats.lastDisconnect?.let { "Last: $it" })
            else -> Summary("Not connected", stats.lastDisconnect?.let { "Last session: $it" } ?: "Ready to connect", Tone.IDLE, Act.CONNECT, false, null)
        }
    }
}

@Composable
fun HomeScreen(app: NineApp, nav: NavController) {
    val state by app.connection.state.collectAsState()
    val hidState by app.hid.state.collectAsState()
    val stats by app.connection.stats.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()
    val profiles by app.profiles.profiles.collectAsState()
    val settings by app.settings.flow.collectAsState(AppSettings())
    val reduced = LocalReducedMotion.current
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) { now = System.nanoTime(); delay(250) } }
    val scope = rememberCoroutineScope()
    // Bluetooth controller mode survives app restarts: the paired computer reconnects on its own.
    LaunchedEffect(Unit) {
        if (app.settings.flow.first().hidMode && !app.hid.active && app.hid.unavailableReason() == null) { app.connection.disconnect(); app.hid.start() }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    val connected = state as? ConnState.Connected
    val ackAge = if (connected != null && app.connection.lastAckNs > 0) ((now - app.connection.lastAckNs) / 1_000_000).coerceAtLeast(0) else null
    val blePerm = app.connection.ble.unavailableReason()?.takeIf { it.contains("permission", ignoreCase = true) }
    val sum = HomeLogic.summarize(state, stats, ackAge, hosts.isNotEmpty(), blePerm?.let { "Bluetooth permission is off, so the Bluetooth fallback can’t be used." })
    val lastHost = hosts.find { it.id == settings.lastHost } ?: hosts.firstOrNull()
    val preset = settings.padPreset
    val profile = profiles.find { it.id == settings.activeProfile }

    Box(Modifier.fillMaxSize().background(Nine.bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Nine.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Nine.gap)) {
            Wordmark(30.dp, Modifier.padding(top = 4.dp, bottom = 14.dp))

            // ---------------------------------------------------------------- connection panel
            Column(Modifier.fillMaxWidth().background(Nine.s1, Nine.card).border(1.dp, if (sum.tone == Tone.OK) Nine.accent.copy(alpha = 0.35f) else Nine.line, Nine.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (sum.busy && !reduced) CircularProgressIndicator(Modifier.size(14.dp), color = Nine.warn, strokeWidth = 2.dp, trackColor = Nine.line)
                    else StatusDot(sum.tone.color(), 9.dp)
                    Micro(when (sum.tone) { Tone.OK -> "Live"; Tone.WARN -> if (sum.busy) "Working" else "Attention"; Tone.BAD -> "Offline"; Tone.IDLE -> "Idle" }, color = sum.tone.color())
                }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(sum.headline, style = NineType.display.copy(fontSize = NineType.title.fontSize * 1.25f, lineHeight = NineType.title.lineHeight * 1.2f), modifier = Modifier.semantics { heading() })
                    sum.detail?.let { Text(it, style = NineType.body, color = Nine.muted) }
                    sum.health?.let { Text(it, style = NineType.small, color = if (sum.tone == Tone.OK) Nine.accent else Nine.warn) }
                }
                connected?.backendWarning?.let {
                    Column(Modifier.fillMaxWidth().background(Nine.warnSoft, Nine.small).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Games on ${connected.hostName} can’t see a controller yet", style = NineType.small, color = Nine.warn)
                        Text("Input reaches the computer, but it can’t create a virtual game controller: $it", style = NineType.small, color = Nine.muted)
                    }
                }
                if (connected != null) Row(Modifier.fillMaxWidth()) {
                    Metric(fmt(stats.rttP50, 0), "Latency ms", Modifier.weight(1f))
                    Metric(fmt(stats.rttP95, 0), "p95 ms", Modifier.weight(1f))
                    Metric(fmt(stats.sendHz, 0), "Updates/s", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (sum.act) {
                        HomeLogic.Act.PAIR -> PrimaryButton("Pair a computer", { nav.navigate("add") })
                        HomeLogic.Act.CONNECT -> PrimaryButton("Connect", { lastHost?.let { app.connection.connect(it) } }, enabled = lastHost != null)
                        HomeLogic.Act.RECONNECT -> PrimaryButton("Reconnect", { lastHost?.let { app.connection.connect(it) } }, enabled = lastHost != null)
                        HomeLogic.Act.CANCEL -> SecondaryButton("Cancel", { app.connection.disconnect() })
                        HomeLogic.Act.CONFIRM -> PrimaryButton("Open pairing", { nav.navigate("add") })
                        HomeLogic.Act.PLAY_DISCONNECT -> { PrimaryButton("Play", { nav.navigate("play") }); SecondaryButton("Disconnect", { app.connection.disconnect() }) }
                    }
                    if (sum.act == HomeLogic.Act.CONNECT || sum.act == HomeLogic.Act.RECONNECT) SecondaryButton("Play", { nav.navigate("play") })
                }
                if (blePerm != null && Build.VERSION.SDK_INT >= 31) SecondaryButton("Allow Bluetooth fallback", {
                    perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
                })
            }

            // ---------------------------------------------------------------- Bluetooth controller (no computer app)
            Section("Bluetooth controller") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (title, detail, tone) = when (val h = hidState) {
                        HidGamepad.State.Off -> Triple("No app needed on the computer", "Your phone becomes a wireless controller. Pair it once from the computer’s Bluetooth settings.", Tone.IDLE)
                        HidGamepad.State.Advertising -> Triple("Waiting for a computer", "On the computer, open Bluetooth settings and connect to this phone. Accept the pairing request on both.", Tone.WARN)
                        is HidGamepad.State.Connected -> Triple("Controller connected to ${h.host}", if (h.subscribed) "Games see an Xbox Wireless Controller." else "Finishing setup…", if (h.subscribed) Tone.OK else Tone.WARN)
                        is HidGamepad.State.Failed -> Triple("Bluetooth controller unavailable", h.reason, Tone.BAD)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(tone.color(), 9.dp)
                        Text(title, style = NineType.title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Text(detail, style = NineType.small, color = Nine.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hidState is HidGamepad.State.Off || hidState is HidGamepad.State.Failed) {
                            PrimaryButton("Start", {
                                if (app.hid.unavailableReason()?.contains("permission", ignoreCase = true) == true && Build.VERSION.SDK_INT >= 31)
                                    perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
                                else { app.connection.disconnect(); app.hid.start(); scope.launch { app.settings.update { it.copy(hidMode = true) } } }
                            })
                        } else {
                            PrimaryButton("Play", { nav.navigate("play") })
                            SecondaryButton("Stop", { app.hid.stop(); scope.launch { app.settings.update { it.copy(hidMode = false) } } })
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- layout
            Section("Layout") {
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change layout") { nav.navigate("layouts") }.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (preset != null) PadDiagram(settings.mappingFor(preset), Modifier.fillMaxWidth().aspectRatio(2.2f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset?.name ?: profile?.name ?: "No layout", style = NineType.title)
                            Text(preset?.tagline ?: profile?.let { "Custom profile · ${it.controls.size} controls" + if (it.motion.enabled) " · tilt steering" else "" } ?: "", style = NineType.small, color = Nine.muted)
                        }
                        Text("Change", style = NineType.small, color = Nine.text)
                    }
                }
            }

            // ---------------------------------------------------------------- computers
            Section("Computers") {
                if (hosts.isEmpty()) ListRow("Add a computer", detail = "Pair by QR code, six-digit code or Bluetooth", onClick = { nav.navigate("add") })
                hosts.forEachIndexed { i, h ->
                    if (i > 0) Divider()
                    val isActive = (state as? ConnState.Connected)?.hostName == h.name
                    ListRow(h.name, detail = if (h.addr.isNotBlank()) "${h.addr}:${h.port}" else "Bluetooth only",
                        onClick = { app.connection.connect(h) },
                        trailing = { Text(if (isActive) "Connected" else if (settings.lastHost == h.id) "Last used" else "Connect", style = NineType.small, color = if (isActive) Nine.accent else Nine.muted) })
                }
                if (hosts.isNotEmpty()) { Divider(); ListRow("Add another computer", onClick = { nav.navigate("add") }) }
            }

            Section(null) {
                ListRow("Calibrate steering", detail = settings.steering?.let { c -> if (c.hasRange) "${c.leftDeg!!.toInt()}° left · ${c.rightDeg!!.toInt()}° right" else "Neutral set · range not measured" } ?: "Neutral grip and range", onClick = { nav.navigate("calibrate") })
                Divider()
                ListRow("Settings", detail = "Controls, connection, privacy", onClick = { nav.navigate("settings") })
                Divider()
                ListRow("Advanced diagnostics", detail = "Links, freshness, reconnects", onClick = { nav.navigate("diagnostics") })
            }
        }
    }
}
