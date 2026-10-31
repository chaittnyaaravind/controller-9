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
import dev.ninecontroller.android.net.HidGamepad
import dev.ninecontroller.android.ui.Divider
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.StatusDot
import dev.ninecontroller.android.ui.Tone
import dev.ninecontroller.android.ui.Wordmark
import dev.ninecontroller.android.ui.color
import dev.ninecontroller.android.ui.layouts.mappingFor
import dev.ninecontroller.android.ui.pad.PadDiagram
import dev.ninecontroller.android.ui.theme.LocalReducedMotion
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.layout
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

/**
 * Settings hub, one tap from the controller. The phone *is* the controller, so this screen is only about
 * that: is the computer connected, which game, and the controller's options.
 */
@Composable
fun HomeScreen(app: NineApp, nav: NavController) {
    val hidState by app.hid.state.collectAsState()
    val settings by app.settings.flow.collectAsState(AppSettings())
    val scope = rememberCoroutineScope()
    val xboxStart = dev.ninecontroller.android.ui.controller.rememberXboxStart(app)
    val games by app.games.games.collectAsState()
    val game = games.find { it.id == settings.activeGame } ?: games.firstOrNull()
    val accent = LocalAccent.current
    val preset = settings.padPreset
    val profiles by app.profiles.profiles.collectAsState()
    val profile = profiles.find { it.id == settings.activeProfile }

    val (title, detail, tone) = when (val h = hidState) {
        HidGamepad.State.Off -> Triple("Controller is off", "Turn it on and pair once from the computer\u2019s Bluetooth settings.", Tone.IDLE)
        HidGamepad.State.Advertising -> Triple("Waiting for a computer", null, Tone.WARN)
        is HidGamepad.State.Connected -> if (h.subscribed) Triple("Connected", "Games on ${h.host} see an Xbox Wireless Controller.", Tone.OK)
            else Triple("Finishing pairing\u2026", "Accept the request on the computer and on this phone if asked.", Tone.WARN)
        is HidGamepad.State.Failed -> Triple("Controller unavailable", h.reason, Tone.BAD)
    }

    Box(Modifier.fillMaxSize().drawBehind {
        // Matte, like the controller: no coloured light.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF111113), Color(0xFF0A0A0B)), 0f, size.height))
        drawRect(Grain.brush, alpha = Grain.ALPHA)
    }.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Nine.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Nine.gap)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(28.dp)
                Spacer(Modifier.weight(1f))
                Pill(when (tone) { Tone.OK -> "Connected"; Tone.WARN -> "Pairing"; Tone.BAD -> "Off"; Tone.IDLE -> "Off" }, tone)
            }

            // ---------------------------------------------------------------- the controller (the one hero)
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF1A1B1E), Nine.s1)), Nine.card)
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Nine.line)), Nine.card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Xbox controller", color = Nine.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tone.color(Nine.text), 10.dp)
                    Text(title, style = NineType.hero, maxLines = 2, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                }
                Text((game?.name ?: "Any game") + " \u00b7 " + (preset?.name ?: profile?.name ?: "No layout"), style = NineType.small, color = Nine.muted)
                if (preset != null) PadDiagram(settings.mappingFor(preset), Modifier.align(Alignment.CenterHorizontally).widthIn(max = 460.dp).fillMaxWidth().aspectRatio(2.1f).padding(vertical = 10.dp), accent = accent) // a picture, not the controller: never larger than a real pad
                if (hidState is HidGamepad.State.Advertising) PairingSteps()
                else detail?.let { Text(it, style = NineType.small, color = Nine.muted) }
                if (hidState is HidGamepad.State.Connected) Text("Stopped responding after an update or a phone restart? Turn the computer\u2019s Bluetooth off and on.", style = NineType.small, color = Nine.dim)
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Play", { if (!nav.popBackStack("play", false)) nav.navigate("play") })
                    if (hidState is HidGamepad.State.Off || hidState is HidGamepad.State.Failed) SecondaryButton("Turn on", { xboxStart() })
                    else SecondaryButton("Turn off", { app.hid.stop(); scope.launch { app.settings.update { it.copy(hidMode = false) } } })
                }
            }

            // ---------------------------------------------------------------- games
            Micro("Games", Modifier.padding(start = 4.dp, top = 14.dp).semantics { heading() })
            // The rail runs to the screen edges (cards scroll out of view, not into a margin); it starts on the gutter.
            Row(Modifier.bleed(Nine.gutter).horizontalScroll(rememberScrollState()).padding(horizontal = Nine.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEach { g -> GameCard(g.name, Accent.of(g.accent), g.id == game?.id) { scope.launch { app.play(g) } } }
                NewGameCard("+", "New game") { nav.navigate("game/new") }
                NewGameCard("\u2317", "Scan a game") { nav.navigate("games/scan") }
            }
            game?.let { g -> ListRowCard("Game settings", "${g.name} \u00b7 layout, steering, gyro aim, turbo") { nav.navigate("game/${g.id}") } }

            Section("Controller") {
                ListRow("Layouts", detail = "Xbox, DualSense, Switch Pro, GameCube, arcade stick \u2014 or move and resize your own", onClick = { nav.navigate("layouts") })
                Divider()
                ListRow("Controls & feel", detail = "Click strength, gyro aim, stick dead zone, Controller Lock", onClick = { nav.navigate("settings") })
                Divider()
                ListRow("Calibrate steering", detail = settings.steering?.let { c -> if (c.hasRange) "${c.leftDeg!!.toInt()}\u00b0 left \u00b7 ${c.rightDeg!!.toInt()}\u00b0 right" else "Neutral set \u00b7 range not measured" } ?: "Neutral grip and range", onClick = { nav.navigate("calibrate") })
                Divider()
                ListRow("Remote", detail = "Trackpad, keyboard and media keys for the computer", onClick = { nav.navigate("remote") })
            }
            Text("Controller-9 ${BuildConfigVersion.name(app)} \u00b7 no account, no internet, nothing installed on the computer.", style = NineType.small, color = Nine.dim,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}

/** A single tappable row in its own card. */
@Composable
private fun ListRowCard(title: String, detail: String, onClick: () -> Unit) =
    Column(Modifier.fillMaxWidth().background(Nine.s1, Nine.card).border(1.dp, Nine.line, Nine.card)) { ListRow(title, detail = detail, onClick = onClick) }

/** The installed version name, for the footer. */
private object BuildConfigVersion {
    fun name(app: NineApp): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
}

/** Widens a child of a padded column by [by] on each side, so it reaches the screen edges. */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp) = layout { m, c ->
    val extra = (by * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

/** One game in the rail: its colour as a lit dot, its name; the chosen one carries its colour's rim. */
@Composable
private fun GameCard(name: String, a: Accent, active: Boolean, onClick: () -> Unit) =
    Column(Modifier.width(140.dp).height(96.dp).clickable(role = Role.Button, onClickLabel = "Play $name", onClick = onClick) // before the fill: the whole card dips
        .background(if (active) Brush.verticalGradient(listOf(a.surface, Nine.s1)) else Brush.verticalGradient(listOf(Nine.s1, Nine.s1)), Nine.card)
        .border(1.dp, if (active) a.color.copy(alpha = 0.7f) else Nine.line, Nine.card)
        .semantics { selected = active }.padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(14.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.5f), Color.Transparent), center, size.width * 1.4f), size.width * 1.4f, center)
            drawCircle(a.color, size.width / 2, center)
        })
        Text(name, style = NineType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }

@Composable
private fun NewGameCard(mark: String, label: String, onClick: () -> Unit) =
    Column(Modifier.width(140.dp).height(96.dp).clickable(role = Role.Button, onClick = onClick).border(1.dp, Nine.line2, Nine.card).padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(mark, style = NineType.title, color = Nine.muted)
        Text(label, style = NineType.small, color = Nine.muted)
    }

/**
 * The steps to pair, shown while the phone waits for a computer. A computer lists the phone twice only
 * while the phone's own Bluetooth settings are open (that screen makes its classic identity visible),
 * and old pairings from earlier tries also stay listed, so both are named here.
 */
@Composable
private fun PairingSteps() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        "On the computer, open Bluetooth settings.",
        "Choose “Xbox Wireless Controller” and accept the request on both.",
        "Done. Next time it reconnects on its own.",
    ).forEachIndexed { i, step ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("0${i + 1}", style = NineType.mono, color = Nine.text, modifier = Modifier.padding(top = 2.dp))
            Text(step, style = NineType.small, color = Nine.muted)
        }
    }
    Text("Seeing it twice? Close this phone’s Bluetooth settings — that screen shows the phone under its own name too. "
        + "Remove older “Xbox Wireless Controller” or phone entries on the computer before pairing again.",
        style = NineType.small, color = Nine.dim)
}
