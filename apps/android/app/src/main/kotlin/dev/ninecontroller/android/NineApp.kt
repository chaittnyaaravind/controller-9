package dev.ninecontroller.android

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.ninecontroller.android.data.GameStore
import dev.ninecontroller.android.data.ProfileStore
import dev.ninecontroller.android.data.SettingsStore
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.android.net.HidGamepad

/** Manual dependency container; one instance per process. */
class NineApp : Application() {
    lateinit var profiles: ProfileStore; private set
    lateinit var settings: SettingsStore; private set
    lateinit var games: GameStore; private set
    lateinit var haptics: Haptics; private set
    /** The phone as a Bluetooth Xbox controller (docs/DIRECT_BLUETOOTH.md). */
    lateinit var hid: HidGamepad; private set

    override fun onCreate() {
        super.onCreate()
        profiles = ProfileStore(this)
        settings = SettingsStore(this)
        games = GameStore(this)
        haptics = Haptics(this)
        hid = HidGamepad(this, haptics)
        // A game that ended with the app killed must not leave the phone in Do Not Disturb.
        dev.ninecontroller.android.ui.controller.ControllerQuiet.restoreIfLeftOn(this)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch { if (!settings.flow.first().hidMode) hid.restoreNameIfIdle() }
        // The adapter can't be renamed while Bluetooth is off: give the phone its name back when it comes on.
        val btOn = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_ON)
                    scope.launch { if (!settings.flow.first().hidMode) hid.restoreNameIfIdle() }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(this, btOn,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // Remote keys change the controller's report map, so they take effect when it next starts.
        scope.launch { settings.flow.collect { hid.remoteKeys = it.remoteKeys } }
        // Launcher shortcuts follow the game list and the game being played.
        scope.launch {
            kotlinx.coroutines.flow.combine(games.games, settings.flow) { g, s -> g to s.activeGame }
                .distinctUntilChanged() // not on every settings change: the shortcut service rate-limits updates
                .collect { (g, active) -> dev.ninecontroller.android.data.GameShortcuts.publish(this@NineApp, g, active) }
        }
    }

    /** Make [g] the game being played: its colour, layout and steering, in one step. */
    suspend fun play(g: dev.ninecontroller.android.data.Game) =
        settings.update { it.copy(activeGame = g.id, accent = g.accent, activeProfile = g.layout, padTilt = g.tilt, gyroAim = g.aim) }
}
