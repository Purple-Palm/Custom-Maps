package io.github.purplepalm.custommaps.nav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LaneInfoTest {

    @Test
    fun `counts recommended lanes`() {
        val lanes = LaneInfo(
            listOf(
                Lane(setOf(TurnModifier.LEFT), isRecommended = false),
                Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), isRecommended = true, TurnModifier.RIGHT),
                Lane(setOf(TurnModifier.RIGHT), isRecommended = true, TurnModifier.RIGHT),
            ),
        )
        assertEquals(2, lanes.recommendedLaneCount)
    }

    @Test
    fun `lane guidance needs at least one lane`() {
        assertThrows<IllegalArgumentException> { LaneInfo(emptyList()) }
    }

    @Test
    fun `only a recommended lane can have an active arrow`() {
        assertThrows<IllegalArgumentException> {
            Lane(setOf(TurnModifier.LEFT), isRecommended = false, activeIndication = TurnModifier.LEFT)
        }
    }

    @Test
    fun `a lane is open to every car unless it says otherwise`() {
        assertFalse(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, TurnModifier.STRAIGHT).designated)
    }

    @Test
    fun `a lane kept for car pools adds no check of its own`() {
        // The maps that read the route decide when such a lane is lit; any combination is a lane.
        for (recommended in listOf(true, false)) {
            val lane = Lane(
                setOf(TurnModifier.STRAIGHT),
                isRecommended = recommended,
                activeIndication = if (recommended) TurnModifier.STRAIGHT else null,
                designated = true,
            )
            assertTrue(lane.designated)
            assertEquals(recommended, lane.isRecommended)
        }
        assertEquals(1, LaneInfo(listOf(Lane(emptySet(), isRecommended = true, designated = true))).recommendedLaneCount)
        // The one check there is stays the same for it.
        assertThrows<IllegalArgumentException> {
            Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, activeIndication = TurnModifier.STRAIGHT, designated = true)
        }
    }

    @Test
    fun `a lane as the route lists it can be used only when open and marked for the route`() {
        val straight = setOf(TurnModifier.STRAIGHT)
        assertTrue(RouteLane(straight, valid = true).usable)
        assertTrue(RouteLane(straight, valid = null, active = true).usable, "the preferred flag where the route gives no other")
        assertFalse(RouteLane(straight, valid = false, active = true).usable, "valid decides where it is given")
        assertFalse(RouteLane(straight).usable, "no flags: not marked")
        assertFalse(RouteLane(straight, valid = true, active = true, designated = true).usable, "kept for car pools")
        assertTrue(RouteLane(straight, valid = true, designated = true).marked)
    }
}
