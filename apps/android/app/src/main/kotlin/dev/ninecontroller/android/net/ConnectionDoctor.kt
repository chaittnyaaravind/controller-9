package dev.ninecontroller.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.ninecontroller.android.data.PairedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * "Why won't it connect?" — the most common complaint about phone-controller apps (replica/fixes.md, F2).
 * Gathers what the phone can see (Wi-Fi, VPN, its own address) and tries the computer's address once,
 * then names the likely cause in plain words with the one fix for it. Local network only; nothing is sent
 * anywhere else.
 */
object ConnectionDoctor {
    enum class Probe { REACHABLE, REFUSED, TIMEOUT, NO_ROUTE }

    /** One IPv4 address of this phone with its prefix length, e.g. 192.168.1.23/24. */
    data class Net(val addr: String, val prefix: Int)

    data class Facts(
        val hostName: String,
        val hostAddr: String,
        val hostPort: Int,
        val onWifi: Boolean,
        val vpn: Boolean,
        val phone: List<Net>,
        val probe: Probe,
    )

    enum class Cause { REACHABLE, NO_NETWORK, VPN, NOT_RUNNING, OTHER_NETWORK, BLOCKED, NO_ADDRESS }

    data class Finding(val cause: Cause, val title: String, val detail: String, val fixes: List<String>)

    fun diagnose(f: Facts): Finding {
        val name = f.hostName
        if (f.hostAddr.isBlank()) return Finding(Cause.NO_ADDRESS, "$name was paired over Bluetooth only",
            "There is no network address to try.", listOf("Pair again by QR code while both are on the same Wi-Fi, or keep using Bluetooth."))
        if (f.probe == Probe.REACHABLE) return Finding(Cause.REACHABLE, "$name answers",
            "The network is fine: this phone reached ${f.hostAddr}:${f.hostPort}.", listOf("Tap Connect again. If it still fails, restart Controller-9 on the computer."))
        if (f.probe == Probe.REFUSED) return Finding(Cause.NOT_RUNNING, "Controller-9 isn't running on $name",
            "The computer answered, but nothing is listening for controllers on port ${f.hostPort}.",
            listOf("Open Controller-9 on $name and leave it running.", "If it is open, quit and reopen it."))
        if (f.vpn) return Finding(Cause.VPN, "A VPN is on",
            "A VPN can hide the computers on your own network from this phone.", listOf("Turn the VPN off while you play, or allow local network access in the VPN app."))
        if (f.phone.isEmpty() || !f.onWifi && f.phone.none { sameNet(it, f.hostAddr) }) return Finding(Cause.NO_NETWORK, "This phone isn't on the computer's Wi-Fi",
            "$name is at ${f.hostAddr}, and this phone has no Wi-Fi connection to that network.",
            listOf("Join the same Wi-Fi as $name.", "No Wi-Fi? Turn on this phone's hotspot and join it from the computer.", "Or use Bluetooth controller mode — no network needed."))
        // No answer and different address ranges. Some networks route between ranges, and a closed app can also
        // look like silence (firewalls drop instead of refusing), so this is a likely cause, not a certainty.
        if (f.phone.none { sameNet(it, f.hostAddr) }) return Finding(Cause.OTHER_NETWORK, "Phone and computer may be on different networks",
            "No answer from ${f.hostAddr}, and this phone is on ${f.phone.joinToString { "${it.addr}/${it.prefix}" }}. Guest Wi-Fi, separate 5 GHz and 2.4 GHz networks, or a mesh extender can cause this.",
            listOf("Check Controller-9 is open on $name.", "Join the same Wi-Fi network on both.", "If $name moved networks, pair again from the computer's QR code."))
        return Finding(Cause.BLOCKED, "$name doesn't answer",
            "Both are on the same network, but nothing came back from ${f.hostAddr}. Usually the computer's firewall, or a Wi-Fi that keeps devices apart (guest, office, school and hotel networks often do).",
            listOf("Check Controller-9 is open on $name (a firewall can make a closed app look like silence).",
                "Windows: when it asks, allow Controller-9 on Private networks, and set this Wi-Fi to Private.",
                "Mac: System Settings → Network → Firewall → allow Controller-9.",
                "On a network that keeps devices apart, use this phone's hotspot instead, or Bluetooth controller mode."))
    }

    /** True when [host] (dotted IPv4) is inside [net]. */
    fun sameNet(net: Net, host: String): Boolean {
        val a = ipv4(net.addr) ?: return false; val b = ipv4(host) ?: return false
        val p = net.prefix.coerceIn(0, 32)
        val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return a and mask == b and mask
    }

    private fun ipv4(s: String): Long? {
        val parts = s.trim().split(".").map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts.fold(0L) { acc, v -> (acc shl 8) or v.toLong() }
    }

    /** Gathers the facts on this phone and tries each of the host's addresses (best result wins). */
    suspend fun check(context: Context, host: PairedHost, timeoutMs: Int = 1500): Facts = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var wifi = false; var vpn = false
        val nets = ArrayList<Net>()
        @Suppress("DEPRECATION") // allNetworks: the only way to see Wi-Fi, USB and VPN at once
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
            if (!local) continue
            cm.getLinkProperties(n)?.linkAddresses?.forEach { la ->
                (la.address as? Inet4Address)?.hostAddress?.let { nets += Net(it, la.prefixLength) }
            }
        }
        val addrs = host.allAddrs()
        val results = addrs.map { probe(it, host.port, timeoutMs) }
        val best = results.minByOrNull { it.ordinal } ?: Probe.NO_ROUTE
        val shown = addrs.getOrNull(results.indexOf(best)) ?: host.addr
        Facts(host.name, shown, host.port, wifi, vpn, nets, best)
    }

    private fun probe(addr: String, port: Int, timeoutMs: Int): Probe = try {
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }; Probe.REACHABLE
    } catch (_: SocketTimeoutException) { Probe.TIMEOUT
    } catch (e: Exception) { classify(e.toString() + " " + (e.cause?.toString() ?: "")) }

    /** Android reports refused, unreachable and timed-out connects all as ConnectException; the errno tells them apart. */
    fun classify(error: String): Probe = when {
        "ECONNREFUSED" in error || "Connection refused" in error -> Probe.REFUSED
        "ETIMEDOUT" in error || "timed out" in error -> Probe.TIMEOUT
        else -> Probe.NO_ROUTE
    }
}
