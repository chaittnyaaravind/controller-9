package dev.ninecontroller.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phone side of SPEC §8.5: per-link queue rules, edge detection and the shared replay window. */
class MultipathTest {
    private fun d(n: Int) = byteArrayOf(n.toByte())
    private fun drain(q: PathQueue) = generateSequence { q.take(0)?.first?.get(0)?.toInt() }.toList()

    @Test fun analogUpdatesCoalesceButButtonEdgesKeepTheirPlace() {
        val q = PathQueue()
        q.offer(d(1), input = true, edge = false)   // stick moving
        q.offer(d(2), input = true, edge = true)    // A pressed
        q.offer(d(3), input = false, edge = false)  // ping
        q.offer(d(4), input = true, edge = false)   // stick moving (A held)
        q.offer(d(5), input = true, edge = true)    // A released
        q.offer(d(6), input = true, edge = false)   // stick moving
        // 1 and 4 were superseded by newer analog states; the press, the release and the ping all stay, in order.
        assertEquals(listOf(2, 3, 5, 6), drain(q))
        assertEquals(2, q.coalesced)
    }

    @Test fun boundedAndTheNewestStateIsNeverDropped() {
        val q = PathQueue(max = 4)
        for (i in 1..10) q.offer(d(i), input = true, edge = true) // a congested link, every frame an edge
        assertEquals(listOf(7, 8, 9, 10), drain(q))
        assertEquals(6, q.dropped)
        q.close()
        q.offer(d(11), input = true, edge = true)
        assertNull(q.take(0), "closed queues accept nothing")
    }

    @Test fun edgesAreButtonsPedalsAndFlags() {
        val s = InputState(lx = 100)
        assertTrue(isEdge(null, 0, s, 0))
        assertTrue(!isEdge(s, 0, s.copy(lx = 2000), 0), "analog-only")
        assertTrue(isEdge(s, 0, s.copy(buttons = 1), 0))
        assertTrue(isEdge(s, 0, s.copy(rt = 1), 0), "pedal leaves zero")
        assertTrue(!isEdge(s.copy(rt = 10), 0, s.copy(rt = 30000), 0), "pedal travel is analog")
        assertTrue(isEdge(s.copy(rt = 10), 0, s, 0), "pedal back to zero")
        assertTrue(isEdge(s, 0, s, FLAG_PAUSED))
    }

    @Test fun sharedReplayWindowKeepsTheFirstCopyOnly() {
        val w = ReplayWindow()
        assertEquals(Seen.NEW, w.check(1)); assertEquals(Seen.DUPLICATE, w.check(1))
        assertEquals(Seen.NEW, w.check(3)); assertEquals(Seen.NEW, w.check(2)) // a per-link pong overtaken
        assertEquals(Seen.DUPLICATE, w.check(2))
        assertEquals(Seen.NEW, w.check(200)); assertEquals(Seen.TOO_OLD, w.check(136)); assertEquals(Seen.NEW, w.check(137))
        assertEquals(Seen.TOO_OLD, w.check(0))
    }

    @Test fun oneSessionCounterAcrossLinks() {
        val info = SessionInfo(0, 7, 47900, ByteArray(32) { 1 }, ByteArray(32) { 2 }, 50, 200, 250, null)
        val crypto = SessionCrypto(info)
        val a = InputChannel(object : DatagramLink { override fun send(d: ByteArray) {}; override fun receive(timeoutMs: Int): ByteArray? = null; override fun close() {} }, info, crypto)
        assertEquals(1L, Datagram.open(info.kIn, crypto.sealInput(InputState(buttons = 1), 0)).first.counter)
        assertEquals(2L, Datagram.open(info.kIn, a.pingDatagram()).first.counter, "a link's ping uses the same session counter")
        assertEquals(3L, Datagram.open(info.kIn, crypto.sealInput(InputState(), 0)).first.counter)
    }
}
