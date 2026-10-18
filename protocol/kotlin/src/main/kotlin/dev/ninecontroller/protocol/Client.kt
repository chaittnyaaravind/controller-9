package dev.ninecontroller.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

class ProtocolException(message: String) : Exception(message)
class RefusedException(val code: String) : Exception(code)
class WrongHostException : Exception("Host identity does not match the QR code")

data class SessionInfo(
    val slot: Int, val sessionId: Int, val udpPort: Int, val kIn: ByteArray, val kOut: ByteArray,
    val heartbeatMs: Int, val idleHeartbeatMs: Int, val timeoutMs: Int, val backendWarning: String?,
) { override fun toString() = "SessionInfo(slot=$slot, udpPort=$udpPort, keys=<redacted>)" }

/**
 * Encrypted control channel (blocking; call from a background thread / Dispatchers.IO).
 * Mirrors hosts/core/src/client.rs.
 */
class ControlConnection private constructor(
    private val socket: Socket,
    private val t: Transport,
    val host: HostHello,
) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream())
    val hostStatic: ByteArray get() = t.remoteStatic
    val handshakeHash: ByteArray get() = t.handshakeHash
    val remote: InetAddress get() = socket.inetAddress

    @Synchronized fun send(m: Control) = writeFrame(output, t.encrypt(m.toJson()))

    fun recv(): Control = Control.fromJson(t.decrypt(readFrame(input)))

    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }

    fun pairQr(uri: PairUri) {
        if (!hostStatic.contentEquals(uri.hostStatic)) throw WrongHostException()
        send(Control.PairQr(b64(Pairing.qrProof(uri.token, handshakeHash))))
        when (val r = recv()) {
            is Control.Paired -> Unit
            is Control.PairFailed -> throw RefusedException(r.reason)
            is Control.Error -> throw RefusedException(r.code)
            else -> throw ProtocolException("unexpected $r")
        }
    }

    /** Numeric comparison. [confirm] shows the code and blocks until the user decides. */
    fun pairCode(confirm: (code: Int) -> Boolean): Int {
        send(Control.PairCode)
        val c = when (val r = recv()) {
            is Control.Commit -> unb64(r.c)
            is Control.Error -> throw RefusedException(r.code)
            else -> throw ProtocolException("unexpected $r")
        }
        val na = Crypto.random(16)
        send(Control.Nonce(b64(na)))
        val nb = when (val r = recv()) { is Control.Reveal -> unb64(r.n); else -> throw ProtocolException("unexpected $r") }
        if (nb.size != 16 || !Pairing.verifyCommitment(handshakeHash, nb, c)) {
            throw ProtocolException("Host commitment mismatch — possible interception, pairing aborted")
        }
        val code = Pairing.sasCode(handshakeHash, na, nb)
        val ok = confirm(code)
        send(if (ok) Control.CodeOk else Control.CodeReject)
        if (!ok) throw RefusedException("rejected on controller")
        return when (val r = recv()) {
            is Control.Paired -> code
            is Control.PairFailed -> throw RefusedException(r.reason)
            else -> throw ProtocolException("unexpected $r")
        }
    }

    fun open(resumeSlot: Int?, profile: String?): SessionInfo {
        send(Control.Open(resumeSlot, profile))
        val s = when (val r = recv()) {
            is Control.Session -> r
            is Control.PairRequired -> throw RefusedException("not_paired")
            is Control.Error -> throw RefusedException(r.code)
            else -> throw ProtocolException("unexpected $r")
        }
        var warning: String? = null
        val old = socket.soTimeout
        socket.soTimeout = 50
        try { (recv() as? Control.Error)?.takeIf { it.code == "backend_unavailable" }?.let { warning = it.message ?: it.code } }
        catch (_: SocketTimeoutException) {} finally { socket.soTimeout = old }
        return SessionInfo(s.slot, s.sessionId.toInt(), s.udpPort, unb64(s.kIn), unb64(s.kOut), s.heartbeatMs, s.idleHeartbeatMs, s.timeoutMs, warning)
    }

    override fun close() { runCatching { send(Control.Bye()) }; runCatching { socket.close() } }

    companion object {
        private val b = Base64.getEncoder()
        private val d = Base64.getDecoder()
        fun b64(x: ByteArray): String = b.encodeToString(x)
        fun unb64(s: String): ByteArray = try { d.decode(s) } catch (e: IllegalArgumentException) { throw ProtocolException("bad base64") }

        fun connect(addr: InetSocketAddress, identity: KeyPair, hello: ControllerHello, timeoutMs: Int = 3000): ControlConnection {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(addr, timeoutMs)
            s.soTimeout = timeoutMs
            try {
                val inp = DataInputStream(s.getInputStream())
                val out = DataOutputStream(s.getOutputStream())
                val hs = Handshake(true, KeyPair.fromSecret(identity.secretBytes()))
                writeFrame(out, hs.writeMessage(Control.json.encodeToString(InitHello.serializer(), InitHello(listOf(1))).toByteArray()))
                val host = Control.json.decodeFromString(HostHello.serializer(), hs.readMessage(readFrame(inp)).decodeToString())
                writeFrame(out, hs.writeMessage(Control.json.encodeToString(ControllerHello.serializer(), hello).toByteArray()))
                s.soTimeout = 0
                return ControlConnection(s, hs.intoTransport(), host)
            } catch (e: Exception) { s.close(); throw e }
        }

        private fun writeFrame(out: DataOutputStream, b: ByteArray) {
            require(b.isNotEmpty() && b.size <= 65535)
            synchronized(out) { out.writeShort(b.size); out.write(b); out.flush() }
        }

        private fun readFrame(inp: DataInputStream): ByteArray {
            val n = try { inp.readUnsignedShort() } catch (e: EOFException) { throw EOFException("host closed the connection") }
            if (n == 0) throw ProtocolException("empty frame")
            return ByteArray(n).also { inp.readFully(it) }
        }
    }
}

/** UDP input channel. `send*` may be called from one sender thread; `receive` from another. */
class InputChannel(host: InetAddress, val info: SessionInfo) : AutoCloseable {
    private val socket = DatagramSocket().apply { connect(InetSocketAddress(host, info.udpPort)) }
    private var counter = 0L
    private val freshIn = Freshness()
    private val epochNs = System.nanoTime()
    private val pings = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private var nextPing = 1
    val rttMs = Window(256)
    var sent = 0L; private set

    fun nowUs(): Long = (System.nanoTime() - epochNs) / 1000

    @Synchronized private fun seal(type: PacketType, payload: ByteArray): ByteArray =
        Datagram.seal(info.kIn, Header(type, info.sessionId, ++counter), payload)

    fun sendInput(state: InputState, flags: Int) {
        val d = seal(PacketType.INPUT, InputFrame(nowUs(), state, flags).encode())
        socket.send(DatagramPacket(d, d.size)); sent++
    }

    fun ping() {
        val id = nextPing++
        val t = nowUs()
        pings[id] = t
        if (pings.size > 64) pings.keys.minOrNull()?.let { pings.remove(it) }
        val d = seal(PacketType.PING, Ping(t, id).encode())
        socket.send(DatagramPacket(d, d.size))
    }

    fun bye() { val d = seal(PacketType.BYE, ByteArray(0)); runCatching { socket.send(DatagramPacket(d, d.size)) } }

    /** Blocks up to [timeoutMs]; returns host feedback (rumble) if one arrived, handling pongs internally. */
    fun receive(timeoutMs: Int): Feedback? {
        socket.soTimeout = timeoutMs
        val buf = ByteArray(512)
        val p = DatagramPacket(buf, buf.size)
        try { socket.receive(p) } catch (_: SocketTimeoutException) { return null }
        val (h, pt) = try { Datagram.open(info.kOut, buf, p.length) } catch (_: FrameException) { return null }
        if (!freshIn.accept(h.counter)) return null
        return when (h.type) {
            PacketType.PONG -> { runCatching { Pong.decode(pt) }.getOrNull()?.let { pong ->
                if (pings.remove(pong.id) != null) rttMs.push((nowUs() - pong.tUs) / 1000.0) }; null }
            PacketType.FEEDBACK -> runCatching { Feedback.decode(pt) }.getOrNull()
            else -> null
        }
    }

    override fun close() { socket.close() }
}
