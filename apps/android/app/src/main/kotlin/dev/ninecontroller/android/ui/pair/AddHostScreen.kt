package dev.ninecontroller.android.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors
import dev.ninecontroller.protocol.DEFAULT_TCP_PORT
import dev.ninecontroller.protocol.PairUri

@Composable
fun AddHostScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val found by app.discovery.hosts.collectAsState()
    val discoveryError by app.discovery.error.collectAsState()
    val state by app.connection.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "Camera access is needed only to scan the code. You can pair with a code instead."
    }

    DisposableEffect(Unit) { app.discovery.start(); onDispose { app.discovery.stop() } }
    LaunchedEffect(state) { if (state is ConnState.Connected) nav.navigate("home") { popUpTo("home") { inclusive = true } } }

    Page("Add a computer", "Pair", nav) {
        Text("On the computer, open Controller-9 and choose “Pair a phone”.", color = Nine.muted)

        if (scanning) {
            Card {
                QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(Nine.card)) { text ->
                    scanning = false
                    val uri = PairUri.parse(text)
                    if (uri == null) error = "That isn’t a Controller-9 pairing code." else app.connection.pairWithQr(uri)
                }
                TextButton(onClick = { scanning = false }) { Text("Cancel", color = Nine.text) }
            }
        } else {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                else camera.launch(Manifest.permission.CAMERA)
            }, colors = primaryButtonColors(), modifier = Modifier.fillMaxWidth()) { Text("Scan the QR code") }
        }

        Micro("On this network")
        if (found.isEmpty()) Card {
            Text(discoveryError ?: "Looking for computers…", color = Nine.muted)
            Text("Not listed? Some routers block discovery. Use the QR code or the address below.", color = Nine.dim, style = MaterialTheme.typography.bodySmall)
        }
        found.forEach { h ->
            Card(Modifier.clickable(role = Role.Button, onClickLabel = "Pair with ${h.name} using a code") { error = null; app.connection.pairWithCode(h.addr, h.port, h.name) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Text("${h.addr} · ${h.os ?: "computer"}" + (h.freeSlots?.let { " · $it slots" } ?: ""), color = Nine.dim, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Pair with code", color = Nine.muted)
                }
            }
        }

        Micro("Manual address")
        OutlinedTextField(manual, { manual = it.trim() }, label = { Text("192.168.1.20 or 192.168.1.20:47901") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            val (a, p) = manual.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: DEFAULT_TCP_PORT) }
            if (a.isBlank()) error = "Enter the address shown on the computer." else { error = null; app.connection.pairWithCode(a, p, a) }
        }) { Text("Pair with code") }

        when (val s = state) {
            is ConnState.Connecting -> Text("Connecting to ${s.hostName}…", color = Nine.muted)
            is ConnState.Failed -> Text(s.message, color = Nine.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> Unit
        }
        error?.let { Text(it, color = Nine.bad) }
    }

    (state as? ConnState.ConfirmCode)?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Does ${s.hostName} show this code?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.code, fontFamily = FontFamily.Monospace, fontSize = 44.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    Text("Confirm only if both screens match exactly. Then click Allow on the computer.", color = Nine.muted)
                }
            },
            confirmButton = { Button(onClick = { app.connection.confirmCode(true) }, colors = primaryButtonColors()) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = { app.connection.confirmCode(false) }) { Text("They differ", color = Nine.text) } },
            containerColor = Nine.s1,
            modifier = Modifier.border(1.dp, Nine.line2, MaterialTheme.shapes.extraLarge),
        )
    }
}
