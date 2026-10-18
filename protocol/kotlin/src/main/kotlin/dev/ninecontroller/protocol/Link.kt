package dev.ninecontroller.protocol

import java.io.ByteArrayOutputStream

/** Transport bindings (SPEC §8) — mirrors protocol/rust/src/link.rs. */
object LinkConst {
    const val REC_CONTROL = 0x01
    const val REC_DATAGRAM = 0x02
    const val STREAM_TCP_PORT = 47902
    const val BLE_SERVICE = "c9c90001-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    const val BLE_CONTROL_UP = "c9c90002-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    const val BLE_CONTROL_DOWN = "c9c90003-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    const val BLE_INPUT_UP = "c9c90004-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    const val BLE_INPUT_DOWN = "c9c90005-1c3a-4e9d-8f2b-6c7a0b5d4e10"
    const val SEG_FIRST = 0x80
    const val SEG_LAST = 0x40
}

sealed class Record {
    abstract val body: ByteArray
    class Control(override val body: ByteArray) : Record()
    class Datagram(override val body: ByteArray) : Record()

    fun encode(): ByteArray {
        require(body.isNotEmpty() && body.size <= 65535)
        val kind = if (this is Control) LinkConst.REC_CONTROL else LinkConst.REC_DATAGRAM
        return byteArrayOf(kind.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }
}

class LinkException(message: String) : Exception(message)

/** Incremental stream-record parser. */
class RecordReader {
    private var buf = ByteArray(0)
    fun push(bytes: ByteArray, len: Int = bytes.size) { buf += bytes.copyOf(len) }
    fun next(): Record? {
        if (buf.size < 3) return null
        val kind = buf[0].toInt() and 0xFF
        val len = ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
        if (len == 0) throw LinkException("record length out of range")
        if (kind != LinkConst.REC_CONTROL && kind != LinkConst.REC_DATAGRAM) throw LinkException("unknown record kind $kind")
        if (buf.size < 3 + len) return null
        val body = buf.copyOfRange(3, 3 + len)
        buf = buf.copyOfRange(3 + len, buf.size)
        return if (kind == LinkConst.REC_CONTROL) Record.Control(body) else Record.Datagram(body)
    }
}

/** Splits messages into characteristic values for one GATT characteristic. */
class Segmenter {
    private var counter = 0
    fun segment(msg: ByteArray, attMtu: Int): List<ByteArray> {
        val chunk = maxOf(attMtu - 4, 1)
        val pieces = if (msg.isEmpty()) listOf(ByteArray(0)) else msg.toList().chunked(chunk).map { it.toByteArray() }
        return pieces.mapIndexed { i, c ->
            var hdr = counter and 0x3F
            counter = (counter + 1) and 0x3F
            if (i == 0) hdr = hdr or LinkConst.SEG_FIRST
            if (i == pieces.size - 1) hdr = hdr or LinkConst.SEG_LAST
            byteArrayOf(hdr.toByte()) + c
        }
    }
}

/** Reassembles messages; drops partial messages on any counter gap (mirrors the Rust semantics exactly). */
class Reassembler(private val max: Int) {
    private var expect: Int? = null
    private var partial: ByteArrayOutputStream? = null
    private var discarding = false
    var dropped = 0L; private set

    fun push(value: ByteArray): ByteArray? {
        if (value.isEmpty()) return null
        val hdr = value[0].toInt() and 0xFF
        val ctr = hdr and 0x3F
        val gap = expect != null && expect != ctr
        expect = (ctr + 1) and 0x3F
        if (gap && partial != null) { partial = null; dropped++ }
        if (hdr and LinkConst.SEG_FIRST != 0) {
            if (partial != null) dropped++
            partial = ByteArrayOutputStream(); discarding = false
        }
        val p = partial ?: run {
            if (!discarding) dropped++
            discarding = hdr and LinkConst.SEG_LAST == 0
            return null
        }
        p.write(value, 1, value.size - 1)
        if (p.size() > max) { partial = null; dropped++; discarding = hdr and LinkConst.SEG_LAST == 0; return null }
        if (hdr and LinkConst.SEG_LAST != 0) { partial = null; return p.toByteArray() }
        return null
    }
}
