package dev.ninecontroller.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

object Pairing {
    fun qrProof(token: ByteArray, h: ByteArray): ByteArray = Crypto.hmac(token, "9ctl-qr".toByteArray(), h)
    fun commitment(h: ByteArray, nb: ByteArray): ByteArray = Crypto.sha256("9ctl-commit".toByteArray(), h, nb)
    fun verifyCommitment(h: ByteArray, nb: ByteArray, c: ByteArray) = c.size == 32 && Crypto.constantTimeEquals(commitment(h, nb), c)

    /** Six-digit short authentication string, 0..999999. */
    fun sasCode(h: ByteArray, na: ByteArray, nb: ByteArray): Int {
        val d = Crypto.sha256("9ctl-sas".toByteArray(), h, na, nb)
        val v = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return (v % 1_000_000).toInt()
    }

    fun formatCode(code: Int): String = "%06d".format(code).let { "${it.substring(0, 3)} ${it.substring(3)}" }

    fun hostId(staticPub: ByteArray): String = Crypto.sha256(staticPub).copyOf(8).joinToString("") { "%02x".format(it) }
}

data class PairUri(val addr: String, val port: Int, val hostStatic: ByteArray, val token: ByteArray, val name: String, val extra: List<String> = emptyList()) {
    /** Every address to try (primary first). SPEC §8.1. */
    val addresses: List<String> get() = listOf(addr) + extra
    fun toUri(): String {
        val b = Base64.getUrlEncoder().withoutPadding()
        val base = "ninectl://pair?v=1&a=${enc(addr)}&p=$port&k=${b.encodeToString(hostStatic)}&t=${b.encodeToString(token)}&n=${enc(name)}"
        return if (extra.isEmpty()) base else base + "&x=" + extra.joinToString(",") { enc(it) }
    }

    override fun equals(other: Any?) = other is PairUri && addr == other.addr && port == other.port &&
        hostStatic.contentEquals(other.hostStatic) && token.contentEquals(other.token) && name == other.name && extra == other.extra
    override fun hashCode() = addr.hashCode() * 31 + port

    companion object {
        // RFC 3986 unreserved set, matching the Rust encoder (spaces are %20, never '+').
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

        fun parse(uri: String): PairUri? {
            val q = uri.removePrefix("ninectl://pair?").takeIf { it != uri } ?: return null
            val m = HashMap<String, String>()
            for (kv in q.split('&')) {
                val i = kv.indexOf('=')
                if (i < 0) return null
                m[kv.substring(0, i)] = kv.substring(i + 1)
            }
            if (m["v"] != "1") return null
            return try {
                val d = Base64.getUrlDecoder()
                val k = d.decode(m["k"] ?: return null)
                val t = d.decode(m["t"] ?: return null)
                if (k.size != 32 || t.size != 16) return null
                PairUri(URLDecoder.decode(m["a"] ?: return null, "UTF-8"), m["p"]?.toIntOrNull() ?: return null, k, t,
                    URLDecoder.decode(m["n"] ?: "", "UTF-8"),
                    m["x"]?.split(',')?.filter { it.isNotEmpty() }?.map { URLDecoder.decode(it, "UTF-8") }?.take(8) ?: emptyList())
            } catch (e: IllegalArgumentException) { null }
        }
    }
}
