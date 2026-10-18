package dev.ninecontroller.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Process
import android.util.Base64
import dev.ninecontroller.android.data.HostStore
import dev.ninecontroller.android.data.IdentityStore
import dev.ninecontroller.android.data.PairedHost
import dev.ninecontroller.android.data.SettingsStore
import dev.ninecontroller.android.input.Haptics
import dev.ninecontroller.protocol.Control
import dev.ninecontroller.protocol.ControlConnection
import dev.ninecontroller.protocol.ControllerHello
import dev.ninecontroller.protocol.FLAG_PAUSED
import dev.ninecontroller.protocol.InputChannel
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.PairUri
import dev.ninecontroller.protocol.Pairing
import dev.ninecontroller.protocol.RefusedException
import dev.ninecontroller.protocol.WrongHostException
import dev.ninecontroller.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val hostName: String) : ConnState
    /** Numeric comparison: show [code]; the user confirms here and on the computer. */
    data class ConfirmCode(val hostName: String, val code: String) : ConnState
    data class Connected(val hostName: String, val slot: Int, val backendWarning: String?) : ConnState
    data class Reconnecting(val hostName: String, val attempt: Int, val reason: String) : ConnState
    data class Failed(val message: String) : ConnState
}

data class LinkStats(
    val rttP50: Double? = null, val rttP95: Double? = null, val rttLast: Double? = null,
    val sendHz: Double = 0.0, val sent: Long = 0, val sensorHz: Double = 0.0,
    val pongAgeMs: Long? = null, val reconnects: Int = 0, val lastReconnectMs: Long? = null,
)

/**
 * One connection to one host. Threads: control reader, UDP receiver, input sender (all
 * background, high priority for the sender). Input is sent on change plus heartbeats; the link
 * is declared dead after 1 s without a pong or on control-channel loss, then re-established with
 * the same slot (target: under 3 s).
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
    private val _stats = MutableStateFlow(LinkStats())
    val stats: StateFlow<LinkStats> = _stats

    @Volatile private var control: ControlConnection? = null
    @Volatile private var input: InputChannel? = null
    @Volatile private var want: PairedHost? = null
    @Volatile private var lastSlot: Int? = null
    @Volatile private var profileName: String? = null
    private var session: Job? = null
    private var codeDecision: CompletableFuture<Boolean>? = null

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var latest = InputState.NEUTRAL
    @Volatile private var latestFlags = 0
    @Volatile private var version = 0L
    @Volatile private var paused = false
    @Volatile var sensorHz = 0.0
    private var reconnects = 0

    init {
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (_state.value is ConnState.Reconnecting) restart("Wi-Fi back") }
                override fun onLost(network: Network) { if (_state.value is ConnState.Connected) restart("Wi-Fi lost") }
            })
    }

    private fun hello() = ControllerHello(1, identity.deviceName.take(40), "android", BuildConfig.VERSION_NAME)

    /** Called by the controller engine on every state change (any thread). */
    fun submit(state: InputState, flags: Int) {
        lock.withLock { latest = state; latestFlags = flags; version++; changed.signalAll() }
    }

    fun setPaused(p: Boolean) { lock.withLock { paused = p; version++; changed.signalAll() } }
    fun setProfileName(name: String) { profileName = name }

    // ------------------------------------------------------------------ pairing

    fun pairWithQr(uri: PairUri) {
        scope.launch {
            _state.value = ConnState.Connecting(uri.name.ifEmpty { uri.addr })
            try {
                val host = ControlConnection.connect(InetSocketAddress(uri.addr, uri.port), identity.keyPair(), hello()).use { c ->
                    c.pairQr(uri)
                    remember(c, uri.addr, uri.port)
                }
                connect(host)
            } catch (e: WrongHostException) {
                _state.value = ConnState.Failed("This QR code belongs to a different computer than the one that answered. Pairing stopped.")
            } catch (e: Exception) { _state.value = ConnState.Failed(describe(e)) }
        }
    }

    fun pairWithCode(addr: String, port: Int, name: String) {
        scope.launch {
            _state.value = ConnState.Connecting(name)
            try {
                val host = ControlConnection.connect(InetSocketAddress(addr, port), identity.keyPair(), hello()).use { c ->
                    c.pairCode { code ->
                        val f = CompletableFuture<Boolean>()
                        codeDecision = f
                        _state.value = ConnState.ConfirmCode(c.host.name, Pairing.formatCode(code))
                        runCatching { f.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
                    }
                    remember(c, addr, port)
                }
                connect(host)
            } catch (e: Exception) { _state.value = ConnState.Failed(describe(e)) } finally { codeDecision = null }
        }
    }

    /** Phone-side verdict for numeric comparison. */
    fun confirmCode(matches: Boolean) { codeDecision?.complete(matches) }

    private fun remember(c: ControlConnection, addr: String, port: Int): PairedHost =
        PairedHost(Pairing.hostId(c.hostStatic), c.host.name, Base64.encodeToString(c.hostStatic, Base64.NO_WRAP), addr, port, HostStore.now())
            .also { hosts.upsert(it) }

    // ------------------------------------------------------------------ session

    fun connect(host: PairedHost) {
        want = host
        scope.launch { settings.update { it.copy(lastHost = host.id) } }
        startSession(host, attempt = 0)
    }

    fun disconnect() {
        want = null
        session?.cancel()
        teardown(sendBye = true)
        _state.value = ConnState.Idle
    }

    private fun restart(reason: String) {
        val h = want ?: return
        teardown(sendBye = false)
        startSession(h, attempt = 1, reason = reason)
    }

    private fun startSession(host: PairedHost, attempt: Int, reason: String = "") {
        session?.cancel()
        session = scope.launch {
            var n = attempt
            val t0 = System.nanoTime()
            while (isActive && want == host) {
                _state.value = if (n == 0) ConnState.Connecting(host.name) else ConnState.Reconnecting(host.name, n, reason)
                try {
                    openSession(host)
                    if (n > 0) { reconnects++; _stats.value = _stats.value.copy(reconnects = reconnects, lastReconnectMs = (System.nanoTime() - t0) / 1_000_000) }
                    return@launch
                } catch (e: RefusedException) {
                    if (e.code == "not_paired") { hosts.remove(host.id); want = null; _state.value = ConnState.Failed("${host.name} no longer knows this phone. Pair again."); return@launch }
                    if (e.code == "slots_full") { want = null; _state.value = ConnState.Failed("${host.name} already has four controllers connected."); return@launch }
                    _state.value = ConnState.Reconnecting(host.name, ++n, describe(e))
                } catch (e: SecurityException) {
                    want = null; _state.value = ConnState.Failed(e.message ?: "Host identity changed"); return@launch
                } catch (e: Exception) {
                    _state.value = ConnState.Reconnecting(host.name, ++n, describe(e))
                }
                delay(minOf(100L shl minOf(n, 4), 2000L))
            }
        }
    }

    private fun openSession(host: PairedHost) {
        val c = ControlConnection.connect(InetSocketAddress(host.addr, host.port), identity.keyPair(), hello(), timeoutMs = 1500)
        val pinned = Base64.decode(host.pub, Base64.NO_WRAP)
        if (!c.hostStatic.contentEquals(pinned)) {
            c.close()
            throw SecurityException("The computer at ${host.addr} is not ${host.name} (its identity key changed). Pair again only if you trust it.")
        }
        val info = c.open(lastSlot, profileName)
        lastSlot = info.slot
        val ch = InputChannel(c.remote, info)
        control = c; input = ch
        hosts.touch(host.id, host.addr, host.port)
        _state.value = ConnState.Connected(c.host.name, info.slot, info.backendWarning)
        startThreads(c, ch)
    }

    private fun teardown(sendBye: Boolean) {
        val ch = input; val c = control
        input = null; control = null
        if (sendBye) runCatching { ch?.sendInput(InputState.NEUTRAL, 0); ch?.bye() }
        runCatching { ch?.close() }
        runCatching { if (sendBye) c?.close() else c?.let { it.setReadTimeout(1); it.close() } }
        lock.withLock { changed.signalAll() }
    }

    private fun startThreads(c: ControlConnection, ch: InputChannel) {
        // Control reader: rumble, bye, errors. Any failure means the link is gone.
        Thread({
            try {
                while (control === c) {
                    when (val m = c.recv()) {
                        is Control.Rumble -> haptics.rumble(m.low, m.high, m.ms)
                        is Control.Bye -> { if (m.reason == "unpaired") { want?.let { hosts.remove(it.id) }; want = null; teardown(false); _state.value = ConnState.Failed("This phone was removed on the computer.") } else throw java.io.EOFException(m.reason ?: "host closed") ; return@Thread }
                        else -> Unit
                    }
                }
            } catch (e: Exception) { if (control === c) restart(describe(e)) }
        }, "ninectl-control").start()

        // UDP receiver: pongs (RTT) and game rumble.
        Thread({
            var lastPong = System.nanoTime()
            var seen = 0L
            while (input === ch) {
                val fb = runCatching { ch.receive(100) }.getOrNull()
                fb?.let { haptics.rumble(it.low, it.high, it.ms) }
                if (ch.rttMs.total != seen) { seen = ch.rttMs.total; lastPong = System.nanoTime() }
                val age = (System.nanoTime() - lastPong) / 1_000_000
                if (age > 1000 && input === ch) { restart("no response for ${age} ms"); return@Thread }
            }
        }, "ninectl-udp-rx").start()

        // Sender: on change, plus heartbeats; ping every 250 ms; stats every 2 s.
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val hbActive = ch.info.heartbeatMs.coerceIn(10, 1000) * 1_000_000L
            val hbIdle = ch.info.idleHeartbeatMs.coerceIn(50, 2000) * 1_000_000L
            var sentVersion = -1L; var lastSend = 0L; var lastPing = 0L; var lastStats = System.nanoTime()
            var windowStart = System.nanoTime(); var windowSent = 0L; var sendHz = 0.0
            while (input === ch) {
                val now = System.nanoTime()
                val (s, f, v) = lock.withLock { Triple(if (paused) InputState.NEUTRAL else latest, latestFlags or (if (paused) FLAG_PAUSED else 0), version) }
                val hb = if (s.isNeutral) hbIdle else hbActive
                try {
                    if (v != sentVersion || now - lastSend >= hb) { ch.sendInput(s, f); sentVersion = v; lastSend = now; windowSent++ }
                    if (now - lastPing >= 250_000_000L) { ch.ping(); lastPing = now }
                    if (now - windowStart >= 1_000_000_000L) { sendHz = windowSent * 1e9 / (now - windowStart); windowStart = now; windowSent = 0 }
                    if (now - lastStats >= 2_000_000_000L) {
                        lastStats = now
                        val st = LinkStats(ch.rttMs.percentile(50.0), ch.rttMs.percentile(95.0), null, sendHz, ch.sent, sensorHz,
                            null, reconnects, _stats.value.lastReconnectMs)
                        _stats.value = st
                        runCatching { c.send(Control.Stats(st.rttP50?.toFloat(), st.rttP95?.toFloat(), sensorHz.toFloat().takeIf { it > 0 }, sendHz.toFloat(), battery())) }
                    }
                } catch (e: Exception) { if (input === ch) restart(describe(e)); return@Thread }
                lock.withLock {
                    if (version == sentVersion && input === ch) {
                        val untilHb = (lastSend + hb - System.nanoTime()) / 1_000_000
                        val untilPing = (lastPing + 250_000_000L - System.nanoTime()) / 1_000_000
                        val waitMs = minOf(untilHb, untilPing).coerceIn(1, 250)
                        changed.await(waitMs, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }, "ninectl-sender").start()
    }

    private fun battery(): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    companion object {
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
            is java.net.ConnectException -> "Can’t reach the computer. Is 9-controller running and on the same network?"
            is java.net.SocketTimeoutException -> "The computer didn’t answer in time."
            is java.net.NoRouteToHostException -> "No route to the computer. Check Wi-Fi."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
