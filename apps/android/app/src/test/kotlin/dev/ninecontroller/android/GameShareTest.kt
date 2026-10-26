package dev.ninecontroller.android

import dev.ninecontroller.android.data.Game
import dev.ninecontroller.android.data.GameShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameShareTest {
    private val g = Game("asphalt-legends", "Asphalt Legends", "CRIMSON", "pad:xbox", tilt = true, aim = false)

    @Test fun roundTripsTheWholeSetup() {
        val code = GameShare.encode(g, "face_south=east;face_east=south", "face_south=turbo")
        assertTrue(code.startsWith("c9game:1:"))
        val s = GameShare.decode(code) { "imported-id" }!!
        assertEquals(g.copy(id = "imported-id"), s.game)
        assertEquals("face_south=east;face_east=south", s.mapping)
        assertEquals("face_south=turbo", s.modes)
    }

    @Test fun rejectsOtherCodesAndGarbage() {
        assertNull(GameShare.decode("ninectl://pair?v=1") { it })
        assertNull(GameShare.decode("c9game:1:%%%") { it })
        assertNull(GameShare.decode("c9game:2:" + GameShare.encode(g, null, null).removePrefix("c9game:1:")) { it })
    }

    @Test fun rejectsAnUnsafeLayoutAndCapsTheName() {
        val evil = GameShare.encode(g.copy(layout = "../../etc"), null, null)
        assertNull(GameShare.decode(evil) { it })
        val long = GameShare.decode(GameShare.encode(g.copy(name = "x".repeat(200)), null, null)) { it }!!
        assertEquals(Game.MAX_NAME, long.game.name.length)
    }
}
