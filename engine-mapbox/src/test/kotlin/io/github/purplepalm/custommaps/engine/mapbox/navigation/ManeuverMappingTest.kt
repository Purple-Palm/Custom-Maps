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
    fun `roundabouts and rotaries are recognised`() {
        assertTrue(ManeuverMapping.isRoundabout(ManeuverType.ROTARY))
        assertFalse(ManeuverMapping.isRoundabout(ManeuverType.TURN))
    }
}
