package dev.ninecontroller.android.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Button clicks and game rumble. Strength 0..1 scales amplitude; disabled = no vibration at all. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }
    @Volatile var enabled = true
    @Volatile var strength = 0.7
    val available get() = vibrator?.hasVibrator() == true

    fun click() {
        val v = vibrator ?: return
        if (!enabled || strength <= 0) return
        if (Build.VERSION.SDK_INT >= 29 && strength >= 0.5) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        else v.vibrate(VibrationEffect.createOneShot(10, amp(0.6)))
    }

    /** Game rumble from the host. `low`/`high` are motor magnitudes 0..65535; ms 0 stops. */
    fun rumble(low: Int, high: Int, ms: Int) {
        val v = vibrator ?: return
        if (!enabled || ms == 0 || (low == 0 && high == 0)) { v.cancel(); return }
        val mag = maxOf(low, high) / 65535.0
        v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 5000).toLong(), amp(mag)))
    }

    private fun amp(x: Double): Int = if (vibrator?.hasAmplitudeControl() == true) (x * strength * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
}
