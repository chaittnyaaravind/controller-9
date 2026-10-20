package dev.ninecontroller.android.net

import android.content.Context
import dev.ninecontroller.android.data.PairedHost
import dev.ninecontroller.protocol.ControlConnection
import dev.ninecontroller.protocol.ControllerHello
import dev.ninecontroller.protocol.InputChannel
import dev.ninecontroller.protocol.KeyPair
import dev.ninecontroller.protocol.LinkConst
import dev.ninecontroller.protocol.LinkKind
import dev.ninecontroller.protocol.PathQueue
import dev.ninecontroller.protocol.SessionCrypto
import dev.ninecontroller.protocol.SessionInfo
import dev.ninecontroller.protocol.StreamBinding
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One established link: an authenticated control connection plus its input channel. Several links can
 * belong to one session (SPEC §8.5); they then share [channel]'s SessionCrypto. Each link has its own
 * bounded send queue ([out]) drained by its own thread, so one slow link never delays another.
 */
class ActiveLink(val kind: LinkKind, val detail: String, val conn: ControlConnection, val channel: InputChannel, private val extra: AutoCloseable? = null) {
    /** True once the OS reports the underlying radio link as gone (BLE GATT disconnect). */
    val isDown: Boolean get() = (extra as? BleLink)?.disconnected == true
    val out = PathQueue()
    @Volatile var closed = false; private set
    /** System.nanoTime() of this link's last ping sent and last pong received. */
    @Volatile var lastPingNs = 0L
    @Volatile var lastAckNs = System.nanoTime()
    val connectedAtNs = System.nanoTime()
    val crypto: SessionCrypto get() = channel.crypto
    fun close(sendBye: Boolean) {
        closed = true
        out.close()
        if (sendBye) runCatching { channel.sendInput(dev.ninecontroller.protocol.InputState.NEUTRAL, 0); channel.bye() }
        runCatching { channel.close() }
        if (sendBye) runCatching { conn.close() } else runCatching { conn.abort() }
        runCatching { extra?.close() }
    }
}

/** Classification of this phone's local interfaces (mirrors hosts/core/src/netif.rs heuristics). */
object LocalLinks {
    /** Interfaces that can never reach a computer on this phone's own local links: VPN tunnels and
     *  cellular. (Seen on the realme test phone: a VPN's tun0 with 172.16.0.2 stayed up with Wi-Fi off
     *  and was mistaken for Wi-Fi/LAN, so the policy kept trying a LAN that could only route into the VPN.) */
    private val notLocal = listOf("tun", "tap", "ppp", "ipsec", "wg", "utun", "rmnet", "v4-rmnet", "ccmni", "pdp", "clat", "dummy")

    /** Link kind of a local interface, or null when the interface is not a local link at all. */
    fun classify(name: String, ip: Inet4Address): LinkKind? {
        val n = name.lowercase(); val o = ip.address.map { it.toInt() and 0xFF }
        if (notLocal.any { n.startsWith(it) }) return null
        return when {
            n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb") || (o[0] == 192 && o[1] == 168 && o[2] == 42) -> LinkKind.USB
            n.startsWith("p2p") || (o[0] == 192 && o[1] == 168 && o[2] == 49) -> LinkKind.WIFI_DIRECT
            n.startsWith("swlan") || n.startsWith("ap") || n == "wlan1" || (o[0] == 172 && o[1] == 20 && o[2] == 10 && o[3] < 16) ||
                (o[0] == 192 && o[1] == 168 && o[2] == 43) -> LinkKind.HOTSPOT
            n.startsWith("eth") -> LinkKind.ETHERNET
            else -> LinkKind.LAN
        }
    }

    /** IP link kinds currently present on this phone (interfaces up with an IPv4 address). */
    fun available(): Set<LinkKind> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { classify(i.name, it) } }.toSet()
    }.getOrDefault(emptySet())

    fun kindOf(local: InetAddress?): LinkKind {
        val a = local as? Inet4Address ?: return LinkKind.LAN
        val iface = runCatching { NetworkInterface.getByInetAddress(a) }.getOrNull() ?: return LinkKind.LAN
        return classify(iface.name, a) ?: LinkKind.LAN
    }
}

class Transports(private val context: Context, private val ble: BlePeripheral) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "c9-connect").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "c9-timeout").apply { isDaemon = true } }

    /** Message links (stream/BLE) have no socket timeout: close the link if the handshake/open does not
     *  finish in time, which unblocks the waiting reader. */
    private fun <T> withDeadline(ms: Long, link: AutoCloseable, block: () -> T): T {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val f = timer.schedule({ if (!done.get()) runCatching { link.close() } }, ms, TimeUnit.MILLISECONDS)
        try { return block().also { done.set(true) } } finally { f.cancel(false) }
    }

    /** IP binding: race every known address (Wi-Fi, hotspot, USB tethering, Wi-Fi Direct); first handshake wins. */
    fun ip(host: PairedHost, id: KeyPair, hello: ControllerHello, timeoutMs: Int): Pair<ControlConnection, LinkKind> {
        val addrs = host.allAddrs()
        val cs = ExecutorCompletionService<ControlConnection>(pool)
        val futures = addrs.map { a -> cs.submit { ControlConnection.connect(InetSocketAddress(a, host.port), id, hello, timeoutMs) } }
        var last: Throwable? = null
        repeat(addrs.size) {
            val f = cs.poll(timeoutMs + 500L, TimeUnit.MILLISECONDS) ?: return@repeat
            try {
                val c = f.get()
                futures.filter { it != f }.forEach { other -> pool.execute { runCatching { other.get().abort() } } }
                return c to LocalLinks.kindOf(c.local)
            } catch (e: Exception) { last = e.cause ?: e }
        }
        futures.forEach { it.cancel(true) }
        throw (last as? Exception) ?: java.net.SocketTimeoutException("no address answered")
    }

    /** Stream binding over TCP 47902 — for networks that block UDP (some hotspots, guest Wi-Fi). */
    fun stream(host: PairedHost, id: KeyPair, hello: ControllerHello, timeoutMs: Int): Triple<ControlConnection, StreamBinding, LinkKind> {
        var last: Exception? = null
        for (a in host.allAddrs()) {
            try {
                val sb = StreamBinding.tcp(a, LinkConst.STREAM_TCP_PORT, timeoutMs)
                return Triple(withDeadline(timeoutMs + 3000L, sb) { ControlConnection.connectOver(sb.control.input, sb.control.output, sb, id, hello) }, sb, LinkKind.STREAM)
            } catch (e: Exception) { last = e }
        }
        throw last ?: java.net.SocketTimeoutException("stream binding unreachable")
    }

    /** BLE GATT binding: advertise and wait for the host's central to connect. */
    fun ble(host: PairedHost, id: KeyPair, hello: ControllerHello, timeoutMs: Long): Pair<ControlConnection, BleLink> {
        val hint = host.id.chunked(2).take(4).map { it.toInt(16).toByte() }.toByteArray()
        val link = ble.awaitHost(hint, timeoutMs) ?: throw java.net.SocketTimeoutException("no computer connected over Bluetooth within ${timeoutMs / 1000} s")
        return try { withDeadline(6000, link) { ControlConnection.connectOver(link.control.input, link.control.output, link, id, hello) } to link }
        catch (e: Exception) { link.close(); throw e }
    }

    /** Thrown before anything is sent when the host's static key differs from the one pinned at pairing. */
    class IdentityChangedException(val host: PairedHost) : Exception("identity changed")

    private fun pin(c: ControlConnection, host: PairedHost, close: () -> Unit) {
        if (!c.hostStatic.contentEquals(android.util.Base64.decode(host.pub, android.util.Base64.NO_WRAP))) { close(); throw IdentityChangedException(host) }
    }

    /** Thrown when an IP attempt only reached the computer over USB, which never carries controller input in automatic modes. */
    class UsbRefusedException : Exception("Only reachable over USB; USB isn’t used for controller input")

    /**
     * Handshake, verify the pinned host key, THEN open the session (nothing is sent to an unverified host).
     * With [join] (a live session's crypto) the link is added to that session instead (SPEC §8.5): the
     * host must answer with the same session, slot and keys, otherwise the link is closed.
     */
    fun open(kind: LinkKind, host: PairedHost, id: KeyPair, hello: ControllerHello, resumeSlot: Int?, profile: String?,
             join: SessionCrypto? = null, allowUsb: Boolean = true): Pair<ActiveLink, SessionInfo> {
        fun session(c: ControlConnection): SessionInfo {
            if (join == null) return c.open(resumeSlot, profile)
            val info = c.join(join.info.sessionId)
            val same = info.sessionId == join.info.sessionId && info.slot == join.info.slot &&
                info.kIn.contentEquals(join.info.kIn) && info.kOut.contentEquals(join.info.kOut)
            if (!same) throw java.io.IOException("host answered the join with a different session")
            return join.info
        }
        fun channel(link: dev.ninecontroller.protocol.DatagramLink, info: SessionInfo) =
            if (join != null) InputChannel(link, info, join) else InputChannel(link, info)
        return when (kind) {
            LinkKind.STREAM -> {
                val (c, sb, k) = stream(host, id, hello, 1500)
                pin(c, host) { c.abort(); sb.close() }
                val info = withDeadline(4000, sb) { session(c) }
                ActiveLink(k, "TCP ${c.host.name}:${LinkConst.STREAM_TCP_PORT}", c, channel(sb, info), sb) to info
            }
            LinkKind.BLE -> {
                val (c, l) = ble(host, id, hello, 8000)
                pin(c, host) { c.abort(); l.close() }
                val info = try { withDeadline(6000, l) { session(c) } } catch (e: Exception) { c.abort(); l.close(); throw e }
                ActiveLink(LinkKind.BLE, "MTU ${l.mtu}", c, channel(l, info), l) to info
            }
            else -> {
                val (c, k) = ip(host, id, hello, 1500)
                if (!allowUsb && k == LinkKind.USB) { c.abort(); throw UsbRefusedException() }
                pin(c, host) { c.abort() }
                val info = try { session(c) } catch (e: Exception) { c.abort(); throw e }
                ActiveLink(k, "${c.remote?.hostAddress}:${host.port} via ${c.local?.hostAddress}", c, channel(dev.ninecontroller.protocol.UdpLink(c.remote!!, info.udpPort), info)) to info
            }
        }
    }
}
