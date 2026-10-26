package dev.ninecontroller.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A game the player sets up once: its colour lights the app, and choosing it switches to its
 * controller layout and steering. [layout] is an AppSettings.activeProfile value (`pad:xbox`,
 * a custom profile id, …); [accent] is a ui.theme.Accent name.
 */
data class Game(val id: String, val name: String, val accent: String, val layout: String, val tilt: Boolean = false, val aim: Boolean = false) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("accent", accent).put("layout", layout).put("tilt", tilt).put("aim", aim)

    companion object {
        fun fromJson(o: JSONObject) = Game(
            o.getString("id"), o.getString("name").take(MAX_NAME), o.optString("accent", "PLATINUM"),
            o.optString("layout", "pad:xbox"), o.optBoolean("tilt", false), o.optBoolean("aim", false),
        )
        const val MAX_NAME = 32
    }
}

/** Games as one small JSON file in app storage (files/games.json), written atomically. */
class GameStore(context: Context) {
    private val file = File(context.filesDir, "games.json")
    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<Game>> = _games

    fun get(id: String?) = _games.value.find { it.id == id }

    fun save(g: Game) {
        val list = _games.value.toMutableList()
        val i = list.indexOfFirst { it.id == g.id }
        if (i >= 0) list[i] = g else list += g
        write(list)
    }

    fun delete(id: String) = write(_games.value.filterNot { it.id == id })

    fun uniqueId(name: String): String {
        val base = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").trim('-').take(40).ifEmpty { "game" }
        var id = base; var n = 2
        while (get(id) != null) id = "$base-${n++}"
        return id
    }

    private fun load(): List<Game> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { Game.fromJson(a.getJSONObject(it)) }
    }.getOrNull() ?: STARTERS

    private fun write(list: List<Game>) {
        val tmp = File(file.parentFile, "games.json.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(file)
        _games.value = list
    }

    companion object {
        const val ANY = "any"
        /** Shipped on first run; the player edits or deletes them like their own. */
        val STARTERS = listOf(
            Game(ANY, "Any game", "PLATINUM", "pad:xbox"),
            Game("asphalt-legends", "Asphalt Legends", "CRIMSON", "pad:xbox"),
            Game("go-kart-racing", "Go Kart Racing", "CHAMPAGNE", "pad:xbox"),
        )
    }
}
