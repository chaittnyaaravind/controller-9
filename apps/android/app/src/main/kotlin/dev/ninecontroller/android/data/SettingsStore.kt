package dev.ninecontroller.android.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.SteeringCalibrationV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** `pad:<preset>` for a controller preset, otherwise a custom profile id (racing, …). */
    val activeProfile: String = "pad:xbox",
    val lastHost: String? = null,
    val hapticsEnabled: Boolean = true,
    val hapticStrength: Double = 0.7,
    /** Trigger resistance points and steering detents (docs/HAPTICS.md). */
    val feelEffects: Boolean = true,
    val keepScreenOn: Boolean = true,
    val sensorHz: Int = 200,
    val showStats: Boolean = true,
    val onboarded: Boolean = false,
    /** "auto" or a LinkKind name chosen by the user. */
    val linkOverride: String = "auto",
    /** Per-preset remaps, encoded by PadMapping.encode(); empty = preset defaults. */
    val padMappings: Map<String, String> = emptyMap(),
    /** Per-preset button modes (turbo / toggle), encoded by pad.ButtonModes.encode(). */
    val padModes: Map<String, String> = emptyMap(),
    val stickDeadzone: Double = 0.08,
    /** Tilt steering on the left stick X for controller presets (profiles keep their own motion settings). */
    val padTilt: Boolean = false,
    val tiltSensitivity: Double = 1.0,
    val tiltDeadzone: Double = 0.02,
    val tiltLockDeg: Double = 45.0,
    /** Steering calibration relative to the player's grip (schema 2), or null when not measured. */
    val steering: SteeringCalibrationV2? = null,
    /** A calibration from the old absolute-angle method exists; it is not reused (it measured the wrong thing). */
    val legacySteering: Boolean = false,
    /** Bluetooth controller mode was on: restart it at launch so a paired computer reconnects like a real pad. */
    val hidMode: Boolean = false,
    /** The game colour (ui.theme.Accent name) that lights the controller; set by the chosen game. */
    val accent: String = "PLATINUM",
    /** The game being played (data.GameStore id). */
    val activeGame: String = "any",
    /** Pin the controller screen while playing (no notification shade, home or back until unlocked). */
    val lockOnPlay: Boolean = false,
    /** Gyro aim on the right stick (input/GyroAim.kt): turning the phone aims. */
    val gyroAim: Boolean = false,
    val aimSensitivity: Double = 1.2,
    val aimInvertY: Boolean = false,
    /** Bluetooth controller also carries a keyboard, mouse and media keys (input/RemoteHidReport.kt). */
    val remoteKeys: Boolean = false,
    /** Light behind the glass controls: GAME (the game's colour family), RAINBOW or OFF (ui/pad/Glass.kt). */
    val rgb: String = "GAME",
    /** Only alarms interrupt while the controller is open (ui/controller/ControllerQuiet.kt). */
    val quietPlay: Boolean = true,
) {
    val padPreset get() = PadPresets.fromSelection(activeProfile)
    fun padMotion() = MotionSettings(enabled = padTilt, axis = "lx", lockDeg = tiltLockDeg, deadzone = tiltDeadzone, sensitivity = tiltSensitivity)
}

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val profile = stringPreferencesKey("active_profile")
        val lastHost = stringPreferencesKey("last_host")
        val haptics = booleanPreferencesKey("haptics")
        val strength = doublePreferencesKey("haptic_strength")
        val feel = booleanPreferencesKey("feel_effects")
        val screenOn = booleanPreferencesKey("keep_screen_on")
        val sensorHz = intPreferencesKey("sensor_hz")
        /** Legacy (schema 1) keys: read only to tell the player to recenter, removed on the next save. */
        val calibration = doublePreferencesKey("calibration_deg")
        val stats = booleanPreferencesKey("show_stats")
        val onboarded = booleanPreferencesKey("onboarded")
        val link = stringPreferencesKey("link_override")
        val stickDz = doublePreferencesKey("stick_deadzone")
        val padTilt = booleanPreferencesKey("pad_tilt")
        val tiltSens = doublePreferencesKey("tilt_sensitivity")
        val tiltDz = doublePreferencesKey("tilt_deadzone")
        val tiltLock = doublePreferencesKey("tilt_lock_deg")
        val calLeft = doublePreferencesKey("cal_left_deg")
        val calRight = doublePreferencesKey("cal_right_deg")
        val calNoise = doublePreferencesKey("cal_noise_deg")
        val calAt = longPreferencesKey("cal_measured_at")
        val steering = stringPreferencesKey("steering_cal_v2")
        val hidMode = booleanPreferencesKey("hid_mode")
        val accent = stringPreferencesKey("accent")
        val activeGame = stringPreferencesKey("active_game")
        val lockOnPlay = booleanPreferencesKey("lock_on_play")
        val gyroAim = booleanPreferencesKey("gyro_aim")
        val aimSens = doublePreferencesKey("aim_sensitivity")
        val aimInvert = booleanPreferencesKey("aim_invert_y")
        val remoteKeys = booleanPreferencesKey("remote_keys")
        val rgb = stringPreferencesKey("rgb")
        val quietPlay = booleanPreferencesKey("quiet_play")
        fun map(preset: String) = stringPreferencesKey("pad_map_$preset")
        fun modes(preset: String) = stringPreferencesKey("pad_modes_$preset")
    }

    private fun read(p: Preferences): AppSettings {
        val steering = SteeringCalibrationV2.decode(p[K.steering])
        return AppSettings(
            activeProfile = p[K.profile] ?: "pad:xbox",
            lastHost = p[K.lastHost],
            hapticsEnabled = p[K.haptics] ?: true,
            hapticStrength = p[K.strength] ?: 0.7,
            feelEffects = p[K.feel] ?: true,
            keepScreenOn = p[K.screenOn] ?: true,
            sensorHz = p[K.sensorHz] ?: 200,
            showStats = p[K.stats] ?: true,
            onboarded = p[K.onboarded] ?: false,
            linkOverride = p[K.link] ?: "auto",
            padMappings = PadPresets.all.mapNotNull { pr -> p[K.map(pr.id)]?.takeIf { it.isNotEmpty() }?.let { pr.id to it } }.toMap(),
            padModes = PadPresets.all.mapNotNull { pr -> p[K.modes(pr.id)]?.takeIf { it.isNotEmpty() }?.let { pr.id to it } }.toMap(),
            stickDeadzone = p[K.stickDz] ?: 0.08,
            padTilt = p[K.padTilt] ?: false,
            tiltSensitivity = p[K.tiltSens] ?: 1.0,
            tiltDeadzone = p[K.tiltDz] ?: 0.02,
            tiltLockDeg = p[K.tiltLock] ?: 45.0,
            steering = steering,
            legacySteering = steering == null && (p[K.calAt] != null || (p[K.calibration] ?: 0.0) != 0.0),
            hidMode = p[K.hidMode] ?: false,
            accent = p[K.accent] ?: "PLATINUM",
            activeGame = p[K.activeGame] ?: "any",
            lockOnPlay = p[K.lockOnPlay] ?: false,
            gyroAim = p[K.gyroAim] ?: false,
            aimSensitivity = p[K.aimSens] ?: 1.2,
            aimInvertY = p[K.aimInvert] ?: false,
            remoteKeys = p[K.remoteKeys] ?: false,
            rgb = p[K.rgb] ?: "GAME",
            quietPlay = p[K.quietPlay] ?: true,
        )
    }

    private fun write(p: MutablePreferences, n: AppSettings) {
        p[K.profile] = n.activeProfile
        if (n.lastHost != null) p[K.lastHost] = n.lastHost else p.remove(K.lastHost)
        p[K.haptics] = n.hapticsEnabled
        p[K.strength] = n.hapticStrength.coerceIn(0.0, 1.0)
        p[K.feel] = n.feelEffects
        p[K.screenOn] = n.keepScreenOn
        p[K.sensorHz] = n.sensorHz.coerceIn(50, 400)
        p[K.stats] = n.showStats
        p[K.onboarded] = n.onboarded
        p[K.link] = n.linkOverride
        p[K.hidMode] = n.hidMode
        p[K.accent] = n.accent
        p[K.activeGame] = n.activeGame
        p[K.lockOnPlay] = n.lockOnPlay
        p[K.remoteKeys] = n.remoteKeys
        p[K.rgb] = n.rgb
        p[K.quietPlay] = n.quietPlay
        p[K.gyroAim] = n.gyroAim
        p[K.aimSens] = n.aimSensitivity.coerceIn(0.3, 4.0)
        p[K.aimInvert] = n.aimInvertY
        for (pr in PadPresets.all) n.padMappings[pr.id]?.takeIf { it.isNotEmpty() }?.let { p[K.map(pr.id)] = it } ?: p.remove(K.map(pr.id))
        for (pr in PadPresets.all) n.padModes[pr.id]?.takeIf { it.isNotEmpty() }?.let { p[K.modes(pr.id)] = it } ?: p.remove(K.modes(pr.id))
        // Same ranges as the protocol profile validator (MotionSettings / StickSettings).
        p[K.stickDz] = n.stickDeadzone.coerceIn(0.0, 0.5)
        p[K.padTilt] = n.padTilt
        p[K.tiltSens] = n.tiltSensitivity.coerceIn(0.25, 4.0)
        p[K.tiltDz] = n.tiltDeadzone.coerceIn(0.0, 0.5)
        p[K.tiltLock] = n.tiltLockDeg.coerceIn(10.0, 180.0)
        val c = n.steering?.validated()
        if (c == null) p.remove(K.steering) else {
            p[K.steering] = c.encode()
            for (k in listOf(K.calibration, K.calLeft, K.calRight, K.calNoise)) p.remove(k)
            p.remove(K.calAt)
        }
    }

    val flow: Flow<AppSettings> = context.store.data.map(::read)

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.store.edit { p -> write(p, f(read(p))) }
    }
}
