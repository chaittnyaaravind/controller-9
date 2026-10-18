package dev.ninecontroller.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real Kotlin client ↔ Rust host over sockets. Skipped unless NINECTL_URI is set, e.g.
 *   ninectl host --mock --ephemeral --bind 127.0.0.1 --pair --no-advertise   (copy the printed URI)
 *   NINECTL_URI='ninectl://pair?...' ./gradlew :protocol:test --tests '*LiveInterop*'
 */
class LiveInteropTest {
    /** QR tokens are single-use, so one identity pairs once and every test reuses it (like a real phone). */
    companion object {
        val id: KeyPair by lazy { KeyPair.generate() }
        private var paired = false
        @Synchronized fun ensurePaired(uri: PairUri) {
            if (paired) return
            ControlConnection.connect(InetSocketAddress(uri.addr, uri.port), id, ControllerHello(1, "Kotlin interop", "jvm", "test")).use { it.pairQr(uri) }
            paired = true
        }
    }

    @Test fun pairOpenStreamAndPing() {
        val uriText = System.getenv("NINECTL_URI").orEmpty()
        if (uriText.isEmpty()) { println("LiveInteropTest skipped: NINECTL_URI not set"); return }
        val uri = PairUri.parse(uriText)!!
        ensurePaired(uri)
        val conn = ControlConnection.connect(InetSocketAddress(uri.addr, uri.port), id, ControllerHello(1, "Kotlin interop", "jvm", "test"))
        val info = conn.open(null, "Interop")
        val ch = InputChannel(InetAddress.getByName(uri.addr), info)
        repeat(200) { i ->
            ch.sendInput(InputState(buttons = if (i % 20 < 10) Button.A.mask else 0, lx = axisFromUnit(Math.sin(i / 10.0))), 0)
            if (i % 10 == 0) ch.ping()
            ch.receive(4)
        }
        repeat(20) { ch.receive(10) }
        println("Kotlin->Rust interop: sent ${ch.sent} frames, RTT p50=${ch.rttMs.percentile(50.0)} ms p95=${ch.rttMs.percentile(95.0)} ms n=${ch.rttMs.size}")
        assertTrue(ch.rttMs.size >= 15, "pongs from the Rust host")
        ch.sendInput(InputState.NEUTRAL, 0); ch.close(); conn.close()
    }

    /** Same identity fails over to the stream binding (SPEC §8.2, TCP port from NINECTL_STREAM_PORT). */
    @Test fun streamBindingWithSameIdentity() {
        val uriText = System.getenv("NINECTL_URI").orEmpty()
        val port = System.getenv("NINECTL_STREAM_PORT")?.toIntOrNull()
        if (uriText.isEmpty() || port == null) { println("stream interop skipped: NINECTL_URI/NINECTL_STREAM_PORT not set"); return }
        val uri = PairUri.parse(uriText)!!
        ensurePaired(uri)
        val hello = ControllerHello(1, "Kotlin stream", "jvm", "test")
        val sb = StreamBinding.tcp(uri.addr, port)
        val conn = ControlConnection.connectOver(sb.control.input, sb.control.output, sb, id, hello)
        val info = conn.open(null, "Stream")
        val ch = InputChannel(sb, info)
        repeat(100) { i ->
            ch.sendInput(InputState(buttons = if (i % 20 < 10) Button.B.mask else 0), 0)
            if (i % 10 == 0) ch.ping()
            ch.receive(4)
        }
        repeat(20) { ch.receive(10) }
        println("Kotlin->Rust stream interop: sent ${ch.sent} frames, RTT p50=${ch.rttMs.percentile(50.0)} ms p95=${ch.rttMs.percentile(95.0)} ms n=${ch.rttMs.size}")
        assertTrue(conn.host.links.contains("stream"))
        assertTrue(ch.rttMs.size >= 8, "pongs over the stream binding")
        ch.sendInput(InputState.NEUTRAL, 0); conn.close()
    }
}
