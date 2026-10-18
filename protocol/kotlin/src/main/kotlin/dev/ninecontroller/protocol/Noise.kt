package dev.ninecontroller.protocol

/**
 * Noise_XX_25519_ChaChaPoly_SHA256, mirroring protocol/rust/src/noise.rs line for line.
 * Correctness is pinned by protocol/vectors/noise_xx.json and by interop with the Rust host.
 */
object NoiseConst {
    val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256".toByteArray()
    val PROLOGUE = "9ctl/1".toByteArray()
}

class KeyPair private constructor(private val secret: ByteArray) {
    val public: ByteArray = Crypto.x25519Public(secret)
    fun secretBytes(): ByteArray = secret.copyOf()
    internal fun dh(remote: ByteArray): ByteArray = Crypto.x25519(secret, remote)

    companion object {
        fun generate() = KeyPair(Crypto.random(32))
        fun fromSecret(bytes: ByteArray): KeyPair {
            require(bytes.size == 32) { "X25519 secret must be 32 bytes" }
            return KeyPair(bytes.copyOf())
        }
    }
}

internal fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
    val temp = Crypto.hmac(ck, ikm)
    val o1 = Crypto.hmac(temp, byteArrayOf(1))
    val o2 = Crypto.hmac(temp, o1, byteArrayOf(2))
    temp.fill(0)
    return o1 to o2
}

class CipherState internal constructor(private var k: ByteArray?) {
    var n: Long = 0
        private set

    fun encryptWithAd(ad: ByteArray, pt: ByteArray): ByteArray {
        val key = k ?: return pt.copyOf()
        if (n == -1L) throw CryptoException("nonce exhausted")
        return Crypto.seal(key, Crypto.nonce(n), ad, pt).also { n++ }
    }

    fun decryptWithAd(ad: ByteArray, ct: ByteArray): ByteArray {
        val key = k ?: return ct.copyOf()
        if (n == -1L) throw CryptoException("nonce exhausted")
        return Crypto.open(key, Crypto.nonce(n), ad, ct).also { n++ }
    }
}

class Transport internal constructor(
    val send: CipherState,
    val recv: CipherState,
    val handshakeHash: ByteArray,
    val remoteStatic: ByteArray,
) {
    fun encrypt(pt: ByteArray) = send.encryptWithAd(ByteArray(0), pt)
    fun decrypt(ct: ByteArray) = recv.decryptWithAd(ByteArray(0), ct)
}

class NoiseException(message: String) : Exception(message)

class Handshake(private val initiator: Boolean, private val s: KeyPair) {
    private var cs = CipherState(null)
    private var ck: ByteArray
    private var h: ByteArray
    private var e: KeyPair? = null
    private var fixedE: ByteArray? = null
    private var rs: ByteArray? = null
    private var re: ByteArray? = null
    private var step = 0

    init {
        h = NoiseConst.PROTOCOL_NAME.copyOf(32) // exactly 32 bytes: used directly
        ck = h.copyOf()
        mixHash(NoiseConst.PROLOGUE)
    }

    /** Test vectors only. */
    fun withFixedEphemeral(secret: ByteArray): Handshake { fixedE = secret.copyOf(); return this }

    val isFinished get() = step == 3

    private fun mixHash(data: ByteArray) { h = Crypto.sha256(h, data) }
    private fun mixKey(ikm: ByteArray) { val (a, b) = hkdf2(ck, ikm); ck = a; cs = CipherState(b) }
    private fun encryptAndHash(pt: ByteArray) = cs.encryptWithAd(h, pt).also { mixHash(it) }
    private fun decryptAndHash(ct: ByteArray) = cs.decryptWithAd(h, ct).also { mixHash(ct) }
    private fun genE(): ByteArray {
        val kp = fixedE?.let { KeyPair.fromSecret(it) } ?: KeyPair.generate()
        fixedE = null
        e = kp
        return kp.public
    }
    private fun myTurn() = if (initiator) step == 0 || step == 2 else step == 1

    fun writeMessage(payload: ByteArray): ByteArray {
        if (!myTurn()) throw NoiseException("handshake used out of order")
        val out = java.io.ByteArrayOutputStream()
        when (step) {
            0 -> { val ep = genE(); mixHash(ep); out.write(ep) }
            1 -> {
                val ep = genE(); mixHash(ep); out.write(ep)
                val r = re ?: throw NoiseException("state")
                mixKey(e!!.dh(r))
                out.write(encryptAndHash(s.public))
                mixKey(s.dh(r))
            }
            2 -> {
                out.write(encryptAndHash(s.public))
                mixKey(s.dh(re ?: throw NoiseException("state")))
            }
        }
        out.write(encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(msg: ByteArray): ByteArray {
        if (myTurn() || step > 2) throw NoiseException("handshake used out of order")
        var off = 0
        fun take(n: Int): ByteArray {
            if (msg.size - off < n) throw NoiseException("handshake message has the wrong length")
            return msg.copyOfRange(off, off + n).also { off += n }
        }
        when (step) {
            0 -> { val r = take(32); mixHash(r); re = r }
            1 -> {
                val r = take(32); mixHash(r); re = r
                mixKey(e!!.dh(r))
                val remote = decryptAndHash(take(48))
                rs = remote
                mixKey(e!!.dh(remote))
            }
            2 -> {
                val remote = decryptAndHash(take(48))
                rs = remote
                mixKey(e!!.dh(remote))
            }
        }
        val payload = decryptAndHash(msg.copyOfRange(off, msg.size))
        step++
        return payload
    }

    fun intoTransport(): Transport {
        if (step != 3) throw NoiseException("handshake not finished")
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        val (send, recv) = if (initiator) CipherState(k1) to CipherState(k2) else CipherState(k2) to CipherState(k1)
        return Transport(send, recv, h.copyOf(), rs!!.copyOf())
    }
}
