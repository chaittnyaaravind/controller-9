package dev.ninecontroller.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Link-selection policy — mirrors protocol/rust/src/policy.rs; pinned by vectors/policy.json. */
@Serializable
enum class LinkKind(val base: Double, val label: String) {
    @SerialName("usb") USB(100.0, "USB network"),
    @SerialName("ethernet") ETHERNET(95.0, "Ethernet"),
    @SerialName("lan") LAN(85.0, "Wi-Fi / LAN"),
    @SerialName("hotspot") HOTSPOT(85.0, "Hotspot"),
    @SerialName("wifi_direct") WIFI_DIRECT(85.0, "Wi-Fi Direct"),
    @SerialName("stream") STREAM(60.0, "TCP-only (stream)"),
    @SerialName("ble") BLE(40.0, "Bluetooth LE"),
}

@Serializable
data class LinkStats(
    val kind: LinkKind = LinkKind.LAN,
    val available: Boolean = false,
    val rttP95Ms: Double? = null,
    val lossPct: Double = 0.0,
    val disconnectsPerMin: Double = 0.0,
    val lastFailureAgeS: Double? = null,
    val attempts: Int = 0,
    val successes: Int = 0,
)

@Serializable
data class PolicyState(
    val current: LinkKind? = null,
    val currentHealthy: Boolean = false,
    val sinceSwitchS: Double = 0.0,
    val betterForS: Double = 0.0,
    val overrideKind: LinkKind? = null,
)

sealed class Decision {
    data object Stay : Decision()
    data class Switch(val to: LinkKind) : Decision()
    data class Failover(val order: List<LinkKind>) : Decision()
    data object Unavailable : Decision()
}

object LinkPolicy {
    const val SWITCH_MARGIN = 25.0
    const val BETTER_FOR_S = 3.0
    const val MIN_DWELL_S = 10.0
    const val RECENT_FAILURE_S = 10.0

    fun score(s: LinkStats): Double? {
        if (!s.available) return null
        var v = s.kind.base
        s.rttP95Ms?.let { v -= maxOf(it - 20.0, 0.0) }
        v -= s.lossPct * 4.0
        v -= s.disconnectsPerMin * 15.0
        if (s.lastFailureAgeS != null && s.lastFailureAgeS < RECENT_FAILURE_S) v -= 50.0
        if (s.attempts >= 3 && s.successes < 0.5 * s.attempts) v -= 30.0
        return v
    }

    fun rank(c: List<LinkStats>): List<LinkKind> = c.mapNotNull { s -> score(s)?.let { Triple(it, s.kind.ordinal, s.kind) } }
        .sortedWith(compareByDescending<Triple<Double, Int, LinkKind>> { it.first }.thenBy { it.second }).map { it.third }

    fun decide(c: List<LinkStats>, st: PolicyState): Decision {
        fun available(k: LinkKind) = c.any { it.kind == k && it.available }
        st.overrideKind?.takeIf { available(it) }?.let { o ->
            return when {
                st.current == o && st.currentHealthy -> Decision.Stay
                st.currentHealthy && st.current != null -> Decision.Switch(o)
                else -> Decision.Failover(listOf(o))
            }
        }
        val order = rank(c)
        val cur = st.current
        if (cur != null && st.currentHealthy) {
            val best = order.firstOrNull() ?: return Decision.Stay
            if (best == cur) return Decision.Stay
            val sb = c.find { it.kind == best }?.let(::score); val sc = c.find { it.kind == cur }?.let(::score)
            if (sb == null || sc == null) return Decision.Stay
            return if (sb - sc >= SWITCH_MARGIN && st.betterForS >= BETTER_FOR_S && st.sinceSwitchS >= MIN_DWELL_S) Decision.Switch(best) else Decision.Stay
        }
        val o = order.toMutableList()
        if (cur != null && o.remove(cur)) o.add(cur)
        return if (o.isEmpty()) Decision.Unavailable else Decision.Failover(o)
    }
}
