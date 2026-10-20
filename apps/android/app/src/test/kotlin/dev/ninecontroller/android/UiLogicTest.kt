package dev.ninecontroller.android

import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.net.LinkStatsUi
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.Tone
import dev.ninecontroller.android.ui.controller.describeSent
import dev.ninecontroller.android.ui.home.HomeLogic
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.LinkKind
import dev.ninecontroller.protocol.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    private val none = LinkStatsUi()

    // ---------------------------------------------------------------- home / connection states

    @Test fun connectedIsOnlyHealthyWhileTheHostReplies() {
        val c = ConnState.Connected("Mac", 0, null, LinkKind.BLE, "MTU 517")
        val fresh = HomeLogic.summarize(c, none, 300, true, null)
        assertEquals(Tone.OK, fresh.tone); assertFalse(fresh.busy); assertTrue(fresh.health!!.startsWith("Healthy"))
        assertEquals("Player 1 · Bluetooth LE · MTU 517", fresh.detail)
        val stale = HomeLogic.summarize(c, none, 2600, true, null)
        assertEquals("a session without replies is not shown as healthy", Tone.WARN, stale.tone)
        assertTrue(stale.health!!.startsWith("No reply"))
        // Wi-Fi uses the tighter threshold.
        assertEquals(Tone.WARN, HomeLogic.summarize(c.copy(link = LinkKind.LAN), none, 1200, true, null).tone)
    }

    @Test fun missingVirtualControllerIsNeverShownAsAllGood() {
        val c = ConnState.Connected("Mac", 1, "IOHIDUserDevice creation refused", LinkKind.LAN, "")
        val s = HomeLogic.summarize(c, none, 50, true, null)
        assertEquals(Tone.WARN, s.tone)
        assertEquals(HomeLogic.Act.PLAY_DISCONNECT, s.act)
    }

    @Test fun eachStateOffersTheRightAction() {
        assertEquals(HomeLogic.Act.PAIR, HomeLogic.summarize(ConnState.Idle, none, null, false, null).act)
        assertEquals(HomeLogic.Act.CONNECT, HomeLogic.summarize(ConnState.Idle, none, null, true, null).act)
        val connecting = HomeLogic.summarize(ConnState.Connecting("Mac", "Wi-Fi / LAN"), none, null, true, null)
        assertEquals(HomeLogic.Act.CANCEL, connecting.act); assertTrue("spinner only while working", connecting.busy)
        val re = HomeLogic.summarize(ConnState.Reconnecting("Mac", 3, "trying Bluetooth LE"), none.copy(lastDisconnect = "Wi-Fi / LAN: network lost"), null, true, null)
        assertEquals(HomeLogic.Act.CANCEL, re.act); assertTrue(re.busy); assertEquals("Lost: Wi-Fi / LAN: network lost", re.health)
        assertEquals(HomeLogic.Act.RECONNECT, HomeLogic.summarize(ConnState.Failed("x"), none, null, true, null).act)
        assertEquals(HomeLogic.Act.CONFIRM, HomeLogic.summarize(ConnState.ConfirmCode("Mac", "123 456"), none, null, true, null).act)
        // Never busy once connected: no spinner left running on a healthy link.
        assertFalse(HomeLogic.summarize(ConnState.Connected("Mac", 0, null, LinkKind.BLE, ""), none, 10, true, null).busy)
    }

    @Test fun permissionProblemIsExplained() {
        val s = HomeLogic.summarize(ConnState.Idle, none, null, true, "Bluetooth permission is off")
        assertEquals("Bluetooth permission is off", s.detail)
    }

    // ---------------------------------------------------------------- input inspector

    @Test fun inspectorUsesThisLayoutsLabelsForWhatIsSent() {
        val ps = PadMapping.default(PadPresets.DUALSENSE)
        val st = InputState(buttons = Button.A.mask or Button.UP.mask, rt = 65535, lx = -32767)
        assertEquals("Cross  ·  ↑  ·  R2 100%  ·  L -1.00 +0.00", describeSent(st, ps))
        val nin = PadMapping.default(PadPresets.SWITCH_PRO)
        assertEquals("B", describeSent(InputState(buttons = Button.A.mask), nin)) // positional: Nintendo B is bottom
        assertEquals("nothing — all released", describeSent(InputState.NEUTRAL, ps))
        // A remap is reflected: after swapping, the right button (A) sends bit A.
        assertEquals("A", describeSent(InputState(buttons = Button.A.mask), nin.assign("face_e", Action.SOUTH)))
    }

    // ---------------------------------------------------------------- calibration

    @Test fun derivedCalibrationValuesExistOnlyWhenMeasured() {
        // Turn acceptance (steadiness, size, axis agreement) is covered in protocol SteeringTest.
        val held = dev.ninecontroller.protocol.HeldPose(dev.ninecontroller.protocol.Pose.Orientation(dev.ninecontroller.protocol.Quat.IDENTITY), 0.6, 50)
        val neutralOnly = dev.ninecontroller.protocol.SteeringCalibrationV2.neutralOnly(held, 1L, "test")
        assertFalse(neutralOnly.hasRange); assertNull(neutralOnly.suggestedDeadzone)
        val full = neutralOnly.copy(leftDeg = 38.0, rightDeg = 41.0)
        assertTrue(full.hasRange)
        assertEquals(3 * 0.6 / 38.0, full.suggestedDeadzone!!, 1e-9)
    }

    @Test fun vpnTunnelsAndCellularAreNeverLocalLinks() {
        fun ip(a: Int, b: Int, c: Int, d: Int) = java.net.InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())) as java.net.Inet4Address
        val classify = dev.ninecontroller.android.net.LocalLinks::classify
        assertNull("VPN tun0 seen on the test phone", classify("tun0", ip(172, 16, 0, 2)))
        assertNull(classify("rmnet_data1", ip(10, 20, 30, 40)))
        assertNull(classify("ccmni0", ip(10, 1, 1, 1)))
        assertNull(classify("wg0", ip(10, 8, 0, 2)))
        assertEquals(LinkKind.LAN, classify("wlan0", ip(10, 251, 46, 25)))
        assertEquals(LinkKind.USB, classify("rndis0", ip(192, 168, 42, 129)))
        assertEquals(LinkKind.HOTSPOT, classify("swlan0", ip(192, 168, 43, 1)))
    }

    @Test fun defaultsSelectAPresetAndPadMotionIsOffUntilEnabled() {
        val s = AppSettings()
        assertEquals(PadPresets.XBOX, s.padPreset)
        assertFalse(s.padMotion().enabled)
        assertEquals("lx", s.copy(padTilt = true).padMotion().axis)
        s.copy(padTilt = true).padMotion().let { dev.ninecontroller.protocol.Profile(id = "x", name = "x", controls = emptyList(), motion = it).validate() }
    }
}
