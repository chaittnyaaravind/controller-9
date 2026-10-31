package dev.ninecontroller.android

import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.Glyph
import dev.ninecontroller.android.pad.PadGeometry
import dev.ninecontroller.android.pad.PadLook
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.pad.Part
import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadLookTest {
    @Test fun customisedLayoutKeepsEveryPartAndGlyph() {
        for (p in PadPresets.all) {
            val prof = PadMapping.default(p).toProfile(2400f, 1080f, "${p.id}-custom-2", "x")
            for (c in prof.controls) {
                val e = p.element(c.id)
                val (look, a) = PadLook.element(c, 100f, 100f, prof.id)!!
                assertEquals("${p.id} ${c.id}", e.part, look.part)
                assertEquals("${p.id} ${c.id}", e.glyph, look.glyph)
                assertEquals("${p.id} ${c.id}", e.default, a)
            }
        }
    }

    @Test fun stretchedRoundButtonBecomesCapsuleKeepingGlyph() {
        val c = ControlDef("face_s", ControlKind.BUTTON, "a", 0.0, 0.0, 0.2, 0.1, label = "Cross")
        val (look, _) = PadLook.element(c, 300f, 100f)!!
        assertEquals(Part.CENTER, look.part)
        assertEquals(Glyph.CROSS, look.glyph)
        assertEquals(false, look.round)
    }

    @Test fun plainControlsGetGenericParts() {
        assertEquals(Part.FACE, PadLook.element(ControlDef("b1", ControlKind.BUTTON, "a", 0.0, 0.0, 0.1, 0.1), 90f, 100f)!!.first.part)
        assertEquals(Part.BUMPER, PadLook.element(ControlDef("b2", ControlKind.BUTTON, "lb", 0.0, 0.0, 0.3, 0.1), 300f, 100f)!!.first.part)
        assertEquals(Action.TRIGGER_R, PadLook.element(ControlDef("t", ControlKind.TRIGGER, "rt", 0.0, 0.0, 0.3, 0.1), 300f, 100f)!!.second)
        assertEquals(Part.DPAD, PadLook.element(ControlDef("d", ControlKind.DPAD, "dpad", 0.0, 0.0, 0.2, 0.2), 200f, 200f)!!.first.part)
        assertEquals("LB", PadLook.element(ControlDef("b2", ControlKind.BUTTON, "lb", 0.0, 0.0, 0.3, 0.1), 300f, 100f)!!.first.short)
    }

    @Test fun barFitsBetweenTheTriggers() {
        val b = { l: Float, t: Float, r: Float, bo: Float -> PadGeometry.Box(l, t, r, bo) }
        // Triggers at 0..500 and 1900..2400 on a 2400 px screen: gap 1400, less 2 x 20 margin; low controls don't count.
        assertEquals(1360f, PadLook.topGap(listOf(b(0f, 0f, 500f, 100f), b(1900f, 0f, 2400f, 100f), b(800f, 400f, 900f, 500f)), 2400f, 130f, 20f)!!, 0.01f)
        // Asymmetric (right trigger moved in to 1700): the nearer side decides, the bar stays centred.
        assertEquals(960f, PadLook.topGap(listOf(b(0f, 0f, 500f, 100f), b(1700f, 0f, 2400f, 100f)), 2400f, 130f, 20f)!!, 0.01f)
        // Nothing near the top, or only a centred control: no limit from controls.
        assertNull(PadLook.topGap(listOf(b(0f, 400f, 500f, 500f), b(1100f, 0f, 1300f, 100f)), 2400f, 130f, 20f))
    }

    @Test fun pedalsAndSteeringKeepTheirOwnDrawing() {
        assertNull(PadLook.element(ControlDef("p", ControlKind.PEDAL, "rt", 0.0, 0.0, 0.1, 0.3), 100f, 300f))
        assertNull(PadLook.element(ControlDef("s", ControlKind.STEERING, "steer", 0.0, 0.0, 0.3, 0.1), 300f, 100f))
    }
}
