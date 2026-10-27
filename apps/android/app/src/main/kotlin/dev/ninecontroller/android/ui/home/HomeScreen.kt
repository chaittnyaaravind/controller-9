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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import dev.ninecontroller.android.ui.Pill
import dev.ninecontroller.android.ui.theme.Accent
import dev.ninecontroller.android.ui.theme.Grain
import dev.ninecontroller.android.ui.theme.LocalAccent

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
    /** Starts the Bluetooth controller and remembers it as on, but only if it could start. */
    fun startHid() {
        if (app.hid.unavailableReason() != null) return
        app.connection.disconnect(); app.hid.start(); scope.launch { app.settings.update { it.copy(hidMode = true) } }
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) startHid()
    }

    val connected = state as? ConnState.Connected
    val ackAge = if (connected != null && app.connection.lastAckNs > 0) ((now - app.connection.lastAckNs) / 1_000_000).coerceAtLeast(0) else null
    val blePerm = app.connection.ble.unavailableReason()?.takeIf { it.contains("permission", ignoreCase = true) }
    val sum = HomeLogic.summarize(state, stats, ackAge, hosts.isNotEmpty(), blePerm?.let { "Bluetooth permission is off, so the Bluetooth fallback can’t be used." })
    val lastHost = hosts.find { it.id == settings.lastHost } ?: hosts.firstOrNull()
    val preset = settings.padPreset
    val profile = profiles.find { it.id == settings.activeProfile }

    val games by app.games.games.collectAsState()
    val game = games.find { it.id == settings.activeGame } ?: games.firstOrNull()
    val accent = LocalAccent.current
    val hidLive = hidState as? HidGamepad.State.Connected
    // One line that says how this phone reaches the game right now.
    val (linkLine, linkTone) = when {
        hidLive != null -> "Bluetooth controller · ${hidLive.host}" to (if (hidLive.subscribed) Tone.OK else Tone.WARN)
        hidState is HidGamepad.State.Advertising -> "Bluetooth controller · waiting for a computer" to Tone.WARN
        connected != null -> "${connected.hostName} · ${connected.linksLabel}" + (stats.rttP50?.let { " · ${it.toInt()} ms" } ?: "") to sum.tone
        else -> "Not connected" to Tone.IDLE
    }

    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(accent.bg)
        val c = Offset(size.width * 0.85f, size.height * 0.08f)
        drawCircle(Brush.radialGradient(listOf(accent.color.copy(alpha = 0.16f), Color.Transparent), c, size.width * 0.9f), size.width * 0.9f, c)
        drawRect(Grain.brush, alpha = Grain.ALPHA)
    }.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Nine.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Nine.gap)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(28.dp)
                Spacer(Modifier.weight(1f))
                Pill(when (linkTone) { Tone.OK -> "Live"; Tone.WARN -> "Connecting"; Tone.BAD -> "Offline"; Tone.IDLE -> "Idle" }, linkTone, fill = accent.surface)
            }

            // ---------------------------------------------------------------- now playing (the one hero)
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.surface, Nine.s1)), Nine.card)
                .border(1.dp, Brush.verticalGradient(listOf(accent.color.copy(alpha = 0.45f), Nine.line)), Nine.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Now playing", color = accent.color)
                Text(game?.name ?: "Any game", style = NineType.hero, maxLines = 2, modifier = Modifier.semantics { heading() })
                Text((preset?.name ?: profile?.name ?: "No layout") + if (settings.padTilt && preset != null) " · tilt to steer" else "", style = NineType.small, color = Nine.muted)
                if (preset != null) PadDiagram(settings.mappingFor(preset), Modifier.fillMaxWidth().aspectRatio(2.1f).padding(vertical = 10.dp), accent = accent)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    StatusDot(linkTone.color(accent.color), 7.dp)
                    Text(linkLine, style = NineType.small, color = Nine.muted, maxLines = 1)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Play", { nav.navigate("play") }, Modifier.weight(1f))
                    game?.let { g -> SecondaryButton("Edit game", { nav.navigate("game/${g.id}") }) }
                }
            }

            // ---------------------------------------------------------------- games
            Micro("Games", Modifier.padding(start = 4.dp, top = 14.dp).semantics { heading() })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEach { g -> GameCard(g.name, Accent.of(g.accent), g.id == game?.id) { scope.launch { app.play(g) } } }
                NewGameCard("+", "New game") { nav.navigate("game/new") }
                NewGameCard("⌗", "Scan a game") { nav.navigate("games/scan") }
            }

            // ---------------------------------------------------------------- Bluetooth controller (no computer app)
            Section("Bluetooth controller") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val (title, detail, tone) = when (val h = hidState) {
                        HidGamepad.State.Off -> Triple("No app needed on the computer", "Your phone becomes a wireless Xbox controller. Pair it once from the computer’s Bluetooth settings.", Tone.IDLE)
                        HidGamepad.State.Advertising -> Triple("Waiting for a computer", "On the computer, open Bluetooth settings and connect to “Xbox Wireless Controller”. Accept the pairing request on both.", Tone.WARN)
                        is HidGamepad.State.Connected -> Triple("Connected to ${h.host}", if (h.subscribed) "Games see an Xbox Wireless Controller." else "Finishing setup…", if (h.subscribed) Tone.OK else Tone.WARN)
                        is HidGamepad.State.Failed -> Triple("Bluetooth controller unavailable", h.reason, Tone.BAD)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(tone.color(accent.color), 8.dp)
                        Text(title, style = NineType.title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (hidState is HidGamepad.State.Advertising) PairingSteps(accent)
                    else Text(detail, style = NineType.small, color = Nine.muted)
                    if (hidState is HidGamepad.State.Connected) Text("Not responding after this phone’s Bluetooth restarted? Turn the computer’s Bluetooth off and on.", style = NineType.small, color = Nine.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hidState is HidGamepad.State.Off || hidState is HidGamepad.State.Failed) {
                            SecondaryButton("Turn on", {
                                val adapter = app.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                                if (app.hid.unavailableReason()?.contains("permission", ignoreCase = true) == true && Build.VERSION.SDK_INT >= 31)
                                    perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
                                // Bluetooth off: ask Android to turn it on; the mode starts (and is remembered) only if it does.
                                else if (adapter != null && runCatching { !adapter.isEnabled }.getOrDefault(false))
                                    enableBt.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                else {
                                    // The ongoing "controller is on" notification needs this on Android 13+; the controller works without it.
                                    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        perms.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                                    startHid()
                                }
                            })
                        } else SecondaryButton("Turn off", { app.hid.stop(); scope.launch { app.settings.update { it.copy(hidMode = false) } } })
                    }
                }
            }

            // ---------------------------------------------------------------- computer app connection
            Section("Computer app") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (sum.busy && !reduced) CircularProgressIndicator(Modifier.size(14.dp), color = Nine.warn, strokeWidth = 2.dp, trackColor = Nine.line)
                        else StatusDot(sum.tone.color(accent.color), 8.dp)
                        Text(sum.headline, style = NineType.title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    sum.detail?.let { Text(it, style = NineType.small, color = Nine.muted) }
                    sum.health?.let { Text(it, style = NineType.small, color = if (sum.tone == Tone.OK) accent.color else Nine.warn) }
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
                            HomeLogic.Act.PAIR -> SecondaryButton("Pair a computer", { nav.navigate("add") })
                            HomeLogic.Act.CONNECT -> SecondaryButton("Connect", { lastHost?.let { app.connection.connect(it) } }, enabled = lastHost != null)
                            HomeLogic.Act.RECONNECT -> SecondaryButton("Reconnect", { lastHost?.let { app.connection.connect(it) } }, enabled = lastHost != null)
                            HomeLogic.Act.CANCEL -> SecondaryButton("Cancel", { app.connection.disconnect() })
                            HomeLogic.Act.CONFIRM -> SecondaryButton("Open pairing", { nav.navigate("add") })
                            HomeLogic.Act.PLAY_DISCONNECT -> SecondaryButton("Disconnect", { app.connection.disconnect() })
                        }
                    }
                    // Paired but not connected: say why instead of leaving people guessing (replica/fixes.md, F2).
                    if (hosts.isNotEmpty() && sum.act in setOf(HomeLogic.Act.CONNECT, HomeLogic.Act.RECONNECT))
                        SecondaryButton("Why won’t it connect?", { nav.navigate("doctor") })
                    if (blePerm != null && Build.VERSION.SDK_INT >= 31) SecondaryButton("Allow Bluetooth fallback", {
                        perms.launch(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT))
                    })
                }
                if (hosts.isNotEmpty()) {
                    Divider()
                    hosts.forEachIndexed { i, h ->
                        if (i > 0) Divider()
                        val isActive = (state as? ConnState.Connected)?.hostName == h.name
                        ListRow(h.name, detail = if (h.addr.isNotBlank()) "${h.addr}:${h.port}" else "Bluetooth only",
                            onClick = { app.connection.connect(h) },
                            trailing = { Text(if (isActive) "Connected" else if (settings.lastHost == h.id) "Last used" else "Connect", style = NineType.small, color = if (isActive) accent.color else Nine.muted) })
                    }
                }
                Divider(); ListRow(if (hosts.isEmpty()) "Add a computer" else "Add another computer", detail = if (hosts.isEmpty()) "Pair by QR code, six-digit code or Bluetooth" else null, onClick = { nav.navigate("add") })
            }

            // ---------------------------------------------------------------- play with friends
            var friends by remember { androidx.compose.runtime.mutableStateOf(false) }
            Section("Play with friends") {
                ListRow("Add players 2–4", detail = "Friends scan a code to get the app; every phone becomes its own controller", onClick = { friends = true })
            }
            if (friends) androidx.compose.material3.AlertDialog(onDismissRequest = { friends = false }, containerColor = Nine.s1,
                title = { Text("Play with friends") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val img = remember { dev.ninecontroller.android.ui.games.qrImage("https://controller-9.vercel.app/#download") }
                        androidx.compose.foundation.Image(img, "QR code for the Controller-9 download page",
                            Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                        Text("1  Your friend scans this and installs Controller-9.", style = NineType.small, color = Nine.muted)
                        Text("2  Bluetooth: they turn on Bluetooth controller and pair from the computer's Bluetooth settings — each phone is its own Xbox controller.", style = NineType.small, color = Nine.muted)
                        Text("3  Or with the computer app: they pair with it too and join as player 2, 3 or 4.", style = NineType.small, color = Nine.muted)
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { friends = false }) { Text("Done", color = Nine.text) } })

            Section(null) {
                ListRow("Remote", detail = "Trackpad, keyboard and media keys for the computer — no app needed", onClick = { nav.navigate("remote") })
                Divider()
                ListRow("Controller layouts", detail = "Xbox, DualSense, Switch Pro, GameCube, arcade stick and your own", onClick = { nav.navigate("layouts") })
                Divider()
                ListRow("Calibrate steering", detail = settings.steering?.let { c -> if (c.hasRange) "${c.leftDeg!!.toInt()}° left · ${c.rightDeg!!.toInt()}° right" else "Neutral set · range not measured" } ?: "Neutral grip and range", onClick = { nav.navigate("calibrate") })
                Divider()
                ListRow("Settings", detail = "Controls, lock, connection, privacy", onClick = { nav.navigate("settings") })
                Divider()
                ListRow("Advanced diagnostics", detail = "Links, freshness, reconnects", onClick = { nav.navigate("diagnostics") })
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** One game in the rail: its colour as a lit dot, its name; the chosen one carries its colour's rim. */
@Composable
private fun GameCard(name: String, a: Accent, active: Boolean, onClick: () -> Unit) =
    Column(Modifier.width(140.dp).height(96.dp)
        .background(if (active) Brush.verticalGradient(listOf(a.surface, Nine.s1)) else Brush.verticalGradient(listOf(Nine.s1, Nine.s1)), Nine.card)
        .border(1.dp, if (active) a.color.copy(alpha = 0.7f) else Nine.line, Nine.card)
        .semantics { selected = active }
        .clickable(role = Role.Button, onClickLabel = "Play $name", onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(14.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.5f), Color.Transparent), center, size.width * 1.4f), size.width * 1.4f, center)
            drawCircle(a.color, size.width / 2, center)
        })
        Text(name, style = NineType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }

@Composable
private fun NewGameCard(mark: String, label: String, onClick: () -> Unit) =
    Column(Modifier.width(140.dp).height(96.dp).border(1.dp, Nine.line2, Nine.card)
        .clickable(role = Role.Button, onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(mark, style = NineType.title, color = Nine.muted)
        Text(label, style = NineType.small, color = Nine.muted)
    }

/**
 * The steps to pair, shown while the phone waits for a computer. A computer lists the phone twice only
 * while the phone's own Bluetooth settings are open (that screen makes its classic identity visible),
 * and old pairings from earlier tries also stay listed, so both are named here.
 */
@Composable
private fun PairingSteps(accent: Accent) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        "On the computer, open Bluetooth settings.",
        "Choose “Xbox Wireless Controller” and accept the request on both.",
        "Done. Next time it reconnects on its own.",
    ).forEachIndexed { i, step ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("0${i + 1}", style = NineType.mono, color = accent.color, modifier = Modifier.padding(top = 2.dp))
            Text(step, style = NineType.small, color = Nine.muted)
        }
    }
    Text("Seeing it twice? Close this phone’s Bluetooth settings — that screen shows the phone under its own name too. "
        + "Remove older “Xbox Wireless Controller” or phone entries on the computer before pairing again.",
        style = NineType.small, color = Nine.dim)
}
