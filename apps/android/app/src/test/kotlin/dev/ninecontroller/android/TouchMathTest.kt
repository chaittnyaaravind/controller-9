package dev.ninecontroller.android

import dev.ninecontroller.android.input.DpadMath
import dev.ninecontroller.android.pad.PadGeometry
import dev.ninecontroller.protocol.Button as Btn
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** Same cases as apps/ios/Core/Tests/NineAppCoreTests/TouchMathTests.swift. */
class TouchMathTest {
    private val L = Btn.LEFT.mask; private val U = Btn.UP.mask; private val R = Btn.RIGHT.mask; private val D = Btn.DOWN.mask
    private fun at(deg: Double, r: Double, prev: Int = 0) =
        DpadMath.resolve(r * cos(Math.toRadians(deg)), r * sin(Math.toRadians(deg)), 1.0, prev)

    @Test fun slightlyOffCardinalStaysCardinal() {
        // The old 8 x 45° split sent left+up here (157.5° boundary); a thumb aiming left means left.
        assertEquals(L, at(153.0, 0.8)); assertEquals(L, at(207.0, 0.8))
        assertEquals(U, at(63.0, 0.8)); assertEquals(R, at(-27.0, 0.8)); assertEquals(D, at(242.0, 0.8))
    }

    @Test fun diagonalsAreDeliberate() {
        assertEquals(R or U, at(45.0, 0.8)); assertEquals(U or L, at(135.0, 0.8))
        assertEquals(L or D, at(222.0, 0.8)); assertEquals(D or R, at(313.0, 0.8))
    }

    @Test fun heldDirectionDoesNotFlickerOnARoll() {
        assertEquals(L, at(145.0, 0.8, prev = L))           // 5° past the boundary: still left
        assertEquals(U or L, at(140.0, 0.8, prev = L))      // clearly in the diagonal: switch
        assertEquals(U or L, at(152.0, 0.8, prev = U or L)) // and the diagonal is sticky too
    }

    @Test fun centreDeadZoneHasHysteresis() {
        assertEquals(0, at(180.0, 0.20))                    // not far enough to press
        assertEquals(L, at(180.0, 0.25))
        assertEquals(L, at(180.0, 0.18, prev = L))          // resting near the centre: no chatter
        assertEquals(0, at(180.0, 0.12, prev = L))
    }

    @Test fun fingerOnAControlAlwaysWins() {
        val boxes = mapOf(
            "stick" to PadGeometry.Box(0f, 0f, 300f, 300f),
            "small" to PadGeometry.Box(290f, 140f, 320f, 170f), // overlaps the stick's right edge
        )
        assertEquals("stick", PadGeometry.hit(boxes, 150f, 150f, 132f))
        assertEquals("small", PadGeometry.hit(boxes, 318f, 150f, 132f)) // only inside "small"
        assertEquals("small", PadGeometry.hit(boxes, 295f, 155f, 132f)) // inside both: closer centre
        assertEquals(null, PadGeometry.hit(boxes, 360f, 290f, 0f))
    }

    @Test fun roundButtonsHitTestAsCircles() {
        // Face diamond on an iPhone-15-shaped area: the corner shared by two face buttons' boxes is in
        // neither circle, so a thumb in the gap presses nothing instead of a neighbour.
        val e = dev.ninecontroller.android.pad.PadPresets.XBOX
        val n = PadGeometry.place(e.element("face_n"), 734f, 372f); val east = PadGeometry.place(e.element("face_e"), 734f, 372f)
        val boxes = mapOf("face_n" to n, "face_e" to east)
        assertEquals(null, PadGeometry.hit(boxes, (n.right + east.left) / 2, (n.bottom + east.top) / 2, 0f))
        assertEquals("face_n", PadGeometry.hit(boxes, n.cx, n.cy, 0f))
    }

    @Test fun nearMissGoesToNearestEdgeNotNearestCentre() {
        val boxes = mapOf(
            "tall" to PadGeometry.Box(100f, 0f, 110f, 300f),   // centre far away vertically
            "small" to PadGeometry.Box(140f, 10f, 160f, 30f),  // centre close by
        )
        // Point is 12 px from "tall"'s edge and 18 px from "small"'s; both grown targets contain it.
        assertEquals("tall", PadGeometry.hit(boxes, 122f, 20f, 60f))
    }
}
