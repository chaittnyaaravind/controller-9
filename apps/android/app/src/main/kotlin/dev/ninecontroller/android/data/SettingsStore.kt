package dev.ninecontroller.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val activeProfile: String = "racing",
    val lastHost: String? = null,
    val hapticsEnabled: Boolean = true,
    val hapticStrength: Double = 0.7,
    val keepScreenOn: Boolean = true,
    val sensorHz: Int = 200,
    val calibrationDeg: Double = 0.0,
    val showStats: Boolean = true,
    val onboarded: Boolean = false,
    /** "auto" or a LinkKind name chosen by the user. */
    val linkOverride: String = "auto",
)

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val profile = stringPreferencesKey("active_profile")
        val lastHost = stringPreferencesKey("last_host")
        val haptics = booleanPreferencesKey("haptics")
        val strength = doublePreferencesKey("haptic_strength")
        val screenOn = booleanPreferencesKey("keep_screen_on")
        val sensorHz = intPreferencesKey("sensor_hz")
        val calibration = doublePreferencesKey("calibration_deg")
        val stats = booleanPreferencesKey("show_stats")
        val onboarded = booleanPreferencesKey("onboarded")
        val link = stringPreferencesKey("link_override")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            activeProfile = p[K.profile] ?: "racing",
            lastHost = p[K.lastHost],
            hapticsEnabled = p[K.haptics] ?: true,
            hapticStrength = p[K.strength] ?: 0.7,
            keepScreenOn = p[K.screenOn] ?: true,
            sensorHz = p[K.sensorHz] ?: 200,
            calibrationDeg = p[K.calibration] ?: 0.0,
            showStats = p[K.stats] ?: true,
            onboarded = p[K.onboarded] ?: false,
            linkOverride = p[K.link] ?: "auto",
        )
    }

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val cur = AppSettings(
                activeProfile = p[K.profile] ?: "racing", lastHost = p[K.lastHost], hapticsEnabled = p[K.haptics] ?: true,
                hapticStrength = p[K.strength] ?: 0.7, keepScreenOn = p[K.screenOn] ?: true, sensorHz = p[K.sensorHz] ?: 200,
                calibrationDeg = p[K.calibration] ?: 0.0, showStats = p[K.stats] ?: true, onboarded = p[K.onboarded] ?: false,
                linkOverride = p[K.link] ?: "auto",
            )
            val n = f(cur)
            p[K.profile] = n.activeProfile
            if (n.lastHost != null) p[K.lastHost] = n.lastHost else p.remove(K.lastHost)
            p[K.haptics] = n.hapticsEnabled
            p[K.strength] = n.hapticStrength.coerceIn(0.0, 1.0)
            p[K.screenOn] = n.keepScreenOn
            p[K.sensorHz] = n.sensorHz.coerceIn(50, 400)
            p[K.calibration] = n.calibrationDeg
            p[K.stats] = n.showStats
            p[K.onboarded] = n.onboarded
            p[K.link] = n.linkOverride
        }
    }
}
