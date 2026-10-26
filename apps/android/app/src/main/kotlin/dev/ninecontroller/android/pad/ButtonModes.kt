package dev.ninecontroller.android.pad

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * How a button behaves when held, per element of a preset:
 * - [Mode.TURBO] fires repeatedly while held (rapid fire, skipping dialogue);
 * - [Mode.TOGGLE] latches: tap to hold, tap again to let go (long holds, one-handed play).
 * Stored per preset as `elementId=turbo;elementId=toggle` (AppSettings.padModes).
 */
object ButtonModes {
    enum class Mode(val id: String, val title: String, val detail: String) {
        NORMAL("normal", "Normal", "Pressed while your thumb is on it"),
        TURBO("turbo", "Turbo", "Fires about 12 times a second while held"),
        TOGGLE("toggle", "Toggle", "Tap to hold, tap again to let go"),
    }

    fun decode(text: String?): Map<String, Mode> =
        text.orEmpty().split(';').mapNotNull { part ->
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            Mode.entries.firstOrNull { it.id == v && it != Mode.NORMAL }?.let { k.trim() to it }
        }.filter { it.first.isNotEmpty() }.toMap()

    fun encode(modes: Map<String, Mode>): String =
        modes.filterValues { it != Mode.NORMAL }.toSortedMap().entries.joinToString(";") { (k, v) -> "$k=${v.id}" }

    /** Turbo period: 40 ms down, 40 ms up — every press spans several frames on every link. */
    const val TURBO_HALF_PERIOD_MS = 40L
}

/** Drives turbo buttons: [set] is called with the button's mask, then 0, alternately, until stopped. */
class TurboPulser(private val set: (id: String, mask: Int) -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "c9-turbo").apply { isDaemon = true } }
    private val running = HashMap<String, ScheduledFuture<*>>()

    @Synchronized fun start(id: String, mask: Int) {
        if (running.containsKey(id)) return
        var down = false
        running[id] = exec.scheduleAtFixedRate({ down = !down; set(id, if (down) mask else 0) }, 0, ButtonModes.TURBO_HALF_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    /** Release runs on the pulse thread, after any tick already in flight, so nothing stays held. */
    @Synchronized fun stop(id: String) { running.remove(id)?.cancel(false); exec.execute { set(id, 0) } }

    @Synchronized fun stopAll() { running.keys.toList().forEach(::stop) }
}
