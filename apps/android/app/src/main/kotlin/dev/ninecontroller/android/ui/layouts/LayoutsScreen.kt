package dev.ninecontroller.android.ui.layouts

import dev.ninecontroller.android.ui.theme.LocalAccent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.MappingException
import dev.ninecontroller.android.pad.PadElement
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPreset
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.pad.Part
import dev.ninecontroller.android.ui.Divider
import dev.ninecontroller.android.ui.ListRow
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.SecondaryButton
import dev.ninecontroller.android.ui.Section
import dev.ninecontroller.android.ui.pad.PadDiagram
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import kotlinx.coroutines.launch

/** Mapping for a preset as stored in settings (invalid stored text falls back to defaults). */
fun AppSettings.mappingFor(p: PadPreset): PadMapping = PadMapping.decode(p, padMappings[p.id]).first

@Composable
fun LayoutsScreen(app: NineApp, nav: NavController) {
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    val profiles by app.profiles.profiles.collectAsState()

    Page("Layouts", "Controller", nav) {
        Text("Interface layouts only: they change where controls sit and how they are labelled. Every layout sends the same protocol-v1 gamepad input — no hardware is emulated.",
            style = NineType.small, color = Nine.muted)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = if (maxWidth > 560.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(Nine.gap)) {
                PadPresets.all.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Nine.gap)) {
                        row.forEach { p ->
                            PresetCard(p, s.mappingFor(p), active = s.activeProfile == PadPresets.selection(p), Modifier.weight(1f),
                                onUse = { scope.launch { app.settings.update { it.copy(activeProfile = PadPresets.selection(p)) } } },
                                onOpen = { nav.navigate("layout/${p.id}") })
                        }
                        if (row.size < cols) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        Section("Custom profiles", footer = "Profiles keep their own free-form layout, analog pedals and motion settings (for example Racing).") {
            profiles.forEachIndexed { i, p ->
                if (i > 0) Divider()
                val active = s.activeProfile == p.id
                ListRow(p.name, detail = "${p.controls.size} controls" + (if (p.motion.enabled) " · tilt steering" else "") + (if (active) " · active" else ""),
                    detailColor = if (active) LocalAccent.current.color else Nine.muted,
                    onClick = { scope.launch { app.settings.update { it.copy(activeProfile = p.id) } } })
            }
            Divider()
            ListRow("Manage profiles", detail = "Edit, import, export, motion", onClick = { nav.navigate("profiles") })
        }
    }
}

@Composable
private fun PresetCard(p: PadPreset, mapping: PadMapping, active: Boolean, modifier: Modifier, onUse: () -> Unit, onOpen: () -> Unit) {
    Column(modifier
        .semantics(mergeDescendants = false) { selected = active; contentDescription = "${p.name}${if (active) ", active" else ""}" }
        .background(Nine.s1, Nine.card).border(1.dp, if (active) LocalAccent.current.color.copy(alpha = 0.55f) else Nine.line, Nine.card)
        .clickable(role = Role.Button, onClickLabel = "Show details", onClick = onOpen).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PadDiagram(mapping, Modifier.fillMaxWidth().aspectRatio(2.1f), accent = LocalAccent.current)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = NineType.title)
                Text(p.tagline + if (!mapping.isDefault) " · remapped" else "", style = NineType.small, color = Nine.muted)
            }
            if (active) Micro("Active", color = LocalAccent.current.color)
            else TextButton(onClick = onUse, modifier = Modifier.heightIn(min = Nine.target)) { Text("Use", color = Nine.text) }
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
fun LayoutDetailScreen(app: NineApp, nav: NavController, presetId: String) {
    val p = PadPresets.byId(presetId) ?: run { nav.popBackStack(); return }
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(AppSettings())
    val mapping = s.mappingFor(p)
    val active = s.activeProfile == PadPresets.selection(p)
    var editing by remember { mutableStateOf<PadElement?>(null) }
    var focus by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(m: PadMapping) = scope.launch { app.settings.update { it.copy(padMappings = it.padMappings + (p.id to m.encode())) } }

    Page(p.name, if (active) "Active layout" else "Layout", nav) {
        PadDiagram(mapping, Modifier.fillMaxWidth().aspectRatio(2f).background(Nine.bg), highlight = focus, accent = LocalAccent.current)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) Text("This is the layout on your controller screen.", style = NineType.small, color = LocalAccent.current.color, modifier = Modifier.weight(1f).padding(top = 12.dp))
            else PrimaryButton("Use this layout", { scope.launch { app.settings.update { it.copy(activeProfile = PadPresets.selection(p)) } } })
            SecondaryButton("Restore defaults", { save(mapping.reset()); error = null }, enabled = !mapping.isDefault)
        }
        error?.let { Text(it, color = Nine.bad, style = NineType.small) }

        val groups = listOf(
            "Face buttons" to listOf(Part.FACE),
            "Shoulders" to listOf(Part.TRIGGER, Part.BUMPER),
            "Sticks and D-pad" to listOf(Part.STICK, Part.STICK_PRESS, Part.DPAD),
            "Centre" to listOf(Part.CENTER, Part.TOUCHPAD),
            "Extra" to listOf(Part.PADDLE),
        )
        for ((title, parts) in groups) {
            val items = p.elements.filter { it.part in parts }
            if (items.isEmpty()) continue
            Section(title) {
                items.forEachIndexed { i, e ->
                    if (i > 0) Divider()
                    val a = mapping.actionOf(e)
                    val changed = a != e.default
                    ListRow(
                        e.label,
                        detail = a?.let { "Sends ${it.title} · ${it.wire}" + if (changed) " · remapped" else "" } ?: (e.unavailable ?: "Sends nothing"),
                        detailColor = when { a == null -> Nine.dim; changed -> LocalAccent.current.color; else -> Nine.muted },
                        onClick = if (e.remappable) ({ focus = e.id; editing = e }) else ({ focus = e.id }),
                        trailing = if (e.remappable) ({ Text("Change", style = NineType.small, color = Nine.text) }) else ({ }),
                    )
                }
            }
        }
        Section("About this layout") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                p.notes.forEach { Text("· $it", style = NineType.small, color = Nine.muted) }
                Text("· Face buttons map by position (bottom, right, left, top), so changing layout never changes what a position sends.", style = NineType.small, color = Nine.muted)
            }
        }
    }

    editing?.let { e ->
        val choices = mapping.choices(e)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (e.part == Part.PADDLE) "A paddle mirrors a button that is already on this layout." else "Picking a button another control already sends swaps the two, so nothing is lost or doubled.",
                        style = NineType.small, color = Nine.muted, modifier = Modifier.padding(bottom = 8.dp))
                    choices.forEach { a ->
                        val sel = mapping.actionOf(e) == a
                        Row(Modifier.fillMaxWidth().heightIn(min = Nine.target).selectable(sel, role = Role.RadioButton) {
                            try { save(mapping.assign(e.id, a)); error = null } catch (x: MappingException) { error = x.message }
                            editing = null
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(sel, null, colors = RadioButtonDefaults.colors(selectedColor = LocalAccent.current.color, unselectedColor = Nine.dim))
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(a?.title ?: "Unassigned", style = NineType.body)
                                Text(a?.let { holderLabel(mapping, it, e) } ?: "Sends nothing", style = NineType.small, color = Nine.dim)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Close", color = Nine.text) } },
            containerColor = Nine.s1,
        )
    }
}

private fun holderLabel(m: PadMapping, a: Action, self: PadElement): String {
    val holder = m.preset.elements.firstOrNull { it.id != self.id && it.part != Part.PADDLE && m.actionOf(it) == a }
    return a.wire + (holder?.let { " · now on ${it.label}" } ?: "")
}
