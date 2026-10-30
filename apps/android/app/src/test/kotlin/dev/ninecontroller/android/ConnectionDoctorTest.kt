package dev.ninecontroller.android

import dev.ninecontroller.android.net.ConnectionDoctor
import dev.ninecontroller.android.net.ConnectionDoctor.Cause
import dev.ninecontroller.android.net.ConnectionDoctor.Facts
import dev.ninecontroller.android.net.ConnectionDoctor.Net
import dev.ninecontroller.android.net.ConnectionDoctor.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDoctorTest {
    private val home = listOf(Net("192.168.1.23", 24))
    private fun facts(probe: Probe, phone: List<Net> = home, wifi: Boolean = true, vpn: Boolean = false, addr: String = "192.168.1.20") =
        Facts("Desk PC", addr, 47901, wifi, vpn, phone, probe)

    @Test fun eachSituationGetsItsOwnCause() {
        assertEquals(Cause.REACHABLE, ConnectionDoctor.diagnose(facts(Probe.REACHABLE)).cause)
        assertEquals(Cause.NOT_RUNNING, ConnectionDoctor.diagnose(facts(Probe.REFUSED)).cause)
        assertEquals(Cause.VPN, ConnectionDoctor.diagnose(facts(Probe.TIMEOUT, vpn = true)).cause)
        assertEquals(Cause.NO_NETWORK, ConnectionDoctor.diagnose(facts(Probe.NO_ROUTE, phone = emptyList(), wifi = false)).cause)
        assertEquals(Cause.OTHER_NETWORK, ConnectionDoctor.diagnose(facts(Probe.TIMEOUT, addr = "10.0.0.5")).cause)
        // Same network but no answer: the firewall / devices-kept-apart case (a campus /16 here).
        assertEquals(Cause.BLOCKED, ConnectionDoctor.diagnose(facts(Probe.TIMEOUT, phone = listOf(Net("10.250.244.195", 16)), addr = "10.250.187.182")).cause)
        assertEquals(Cause.NO_ADDRESS, ConnectionDoctor.diagnose(facts(Probe.NO_ROUTE, addr = "")).cause)
    }

    @Test fun refusedWinsOverVpn() {
        // The computer answered, so the network works even with a VPN on: the app is what's missing.
        assertEquals(Cause.NOT_RUNNING, ConnectionDoctor.diagnose(facts(Probe.REFUSED, vpn = true)).cause)
    }

    @Test fun everyFindingNamesTheComputerAndAFix() {
        for (p in Probe.entries) for (f in listOf(facts(p), facts(p, addr = "10.0.0.5"), facts(p, vpn = true))) {
            val d = ConnectionDoctor.diagnose(f)
            assertTrue(d.fixes.isNotEmpty())
            assertTrue(d.title.contains("Desk PC") || d.cause in setOf(Cause.VPN, Cause.NO_NETWORK, Cause.OTHER_NETWORK))
        }
    }

    @Test fun subnetMaths() {
        assertTrue(ConnectionDoctor.sameNet(Net("192.168.1.23", 24), "192.168.1.200"))
        assertFalse(ConnectionDoctor.sameNet(Net("192.168.1.23", 24), "192.168.2.1"))
        assertTrue(ConnectionDoctor.sameNet(Net("10.250.244.195", 16), "10.250.187.182"))
        assertFalse(ConnectionDoctor.sameNet(Net("10.250.244.195", 24), "10.250.187.182"))
        assertFalse(ConnectionDoctor.sameNet(Net("192.168.1.23", 24), "not-an-ip"))
    }

    @Test fun androidConnectErrorsAreToldApart() {
        assertEquals(Probe.REFUSED, ConnectionDoctor.classify("java.net.ConnectException: failed to connect to /192.168.1.20 (port 47901) after 1500ms: isConnected failed: ECONNREFUSED (Connection refused)"))
        assertEquals(Probe.NO_ROUTE, ConnectionDoctor.classify("java.net.ConnectException: failed to connect to /10.0.0.5 (port 47901) after 1500ms: isConnected failed: EHOSTUNREACH (No route to host)"))
        assertEquals(Probe.TIMEOUT, ConnectionDoctor.classify("java.net.ConnectException: failed to connect: ETIMEDOUT (Connection timed out)"))
    }

    @Test fun onlySilenceTriesBluetooth() {
        // A Wi-Fi that keeps devices apart drops packets: timeouts and no-route are "no answer"; a refusal is an answer.
        assertTrue(ConnectionDoctor.noAnswer(java.net.SocketTimeoutException("no address answered")))
        assertTrue(ConnectionDoctor.noAnswer(java.net.NoRouteToHostException()))
        assertTrue(ConnectionDoctor.noAnswer(java.net.ConnectException("failed to connect to /10.251.54.244 (port 47901) after 10000ms: isConnected failed: EHOSTUNREACH (No route to host)")))
        assertTrue(ConnectionDoctor.noAnswer(java.net.ConnectException("failed to connect: ETIMEDOUT (Connection timed out)")))
        assertFalse(ConnectionDoctor.noAnswer(java.net.ConnectException("failed to connect to /127.0.0.1 (port 47901): ECONNREFUSED (Connection refused)")))
        assertFalse(ConnectionDoctor.noAnswer(IllegalStateException("pairing_closed")))
    }

    @Test fun explanationListsEveryFix() {
        val f = ConnectionDoctor.diagnose(facts(Probe.TIMEOUT, phone = listOf(Net("10.251.46.25", 16)), addr = "10.251.54.244"))
        val text = ConnectionDoctor.explain(f).lines()
        assertEquals(f.title, text[0])
        assertEquals(f.detail, text[1])
        assertEquals(f.fixes.map { "• $it" }, text.drop(2))
    }
}
