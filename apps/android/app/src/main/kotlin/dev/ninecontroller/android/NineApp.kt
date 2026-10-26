package dev.ninecontroller.android

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.ninecontroller.android.data.GameStore
import dev.ninecontroller.android.data.HostStore
import dev.ninecontroller.android.data.IdentityStore
import dev.ninecontroller.android.data.ProfileStore
import dev.ninecontroller.android.data.SettingsStore
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.android.net.ConnectionManager
import dev.ninecontroller.android.net.Discovery
import dev.ninecontroller.android.net.HidGamepad
import dev.ninecontroller.android.net.LocalNetwork

/** Manual dependency container; one instance per process. */
class NineApp : Application() {
    lateinit var identity: IdentityStore; private set
    lateinit var hosts: HostStore; private set
    lateinit var profiles: ProfileStore; private set
    lateinit var settings: SettingsStore; private set
    lateinit var games: GameStore; private set
    lateinit var haptics: Haptics; private set
    lateinit var discovery: Discovery; private set
    lateinit var connection: ConnectionManager; private set
    lateinit var localNetwork: LocalNetwork; private set
    /** The phone as a Bluetooth controller with no computer app (docs/DIRECT_BLUETOOTH.md). */
    lateinit var hid: HidGamepad; private set

    override fun onCreate() {
        super.onCreate()
        identity = IdentityStore(this)
        hosts = HostStore(this)
        profiles = ProfileStore(this)
        settings = SettingsStore(this)
        games = GameStore(this)
        haptics = Haptics(this)
        discovery = Discovery(this)
        connection = ConnectionManager(this, identity, hosts, settings, haptics)
        localNetwork = LocalNetwork(this)
        hid = HidGamepad(this, haptics)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch { if (!settings.flow.first().hidMode) hid.restoreNameIfIdle() }
        // Remote keys change the controller's report map, so they take effect when it next starts.
        scope.launch { settings.flow.collect { hid.remoteKeys = it.remoteKeys } }
    }

    /** Make [g] the game being played: its colour, layout and steering, in one step. */
    suspend fun play(g: dev.ninecontroller.android.data.Game) =
        settings.update { it.copy(activeGame = g.id, accent = g.accent, activeProfile = g.layout, padTilt = g.tilt, gyroAim = g.aim) }
}
