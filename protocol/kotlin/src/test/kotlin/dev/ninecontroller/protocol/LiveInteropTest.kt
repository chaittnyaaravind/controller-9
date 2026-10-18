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
    @Test fun pairOpenStreamAndPing() {
        val uriText = System.getenv("NINECTL_URI").orEmpty()
        if (uriText.isEmpty()) { println("LiveInteropTest skipped: NINECTL_URI not set"); return }
        val uri = PairUri.parse(uriText)!!
        val conn = ControlConnection.connect(InetSocketAddress(uri.addr, uri.port), KeyPair.generate(),
            ControllerHello(1, "Kotlin interop", "jvm", "test"))
        conn.pairQr(uri)
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
}
