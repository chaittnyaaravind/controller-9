package dev.ninecontroller.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Control-channel messages (SPEC §4). The JSON discriminator is `t`. */
@Serializable
sealed class Control {
    /** [join]: add this link to the caller's live session instead of opening one (SPEC §8.5); only to hosts with `multipath`. */
    @Serializable @SerialName("open") data class Open(val resumeSlot: Int? = null, val profile: String? = null, val join: Long? = null) : Control()
    @Serializable @SerialName("pair_qr") data class PairQr(val proof: String) : Control()
    @Serializable @SerialName("pair_code") data object PairCode : Control()
    @Serializable @SerialName("nonce") data class Nonce(val n: String) : Control()
    @Serializable @SerialName("code_ok") data object CodeOk : Control()
    @Serializable @SerialName("code_reject") data object CodeReject : Control()
    @Serializable @SerialName("stats") data class Stats(
        val rttP50Ms: Float? = null, val rttP95Ms: Float? = null, val sensorHz: Float? = null,
        val sendHz: Float? = null, val batteryPct: Int? = null,
    ) : Control()
    @Serializable @SerialName("session") data class Session(
        val slot: Int, val sessionId: Long, val udpPort: Int, val kIn: String, val kOut: String,
        val heartbeatMs: Int, val idleHeartbeatMs: Int, val timeoutMs: Int,
    ) : Control() {
        override fun toString() = "Session(slot=$slot, sessionId=$sessionId, udpPort=$udpPort, keys=<redacted>)"
    }
    @Serializable @SerialName("pair_required") data class PairRequired(val modes: List<String>) : Control()
    @Serializable @SerialName("commit") data class Commit(val c: String) : Control()
    @Serializable @SerialName("reveal") data class Reveal(val n: String) : Control()
    @Serializable @SerialName("paired") data object Paired : Control()
    @Serializable @SerialName("pair_failed") data class PairFailed(val reason: String) : Control()
    @Serializable @SerialName("rumble") data class Rumble(val low: Int, val high: Int, val ms: Int) : Control()
    @Serializable @SerialName("error") data class Error(val code: String, val message: String? = null) : Control()
    @Serializable @SerialName("bye") data class Bye(val reason: String? = null) : Control()

    fun toJson(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        fun fromJson(b: ByteArray): Control = json.decodeFromString(serializer(), b.decodeToString())
    }
}

@Serializable data class InitHello(val proto: List<Int>)
@Serializable data class HostHello(val proto: Int, val name: String, val id: String, val os: String, val links: List<String> = emptyList(), val addrs: List<String> = emptyList(), val multipath: Boolean = false)
@Serializable data class ControllerHello(val proto: Int, val name: String, val platform: String, val app: String)
