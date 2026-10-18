package dev.ninecontroller.android.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.ninecontroller.protocol.KeyPair
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The controller's long-lived X25519 identity. The 32-byte secret is stored encrypted with an
 * AES-256-GCM key that lives in the Android Keystore (non-exportable), in app-private storage
 * excluded from backup and device transfer. It is never logged or exported.
 */
class IdentityStore(private val context: Context) {
    private val file = File(context.filesDir, "identity.bin")
    @Volatile private var cached: KeyPair? = null

    @Synchronized fun keyPair(): KeyPair {
        cached?.let { return it }
        val kp = runCatching { load() }.getOrNull() ?: KeyPair.generate().also { save(it) }
        cached = kp
        return kp
    }

    /** Creates a brand-new identity; every host must pair again. */
    @Synchronized fun reset() {
        file.delete()
        runCatching { KeyStore.getInstance(KS).apply { load(null) }.deleteEntry(ALIAS) }
        cached = null
    }

    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, "device_name")
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun save(kp: KeyPair) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val secret = kp.secretBytes()
        val ct = c.doFinal(secret)
        secret.fill(0)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + ct)
        tmp.renameTo(file)
    }

    private fun load(): KeyPair? {
        if (!file.exists()) return null
        val b = file.readBytes()
        val ivLen = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b, 1, ivLen))
        val secret = c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen)
        return KeyPair.fromSecret(secret).also { secret.fill(0) }
    }

    private companion object { const val KS = "AndroidKeyStore"; const val ALIAS = "ninectl-identity-wrap" }
}
