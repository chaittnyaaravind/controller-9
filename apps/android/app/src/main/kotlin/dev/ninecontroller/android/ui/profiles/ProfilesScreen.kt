package dev.ninecontroller.android.ui.profiles

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.ui.Card
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.primaryButtonColors
import dev.ninecontroller.protocol.Profile
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by app.profiles.profiles.collectAsState()
    val settings by app.settings.flow.collectAsState(AppSettings())
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString().take(256 * 1024) }
            "Imported “${app.profiles.import(text).name}”."
        }.getOrElse { "Couldn’t import: ${it.message}" }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        uri ?: return@rememberLauncherForActivityResult
        message = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(p.toJson().toByteArray()) }; "Exported “${p.name}”." }
            .getOrElse { "Couldn’t export: ${it.message}" }
    }

    Page("Layouts", "Profiles", nav) {
        profiles.forEach { p ->
            Card {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (p.id == settings.activeProfile) Micro("In use", color = LocalAccent.current.color)
                }
                Text("${p.controls.size} controls" + if (p.motion.enabled) " · motion steering (${p.motion.lockDeg.toInt()}° lock)" else "", color = Nine.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (p.id != settings.activeProfile) TextButton(onClick = { scope.launch { app.settings.update { it.copy(activeProfile = p.id) } } }) { Text("Use", color = Nine.text) }
                    TextButton(onClick = { nav.navigate("edit/${p.id}") }) { Text("Edit layout", color = Nine.text) }
                    TextButton(onClick = { nav.navigate("motion/${p.id}") }) { Text("Motion", color = Nine.text) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { app.profiles.duplicate(p) }) { Text("Duplicate", color = Nine.muted) }
                    TextButton(onClick = { renaming = p }) { Text("Rename", color = Nine.muted) }
                    TextButton(onClick = { exporting = p; exporter.launch("${p.id}.controller9.json") }) { Text("Export", color = Nine.muted) }
                    if (profiles.size > 1) TextButton(onClick = { confirmDelete = p }) { Text("Delete", color = Nine.bad) }
                }
            }
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            dev.ninecontroller.android.ui.PrimaryButton("Import", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) })
            dev.ninecontroller.android.ui.SecondaryButton("Restore presets", { app.profiles.installPresets(); message = "Presets restored." })
        }
        message?.let { Text(it, color = Nine.muted) }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename layout") },
            text = { OutlinedTextField(name, { name = it.take(48) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { runCatching { app.profiles.save(p.copy(name = name.trim())) }.onFailure { message = it.message }; renaming = null }, colors = primaryButtonColors()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Nine.text) } }, containerColor = Nine.s1)
    }
    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete “${p.name}”?") }, text = { Text("Export it first if you may want it back.") },
            confirmButton = { Button(onClick = {
                app.profiles.delete(p.id)
                if (settings.activeProfile == p.id) scope.launch { app.settings.update { s -> s.copy(activeProfile = app.profiles.profiles.value.first().id) } }
                confirmDelete = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep", color = Nine.text) } }, containerColor = Nine.s1)
    }
}
