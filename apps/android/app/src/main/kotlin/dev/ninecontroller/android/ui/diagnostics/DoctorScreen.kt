package dev.ninecontroller.android.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.PairedHost
import dev.ninecontroller.android.net.ConnectionDoctor
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.StatusDot
import dev.ninecontroller.android.ui.theme.LocalAccent
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType

/** "Why won't it connect?": checks each paired computer and names the cause with its fix (net/ConnectionDoctor.kt). */
@Composable
fun DoctorScreen(app: NineApp, nav: NavController) {
    val hosts by app.hosts.hosts.collectAsState()
    var round by remember { mutableIntStateOf(0) }
    Page("Connection check", "Why won't it connect?", nav) {
        if (hosts.isEmpty()) {
            Text("No computer is paired yet. Pair one first, or use Bluetooth controller mode — it needs no computer app.", style = NineType.small, color = Nine.muted)
            SecondaryButton("Pair a computer", { nav.navigate("add") })
            return@Page
        }
        hosts.forEach { h -> HostCheck(app, h, round) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Check again", { round++ })
            SecondaryButton("Enter the address by hand", { nav.navigate("add") })
        }
        Text("The check only talks to your own computer on your own network.", style = NineType.small, color = Nine.dim)
    }
}

@Composable
private fun HostCheck(app: NineApp, h: PairedHost, round: Int) {
    val context = LocalContext.current
    var finding by remember(h.id, round) { mutableStateOf<ConnectionDoctor.Finding?>(null) }
    LaunchedEffect(h.id, round) { finding = ConnectionDoctor.diagnose(ConnectionDoctor.check(context, h)) }
    val accent = LocalAccent.current
    Section(h.name) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val f = finding
            if (f == null) { Text("Checking ${h.addr.ifBlank { "Bluetooth" }}…", style = NineType.small, color = Nine.muted); return@Column }
            val ok = f.cause == ConnectionDoctor.Cause.REACHABLE
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(if (ok) accent.color else Nine.warn, 8.dp)
                Text(f.title, style = NineType.title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(f.detail, style = NineType.small, color = Nine.muted)
            f.fixes.forEachIndexed { i, fix ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("0${i + 1}", style = NineType.mono, color = accent.color, modifier = Modifier.padding(top = 2.dp))
                    Text(fix, style = NineType.small, color = Nine.text)
                }
            }
            if (ok) SecondaryButton("Connect", { app.connection.connect(h) })
        }
    }
}
