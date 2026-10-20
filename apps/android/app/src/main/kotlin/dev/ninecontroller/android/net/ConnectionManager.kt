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
import dev.ninecontroller.protocol.PacketType
import dev.ninecontroller.protocol.PairUri
import dev.ninecontroller.protocol.Pairing
import dev.ninecontroller.protocol.PolicyState
import dev.ninecontroller.protocol.RefusedException
import dev.ninecontroller.protocol.SessionCrypto
import dev.ninecontroller.protocol.WrongHostException
import dev.ninecontroller.protocol.isEdge
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
    /** [link] is the fastest link right now; [also] the other links carrying the same input (SPEC §8.5). */
    data class Connected(val hostName: String, val slot: Int, val backendWarning: String?, val link: LinkKind, val linkDetail: String,
                         val also: List<LinkKind> = emptyList()) : ConnState {
        val linksLabel: String get() = (listOf(link) + also).joinToString(" + ") { it.label }
    }
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
    /** Every link of the current session, fastest first. */
    val paths: List<PathUi> = emptyList(),
)

/** One link of the session as the phone measures it. */
data class PathUi(val kind: LinkKind, val detail: String, val rttP50: Double?, val rttP95: Double?, val replyAgeMs: Long,
                  val queued: Int, val coalesced: Long, val dropped: Long)

/** The link-preference choices: redundant automatic (default), single-link automatic, or one fixed link. */
object LinkChoices {
    val all: List<String> = listOf("auto", "auto1") + LinkKind.entries.map { it.name }
    fun label(o: String) = when (o) { "auto" -> "Automatic (all links)"; "auto1" -> "Automatic, one link"; else -> LinkKind.valueOf(o).label }
}

/** What the user sees per link: usable or not, why, and recent measurements. */
data class LinkReport(val kind: LinkKind, val available: Boolean, val reason: String?, val score: Double?, val rttP95: Double?, val attempts: Int, val successes: Int)

/**
 * Connection manager with pluggable links (IP / stream / BLE) and the shared policy
 * (protocol LinkPolicy, docs/CONNECTION_MANAGER.md).
 *
 * Automatic (default) is redundant (SPEC §8.5): the first link opens the session, every other usable
 * non-USB link joins the same session, and each input frame is sealed once and sent over all of them
 * through per-link queues. A failed link is dropped and rejoined on its own; the session (slot, held
 * input, counters) survives as long as one link is up. Only when the last link fails does the phone
 * open a new session (the host neutralizes on its own after 250 ms of silence either way).
 *
 * "Automatic, one link" and the manual link choices keep the earlier single-link behaviour: one link
 * at a time, a failed link retried last, planned make-before-break switches after the hysteresis window.
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

    /** The link that opened the session (or took over when it failed). */
    @Volatile private var active: ActiveLink? = null
    /** Other links joined to the same session (redundant mode). */
    private val extras = java.util.concurrent.CopyOnWriteArrayList<ActiveLink>()
    /** Crypto of the current session; the sender thread runs while this is its session. */
    @Volatile private var sessionCrypto: SessionCrypto? = null
    private val joining = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val joinRetryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val joinFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun paths(): List<ActiveLink> = listOfNotNull(active) + extras
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
    /** Last time an unmeasured IP link was tried while on BLE (see evaluateLoop). */
    @Volatile private var lastProbe = 0L

    init {
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_state.value is ConnState.Reconnecting && active == null) restart("network available", healthy = false)
                    else {
                        lastProbe = now() - PROBE_MS + 3000 // a network appeared: probe it in ~3 s (DHCP settles)
                        joinRetryAt["ip"] = now() + 1500; joinFails.remove("ip") // redundant mode: join it soon
                    }
                }
                override fun onLost(network: Network) {
                    val up = LocalLinks.available()
                    for (p in paths()) if (p.kind != LinkKind.BLE && LocalLinks.kindOf(p.conn.local) !in up) pathFailed(p, "network lost")
                }
            })
        // Bluetooth adapter on/off. Off under a BLE link: start reconnecting now instead of waiting for the
        // 5 s watchdog (the host still neutralizes on its own after 250 ms). On while reconnecting: the
        // in-flight attempt advertised on the old adapter session and can never be found, so retry now.
        androidx.core.content.ContextCompat.registerReceiver(context, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                    android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF, android.bluetooth.BluetoothAdapter.STATE_OFF ->
                        paths().filter { it.kind == LinkKind.BLE }.forEach { pathFailed(it, "Bluetooth turned off") }
                    android.bluetooth.BluetoothAdapter.STATE_ON -> if (want != null && active == null) {
                        Log.i(TAG, "Bluetooth turned on; retrying now"); ble.abortPending(); restart("Bluetooth turned on", healthy = false)
                    } else if (want != null) {
                        // A join that was advertising on the old adapter session can never be found: retry now.
                        ble.abortPending(); joinRetryAt.remove("ble"); joinFails.remove("ble")
                    }
                }
            }
        }, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { evaluateLoop() }
    }

    private fun hello() = ControllerHello(1, identity.deviceName.take(40), "android", BuildConfig.VERSION_NAME)

    fun submit(state: InputState, flags: Int) { lock.withLock { latest = state; latestFlags = flags; version++; changed.signalAll() } }
    /** Paused (app in background): the controller sends neutral + paused on the session's first link only;
     *  extra links are closed to save battery and rejoin when the controller is in use again. */
    fun setPaused(p: Boolean) {
        lock.withLock { paused = p; version++; changed.signalAll() }
        if (p) closeExtras("controller paused") else { joinRetryAt.clear(); joinFails.clear() }
        syncWifiLock()
    }

    /**
     * Low-latency Wi-Fi lock while an IP link carries input and the controller is in use. Measured on a
     * realme 12 5G: without it, frames the phone sent 50 ms apart arrived up to 440 ms apart (Wi-Fi
     * power-save batching), long enough to trip the host's 250 ms input timeout mid-press.
     */
    @Suppress("DEPRECATION")
    private val wifiLock = runCatching {
        context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            .createWifiLock(if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Controller-9:input")
            .apply { setReferenceCounted(false) }
    }.getOrNull()

    @Synchronized private fun syncWifiLock() {
        val l = wifiLock ?: return
        val want = paths().any { it.kind != LinkKind.BLE } && !paused
        runCatching { if (want && !l.isHeld) l.acquire() else if (!want && l.isHeld) l.release() }
        if (want != l.isHeld) Log.w(TAG, "wifi lock state mismatch want=$want")
    }
    fun setProfileName(name: String) { profileName = name }

    // ------------------------------------------------------------------ candidates & policy

    private fun now() = System.currentTimeMillis()

    private suspend fun overrideKind(): LinkKind? = settings.flow.first().linkOverride.let { o -> LinkKind.entries.find { it.name == o } }
    /** Automatic modes never use USB for controller input; a manual USB choice still can (troubleshooting). */
    private suspend fun automatic(): Boolean = settings.flow.first().linkOverride.startsWith("auto")
    private suspend fun redundant(): Boolean = settings.flow.first().linkOverride == "auto"
    @Volatile private var usbAllowed = true

    private fun candidates(host: PairedHost): List<Pair<LinkStats, String?>> {
        val ipKinds = LocalLinks.available()
        val hasAddr = host.allAddrs().isNotEmpty()
        val knows = { l: String -> host.links.isEmpty() || l in host.links }
        val bleReason = ble.unavailableReason() ?: if (!knows("ble")) "${host.name} doesn't offer Bluetooth" else null
        return LinkKind.entries.map { k ->
            val reason: String? = if (k == LinkKind.USB && !usbAllowed) "USB isn’t used for controller input in Automatic" else when (k) {
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
        sessionCrypto = null
        closeExtras("you disconnected", sendBye = true)
        active?.close(sendBye = true)
        active = null
        syncWifiLock()
        _stats.value = _stats.value.copy(lastDisconnect = "You disconnected")
        _state.value = ConnState.Idle
    }

    /** The link a make-before-break switch is replacing, and whether it died meanwhile. */
    @Volatile private var switchingFrom: ActiveLink? = null
    @Volatile private var switchingFromFailed = false

    /**
     * A link's own thread saw it end. During a make-before-break switch the host closes the old link as
     * soon as the new one takes over the slot; that is expected, so it is only noted (seen on a real
     * phone as a false "host closed the connection" failure plus a duplicate reconnect). If the switch
     * then fails, the note turns into a normal failover.
     */
    private fun linkFailed(link: ActiveLink, reason: String) {
        if (active !== link) return
        if (switchingFrom === link) { switchingFromFailed = true; Log.i(TAG, "old ${link.kind} link ended during switch: $reason"); return }
        restart(reason, healthy = false)
    }

    /** Called from link threads on failure (healthy=false) or by the evaluator for a planned switch. */
    private fun restart(reason: String, healthy: Boolean, to: LinkKind? = null) {
        val h = want ?: return
        Log.i(TAG, "restart link=${active?.kind} healthy=$healthy to=$to reason=$reason")
        val old = active
        val failed = old?.kind
        if (!healthy && failed != null) hist.getValue(failed).apply { lastFailureAt = now(); disconnects.addLast(now()) }
        if (!healthy) _stats.value = _stats.value.copy(lastDisconnect = failed?.label?.takeUnless { reason.contains(it) }?.let { "$it: $reason" } ?: reason)
        if (to == null) { active = null; switchingFrom = null; sessionCrypto = null; closeExtras(reason); old?.close(sendBye = false); syncWifiLock() }
        else { switchingFrom = old; switchingFromFailed = false }
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
                        usbAllowed = !automatic()
                        val (link, info) = transports.open(kind, h, identity.keyPair(), hello(), lastSlot, profileName, allowUsb = usbAllowed)
                        if (!current()) { Log.i(TAG, "stale attempt ${link.kind} finished after a newer loop started; closing it"); link.close(sendBye = false); return@launch }
                        hist.getValue(link.kind).successes++
                        if (link.kind != kind) hs.successes++
                        lastSlot = info.slot
                        hosts.upsert(h.copy(lastUsed = HostStore.now(), addrs = (h.addrs + link.conn.host.addrs).distinct().take(12),
                            links = link.conn.host.links.ifEmpty { h.links }))
                        makeBeforeBreak?.close(sendBye = false)
                        if (makeBeforeBreak != null) switchingFrom = null
                        closeExtras("new session")
                        active = link
                        sessionCrypto = link.crypto
                        syncWifiLock()
                        switchedAt = now(); betterSince = 0
                        connectedAtNs = System.nanoTime(); lastAckNs = connectedAtNs
                        if (n > 0) { reconnects++; _stats.value = _stats.value.copy(reconnects = reconnects, lastReconnectMs = (System.nanoTime() - t0) / 1_000_000) }
                        if (failedKind != null && failedKind != link.kind) _stats.value = _stats.value.copy(lastSwitch = "${failedKind.label} → ${link.kind.label}: $reason")
                        _state.value = ConnState.Connected(link.conn.host.name, info.slot, null, link.kind, link.detail)
                        Log.i(TAG, "connected link=${link.kind} slot=${info.slot} detail=${link.detail} attempt=$n")
                        lock.withLock { version++ } // resend current state on the new link
                        startPathThreads(link)
                        startSender(link.crypto)
                        joinRetryAt.clear(); joinFails.clear()
                        return@launch
                    } catch (e: Transports.IdentityChangedException) {
                        // Never delete the pairing on an unverified host's word: another device may be impersonating it.
                        Log.w(TAG, "pinned host key mismatch at ${kind}; pairing kept")
                        want = null
                        _state.value = ConnState.Failed("The computer that answered is not ${h.name} (its identity key changed). Your pairing was kept. If ${h.name} was reinstalled, forget it and pair again.")
                        return@launch
                    } catch (e: Transports.UsbRefusedException) {
                        Log.i(TAG, "attempt ${kind} reached the computer only over USB; not used in Automatic")
                        if (!current()) return@launch
                        _state.value = ConnState.Reconnecting(h.name, ++n, e.message ?: "USB only")
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
                    makeBeforeBreak?.let { old ->
                        switchingFrom = null
                        if (!switchingFromFailed) { active = old; return@launch }
                        // The old link died while we tried the new one: fail over normally.
                        active = null; old.close(sendBye = false); syncWifiLock()
                        restart("${old.kind.label} ended during a link switch", healthy = false); return@launch
                    }
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
            for (p in paths()) hist.getValue(p.kind).rttP95 = p.channel.rttMs.percentile(95.0)
            publishReports(h)
            publishPaths()
            if (redundant()) { manageRedundancy(h); continue }
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
            } else if (a.kind == LinkKind.BLE && st.overrideKind == null && now() - lastProbe >= PROBE_MS) {
                // The policy only switches to measured links, so a faster IP link that came back while we were
                // on BLE (Wi-Fi turned on again) would never be used. Try it make-before-break: BLE stays
                // active unless the IP link actually connects, and the attempt measures it for the policy.
                cands.filter { it.available && it.kind != LinkKind.BLE && it.kind != LinkKind.STREAM && it.rttP95Ms == null }
                    .maxByOrNull { it.kind.base }?.let { ip ->
                        lastProbe = now()
                        Log.i(TAG, "probing unmeasured ${ip.kind} while on BLE")
                        restart("trying ${ip.kind.label}", healthy = true, to = ip.kind)
                    }
            }
        }
    }

    // ------------------------------------------------------------------ redundant links (SPEC §8.5)

    private fun group(k: LinkKind) = when (k) { LinkKind.BLE -> "ble"; LinkKind.STREAM -> "stream"; else -> "ip" }

    /** Join every usable non-USB link group the session does not have yet, one attempt per group at a time. */
    private suspend fun manageRedundancy(h: PairedHost) {
        val first = active ?: return
        val crypto = sessionCrypto ?: return
        if (paused || !first.conn.host.multipath) return
        val have = paths().map { group(it.kind) }.toSet()
        val usable = candidates(h).filter { it.first.available }.map { it.first.kind }
        val wanted = buildList {
            usable.filter { group(it) == "ip" && it != LinkKind.USB }.maxByOrNull { it.base }?.let { add(it) }
            if (LinkKind.BLE in usable) add(LinkKind.BLE)
        }
        for (kind in wanted) {
            val g = group(kind)
            if (g in have || g in joining || now() < (joinRetryAt[g] ?: 0L)) continue
            joining += g
            scope.launch { try { join(kind, g, h, crypto) } finally { joining -= g } }
        }
    }

    private fun join(kind: LinkKind, g: String, h: PairedHost, crypto: SessionCrypto) {
        val hs = hist.getValue(kind)
        hs.attempts++
        try {
            val (link, _) = transports.open(kind, h, identity.keyPair(), hello(), null, null, join = crypto, allowUsb = false)
            if (sessionCrypto !== crypto || want?.id != h.id || paused) { Log.i(TAG, "join ${link.kind} finished after the session changed; closing it"); link.close(sendBye = false); return }
            hist.getValue(link.kind).successes++
            joinFails.remove(g); joinRetryAt.remove(g)
            extras += link
            startPathThreads(link)
            syncWifiLock()
            publishConnected()
            lock.withLock { version++; changed.signalAll() } // the new link gets the current state right away
            Log.i(TAG, "joined link=${link.kind} detail=${link.detail} links=${paths().map { it.kind }}")
        } catch (e: Exception) {
            hs.lastFailureAt = now()
            val fails = (joinFails[g] ?: 0) + 1
            joinFails[g] = fails
            joinRetryAt[g] = now() + minOf(2000L shl minOf(fails - 1, 4), 30_000L)
            Log.i(TAG, "join ${kind} failed (${fails}): ${describe(e)}")
        }
    }

    private fun closeExtras(reason: String, sendBye: Boolean = false) {
        if (extras.isEmpty()) return
        val gone = extras.toList(); extras.clear()
        gone.forEach { it.close(sendBye) }
        Log.i(TAG, "closed extra links ${gone.map { it.kind }}: $reason")
        publishConnected()
    }

    /**
     * A link's own thread saw it fail. An extra link is just dropped (and rejoined later). If the
     * session's first link fails while another is up, that one takes over — same session, nothing
     * reset, nothing released. Only the last link failing reconnects (a new session).
     */
    private fun pathFailed(link: ActiveLink, reason: String) {
        if (link.closed) return
        if (extras.remove(link)) {
            link.close(sendBye = false)
            noteDown(link, reason)
            return
        }
        if (active !== link) return
        val next = extras.firstOrNull { !it.closed && !it.isDown }
        if (next == null || switchingFrom != null) { linkFailed(link, reason); return }
        extras.remove(next)
        active = next
        link.close(sendBye = false)
        Log.i(TAG, "${link.kind} failed ($reason); ${next.kind} carries the session on")
        noteDown(link, reason)
    }

    private fun noteDown(link: ActiveLink, reason: String) {
        hist.getValue(link.kind).apply { lastFailureAt = now(); disconnects.addLast(now()) }
        joinRetryAt[group(link.kind)] = now() + 2000
        val rest = paths().joinToString(" + ") { it.kind.label }
        _stats.value = _stats.value.copy(lastDisconnect = "${link.kind.label}: $reason" + if (rest.isNotEmpty()) " (still connected over $rest)" else "")
        Log.i(TAG, "link down ${link.kind}: $reason; links=${paths().map { it.kind }}")
        syncWifiLock()
        publishConnected()
    }

    /** Connected state: the fastest link first, then the others. */
    private fun publishConnected() {
        val c = _state.value as? ConnState.Connected ?: return
        val ps = paths().sortedBy { it.channel.rttMs.percentile(50.0) ?: Double.MAX_VALUE }
        val first = ps.firstOrNull() ?: return
        _state.value = c.copy(link = first.kind, linkDetail = first.detail, also = ps.drop(1).map { it.kind })
    }

    private fun publishPaths() {
        val t = System.nanoTime()
        val ps = paths().map { PathUi(it.kind, it.detail, it.channel.rttMs.percentile(50.0), it.channel.rttMs.percentile(95.0),
            (t - it.lastAckNs) / 1_000_000, it.out.size, it.out.coalesced, it.out.dropped) }.sortedBy { it.rttP50 ?: Double.MAX_VALUE }
        _stats.value = _stats.value.copy(paths = ps)
        publishConnected()
    }

    /** Per-link threads: control reader, datagram receiver (pongs, rumble, watchdog, stats), and writer. */
    private fun startPathThreads(link: ActiveLink) {
        val c = link.conn; val ch = link.channel
        Thread({
            try {
                while (!link.closed) {
                    when (val m = c.recv()) {
                        is Control.Rumble -> haptics.rumble(m.low, m.high, m.ms)
                        is Control.Error -> if (m.code == "backend_unavailable") (_state.value as? ConnState.Connected)?.let { _state.value = it.copy(backendWarning = m.message ?: m.code) }
                        is Control.Bye -> {
                            when {
                                m.reason == "unpaired" -> { want?.let { hosts.remove(it.id) }; want = null; sessionCrypto = null; closeExtras("unpaired"); active = null; link.close(false); _state.value = ConnState.Failed("This phone was removed on the computer.") }
                                m.reason?.startsWith("replaced") == true -> Unit // our own newer session took over
                                else -> pathFailed(link, m.reason ?: "host closed")
                            }
                            return@Thread
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) { pathFailed(link, describe(e)) }
        }, "c9-control-${link.kind}").start()

        Thread({
            var seen = 0L; var lastStats = System.nanoTime()
            // BLE: host→phone writes without response can stall for >1.5 s under load while the link is
            // healthy (measured on a real phone), so rely on the GATT disconnect callback and allow 5 s
            // of pong silence. The host's own 250 ms input timeout still neutralizes controls.
            val deadMs = if (link.kind == LinkKind.BLE) 5000 else 1000
            while (!link.closed) {
                if (link.isDown) { pathFailed(link, "Bluetooth link disconnected"); return@Thread }
                // Feedback may arrive once per link; the session's replay window keeps only the first copy.
                try { ch.receive(100)?.let { haptics.rumble(it.low, it.high, it.ms) } }
                catch (e: Exception) { pathFailed(link, "input link: ${describe(e)}"); return@Thread }
                if (ch.rttMs.total != seen) { seen = ch.rttMs.total; link.lastAckNs = System.nanoTime(); lastAckNs = link.lastAckNs }
                val age = (System.nanoTime() - link.lastAckNs) / 1_000_000
                if (age > deadMs && !link.closed) { watchdogTrips++; _stats.value = _stats.value.copy(watchdogTrips = watchdogTrips); pathFailed(link, "no response for $age ms on ${link.kind.label}"); return@Thread }
                val t = System.nanoTime()
                if (t - lastStats >= 2_000_000_000L) {
                    lastStats = t
                    // Round trip is per link; the host shows each link's own.
                    runCatching { c.send(Control.Stats(ch.rttMs.percentile(50.0)?.toFloat(), ch.rttMs.percentile(95.0)?.toFloat(), sensorHz.toFloat().takeIf { it > 0 }, _stats.value.sendHz.toFloat(), battery())) }
                }
            }
        }, "c9-rx-${link.kind}").start()

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (!link.closed) {
                val (d, edge) = link.out.take(100) ?: continue
                try {
                    if (d.size > 3 && d[3].toInt() == PacketType.INPUT.code) ch.sendRawInput(d, edge) else ch.sendRaw(d)
                } catch (e: Exception) { pathFailed(link, describe(e)); return@Thread }
            }
        }, "c9-out-${link.kind}").start()
    }

    /**
     * One sender per session: reads the latest state, seals each frame once and queues the same bytes on
     * every link (never waiting for any link), plus per-link pings. Runs until the session changes.
     */
    private fun startSender(crypto: SessionCrypto) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val hbActive = crypto.info.heartbeatMs.coerceIn(10, 1000) * 1_000_000L
            val hbIdle = crypto.info.idleHeartbeatMs.coerceIn(50, 2000) * 1_000_000L
            var sentVersion = -1L; var lastSend = 0L; var lastStats = System.nanoTime()
            var prev: InputState? = null; var prevFlags = 0
            var windowStart = System.nanoTime(); var windowSent = 0L; var sendHz = 0.0
            while (sessionCrypto === crypto) {
                val now = System.nanoTime()
                val (s, f, v) = lock.withLock { Triple(if (paused) InputState.NEUTRAL else latest, latestFlags or (if (paused) FLAG_PAUSED else 0), version) }
                val hb = if (s.isNeutral) hbIdle else hbActive
                val ps = paths()
                if (v != sentVersion || now - lastSend >= hb) {
                    val d = crypto.sealInput(s, f)
                    val edge = isEdge(prev, prevFlags, s, f)
                    for (p in ps) p.out.offer(d, input = true, edge = edge)
                    prev = s; prevFlags = f; sentVersion = v; lastSend = now; lastSendNs = now; windowSent++
                }
                var nextPing = Long.MAX_VALUE
                for (p in ps) {
                    val pingNs = if (p.kind == LinkKind.BLE) 500_000_000L else 250_000_000L
                    if (now - p.lastPingNs >= pingNs) { p.out.offer(p.channel.pingDatagram(), input = false, edge = false); p.lastPingNs = now }
                    nextPing = minOf(nextPing, p.lastPingNs + pingNs)
                }
                if (now - windowStart >= 1_000_000_000L) { sendHz = windowSent * 1e9 / (now - windowStart); windowStart = now; windowSent = 0 }
                if (now - lastStats >= 2_000_000_000L) {
                    lastStats = now
                    val primary = active
                    _stats.value = _stats.value.copy(rttP50 = primary?.channel?.rttMs?.percentile(50.0), rttP95 = primary?.channel?.rttMs?.percentile(95.0),
                        sendHz = sendHz, sent = ps.sumOf { it.channel.sent }, sensorHz = sensorHz)
                }
                lock.withLock {
                    if (version == sentVersion && sessionCrypto === crypto) {
                        val untilHb = (lastSend + hb - System.nanoTime()) / 1_000_000
                        val untilPing = (nextPing - System.nanoTime()) / 1_000_000
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
        /** Minimum time between attempts at an unmeasured IP link while on BLE. */
        private const val PROBE_MS = 30_000L
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
