package dev.ninecontroller.protocol

import kotlin.math.ceil

/** Fixed-size ring with nearest-rank percentiles (same definition as the Rust host). */
class Window(private val cap: Int) {
    private val buf = DoubleArray(cap)
    private var len = 0
    private var next = 0
    var total = 0L; private set
    val size get() = len

    @Synchronized fun push(v: Double) {
        buf[next] = v; next = (next + 1) % cap
        if (len < cap) len++
        total++
    }

    @Synchronized fun percentile(p: Double): Double? {
        if (len == 0) return null
        val s = buf.copyOf(len).also { it.sort() }
        val rank = maxOf(1, ceil(p / 100.0 * len).toInt())
        return s[minOf(rank, len) - 1]
    }

    @Synchronized fun clear() { len = 0; next = 0 }
}
