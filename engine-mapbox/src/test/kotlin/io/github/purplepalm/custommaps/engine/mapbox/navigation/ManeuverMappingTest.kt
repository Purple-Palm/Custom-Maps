package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ManeuverMappingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "turn, TURN",
        "off ramp, OFF_RAMP",
        "on ramp, ON_RAMP",
        "fork, FORK",
        "merge, MERGE",
        "roundabout, ROUNDABOUT",
        "exit rotary, EXIT_ROTARY",
        "arrive, ARRIVE",
        "something new, UNKNOWN",
    )
    fun `maneuver types`(value: String, expected: ManeuverType) {
        assertEquals(expected, ManeuverMapping.type(value))
    }

    @Test
    fun `missing type is unknown`() {
        assertEquals(ManeuverType.UNKNOWN, ManeuverMapping.type(null))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "uturn, U_TURN",
        "sharp right, SHARP_RIGHT",
        "slight left, SLIGHT_LEFT",
        "straight, STRAIGHT",
    )
    fun `turn modifiers`(value: String, expected: TurnModifier) {
        assertEquals(expected, ManeuverMapping.modifier(value))
    }

    @Test
    fun `unknown modifier maps to none`() {
        assertNull(ManeuverMapping.modifier("none"))
        assertNull(ManeuverMapping.modifier(null))
    }

    @Test
    fun `a recommended lane keeps the arrow the route takes`() {
        val lane = ManeuverMapping.lane(listOf("straight", "right"), isActive = true, activeDirection = "right")
        assertEquals(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), lane.indications)
        assertTrue(lane.isRecommended)
        assertEquals(TurnModifier.RIGHT, lane.activeIndication)
    }

    @Test
    fun `a recommended single-arrow lane without an active direction uses that arrow`() {
        val lane = ManeuverMapping.lane(listOf("left"), isActive = true, activeDirection = null)
        assertEquals(TurnModifier.LEFT, lane.activeIndication)
    }

    @Test
    fun `other lanes have no active arrow`() {
        val lane = ManeuverMapping.lane(listOf("left", "straight"), isActive = false, activeDirection = "left")
        assertFalse(lane.isRecommended)
        assertNull(lane.activeIndication)
    }

    @Test
    fun `a lane the route goes straight on from is lit with the arrow the route names`() {
        val lane = ManeuverMapping.laneAhead(listOf("straight", "slight left"), usable = true, usedDirection = "slight left")
        assertEquals(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_LEFT), lane.indications)
        assertTrue(lane.isRecommended)
        assertEquals(TurnModifier.SLIGHT_LEFT, lane.activeIndication)
    }

    @Test
    fun `without a named arrow a usable lane is lit with the arrow that turns least`() {
        fun lit(vararg arrows: String?) = ManeuverMapping.laneAhead(arrows.toList(), usable = true, usedDirection = null).activeIndication
        assertEquals(TurnModifier.STRAIGHT, lit("straight"))
        assertEquals(TurnModifier.LEFT, lit("left"), "its only arrow")
        assertEquals(TurnModifier.STRAIGHT, lit("right", "straight"))
        assertEquals(TurnModifier.STRAIGHT, lit("slight left", "straight", "slight right"))
        assertEquals(TurnModifier.SLIGHT_RIGHT, lit("right", "slight right"))
        assertEquals(TurnModifier.SLIGHT_LEFT, lit("sharp left", "slight left", "left"))
        assertEquals(TurnModifier.LEFT, lit("uturn", "left", "sharp left"))
        assertEquals(TurnModifier.RIGHT, lit("sharp right", "right"))
        assertEquals(TurnModifier.SHARP_RIGHT, lit("uturn", "sharp right"))
        // A lane without arrows is drawn with a straight one, and that one is lit.
        assertEquals(TurnModifier.STRAIGHT, lit())
        assertEquals(TurnModifier.STRAIGHT, lit("none", null))
        assertTrue(ManeuverMapping.laneAhead(listOf("none", null), usable = true, usedDirection = null).indications.isEmpty())
    }

    @Test
    fun `a lane that leaves the route has no lit arrow`() {
        val lane = ManeuverMapping.laneAhead(listOf("slight right"), usable = false, usedDirection = "slight right")
        assertEquals(setOf(TurnModifier.SLIGHT_RIGHT), lane.indications)
        assertFalse(lane.isRecommended)
        assertNull(lane.activeIndication)
    }

    @Test
    fun `roundabouts and rotaries are recognised`() {
        assertTrue(ManeuverMapping.isRoundabout(ManeuverType.ROTARY))
        assertFalse(ManeuverMapping.isRoundabout(ManeuverType.TURN))
    }
}
