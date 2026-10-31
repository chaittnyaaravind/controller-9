package dev.ninecontroller.android.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Button clicks, game rumble and controller "feel" (docs/HAPTICS.md). Strength 0..1 scales amplitude;
 * disabled = no vibration at all.
 *
 * Game rumble arrives as two motor magnitudes, like a console pad: `low` drives the heavy, slow motor
 * and `high` the light, fast one. A phone has one actuator, so [RumbleWaveform] renders both on it:
 * the heavy motor as a 25 Hz pulse (felt as a deep rumble), the light motor as a steady buzz, mixed by
 * taking the stronger at each step.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }
    @Volatile var enabled = true
    @Volatile var strength = 0.7
    /** Trigger resistance points and steering detents (local; not from the game). */
    @Volatile var feel = true
    val available get() = vibrator?.hasVibrator() == true
    private val amplitudeControl get() = vibrator?.hasAmplitudeControl() == true
    /** Last game rumble received: (low, high, ms), for diagnostics. */
    @Volatile var lastRumble: Triple<Int, Int, Int>? = null; private set
    @Volatile private var rumbleUntilNs = 0L

    /** A button going down: a crisp, short click like a real switch (not the soft UI tick). */
    fun click() = press(down = true)

    /** The same button coming back up: a lighter click, so a press feels like a switch with travel. */
    fun release() = press(down = false)

    private fun press(down: Boolean) {
        val v = vibrator ?: return
        if (!enabled || strength <= 0 || System.nanoTime() < rumbleUntilNs) return
        val prim = if (down) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK
        val scale = ((if (down) 1.0 else 0.55) * strength / 0.7).toFloat().coerceIn(0.1f, 1f)
        when {
            Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(prim) -> v.vibrate(VibrationEffect.startComposition().addPrimitive(prim, scale).compose())
            Build.VERSION.SDK_INT >= 29 -> v.vibrate(VibrationEffect.createPredefined(if (down) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
            else -> v.vibrate(VibrationEffect.createOneShot(if (down) 12 else 6, amp(if (down) 0.9 else 0.5)))
        }
    }

    /** Game rumble from the host. `low`/`high` are motor magnitudes 0..65535; ms 0 (or both 0) stops. */
    fun rumble(low: Int, high: Int, ms: Int) {
        val v = vibrator ?: return
        lastRumble = Triple(low, high, ms)
        if (!enabled || ms == 0 || (low == 0 && high == 0)) { rumbleUntilNs = 0; v.cancel(); return }
        val w = RumbleWaveform.render(low / 65535.0, high / 65535.0, ms.coerceIn(1, 5000), strength)
        rumbleUntilNs = System.nanoTime() + ms * 1_000_000L
        if (amplitudeControl) v.vibrate(VibrationEffect.createWaveform(w.timings, w.amplitudes, -1))
        else v.vibrate(VibrationEffect.createWaveform(w.onOffTimings(), -1)) // on/off actuator: the pulse pattern only
    }

    /** A "feel" cue; skipped while game rumble plays (the game's own effect wins). */
    fun cue(c: Cue) {
        val v = vibrator ?: return
        if (!enabled || !feel || strength <= 0 || System.nanoTime() < rumbleUntilNs) return
        if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(c.primitive)) {
            v.vibrate(VibrationEffect.startComposition().addPrimitive(c.primitive, (c.scale * strength / 0.7).toFloat().coerceIn(0.05f, 1f)).compose())
        } else if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(c.predefined))
        else v.vibrate(VibrationEffect.createOneShot(c.fallbackMs, amp(c.scale)))
    }

    private fun amp(x: Double): Int = if (amplitudeControl) (x * strength * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE

    enum class Cue(val primitive: Int, val predefined: Int, val scale: Double, val fallbackMs: Long) {
        /** Pedal/trigger reaches the resistance point (like the wall of a DualSense trigger effect). */
        RESISTANCE(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK, 0.9, 18),
        /** Pedal/trigger fully pressed: end stop. */
        END_STOP(VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.EFFECT_HEAVY_CLICK, 0.7, 14),
        /** Steering passes center. */
        DETENT(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.EFFECT_TICK, 0.5, 6),
        /** Steering reaches full lock. */
        LOCK(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.EFFECT_CLICK, 0.8, 12),
    }
}

/** Two-motor rumble rendered on one actuator in 10 ms steps (pure; unit-tested). */
object RumbleWaveform {
    const val STEP_MS = 10L
    /** Heavy motor pulse: 20 ms on / 20 ms off = 25 Hz. */
    const val HEAVY_PERIOD_MS = 40L
    /** The light motor of a console pad is weaker than the heavy one. */
    const val LIGHT_GAIN = 0.75

    class Wave(val timings: LongArray, val amplitudes: IntArray) {
        /** For actuators without amplitude control: [off, on, off, on, …] durations. */
        fun onOffTimings(): LongArray {
            val runs = ArrayList<Pair<Boolean, Long>>()
            for (i in timings.indices) {
                val on = amplitudes[i] > 0
                if (runs.isNotEmpty() && runs.last().first == on) runs[runs.size - 1] = on to runs.last().second + timings[i]
                else runs += on to timings[i]
            }
            val out = ArrayList<Long>()
            if (runs.firstOrNull()?.first == true) out += 0L
            runs.forEach { out += it.second }
            return out.toLongArray()
        }
    }

    fun render(low: Double, high: Double, ms: Int, strength: Double): Wave {
        val n = ((ms + STEP_MS - 1) / STEP_MS).toInt().coerceAtLeast(1)
        val t = LongArray(n) { STEP_MS }
        val a = IntArray(n) { i ->
            val heavyOn = (i * STEP_MS) % HEAVY_PERIOD_MS < HEAVY_PERIOD_MS / 2
            val mix = maxOf(if (heavyOn) low else 0.0, high * LIGHT_GAIN)
            (mix.coerceIn(0.0, 1.0) * (strength / 0.7).coerceIn(0.0, 1.0) * 255).toInt().coerceIn(0, 255)
        }
        return Wave(t, a)
    }
}

/**
 * Local controller feel, in the spirit of a DualSense's adaptive triggers (docs/HAPTICS.md): a click
 * when a pedal or trigger passes its resistance point, a thud at the end stop, a light detent when
 * steering passes center and a click at full lock. Pure decision logic (unit-tested); [fire] plays it.
 * Generated on the phone — not by the game — and it never changes what is sent.
 */
class FeelEffects(private val fire: (Haptics.Cue) -> Unit, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val triggers = HashMap<String, Double>()
    private var steer = 0.0
    private var lastSteerCueMs = Long.MIN_VALUE / 2

    fun trigger(id: String, v: Double) {
        val prev = triggers[id] ?: 0.0
        if (prev < RESISTANCE && v >= RESISTANCE && v < 1.0) fire(Haptics.Cue.RESISTANCE)
        if (prev < 1.0 && v >= 1.0) fire(Haptics.Cue.END_STOP)
        if (v <= 0.0) triggers.remove(id) else triggers[id] = v
    }

    fun steering(v: Double) {
        val prev = steer; steer = v
        val t = nowMs()
        if (t - lastSteerCueMs < MIN_STEER_GAP_MS) return
        val cue = when {
            kotlin.math.abs(prev) < 1.0 && kotlin.math.abs(v) >= 1.0 -> Haptics.Cue.LOCK
            (prev > 0.0 && v <= 0.0) || (prev < 0.0 && v >= 0.0) -> Haptics.Cue.DETENT
            else -> null
        } ?: return
        lastSteerCueMs = t
        fire(cue)
    }

    fun reset() { triggers.clear(); steer = 0.0 }

    companion object {
        const val RESISTANCE = 0.7
        /** Steering cues at most every 120 ms, so tremor at center or at lock can't buzz. */
        const val MIN_STEER_GAP_MS = 120L
    }
}
