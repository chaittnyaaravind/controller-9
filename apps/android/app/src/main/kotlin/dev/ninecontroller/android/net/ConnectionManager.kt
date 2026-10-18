package dev.ninecontroller.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Process
import android.util.Base64
import android.util.Log
import dev.ninecontroller.android.BuildConfig
import dev.ninecontroller.android.data.HostStore
import dev.ninecontroller.android.data.IdentityStore
import dev.ninecontroller.android.data.PairedHost
import dev.ninecontroller.android.data.SettingsStore
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.protocol.Control
import dev.ninecontroller.protocol.ControlConnection
import dev.ninecontroller.protocol.ControllerHello
import dev.ninecontroller.protocol.Decision
import dev.ninecontroller.protocol.FLAG_PAUSED
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.LinkKind
import dev.ninecontroller.protocol.LinkPolicy
import dev.ninecontroller.protocol.LinkStats
import dev.ninecontroller.protocol.PairUri
import dev.ninecontroller.protocol.Pairing
import dev.ninecontroller.protocol.PolicyState
import dev.ninecontroller.protocol.RefusedException
import dev.ninecontroller.protocol.WrongHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val hostName: String, val via: String = "") : ConnState
    /** Numeric comparison: show [code]; the user confirms here and on the computer. */
    data class ConfirmCode(val hostName: String, val code: String) : ConnState
    data class Connected(val hostName: String, val slot: Int, val backendWarning: String?, val link: LinkKind, val linkDetail: String) : ConnState
    data class Reconnecting(val hostName: String, val attempt: Int, val reason: String) : ConnState
    data class Failed(val message: String) : ConnState
}

data class LinkStatsUi(
    val rttP50: Double? = null, val rttP95: Double? = null,
    val sendHz: Double = 0.0, val sent: Long = 0, val sensorHz: Double = 0.0,
    val reconnects: Int = 0, val lastReconnectMs: Long? = null,
    val lastSwitch: String? = null, val watchdogTrips: Int = 0,
    /** Why the previous link ended (failure, host bye, or the user), for the UI. */
    val lastDisconnect: String? = null,
)

/** What the user sees per link: usable or not, why, and recent measurements. */
data class LinkReport(val kind: LinkKind, val available: Boolean, val reason: String?, val score: Double?, val rttP95: Double?, val attempts: Int, val successes: Int)

/**
 * Connection manager with pluggable links (IP / stream / BLE) and the shared policy
 * (protocol LinkPolicy, docs/CONNECTION_MANAGER.md). One link at a time; input is never duplicated
 * across links. A failed link is retried last; a healthy link is replaced only by a clearly better
 * one after the hysteresis window. Every transition goes through the host's slot takeover, which
 * neutralizes the controller before the new link's first frame.
 */
class ConnectionManager(
    private val context: Context,
    private val identity: IdentityStore,
    private val hosts: HostStore,
    private val settings: SettingsStore,
    private val haptics: Haptics,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state
    private val _stats = MutableStateFlow(LinkStatsUi())
    val stats: StateFlow<LinkStatsUi> = _stats
    private val _links = MutableStateFlow<List<LinkReport>>(emptyList())
    val links: StateFlow<List<LinkReport>> = _links

    val ble = BlePeripheral(context)
    private val transports = Transports(context, ble)

    @Volatile private var active: ActiveLink? = null
    @Volatile private var want: PairedHost? = null
    @Volatile private var lastSlot: Int? = null
    @Volatile private var profileName: String? = null
    private var session: Job? = null
    /** Only the newest reconnect loop may change state or install a link: cancelling a coroutine does
     *  not interrupt an attempt that is blocked inside Bluetooth/socket I/O (seen on a real device). */
    @Volatile private var generation = 0L
    private var codeDecision: CompletableFuture<Boolean>? = null

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var latest = InputState.NEUTRAL
    @Volatile private var latestFlags = 0
    @Volatile private var version = 0L
    @Volatile private var paused = false
    @Volatile var sensorHz = 0.0
    /** System.nanoTime() of the last host reply (pong) on the active link — input freshness for the UI. */
    @Volatile var lastAckNs = 0L; private set
    /** System.nanoTime() of the last input frame sent. */
    @Volatile var lastSendNs = 0L; private set
    @Volatile var connectedAtNs = 0L; private set
    private var reconnects = 0
    private var watchdogTrips = 0

    // Per-link history for the policy.
    private class Hist { var attempts = 0; var successes = 0; var lastFailureAt = 0L; val disconnects = ArrayDeque<Long>(); var rttP95: Double? = null }
    private val hist = LinkKind.entries.associateWith { Hist() }
    private var switchedAt = 0L
    private var betterSince = 0L

    init {
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (_state.value is ConnState.Reconnecting && active == null) restart("network available", healthy = false) }
                override fun onLost(network: Network) {
                    val a = active ?: return
                    if (a.kind != LinkKind.BLE && LocalLinks.kindOf(a.conn.local) !in LocalLinks.available()) restart("network lost", healthy = false)
                }
            })
        scope.launch { evaluateLoop() }
    }

    private fun hello() = ControllerHello(1, identity.deviceName.take(40), "android", BuildConfig.VERSION_NAME)

    fun submit(state: InputState, flags: Int) { lock.withLock { latest = state; latestFlags = flags; version++; changed.signalAll() } }
    fun setPaused(p: Boolean) { lock.withLock { paused = p; version++; changed.signalAll() } }
    fun setProfileName(name: String) { profileName = name }

    // ------------------------------------------------------------------ candidates & policy

    private fun now() = System.currentTimeMillis()

    private suspend fun overrideKind(): LinkKind? = settings.flow.first().linkOverride.let { o -> LinkKind.entries.find { it.name == o } }

    private fun candidates(host: PairedHost): List<Pair<LinkStats, String?>> {
        val ipKinds = LocalLinks.available()
        val hasAddr = host.allAddrs().isNotEmpty()
        val knows = { l: String -> host.links.isEmpty() || l in host.links }
        val bleReason = ble.unavailableReason() ?: if (!knows("ble")) "${host.name} doesn't offer Bluetooth" else null
        return LinkKind.entries.map { k ->
            val reason: String? = when (k) {
                LinkKind.BLE -> bleReason
                LinkKind.STREAM -> when { ipKinds.isEmpty() -> "No network interface is up"; !hasAddr -> "No address known for ${host.name}"; !knows("stream") -> "${host.name} doesn't offer TCP-only mode"; else -> null }
                else -> when { k !in ipKinds -> "Not connected to a ${k.label} network"; !hasAddr -> "No address known for ${host.name}"; else -> null }
            }
            val h = hist.getValue(k)
            val cutoff = now() - 60_000
            while (h.disconnects.isNotEmpty() && h.disconnects.first() < cutoff) h.disconnects.removeFirst()
            LinkStats(k, reason == null, h.rttP95, 0.0, h.disconnects.size.toDouble(),
                if (h.lastFailureAt == 0L) null else (now() - h.lastFailureAt) / 1000.0, h.attempts, h.successes) to reason
        }
    }

    private fun publishReports(host: PairedHost) {
        _links.value = candidates(host).map { (s, r) -> LinkReport(s.kind, s.available, r, LinkPolicy.score(s), s.rttP95Ms, s.attempts, s.successes) }
    }

    /** Collapse the policy order to attempts: all IP kinds are one attempt (it races every address). */
    private fun attempts(order: List<LinkKind>): List<LinkKind> {
        val out = ArrayList<LinkKind>()
        for (k in order) {
            val ip = k != LinkKind.BLE && k != LinkKind.STREAM
            if (ip && out.any { it != LinkKind.BLE && it != LinkKind.STREAM }) continue
            out += k
        }
        return out
    }

    // ------------------------------------------------------------------ pairing

    fun pairWithQr(uri: PairUri) {
        scope.launch {
            _state.value = ConnState.Connecting(uri.name.ifEmpty { uri.addr })
            try {
                val tmp = PairedHost(Pairing.hostId(uri.hostStatic), uri.name, "", uri.addr, uri.port, "", addrs = uri.extra)
                val (c, _) = transports.ip(tmp, identity.keyPair(), hello(), 2000)
                val host = c.use { it.pairQr(uri); remember(it, uri.addr, uri.port, uri.extra) }
                connect(host)
            } catch (e: WrongHostException) {
                _state.value = ConnState.Failed("This QR code belongs to a different computer than the one that answered. Pairing stopped.")
            } catch (e: Exception) { _state.value = ConnState.Failed(describe(e)) }
        }
    }

    /** Numeric-comparison pairing over IP (address known) or Bluetooth (no network at all). */
    fun pairWithCode(addr: String?, port: Int, name: String) {
        scope.launch {
            _state.value = ConnState.Connecting(name, if (addr == null) "Bluetooth" else addr)
            try {
                val id = identity.keyPair()
                val (c, viaAddr) = if (addr != null) {
                    ControlConnection.connect(java.net.InetSocketAddress(addr, port), id, hello()) to addr
                } else {
                    val (conn, _) = transports.ble(PairedHost("00000000", name, "", "", port, ""), id, hello(), 30_000)
                    conn to ""
                }
                val host = c.use { conn ->
                    conn.pairCode { code ->
                        val f = CompletableFuture<Boolean>()
                        codeDecision = f
                        _state.value = ConnState.ConfirmCode(conn.host.name, Pairing.formatCode(code))
                        runCatching { f.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
                    }
                    remember(conn, viaAddr, port, emptyList())
                }
                connect(host)
            } catch (e: Exception) { _state.value = ConnState.Failed(describe(e)) } finally { codeDecision = null }
        }
    }

    fun confirmCode(matches: Boolean) { codeDecision?.complete(matches) }

    private fun remember(c: ControlConnection, addr: String, port: Int, extra: List<String>): PairedHost {
        val h = PairedHost(Pairing.hostId(c.hostStatic), c.host.name, Base64.encodeToString(c.hostStatic, Base64.NO_WRAP),
            addr.ifEmpty { c.host.addrs.firstOrNull() ?: "" }, port, HostStore.now(),
            addrs = (extra + c.host.addrs).distinct(), links = c.host.links)
        hosts.upsert(h)
        return h
    }

    // ------------------------------------------------------------------ session lifecycle

    fun connect(host: PairedHost) {
        want = host
        scope.launch { settings.update { it.copy(lastHost = host.id) } }
        startSession(host, attempt = 0, reason = "", failedKind = null)
    }

    fun disconnect() {
        want = null
        session?.cancel()
        active?.close(sendBye = true)
        active = null
        _stats.value = _stats.value.copy(lastDisconnect = "You disconnected")
        _state.value = ConnState.Idle
    }

    /** Called from link threads on failure (healthy=false) or by the evaluator for a planned switch. */
    private fun restart(reason: String, healthy: Boolean, to: LinkKind? = null) {
        val h = want ?: return
        Log.i(TAG, "restart link=${active?.kind} healthy=$healthy to=$to reason=$reason")
        val old = active
        val failed = old?.kind
        if (!healthy && failed != null) hist.getValue(failed).apply { lastFailureAt = now(); disconnects.addLast(now()) }
        if (!healthy) _stats.value = _stats.value.copy(lastDisconnect = failed?.label?.takeUnless { reason.contains(it) }?.let { "$it: $reason" } ?: reason)
        if (to == null) { active = null; old?.close(sendBye = false) }
        startSession(h, attempt = 1, reason = reason, failedKind = failed, preferred = to, makeBeforeBreak = old.takeIf { to != null })
    }

    private fun startSession(host: PairedHost, attempt: Int, reason: String, failedKind: LinkKind?, preferred: LinkKind? = null, makeBeforeBreak: ActiveLink? = null) {
        session?.cancel()
        val gen = ++generation
        session = scope.launch {
            var n = attempt
            val t0 = System.nanoTime()
            fun current() = gen == generation && isActive && want?.id == host.id
            while (current()) {
                val h = hosts.byId(host.id) ?: host
                publishReports(h)
                val order: List<LinkKind> = preferred?.let { listOf(it) } ?: when (val d = LinkPolicy.decide(candidates(h).map { it.first },
                    PolicyState(current = failedKind, currentHealthy = false, overrideKind = overrideKind()))) {
                    is Decision.Failover -> d.order
                    is Decision.Switch -> listOf(d.to)
                    Decision.Stay -> listOfNotNull(failedKind)
                    Decision.Unavailable -> emptyList()
                }
                if (order.isEmpty()) {
                    _state.value = ConnState.Reconnecting(h.name, ++n, "No usable link: " + candidates(h).mapNotNull { it.second }.distinct().joinToString("; "))
                } else for (kind in attempts(order)) {
                    if (!current()) return@launch
                    _state.value = if (n == 0) ConnState.Connecting(h.name, kind.label) else ConnState.Reconnecting(h.name, n, "trying ${kind.label}")
                    val hs = hist.getValue(kind)
                    hs.attempts++
                    try {
                        val (link, info) = transports.open(kind, h, identity.keyPair(), hello(), lastSlot, profileName)
                        if (!current()) { Log.i(TAG, "stale attempt ${link.kind} finished after a newer loop started; closing it"); link.close(sendBye = false); return@launch }
                        hist.getValue(link.kind).successes++
                        if (link.kind != kind) hs.successes++
                        lastSlot = info.slot
                        hosts.upsert(h.copy(lastUsed = HostStore.now(), addrs = (h.addrs + link.conn.host.addrs).distinct().take(12),
                            links = link.conn.host.links.ifEmpty { h.links }))
                        makeBeforeBreak?.close(sendBye = false)
                        active = link
                        switchedAt = now(); betterSince = 0
                        connectedAtNs = System.nanoTime(); lastAckNs = connectedAtNs
                        if (n > 0) { reconnects++; _stats.value = _stats.value.copy(reconnects = reconnects, lastReconnectMs = (System.nanoTime() - t0) / 1_000_000) }
                        if (failedKind != null && failedKind != link.kind) _stats.value = _stats.value.copy(lastSwitch = "${failedKind.label} → ${link.kind.label}: $reason")
                        _state.value = ConnState.Connected(link.conn.host.name, info.slot, null, link.kind, link.detail)
                        Log.i(TAG, "connected link=${link.kind} slot=${info.slot} detail=${link.detail} attempt=$n")
                        lock.withLock { version++ } // resend current state on the new link
                        startThreads(link)
                        return@launch
                    } catch (e: Transports.IdentityChangedException) {
                        // Never delete the pairing on an unverified host's word: another device may be impersonating it.
                        Log.w(TAG, "pinned host key mismatch at ${kind}; pairing kept")
                        want = null
                        _state.value = ConnState.Failed("The computer that answered is not ${h.name} (its identity key changed). Your pairing was kept. If ${h.name} was reinstalled, forget it and pair again.")
                        return@launch
                    } catch (e: RefusedException) {
                        hs.lastFailureAt = now()
                        when (e.code) {
                            "not_paired" -> { hosts.remove(h.id); want = null; _state.value = ConnState.Failed("${h.name} no longer knows this phone. Pair again."); return@launch }
                            "slots_full" -> { want = null; _state.value = ConnState.Failed("${h.name} already has four controllers connected."); return@launch }
                        }
                    } catch (e: Exception) {
                        hs.lastFailureAt = now()
                        Log.w(TAG, "attempt ${kind} failed: ${describe(e)}")
                        if (!current()) return@launch
                        _state.value = ConnState.Reconnecting(h.name, ++n, "${kind.label}: ${describe(e)}")
                    }
                }
                if (preferred != null) { // planned switch failed: keep the old link
                    makeBeforeBreak?.let { active = it; return@launch }
                }
                delay(minOf(100L shl minOf(n, 4), 2000L))
            }
        }
    }

    /** Planned switches: re-evaluate every second; switch only on a clear, sustained improvement. */
    private suspend fun evaluateLoop() {
        while (true) {
            delay(1000)
            val a = active ?: continue
            val h = want ?: continue
            hist.getValue(a.kind).rttP95 = a.channel.rttMs.percentile(95.0)
            publishReports(h)
            val cands = candidates(h).map { it.first }
            val best = LinkPolicy.rank(cands).firstOrNull()
            val cur = cands.find { it.kind == a.kind }?.let(LinkPolicy::score)
            val bs = cands.find { it.kind == best }?.let(LinkPolicy::score)
            val better = best != null && best != a.kind && cur != null && bs != null && bs - cur >= LinkPolicy.SWITCH_MARGIN
            if (!better) betterSince = 0 else if (betterSince == 0L) betterSince = now()
            val st = PolicyState(a.kind, true, (now() - switchedAt) / 1000.0, if (betterSince == 0L) 0.0 else (now() - betterSince) / 1000.0, overrideKind())
            val d = LinkPolicy.decide(cands, st)
            if (d is Decision.Switch) {
                // Same IP binding, different interface (e.g. LAN → USB): the IP attempt already prefers it by racing.
                val ipToIp = d.to != LinkKind.BLE && d.to != LinkKind.STREAM && a.kind != LinkKind.BLE && a.kind != LinkKind.STREAM
                if (!ipToIp) restart("better link available", healthy = true, to = d.to)
            }
        }
    }

    private fun startThreads(link: ActiveLink) {
        val c = link.conn; val ch = link.channel
        Thread({
            try {
                while (active === link) {
                    when (val m = c.recv()) {
                        is Control.Rumble -> haptics.rumble(m.low, m.high, m.ms)
                        is Control.Error -> if (m.code == "backend_unavailable") (_state.value as? ConnState.Connected)?.let { _state.value = it.copy(backendWarning = m.message ?: m.code) }
                        is Control.Bye -> {
                            when {
                                m.reason == "unpaired" -> { want?.let { hosts.remove(it.id) }; want = null; active = null; link.close(false); _state.value = ConnState.Failed("This phone was removed on the computer.") }
                                m.reason?.startsWith("replaced") == true -> Unit // our own newer link took over
                                else -> if (active === link) restart(m.reason ?: "host closed", healthy = false)
                            }
                            return@Thread
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) { if (active === link) restart(describe(e), healthy = false) }
        }, "c9-control").start()

        Thread({
            var lastPong = System.nanoTime(); var seen = 0L
            // BLE: host→phone writes without response can stall for >1.5 s under load while the link is
            // healthy (measured on a real phone), so rely on the GATT disconnect callback and allow 5 s
            // of pong silence. The host's own 250 ms input timeout still neutralizes controls.
            val deadMs = if (link.kind == LinkKind.BLE) 5000 else 1000
            while (active === link) {
                if (link.isDown) { if (active === link) restart("Bluetooth link disconnected", healthy = false); return@Thread }
                try { ch.receive(100)?.let { haptics.rumble(it.low, it.high, it.ms) } }
                catch (e: Exception) { if (active === link) restart("input link: ${describe(e)}", healthy = false); return@Thread }
                if (ch.rttMs.total != seen) { seen = ch.rttMs.total; lastPong = System.nanoTime(); lastAckNs = lastPong }
                val age = (System.nanoTime() - lastPong) / 1_000_000
                if (age > deadMs && active === link) { watchdogTrips++; _stats.value = _stats.value.copy(watchdogTrips = watchdogTrips); restart("no response for $age ms on ${link.kind.label}", healthy = false); return@Thread }
            }
        }, "c9-rx").start()

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val hbActive = ch.info.heartbeatMs.coerceIn(10, 1000) * 1_000_000L
            val hbIdle = ch.info.idleHeartbeatMs.coerceIn(50, 2000) * 1_000_000L
            val pingNs = if (link.kind == LinkKind.BLE) 500_000_000L else 250_000_000L
            var sentVersion = -1L; var lastSend = 0L; var lastPing = 0L; var lastStats = System.nanoTime()
            var windowStart = System.nanoTime(); var windowSent = 0L; var sendHz = 0.0
            while (active === link) {
                val now = System.nanoTime()
                val (s, f, v) = lock.withLock { Triple(if (paused) InputState.NEUTRAL else latest, latestFlags or (if (paused) FLAG_PAUSED else 0), version) }
                val hb = if (s.isNeutral) hbIdle else hbActive
                try {
                    if (v != sentVersion || now - lastSend >= hb) { ch.sendInput(s, f); sentVersion = v; lastSend = now; lastSendNs = now; windowSent++ }
                    if (now - lastPing >= pingNs) { ch.ping(); lastPing = now }
                    if (now - windowStart >= 1_000_000_000L) { sendHz = windowSent * 1e9 / (now - windowStart); windowStart = now; windowSent = 0 }
                    if (now - lastStats >= 2_000_000_000L) {
                        lastStats = now
                        _stats.value = _stats.value.copy(rttP50 = ch.rttMs.percentile(50.0), rttP95 = ch.rttMs.percentile(95.0), sendHz = sendHz, sent = ch.sent, sensorHz = sensorHz)
                        runCatching { c.send(Control.Stats(ch.rttMs.percentile(50.0)?.toFloat(), ch.rttMs.percentile(95.0)?.toFloat(), sensorHz.toFloat().takeIf { it > 0 }, sendHz.toFloat(), battery())) }
                    }
                } catch (e: Exception) { if (active === link) restart(describe(e), healthy = false); return@Thread }
                lock.withLock {
                    if (version == sentVersion && active === link) {
                        val untilHb = (lastSend + hb - System.nanoTime()) / 1_000_000
                        val untilPing = (lastPing + pingNs - System.nanoTime()) / 1_000_000
                        changed.await(minOf(untilHb, untilPing).coerceIn(1, 250), TimeUnit.MILLISECONDS)
                    }
                }
            }
        }, "c9-sender").start()
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    companion object {
        private const val TAG = "C9Conn"
        fun describe(e: Throwable): String = when (e) {
            is RefusedException -> when (e.code) {
                "pairing_closed" -> "Pairing isn’t open on the computer. Open “Pair a phone” there first."
                "rate_limited" -> "Too many pairing attempts. Wait a minute and try again."
                "bad_proof" -> "That QR code has expired or was already used. Make a new one on the computer."
                "rejected" -> "Pairing was declined."
                "rejected on controller" -> "You declined the code."
                "slots_full" -> "All four player slots are in use."
                "unsupported_version" -> "The computer runs an incompatible version. Update both apps."
                else -> "The computer refused: ${e.code}"
            }
            is java.net.ConnectException -> "Can’t reach the computer on this network"
            is java.net.SocketTimeoutException -> e.message ?: "The computer didn’t answer in time"
            is java.net.NoRouteToHostException -> "No route to the computer"
            is IllegalStateException -> e.message ?: "Unavailable"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
