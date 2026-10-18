package dev.ninecontroller.android

import dev.ninecontroller.android.input.ControllerEngine
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.Family
import dev.ninecontroller.android.pad.Glyph
import dev.ninecontroller.android.pad.MappingException
import dev.ninecontroller.android.pad.PadGeometry
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPreset
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.pad.Part
import dev.ninecontroller.protocol.Button
import dev.ninecontroller.protocol.ControlKind
import dev.ninecontroller.protocol.InputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PadMappingTest {
    private val all = PadPresets.all

    // ---------------------------------------------------------------- the five presets

    @Test fun fiveDistinctPresets() {
        assertEquals(listOf("xbox", "dualsense", "dualshock4", "switchpro", "8bitdo-pro2"), all.map { it.id })
        assertEquals(5, all.map { it.name }.toSet().size)
        assertEquals(listOf(Family.XBOX, Family.PLAYSTATION, Family.PLAYSTATION, Family.NINTENDO, Family.RETRO), all.map { it.family })
        // Genuinely different: no two presets share the same set of (label, position).
        val sigs = all.map { p -> p.elements.map { "${it.label}@${it.cx},${it.cy}" }.toSet() }
        for (i in sigs.indices) for (j in i + 1 until sigs.size) assertTrue("${all[i].id} vs ${all[j].id}", sigs[i] != sigs[j])
    }

    @Test fun everyPresetFitsTheCanvasWithoutOverlaps() = all.forEach { p ->
        assertEquals("${p.id} ids unique", p.elements.size, p.elements.map { it.id }.toSet().size)
        for (e in p.elements) {
            assertTrue("${p.id}/${e.id} inside", e.cx - e.w / 2 >= 0 && e.cx + e.w / 2 <= 200 && e.cy - e.h / 2 >= 0 && e.cy + e.h / 2 <= 100)
        }
        for (i in p.elements.indices) for (j in i + 1 until p.elements.size) {
            val a = p.elements[i]; val b = p.elements[j]
            val overlap = if (a.round && b.round) kotlin.math.hypot(a.cx - b.cx, a.cy - b.cy) < (a.w + b.w) / 2
                else kotlin.math.abs(a.cx - b.cx) < (a.w + b.w) / 2 && kotlin.math.abs(a.cy - b.cy) < (a.h + b.h) / 2
            assertFalse("${p.id}: ${a.id} overlaps ${b.id}", overlap)
        }
    }

    @Test fun everyPresetCarriesEveryLogicalInputExactlyOnce() = all.forEach { p ->
        val m = PadMapping.default(p)
        val sent = p.elements.filter { it.part != Part.PADDLE }.mapNotNull { m.actionOf(it) }
        assertEquals("${p.id} sends each action once", Action.entries.toSet(), sent.toSet())
        assertEquals("${p.id} no duplicates", sent.size, sent.toSet().size)
        assertTrue("${p.id} default mapping valid", m.problems().isEmpty())
    }

    @Test fun faceLabelsMatchEachFamily() {
        fun labels(p: PadPreset) = listOf("face_s", "face_e", "face_w", "face_n").map { p.element(it).label }
        assertEquals(listOf("A", "B", "X", "Y"), labels(PadPresets.XBOX))
        assertEquals(listOf("Cross", "Circle", "Square", "Triangle"), labels(PadPresets.DUALSENSE))
        assertEquals(listOf("Cross", "Circle", "Square", "Triangle"), labels(PadPresets.DUALSHOCK4))
        assertEquals(listOf("B", "A", "Y", "X"), labels(PadPresets.SWITCH_PRO))
        assertEquals(listOf("B", "A", "Y", "X"), labels(PadPresets.EIGHTBITDO_PRO2))
        assertEquals(listOf(Glyph.CROSS, Glyph.CIRCLE, Glyph.SQUARE, Glyph.TRIANGLE), listOf("face_s", "face_e", "face_w", "face_n").map { PadPresets.DUALSENSE.element(it).glyph })
    }

    @Test fun faceButtonsArePositionalSoBottomAlwaysSendsA() = all.forEach { p ->
        val m = PadMapping.default(p)
        val s = p.element("face_s"); val e = p.element("face_e"); val w = p.element("face_w"); val n = p.element("face_n")
        assertTrue("${p.id} south below north", s.cy > n.cy); assertTrue("${p.id} east right of west", e.cx > w.cx)
        assertEquals(Action.SOUTH, m.actionOf(s)); assertEquals(Button.A, Action.SOUTH.button)
        assertEquals(Action.EAST, m.actionOf(e)); assertEquals(Action.WEST, m.actionOf(w)); assertEquals(Action.NORTH, m.actionOf(n))
    }

    @Test fun placementMatchesEachControllerShape() = all.forEach { p ->
        val sl = p.element("stick_l"); val sr = p.element("stick_r"); val dp = p.element("dpad"); val fs = p.element("face_n")
        if (p.symmetric) {
            assertTrue("${p.id}: D-pad above both sticks", dp.cy < sl.cy && dp.cy < sr.cy)
            assertEquals("${p.id}: sticks level", sl.cy, sr.cy, 0.0)
        } else {
            assertTrue("${p.id}: left stick above D-pad", sl.cy < dp.cy)
            assertTrue("${p.id}: left stick outboard of D-pad", sl.cx < dp.cx)
            assertTrue("${p.id}: face buttons above right stick", fs.cy < sr.cy)
        }
        val tops = listOf("trigger_l", "trigger_r").map { p.element(it) }
        val bumpers = listOf("bumper_l", "bumper_r").map { p.element(it) }
        assertTrue("${p.id}: triggers on the top edge", tops.all { t -> t.cy - t.h / 2 <= 2.0 })
        assertTrue("${p.id}: bumpers below triggers", bumpers.zip(tops).all { (b, t) -> b.cy > t.cy })
        // Left/right halves mirror around the centre line.
        assertEquals(200.0, p.element("trigger_l").cx + p.element("trigger_r").cx, 1e-9)
        assertEquals(200.0, sl.cx + sr.cx, if (p.symmetric) 1e-9 else 200.0)
    }

    @Test fun centreButtonsAreDistinctAndUnsupportedOnesSendNothing() {
        val expectUnavailable = mapOf(
            "xbox" to setOf("share"), "dualsense" to setOf("touchpad", "mute"), "dualshock4" to setOf("touchpad"),
            "switchpro" to setOf("capture"), "8bitdo-pro2" to setOf("star", "paddle_l", "paddle_r"),
        )
        all.forEach { p ->
            val m = PadMapping.default(p)
            val off = p.elements.filter { m.actionOf(it) == null }.map { it.id }.toSet()
            assertEquals(p.id, expectUnavailable[p.id], off)
            off.forEach { assertNotNull("${p.id}/$it explains why", p.element(it).unavailable) }
            assertTrue("${p.id}: no control for unavailable parts", m.controls().none { it.id in off })
            val centre = listOf(Action.BACK, Action.START, Action.GUIDE).map { a -> p.elements.single { m.actionOf(it) == a }.id }
            assertEquals("${p.id}: Back, Start, Guide on three different buttons", 3, centre.toSet().size)
        }
        assertEquals("Create", PadPresets.DUALSENSE.elements.single { it.default == Action.BACK }.label)
        assertEquals("Share", PadPresets.DUALSHOCK4.elements.single { it.default == Action.BACK }.label)
        assertEquals("−", PadPresets.SWITCH_PRO.elements.single { it.default == Action.BACK }.label)
    }

    @Test fun everyPresetIsAValidProtocolProfile() = all.forEach { p ->
        val profile = PadMapping.default(p).toProfile()
        profile.validate()
        assertEquals(1, profile.controls.count { it.kind == ControlKind.DPAD })
        assertEquals(setOf("left", "right"), profile.controls.filter { it.kind == ControlKind.STICK }.map { it.bind }.toSet())
        assertEquals(setOf("lt", "rt"), profile.controls.filter { it.kind == ControlKind.TRIGGER }.map { it.bind }.toSet())
    }

    @Test fun switchingPresetNeverChangesWhatAPositionSends() {
        val ids = all.map { p -> p.elements.map { it.id }.toSet() }.reduce { a, b -> a intersect b }
        assertTrue(ids.containsAll(listOf("face_s", "face_e", "face_w", "face_n", "bumper_l", "bumper_r", "trigger_l", "trigger_r", "stick_l", "stick_r", "dpad")))
        for (id in ids) assertEquals(id, 1, all.map { PadMapping.default(it).actionOf(id) }.toSet().size)
    }

    // ---------------------------------------------------------------- pressed / released on the wire

    @Test fun pressAndReleaseThroughTheEngine() = all.forEach { p ->
        var last = InputState.NEUTRAL
        val e = ControllerEngine { s, _ -> last = s }
        val controls = PadMapping.default(p).controls().associateBy { it.id }
        val south = controls.getValue("face_s")
        e.setButtons(south.id, Button.fromId(south.bind)!!.mask)
        assertEquals("${p.id}: bottom face button sends A", Button.A.mask, last.buttons)
        val guide = controls.values.single { it.bind == Button.GUIDE.id }
        e.setButtons(guide.id, Button.GUIDE.mask)
        assertEquals(Button.A.mask or Button.GUIDE.mask, last.buttons)
        e.setTrigger(controls.getValue("trigger_r").id, controls.getValue("trigger_r").bind, 1.0)
        assertEquals(65535, last.rt)
        e.setButtons(south.id, 0); e.setButtons(guide.id, 0); e.setTrigger("trigger_r", "rt", 0.0)
        assertEquals("${p.id}: released", InputState.NEUTRAL, last)
    }

    @Test fun layoutChangeOrDisconnectReleasesEverything() {
        var last = InputState.NEUTRAL
        val e = ControllerEngine { s, _ -> last = s }
        e.setButtons("face_s", Button.A.mask); e.setStick("left", 0.5, -1.0); e.setTrigger("trigger_r", "rt", 0.8)
        assertFalse(last.isNeutral)
        e.releaseAll() // what ControllerScreen does on preset change, pause and exit
        assertEquals(InputState.NEUTRAL, last)
    }

    // ---------------------------------------------------------------- remapping

    @Test fun remappingSwapsSoNothingIsLostOrDuplicated() {
        val m = PadMapping.default(PadPresets.SWITCH_PRO).assign("face_e", Action.SOUTH) // Nintendo label order
        assertEquals(Action.SOUTH, m.actionOf("face_e")); assertEquals(Action.EAST, m.actionOf("face_s"))
        assertTrue(m.problems().isEmpty())
        assertEquals(m.controls().map { it.bind }.sorted(), PadMapping.default(PadPresets.SWITCH_PRO).controls().map { it.bind }.sorted())
        val back = m.assign("face_e", Action.EAST).assign("face_s", Action.SOUTH)
        assertTrue("swapping back restores defaults", back.isDefault)
    }

    @Test fun paddlesMirrorButtonsOnly() {
        val p = PadPresets.EIGHTBITDO_PRO2
        val m = PadMapping.default(p).assign("paddle_l", Action.SOUTH)
        assertEquals(Action.SOUTH, m.actionOf("paddle_l")); assertEquals(Action.SOUTH, m.actionOf("face_s"))
        assertEquals(2, m.controls().count { it.bind == Button.A.id })
        assertNull(m.assign("paddle_l", null).actionOf("paddle_l"))
        assertTrue(m.choices(p.element("paddle_l")).contains(null))
        expectFail { m.assign("paddle_l", Action.TRIGGER_R) }
    }

    @Test fun invalidAssignmentsAreRejected() {
        val x = PadMapping.default(PadPresets.XBOX)
        expectFail { x.assign("stick_l", Action.STICK_R) }       // sticks keep their kind
        expectFail { x.assign("trigger_l", Action.SOUTH) }      // analog triggers are not remappable
        expectFail { x.assign("dpad", Action.SOUTH) }
        expectFail { x.assign("face_s", null) }                 // a primary button can't be emptied
        expectFail { x.assign("face_s", Action.STICK_L) }
        expectFail { x.assign("share", Action.SOUTH) }          // no protocol input
        expectFail { x.assign("nope", Action.SOUTH) }
        assertTrue(x.choices(PadPresets.XBOX.element("stick_l")).isEmpty())
    }

    // ---------------------------------------------------------------- persistence

    @Test fun mappingRoundTripsThroughSettingsText() {
        val p = PadPresets.EIGHTBITDO_PRO2
        val m = PadMapping.default(p).assign("face_e", Action.SOUTH).assign("paddle_r", Action.BUMPER_R)
        val text = m.encode()
        assertEquals("face_e=south;face_s=east;paddle_r=bumper_r", text)
        val (back, err) = PadMapping.decode(p, text)
        assertNull(err)
        p.elements.forEach { assertEquals(it.id, m.actionOf(it), back.actionOf(it)) }
        assertEquals("", PadMapping.default(p).encode())
        assertTrue(PadMapping.decode(p, "").first.isDefault)
    }

    @Test fun corruptOrDangerousStoredMappingsFallBackToDefaults() {
        val p = PadPresets.XBOX
        listOf(
            "face_s=north",                    // duplicate: north on two buttons, south unreachable
            "face_s=-",                        // emptied primary
            "stick_l=stick_r",                 // kind change
            "face_s=warp",                     // unknown action
            "garbage",                         // unreadable
            "share=south",                     // unavailable control
            "nope=south",                      // unknown element
        ).forEach { bad ->
            val (m, err) = PadMapping.decode(p, bad)
            assertTrue("'$bad' rejected", m.isDefault); assertNotNull("'$bad' explained", err)
        }
    }

    // ---------------------------------------------------------------- geometry & touch

    @Test fun placementKeepsControlsInsideAndTargetsAtLeast48dp() {
        // realme 12 5G landscape, immersive: 873 x 393 dp. Also a narrow 16:9 tablet-like area.
        for ((w, h) in listOf(873f to 393f, 640f to 360f, 1280f to 800f)) all.forEach { p ->
            val boxes = PadMapping.default(p).active.associate { (e, _) -> e.id to PadGeometry.place(e, w, h) }
            boxes.forEach { (id, b) -> assertTrue("${p.id}/$id in ${w}x$h", b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h) }
            for ((id, b) in boxes) {
                // A finger at the centre of every control hits that control.
                assertEquals("${p.id}/$id at ${w}x$h", id, PadGeometry.hit(boxes, b.cx, b.cy, 48f))
            }
        }
    }

    @Test fun previewFitKeepsTheCanvasShape() {
        val e = PadPresets.XBOX.element("stick_l")
        val wide = PadGeometry.place(e, 400f, 100f, fit = true)
        assertEquals(wide.width, wide.height, 0.01f)
        assertTrue("letterboxed horizontally", wide.left > 36f)
    }

    private fun expectFail(f: () -> Unit) { try { f(); fail("expected MappingException") } catch (_: MappingException) { } }
}
