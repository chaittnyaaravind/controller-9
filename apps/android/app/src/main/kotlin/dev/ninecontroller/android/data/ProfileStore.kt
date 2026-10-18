package dev.ninecontroller.android.data

import android.content.Context
import dev.ninecontroller.protocol.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Profiles are plain JSON files (schema 1) in app storage. Presets ship as assets (shared with
 * iOS from protocol/profiles) and are copied on first run; user edits never touch the assets.
 */
class ProfileStore(private val context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val _profiles = MutableStateFlow(emptyList<Profile>())
    val profiles: StateFlow<List<Profile>> = _profiles

    init {
        if (dir.listFiles().isNullOrEmpty()) installPresets()
        reload()
    }

    fun installPresets() {
        for (name in context.assets.list("")?.filter { it.endsWith(".json") }.orEmpty()) {
            val text = context.assets.open(name).bufferedReader().use { it.readText() }
            runCatching { Profile.fromJson(text) }.onSuccess { write(it) }
        }
    }

    private fun reload() {
        _profiles.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { Profile.fromJson(f.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    private fun write(p: Profile) {
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(p.toJson())
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    /** Validates before saving; throws ProfileException with a user-readable message. */
    fun save(p: Profile) { p.validate(); write(p); reload() }

    fun delete(id: String) { File(dir, "$id.json").delete(); reload() }

    fun get(id: String) = _profiles.value.find { it.id == id }

    fun uniqueId(base: String): String {
        val clean = base.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").trim('-').take(56).ifEmpty { "profile" }
        var id = clean; var n = 2
        while (get(id) != null) id = "$clean-${n++}"
        return id
    }

    /** Import: parse (migrating older schemas), give it a non-colliding id, save. */
    fun import(text: String): Profile {
        val p = Profile.fromJson(text)
        val q = if (get(p.id) != null) p.copy(id = uniqueId(p.id), name = "${p.name} (imported)".take(48)) else p
        save(q)
        return q
    }

    fun duplicate(p: Profile): Profile = p.copy(id = uniqueId(p.id + "-copy"), name = "${p.name} copy".take(48)).also { save(it) }
}
