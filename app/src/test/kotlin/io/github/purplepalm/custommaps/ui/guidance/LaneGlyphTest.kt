package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Lanes to take are marked by shape as well as colour, since by lightness alone they differ from
 * the others by 1.91:1 by day and 2.31:1 at night: the route's arrow in them is heavier, and a bar
 * lies under them. A lane kept for car pools or buses never gets the mark.
 */
class LaneGlyphTest {

    @Test
    fun `the route's arrow in a lane to take is the heaviest, the maneuver arrow between`() {
        assertEquals(0.15f, laneStroke(active = true))
        assertEquals(0.09f, laneStroke(active = false))
        assertEquals(0.11f, MANEUVER_STROKE)
        assertTrue(laneStroke(active = false) < MANEUVER_STROKE && MANEUVER_STROKE < laneStroke(active = true))
    }

    private class Row(val what: String, val lane: Lane, val arrow: TurnModifier, val stroke: Float, val toTake: Boolean)

    private val rows = listOf(
        Row("a lane to take, its route's arrow", straightOrRight(lit = true), TurnModifier.SLIGHT_RIGHT, 0.15f, toTake = true),
        Row("a lane to take, its other arrow", straightOrRight(lit = true), TurnModifier.STRAIGHT, 0.09f, toTake = true),
        Row("a lane not to take", straightOrRight(lit = false), TurnModifier.SLIGHT_RIGHT, 0.09f, toTake = false),
        // The route can be followed from it, but the data names none of its two arrows.
        Row("a lane to take whose arrow nobody named", Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.LEFT), isRecommended = true), TurnModifier.STRAIGHT, 0.09f, toTake = true),
        Row("a car pool lane, dim", Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true), TurnModifier.STRAIGHT, 0.09f, toTake = false),
        // Where it is the only lane the route marks, its arrow is lit, in colour alone.
        Row("a car pool lane lit as the only one", Lane(setOf(TurnModifier.STRAIGHT), true, TurnModifier.STRAIGHT, designated = true), TurnModifier.STRAIGHT, 0.09f, toTake = false),
        // A lane the map does not draw yet: hollow arrows as wide as a lane to take's, never lit, never the bar.
        Row("a lane still to come", Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false, outline = true), TurnModifier.SLIGHT_RIGHT, 0.15f, toTake = false),
        Row("a lane still to come, marked to take", straightOrRight(lit = true).copy(outline = true), TurnModifier.SLIGHT_RIGHT, 0.15f, toTake = false),
    )

    @Test
    fun `which arrow is drawn heavy`() {
        assertAll(rows.map { row -> Executable { assertEquals(row.stroke, laneArrowStroke(row.lane, row.arrow), row.what) } })
    }

    @Test
    fun `which lanes get the bar`() {
        assertAll(rows.map { row -> Executable { assertEquals(row.toTake, isLaneToTake(row.lane), row.what) } })
    }

    private fun straightOrRight(lit: Boolean) =
        Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = lit, activeIndication = if (lit) TurnModifier.SLIGHT_RIGHT else null)
}
