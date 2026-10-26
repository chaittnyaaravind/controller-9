package dev.ninecontroller.android.data

import java.util.Base64

/**
 * A game setup shared phone-to-phone as a QR code (no server): its name, tone, layout, tilt and gyro
 * aim, plus the layout's remaps and button modes. Format `c9game:1:<base64url>`, the payload being
 * seven fields separated by U+001F. Decoding never trusts the input: unknown versions, wrong field
 * counts and over-long values are rejected.
 */
object GameShare {
    const val PREFIX = "c9game:1:"
    private const val SEP = '\u001F'
    private const val MAX = 600

    data class Shared(val game: Game, val mapping: String, val modes: String)

    fun encode(g: Game, mapping: String?, modes: String?): String {
        val fields = listOf(g.name, g.accent, g.layout, if (g.tilt) "1" else "0", if (g.aim) "1" else "0", mapping.orEmpty(), modes.orEmpty())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString(SEP.toString()).toByteArray(Charsets.UTF_8))
    }

    /** The shared setup, or null if [text] isn't a valid game code. [newId] gives the imported game its id. */
    fun decode(text: String, newId: (String) -> String): Shared? {
        if (!text.startsWith(PREFIX) || text.length > 2 * MAX) return null
        val raw = runCatching { String(Base64.getUrlDecoder().decode(text.removePrefix(PREFIX)), Charsets.UTF_8) }.getOrNull() ?: return null
        val f = raw.split(SEP)
        if (f.size != 7 || f.any { it.length > MAX }) return null
        val name = f[0].trim().take(Game.MAX_NAME).ifEmpty { return null }
        val layout = f[2].takeIf { it.matches(Regex("[a-z0-9:_-]{1,64}")) } ?: return null
        val game = Game(newId(name), name, f[1].takeIf { it.matches(Regex("[A-Z]{3,12}")) } ?: "PLATINUM", layout, f[3] == "1", f[4] == "1")
        return Shared(game, f[5], f[6])
    }
}
