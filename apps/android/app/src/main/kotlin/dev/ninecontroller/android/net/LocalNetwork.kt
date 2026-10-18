package dev.ninecontroller.android.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Router-free local network hosted by this phone: an "autonomous" Wi-Fi Direct group that a computer
 * joins like an ordinary Wi-Fi network (legacy client mode). No Internet, no router, no hotspot
 * plan required. Android 10+ (custom SSID/passphrase). The computer then appears at 192.168.49.x
 * and pairing/connection proceeds over the IP binding.
 * STATUS: compiles; NOT yet tested on a device (UNVERIFIED).
 */
@SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES (33+) / ACCESS_FINE_LOCATION (≤32) requested by the UI first
class LocalNetwork(context: Context) {
    data class Info(val ssid: String, val passphrase: String, val ownerAddress: String = "192.168.49.1")

    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    private val channel = p2p?.initialize(context, Looper.getMainLooper(), null)
    private val _state = MutableStateFlow<Info?>(null)
    val state: StateFlow<Info?> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val supported: Boolean get() = p2p != null && Build.VERSION.SDK_INT >= 29

    fun start() {
        val ch = channel
        if (!supported || ch == null) { _error.value = "Needs Android 10 or later with Wi-Fi Direct"; return }
        val rnd = SecureRandom()
        val suffix = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[rnd.nextInt(32)] }.joinToString("")
        val pass = (1..12).map { "abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)] }.joinToString("")
        val ssid = "DIRECT-C9-Controller-9-$suffix"
        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).enablePersistentMode(false).build()
        p2p!!.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _error.value = null; _state.value = Info(ssid, pass) }
            override fun onFailure(reason: Int) {
                _error.value = when (reason) { WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct isn't supported on this phone"
                    WifiP2pManager.BUSY -> "Wi-Fi is busy; turn Wi-Fi on and try again"; else -> "Couldn't create the network ($reason)" }
            }
        })
    }

    fun stop() {
        channel?.let { p2p?.removeGroup(it, null) }
        _state.value = null
    }

    fun refresh() { channel?.let { c -> p2p?.requestGroupInfo(c) { g: WifiP2pGroup? -> if (g == null) _state.value = null } } }
}
