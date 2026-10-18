package dev.ninecontroller.android

import android.app.Application
import dev.ninecontroller.android.data.HostStore
import dev.ninecontroller.android.data.IdentityStore
import dev.ninecontroller.android.data.ProfileStore
import dev.ninecontroller.android.data.SettingsStore
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.android.net.ConnectionManager
import dev.ninecontroller.android.net.Discovery
import dev.ninecontroller.android.net.LocalNetwork

/** Manual dependency container; one instance per process. */
class NineApp : Application() {
    lateinit var identity: IdentityStore; private set
    lateinit var hosts: HostStore; private set
    lateinit var profiles: ProfileStore; private set
    lateinit var settings: SettingsStore; private set
    lateinit var haptics: Haptics; private set
    lateinit var discovery: Discovery; private set
    lateinit var connection: ConnectionManager; private set
    lateinit var localNetwork: LocalNetwork; private set

    override fun onCreate() {
        super.onCreate()
        identity = IdentityStore(this)
        hosts = HostStore(this)
        profiles = ProfileStore(this)
        settings = SettingsStore(this)
        haptics = Haptics(this)
        discovery = Discovery(this)
        connection = ConnectionManager(this, identity, hosts, settings, haptics)
        localNetwork = LocalNetwork(this)
    }
}
