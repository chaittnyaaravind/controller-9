package dev.ninecontroller.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PacketType(val code: Int) {
    INPUT(1), PING(2), PONG(3), FEEDBACK(4), BYE(5);
    companion object { fun of(v: Int) = entries.firstOrNull { it.code == v } }
}

enum class FrameError { TOO_SHORT, TOO_LONG, BAD_MAGIC, BAD_VERSION, UNKNOWN_TYPE, AUTH, BAD_LENGTH, OUT_OF_RANGE }

class FrameException(val error: FrameError) : Exception(error.name)

data class Header(val type: PacketType, val sessionId: Int, val counter: Long) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        .put('9'.code.toByte()).put('C'.code.toByte()).put(PROTO_VERSION.toByte()).put(type.code.toByte())
        .putInt(sessionId).putLong(counter).array()

    companion object {
        const val HEADER_LEN = 16
        const val TAG_LEN = 16
        const val MIN_DATAGRAM = HEADER_LEN + TAG_LEN
        const val MAX_DATAGRAM = 256

        fun peek(d: ByteArray, len: Int = d.size): Header {
            if (len < MIN_DATAGRAM) throw FrameException(FrameError.TOO_SHORT)
            if (len > MAX_DATAGRAM) throw FrameException(FrameError.TOO_LONG)
            if (d[0] != '9'.code.toByte() || d[1] != 'C'.code.toByte()) throw FrameException(FrameError.BAD_MAGIC)
            if (d[2].toInt() != PROTO_VERSION) throw FrameException(FrameError.BAD_VERSION)
            val t = PacketType.of(d[3].toInt() and 0xFF) ?: throw FrameException(FrameError.UNKNOWN_TYPE)
            val b = ByteBuffer.wrap(d, 4, 12).order(ByteOrder.LITTLE_ENDIAN)
            return Header(t, b.int, b.long)
        }
    }
}

const val PROTO_VERSION = 1

object Datagram {
    fun seal(key: ByteArray, header: Header, payload: ByteArray): ByteArray {
        val h = header.encode()
        return h + Crypto.seal(key, Crypto.nonce(header.counter), h, payload)
    }

    fun open(key: ByteArray, d: ByteArray, len: Int = d.size): Pair<Header, ByteArray> {
        val header = Header.peek(d, len)
        val pt = try {
            Crypto.open(key, Crypto.nonce(header.counter), d.copyOfRange(0, Header.HEADER_LEN), d.copyOfRange(Header.HEADER_LEN, len))
        } catch (e: CryptoException) { throw FrameException(FrameError.AUTH) }
        return header to pt
    }
}

private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
private fun rd(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

data class InputFrame(val tUs: Long, val state: InputState, val flags: Int) {
    fun encode(): ByteArray = le(LEN).putLong(tUs).putInt(state.buttons)
        .putShort(state.lx.toShort()).putShort(state.ly.toShort()).putShort(state.rx.toShort()).putShort(state.ry.toShort())
        .putShort(state.lt.toShort()).putShort(state.rt.toShort()).putShort(flags.toShort()).array()

    companion object {
        const val LEN = 26
        fun decode(b: ByteArray): InputFrame {
            if (b.size != LEN) throw FrameException(FrameError.BAD_LENGTH)
            val r = rd(b)
            val t = r.long
            val buttons = r.int
            val axes = IntArray(4) { r.short.toInt() }
            val lt = r.short.toInt() and 0xFFFF
            val rt = r.short.toInt() and 0xFFFF
            val flags = r.short.toInt() and 0xFFFF
            if (buttons and VALID_BUTTONS.inv() != 0 || flags and VALID_FLAGS.inv() != 0 || axes.any { it == -32768 })
                throw FrameException(FrameError.OUT_OF_RANGE)
            return InputFrame(t, InputState(buttons, axes[0], axes[1], axes[2], axes[3], lt, rt), flags)
        }
    }
}

data class Ping(val tUs: Long, val id: Int) {
    fun encode(): ByteArray = le(12).putLong(tUs).putInt(id).array()
    companion object {
        fun decode(b: ByteArray): Ping {
            if (b.size != 12) throw FrameException(FrameError.BAD_LENGTH)
            val r = rd(b); return Ping(r.long, r.int)
        }
    }
}

data class Pong(val tUs: Long, val id: Int, val hostRxUs: Long, val hostTxUs: Long) {
    fun encode(): ByteArray = le(28).putLong(tUs).putInt(id).putLong(hostRxUs).putLong(hostTxUs).array()
    companion object {
        fun decode(b: ByteArray): Pong {
            if (b.size != 28) throw FrameException(FrameError.BAD_LENGTH)
            val r = rd(b); return Pong(r.long, r.int, r.long, r.long)
        }
    }
}

data class Feedback(val low: Int, val high: Int, val ms: Int) {
    fun encode(): ByteArray = le(6).putShort(low.toShort()).putShort(high.toShort()).putShort(ms.toShort()).array()
    companion object {
        fun decode(b: ByteArray): Feedback {
            if (b.size != 6) throw FrameException(FrameError.BAD_LENGTH)
            val r = rd(b)
            val f = Feedback(r.short.toInt() and 0xFFFF, r.short.toInt() and 0xFFFF, r.short.toInt() and 0xFFFF)
            if (f.ms > 5000) throw FrameException(FrameError.OUT_OF_RANGE)
            return f
        }
    }
}

/** Receiver freshness: only counters above the highest accepted are applied (stale/replay rejection). */
class Freshness {
    var highest = 0L; private set
    var accepted = 0L; private set
    var stale = 0L; private set
    var lost = 0L; private set

    fun accept(counter: Long): Boolean {
        if (java.lang.Long.compareUnsigned(counter, highest) <= 0) { stale++; return false }
        if (highest != 0L) lost += counter - highest - 1
        highest = counter
        accepted++
        return true
    }
}
