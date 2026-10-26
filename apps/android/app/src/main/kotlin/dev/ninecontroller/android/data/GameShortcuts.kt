package dev.ninecontroller.android.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ninecontroller.android.MainActivity
import dev.ninecontroller.android.R

/**
 * Long-press the app icon → "Play Asphalt Legends": one launcher shortcut per game (the launcher shows
 * up to four), each opening that game's controller directly. Kept in step with the game list.
 */
object GameShortcuts {
    const val EXTRA_GAME = "c9_play_game"

    fun publish(context: Context, games: List<Game>, activeId: String) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        val ordered = games.sortedByDescending { it.id == activeId }.take(max)
        val shortcuts = ordered.mapIndexed { rank, g ->
            ShortcutInfoCompat.Builder(context, "game-${g.id}")
                .setShortLabel("Play ${g.name}".take(25))
                .setLongLabel("Play ${g.name}")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_glass_foreground))
                .setRank(rank)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_GAME, g.id))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
