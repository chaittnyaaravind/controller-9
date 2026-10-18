package dev.ninecontroller.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Carries INPUT datagrams (SPEC §6.1) over one physical link. */
interface DatagramLink : AutoCloseable {
    fun send(d: ByteArray)
    /** Next datagram from the host, or null after [timeoutMs]. */
    fun receive(timeoutMs: Int): ByteArray?
}

class UdpLink(host: InetAddress, port: Int) : DatagramLink {
    private val socket = DatagramSocket().apply { connect(InetSocketAddress(host, port)) }
    private val buf = ByteArray(512)
    override fun send(d: ByteArray) = socket.send(DatagramPacket(d, d.size))
    override fun receive(timeoutMs: Int): ByteArray? {
        socket.soTimeout = maxOf(timeoutMs, 1)
        val p = DatagramPacket(buf, buf.size)
        return try { socket.receive(p); buf.copyOf(p.length) }
        catch (_: SocketTimeoutException) { null }
        catch (e: java.net.SocketException) { if (socket.isClosed) throw LinkClosedException() else throw e }
    }
    override fun close() = socket.close()
}

/** Thrown by a link's receive after the link was closed (normal during failover). */
class LinkClosedException : java.io.IOException("link closed")

/** Blocking InputStream fed with byte chunks from any thread (safer than piped streams). */
class QueueInputStream : InputStream() {
    private val q = LinkedBlockingQueue<ByteArray>()
    private var cur = ByteArray(0); private var pos = 0
    @Volatile private var closed = false
    fun feed(b: ByteArray) { if (b.isNotEmpty()) q.put(b) }
    fun end() { closed = true; q.put(ByteArray(0)) }
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (pos >= cur.size) {
            val next = q.take()
            if (next.isEmpty()) { if (closed) { q.put(next); return -1 } else continue }
            cur = next; pos = 0
        }
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n); pos += n
        return n
    }
    override fun close() = end()
}

/**
 * Adapts a message-based link (stream records, BLE characteristics) to the framed control stream
 * ControlConnection expects (`len:u16be || frame`).
 */
class ControlBridge(private val sendMessage: (ByteArray) -> Unit) {
    val input = QueueInputStream()
    val output: OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            pending.write(b, off, len)
            while (true) {
                val buf = pending.toByteArray()
                if (buf.size < 2) return
                val n = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                if (buf.size < 2 + n) return
                sendMessage(buf.copyOfRange(2, 2 + n))
                pending.reset(); pending.write(buf, 2 + n, buf.size - 2 - n)
            }
        }
    }
    /** A complete CONTROL frame arrived from the host. */
    fun deliver(frame: ByteArray) = input.feed(byteArrayOf((frame.size shr 8).toByte(), frame.size.toByte()) + frame)
    fun close() = input.end()
}

/**
 * Client side of the stream binding (SPEC §8.2) over any reliable byte stream: TCP-only networks
 * (port 47902), and in future RFCOMM / USB accessory streams.
 */
class StreamBinding(private val rawIn: InputStream, private val rawOut: OutputStream, private val closer: AutoCloseable) : DatagramLink {
    private val datagrams = LinkedBlockingQueue<ByteArray>()
    val control = ControlBridge { frame -> write(Record.Control(frame)) }
    @Volatile var failure: Exception? = null; private set
    private val reader = Thread({
        val rr = RecordReader(); val buf = ByteArray(4096)
        try {
            while (true) {
                val n = rawIn.read(buf); if (n < 0) break
                rr.push(buf, n)
                while (true) {
                    when (val r = rr.next() ?: break) {
                        is Record.Control -> control.deliver(r.body)
                        is Record.Datagram -> datagrams.offer(r.body)
                    }
                }
            }
        } catch (e: Exception) { failure = e }
        control.close()
    }, "c9-stream-reader").apply { isDaemon = true; start() }

    @Synchronized private fun write(r: Record) { rawOut.write(r.encode()); rawOut.flush() }
    override fun send(d: ByteArray) = write(Record.Datagram(d))
    override fun receive(timeoutMs: Int): ByteArray? = datagrams.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    override fun close() { runCatching { closer.close() }; control.close() }

    companion object {
        fun tcp(host: String, port: Int = LinkConst.STREAM_TCP_PORT, timeoutMs: Int = 3000): StreamBinding {
            val s = Socket(); s.tcpNoDelay = true; s.connect(InetSocketAddress(host, port), timeoutMs)
            return StreamBinding(s.getInputStream(), s.getOutputStream(), s)
        }
    }
}

internal fun readFrameFrom(inp: DataInputStream): ByteArray {
    val n = try { inp.readUnsignedShort() } catch (e: EOFException) { throw EOFException("host closed the connection") }
    if (n == 0) throw ProtocolException("empty frame")
    return ByteArray(n).also { inp.readFully(it) }
}
