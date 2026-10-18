package dev.ninecontroller.android

import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.data.SteeringCalibration
import dev.ninecontroller.android.net.ConnState
import dev.ninecontroller.android.net.LinkStatsUi
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.Tone
import dev.ninecontroller.android.ui.calibration.CalibrationMath
import dev.ninecontroller.android.ui.controller.describeSent
import dev.ninecontroller.android.ui.home.HomeLogic
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.Calibration
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

    @Test fun calibrationOnlyAcceptsSteadyRealTurns() {
        val m = MotionSettings(enabled = true)
        assertTrue(CalibrationMath.extreme(0.0, null, true, m) is CalibrationMath.Extreme.Retry)
        assertTrue("shaky", CalibrationMath.extreme(0.0, Calibration(30.0, 5.0, 100), true, m) is CalibrationMath.Extreme.Retry)
        assertTrue("too small", CalibrationMath.extreme(0.0, Calibration(4.0, 0.5, 100), true, m) is CalibrationMath.Extreme.Retry)
        val right = CalibrationMath.extreme(0.0, Calibration(32.0, 0.8, 100), true, m) as CalibrationMath.Extreme.Ok
        assertEquals(32.0, right.deltaDeg, 1e-9); assertFalse(right.reversed)
        val wrongWay = CalibrationMath.extreme(0.0, Calibration(32.0, 0.8, 100), false, m) as CalibrationMath.Extreme.Ok
        assertTrue("asked for left, output went right", wrongWay.reversed)
        // Wraps across ±180°.
        val wrap = CalibrationMath.extreme(170.0, Calibration(-160.0, 0.5, 100), true, m) as CalibrationMath.Extreme.Ok
        assertEquals(30.0, wrap.deltaDeg, 1e-9)
    }

    @Test fun derivedCalibrationValuesExistOnlyWhenMeasured() {
        val centreOnly = SteeringCalibration(2.0, null, null, 0.6, 1L)
        assertNull(centreOnly.lockDeg); assertNull(centreOnly.suggestedDeadzone)
        val full = SteeringCalibration(2.0, -38.0, 41.0, 0.6, 1L)
        assertEquals(38.0, full.lockDeg!!, 1e-9)
        assertEquals(3 * 0.6 / 38.0, full.suggestedDeadzone!!, 1e-9)
    }

    @Test fun defaultsSelectAPresetAndPadMotionIsOffUntilEnabled() {
        val s = AppSettings()
        assertEquals(PadPresets.XBOX, s.padPreset)
        assertFalse(s.padMotion().enabled)
        assertEquals("lx", s.copy(padTilt = true).padMotion().axis)
        s.copy(padTilt = true).padMotion().let { dev.ninecontroller.protocol.Profile(id = "x", name = "x", controls = emptyList(), motion = it).validate() }
    }
}
