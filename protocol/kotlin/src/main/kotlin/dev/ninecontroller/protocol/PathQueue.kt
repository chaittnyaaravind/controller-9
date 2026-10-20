package dev.ninecontroller.protocol

/**
 * Bounded send queue for one link of a multi-link session (SPEC §8.5). Each link drains its own
 * queue on its own thread, so a congested Bluetooth link never delays Wi-Fi.
 *
 * Latest-state rule: a new input frame replaces queued input frames that only carried analog changes
 * (edge = false); frames with a button, pedal or flag edge are kept, in order, so a short press is
 * never coalesced away before its release. Order is never changed (counters must stay increasing).
 * The newest frame is never dropped; beyond [max] the oldest frame goes.
 */
class PathQueue(private val max: Int = 16) {
    private class Item(val d: ByteArray, val input: Boolean, val edge: Boolean)
    private val q = ArrayDeque<Item>()
    private val lock = Object()
    @Volatile private var closed = false
    @Volatile var dropped = 0L; private set
    @Volatile var coalesced = 0L; private set
    val size: Int get() = synchronized(lock) { q.size }

    fun offer(d: ByteArray, input: Boolean, edge: Boolean) = synchronized(lock) {
        if (closed) return
        if (input) {
            val before = q.size
            q.removeAll { it.input && !it.edge }
            coalesced += before - q.size
        }
        q.addLast(Item(d, input, edge))
        while (q.size > max) { q.removeFirst(); dropped++ }
        lock.notifyAll()
    }

    /** Next datagram to send and whether it carries an edge, waiting up to [timeoutMs]; null on timeout or after [close]. */
    fun take(timeoutMs: Long): Pair<ByteArray, Boolean>? = synchronized(lock) {
        if (q.isEmpty() && !closed && timeoutMs > 0) lock.wait(timeoutMs) // wait(0) would block forever
        q.removeFirstOrNull()?.let { it.d to it.edge }
    }

    fun close() = synchronized(lock) { closed = true; q.clear(); lock.notifyAll() }
}

/**
 * Whether [next] differs from [prev] by more than analog values: a button press or release, a pedal
 * or trigger leaving or returning to zero, or a flag change (pause, calibrating, motion).
 */
fun isEdge(prev: InputState?, prevFlags: Int, next: InputState, nextFlags: Int): Boolean {
    if (prev == null) return true
    return prev.buttons != next.buttons || prevFlags != nextFlags ||
        (prev.lt == 0) != (next.lt == 0) || (prev.rt == 0) != (next.rt == 0)
}
