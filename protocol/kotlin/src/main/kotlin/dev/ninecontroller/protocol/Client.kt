package dev.ninecontroller.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
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
 * Encrypted control channel over any framed byte stream (TCP socket, stream binding, BLE bridge).
 * Blocking; call from a background thread. Mirrors hosts/core/src/client.rs.
 */
class ControlConnection private constructor(
    inStream: InputStream,
    outStream: OutputStream,
    private val closer: AutoCloseable,
    private val t: Transport,
    val host: HostHello,
    /** Remote IP for the IP binding (UDP input goes there); null on non-IP links. */
    val remote: InetAddress?,
    /** Local IP of the IP binding — used to classify the link (USB, hotspot, Wi-Fi Direct…). */
    val local: InetAddress?,
) : AutoCloseable {
    private val input = DataInputStream(inStream.buffered())
    private val output = DataOutputStream(outStream)
    val hostStatic: ByteArray get() = t.remoteStatic
    val handshakeHash: ByteArray get() = t.handshakeHash

    @Synchronized fun send(m: Control) = writeFrame(output, t.encrypt(m.toJson()))

    fun recv(): Control = Control.fromJson(t.decrypt(readFrameFrom(input)))

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

    /**
     * Opens a session. A host without a virtual-pad driver follows `session` with
     * `error(backend_unavailable)`; the caller's control-reader loop surfaces it (a timed read here
     * cannot be cancelled on non-socket links).
     */
    fun open(resumeSlot: Int?, profile: String?): SessionInfo = openWith(Control.Open(resumeSlot, profile))

    /**
     * Add this link to the live session [sessionId] (SPEC §8.5). The host replies with the same slot,
     * session id and keys after checking that this handshake's identity owns the session; nothing is
     * reset on the host. Only for hosts whose hello says `multipath`.
     */
    fun join(sessionId: Int): SessionInfo {
        if (!host.multipath) throw RefusedException("multipath_unsupported")
        return openWith(Control.Open(join = sessionId.toLong() and 0xFFFFFFFFL))
    }

    private fun openWith(o: Control.Open): SessionInfo {
        send(o)
        val s = when (val r = recv()) {
            is Control.Session -> r
            is Control.PairRequired -> throw RefusedException("not_paired")
            is Control.Error -> throw RefusedException(r.code)
            else -> throw ProtocolException("unexpected $r")
        }
        return SessionInfo(s.slot, s.sessionId.toInt(), s.udpPort, unb64(s.kIn), unb64(s.kOut), s.heartbeatMs, s.idleHeartbeatMs, s.timeoutMs, null)
    }

    override fun close() { runCatching { send(Control.Bye()) }; runCatching { closer.close() } }
    fun abort() { runCatching { closer.close() } }

    companion object {
        private val b = Base64.getEncoder()
        private val d = Base64.getDecoder()
        fun b64(x: ByteArray): String = b.encodeToString(x)
        fun unb64(s: String): ByteArray = try { d.decode(s) } catch (e: IllegalArgumentException) { throw ProtocolException("bad base64") }

        /** IP binding: TCP control channel. */
        fun connect(addr: InetSocketAddress, identity: KeyPair, hello: ControllerHello, timeoutMs: Int = 3000): ControlConnection {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(addr, timeoutMs)
            s.soTimeout = timeoutMs
            try {
                val c = handshake(s.getInputStream(), s.getOutputStream(), s, identity, hello, s.inetAddress, s.localAddress)
                s.soTimeout = 0
                return c
            } catch (e: Exception) { s.close(); throw e }
        }

        /** Any binding: handshake over an already-framed control stream. */
        fun connectOver(inStream: InputStream, outStream: OutputStream, closer: AutoCloseable, identity: KeyPair, hello: ControllerHello): ControlConnection =
            handshake(inStream, outStream, closer, identity, hello, null, null)

        private fun handshake(inStream: InputStream, outStream: OutputStream, closer: AutoCloseable, identity: KeyPair, hello: ControllerHello,
                              remote: InetAddress?, local: InetAddress?): ControlConnection {
            val inp = DataInputStream(inStream)
            val out = DataOutputStream(outStream)
            val hs = Handshake(true, KeyPair.fromSecret(identity.secretBytes()))
            writeFrame(out, hs.writeMessage(Control.json.encodeToString(InitHello.serializer(), InitHello(listOf(1))).toByteArray()))
            val host = Control.json.decodeFromString(HostHello.serializer(), hs.readMessage(readFrameFrom(inp)).decodeToString())
            writeFrame(out, hs.writeMessage(Control.json.encodeToString(ControllerHello.serializer(), hello).toByteArray()))
            return ControlConnection(inStream, outStream, closer, hs.intoTransport(), host, remote, local)
        }

        private fun writeFrame(out: DataOutputStream, b: ByteArray) {
            require(b.isNotEmpty() && b.size <= 65535)
            synchronized(out) { out.writeShort(b.size); out.write(b); out.flush() }
        }
    }
}

/**
 * Datagram crypto of one session, shared by every link attached to it (SPEC §8.5): one counter (so a
 * nonce is never reused across links), one sender clock and one replay window for host datagrams,
 * which may arrive once per link.
 */
class SessionCrypto(val info: SessionInfo) {
    private var counter = 0L
    private val epochNs = System.nanoTime()
    val rx = ReplayWindow()
    fun nowUs(): Long = (System.nanoTime() - epochNs) / 1000
    @Synchronized fun seal(type: PacketType, payload: ByteArray): ByteArray =
        Datagram.seal(info.kIn, Header(type, info.sessionId, ++counter), payload)
    /** One input frame, sealed once; the same bytes may be sent over every link. */
    fun sealInput(state: InputState, flags: Int): ByteArray = seal(PacketType.INPUT, InputFrame(nowUs(), state, flags).encode())
}

/**
 * Input channel over one [DatagramLink]. `send*` from one sender thread; `receive` from another.
 * Several channels (one per link) may share one [SessionCrypto].
 */
class InputChannel(private val link: DatagramLink, val info: SessionInfo, val crypto: SessionCrypto = SessionCrypto(info)) : AutoCloseable {
    /** IP binding: UDP to the host's input port. */
    constructor(host: InetAddress, info: SessionInfo, crypto: SessionCrypto = SessionCrypto(info)) : this(UdpLink(host, info.udpPort), info, crypto)
    private val pings = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private var nextPing = 1
    /** Round trip of this link alone (its pings are answered on the link they came over). */
    val rttMs = Window(256)
    var sent = 0L; private set

    fun nowUs(): Long = crypto.nowUs()

    private fun seal(type: PacketType, payload: ByteArray): ByteArray = crypto.seal(type, payload)

    fun sendInput(state: InputState, flags: Int) = sendRaw(crypto.sealInput(state, flags))

    /** Send an already sealed datagram of this session (the same bytes go over every link). */
    fun sendRaw(d: ByteArray) { link.send(d); sent++ }
    /** Send a sealed INPUT datagram; see [DatagramLink.sendInput]. */
    fun sendRawInput(d: ByteArray, edge: Boolean) { link.sendInput(d, edge); sent++ }

    /** Seal a ping for this link and remember it for the round-trip measurement. */
    fun pingDatagram(): ByteArray {
        val id = nextPing++
        val t = nowUs()
        pings[id] = t
        if (pings.size > 64) pings.keys.minOrNull()?.let { pings.remove(it) }
        return seal(PacketType.PING, Ping(t, id).encode())
    }

    fun ping() = link.send(pingDatagram())

    fun bye() { runCatching { link.send(seal(PacketType.BYE, ByteArray(0))) } }

    /** Blocks up to [timeoutMs]; returns host feedback (rumble) if one arrived, handling pongs internally. */
    fun receive(timeoutMs: Int): Feedback? {
        val d = link.receive(timeoutMs) ?: return null
        val (h, pt) = try { Datagram.open(info.kOut, d) } catch (_: FrameException) { return null }
        if (crypto.rx.check(h.counter) != Seen.NEW) return null
        return when (h.type) {
            PacketType.PONG -> { runCatching { Pong.decode(pt) }.getOrNull()?.let { pong ->
                if (pings.remove(pong.id) != null) rttMs.push((nowUs() - pong.tUs) / 1000.0) }; null }
            PacketType.FEEDBACK -> runCatching { Feedback.decode(pt) }.getOrNull()
            else -> null
        }
    }

    override fun close() { link.close() }
}
