package dev.ninecontroller.android

import dev.ninecontroller.android.data.AppSettings
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.controller.describeSent
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.InputState
import dev.ninecontroller.protocol.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

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

    @Test fun defaultsSelectAPresetAndPadMotionIsOffUntilEnabled() {
        val s = AppSettings()
        assertEquals(PadPresets.XBOX, s.padPreset)
        assertFalse(s.padMotion().enabled)
        assertEquals("lx", s.copy(padTilt = true).padMotion().axis)
        s.copy(padTilt = true).padMotion().let { dev.ninecontroller.protocol.Profile(id = "x", name = "x", controls = emptyList(), motion = it).validate() }
    }
}
