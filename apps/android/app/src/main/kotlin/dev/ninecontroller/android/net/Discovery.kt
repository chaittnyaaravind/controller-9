package dev.ninecontroller.android.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

data class DiscoveredHost(val serviceName: String, val name: String, val id: String?, val addr: String, val port: Int, val os: String?, val freeSlots: Int?)

/** DNS-SD browsing for `_ninectl._tcp`. Runs only while a screen asks for it (battery). */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val _hosts = MutableStateFlow(emptyList<DiscoveredHost>())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Synchronized fun start() {
        if (listener != null) return
        _hosts.value = emptyList()
        lock = wifi.createMulticastLock("ninectl-discovery").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _error.value = null }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { _error.value = "Discovery unavailable ($errorCode)" }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) { _hosts.value = _hosts.value.filterNot { it.serviceName == info.serviceName } }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(info, executor, object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(s: NsdServiceInfo) {
                    val a = s.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: s.hostAddresses.firstOrNull() ?: return
                    add(s, a.hostAddress ?: return)
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            // Pre-34 resolveService handles one request at a time; serialize on our executor.
            executor.execute {
                val done = java.util.concurrent.CountDownLatch(1)
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) { done.countDown() }
                    override fun onServiceResolved(s: NsdServiceInfo) { s.host?.hostAddress?.let { add(s, it) }; done.countDown() }
                })
                done.await(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun add(s: NsdServiceInfo, addr: String) {
        val txt = s.attributes.mapValues { (_, v) -> v?.decodeToString() }
        if (txt["v"] != null && txt["v"] != "1") return
        val h = DiscoveredHost(s.serviceName, txt["name"] ?: s.serviceName, txt["id"], addr, s.port, txt["os"], txt["slots"]?.toIntOrNull())
        _hosts.value = (_hosts.value.filterNot { it.serviceName == h.serviceName } + h).sortedBy { it.name.lowercase() }
    }

    companion object { const val SERVICE_TYPE = "_ninectl._tcp." }
}
