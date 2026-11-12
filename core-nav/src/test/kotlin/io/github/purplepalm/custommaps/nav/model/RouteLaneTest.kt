package io.github.purplepalm.custommaps.nav.model

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Made-up lanes only: what a lane kept for some kinds of vehicle carries, and what that changes. */
class RouteLaneTest {

    private val straight = setOf(TurnModifier.STRAIGHT)

    @Test
    fun `a lane carries every kind of vehicle it is kept for`() {
        for (kind in DesignatedKind.entries) {
            val lane = RouteLane(straight, valid = true, active = true, kinds = setOf(kind))
            assertEquals(setOf(kind), lane.kinds, "$kind")
            assertTrue(lane.designated, "$kind")
        }
        val busAndTaxi = RouteLane(straight, valid = false, kinds = setOf(DesignatedKind.BUS, DesignatedKind.TAXI))
        assertEquals(setOf(DesignatedKind.BUS, DesignatedKind.TAXI), busAndTaxi.kinds)
        assertTrue(busAndTaxi.designated)
    }

    @Test
    fun `a lane open to every car has no kinds and is no designated lane`() {
        val open = RouteLane(straight, valid = true)
        assertTrue(open.kinds.isEmpty())
        assertFalse(open.designated)
        assertTrue(open.usable)
    }

    @Test
    fun `whatever it is kept for, a designated lane is never one this car can use`() {
        // Usable is what it was before the kinds were kept: open to every car and marked for the route.
        for (kind in DesignatedKind.entries) {
            for (valid in listOf(true, false, null)) {
                for (active in listOf(true, false, null)) {
                    val lane = RouteLane(straight, valid = valid, active = active, kinds = setOf(kind))
                    assertFalse(lane.usable, "$kind valid=$valid active=$active")
                    assertEquals(valid ?: active ?: false, lane.marked, "the route's own marks stay as they are")
                }
            }
        }
        for (valid in listOf(true, false, null)) {
            for (active in listOf(true, false, null)) {
                val open = RouteLane(straight, valid = valid, active = active)
                assertEquals(valid ?: active ?: false, open.usable, "an open lane: valid=$valid active=$active")
            }
        }
    }

    @Test
    fun `the route's own names give the kinds, and any other word is a kind the app does not know`() {
        val names = mapOf(
            "hov" to DesignatedKind.HOV,
            "bus" to DesignatedKind.BUS,
            "taxi" to DesignatedKind.TAXI,
            "motorcycle" to DesignatedKind.MOTORCYCLE,
            "moped" to DesignatedKind.MOPED,
            "bicycle" to DesignatedKind.BICYCLE,
        )
        for ((name, kind) in names) assertEquals(kind, DesignatedKind.of(name), name)
        // Never an exception: an unknown word, another spelling, an empty word or none at all.
        for (odd in listOf("delivery", "HOV", " bus", "", "other", null)) {
            assertEquals(DesignatedKind.OTHER, DesignatedKind.of(odd), "$odd")
        }
        val unknown = RouteLane(straight, valid = true, kinds = setOf(DesignatedKind.of("delivery")))
        assertTrue(unknown.designated, "a lane kept for a kind the app does not know is kept all the same")
        assertFalse(unknown.usable)
    }

    @Test
    fun `odd marks and kinds build a lane without any check`() {
        // No combination is an error: the maps that read the route coerce, and nothing here throws.
        val lane = RouteLane(emptySet(), valid = null, active = true, validIndication = TurnModifier.U_TURN, kinds = DesignatedKind.entries.toSet())
        assertEquals(DesignatedKind.entries.size, lane.kinds.size)
        assertTrue(lane.marked)
        assertFalse(lane.usable)
    }
}
