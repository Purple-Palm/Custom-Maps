package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.navigation.tripdata.maneuver.model.LaneIndicator
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
    fun `a lane for car pools is drawn dim and not lit while a lane every car may use is active`() {
        val lane = ManeuverMapping.lane(listOf("straight"), isActive = true, activeDirection = "straight", designated = true, openLaneActive = true)
        assertTrue(lane.designated)
        assertFalse(lane.isRecommended)
        assertNull(lane.activeIndication)
        assertEquals(setOf(TurnModifier.STRAIGHT), lane.indications, "it keeps its arrow, dim")
        // Without word of the other lanes it is taken that one of them is active.
        assertFalse(ManeuverMapping.lane(listOf("straight"), isActive = true, activeDirection = null, designated = true).isRecommended)
    }

    @Test
    fun `where only lanes for car pools are marked they keep their arrow lit`() {
        val lane = ManeuverMapping.lane(listOf("straight", "right"), isActive = true, activeDirection = "right", designated = true, openLaneActive = false)
        assertTrue(lane.designated)
        assertTrue(lane.isRecommended)
        assertEquals(TurnModifier.RIGHT, lane.activeIndication)
    }

    @Test
    fun `a lane every car may use is lit as before whatever the other lanes are`() {
        for (openLaneActive in listOf(true, false)) {
            val lane = ManeuverMapping.lane(listOf("right"), isActive = true, activeDirection = null, openLaneActive = openLaneActive)
            assertFalse(lane.designated)
            assertTrue(lane.isRecommended)
            assertEquals(TurnModifier.RIGHT, lane.activeIndication)
        }
    }

    @Test
    fun `every combination of marks gives a lane the card can draw`() {
        val arrows = listOf(emptyList(), listOf("straight"), listOf("straight", "slight right"), listOf("none"))
        val named = listOf(null, "straight", "slight right", "sharp left", "none")
        for (directions in arrows) {
            for (activeDirection in named) {
                for (isActive in listOf(true, false)) {
                    for (designated in listOf(true, false)) {
                        for (openLaneActive in listOf(true, false)) {
                            val what = "$directions $activeDirection $isActive $designated $openLaneActive"
                            val lane = ManeuverMapping.lane(directions, isActive, activeDirection, designated, openLaneActive)
                            assertEquals(isActive && !(designated && openLaneActive), lane.isRecommended, what)
                            assertEquals(designated, lane.designated, what)
                            assertTrue(lane.activeIndication == null || lane.isRecommended, what)
                            val ahead = ManeuverMapping.laneAhead(directions, isActive, activeDirection, designated, openLaneActive)
                            assertEquals(isActive && !(designated && openLaneActive), ahead.isRecommended, what)
                            assertEquals(designated, ahead.designated, what)
                            assertEquals(ahead.isRecommended, ahead.activeIndication != null, what)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `at a point the route goes straight through a lane for car pools is dim while another lane can be used`() {
        val dim = ManeuverMapping.laneAhead(listOf("straight"), usable = true, usedDirection = "straight", designated = true, openLaneUsable = true)
        assertTrue(dim.designated)
        assertFalse(dim.isRecommended)
        assertNull(dim.activeIndication)
        val only = ManeuverMapping.laneAhead(listOf("straight"), usable = true, usedDirection = null, designated = true, openLaneUsable = false)
        assertTrue(only.isRecommended)
        assertEquals(TurnModifier.STRAIGHT, only.activeIndication)
    }

    private fun indicator(active: Boolean, vararg directions: String, designated: List<String> = emptyList(), used: String? = null) =
        LaneIndicator.Builder()
            .isActive(active)
            .directions(directions.toList())
            .drivingSide("right")
            .activeDirection(used)
            .accessDesignated(designated)
            .build()

    @Test
    fun `the maneuver's row keeps the car pool lane dim while a lane every car may use is active`() {
        val row = requireNotNull(
            laneInfoOf(
                listOf(
                    indicator(true, "straight", designated = listOf("hov")),
                    indicator(true, "straight"),
                    indicator(false, "straight"),
                    indicator(true, "slight right", used = "slight right"),
                ),
            ),
        ).lanes
        assertEquals(listOf(true, false, false, false), row.map { it.designated })
        assertEquals(listOf(false, true, false, true), row.map { it.isRecommended })
        assertEquals(listOf(null, TurnModifier.STRAIGHT, null, TurnModifier.SLIGHT_RIGHT), row.map { it.activeIndication })
    }

    @Test
    fun `the maneuver's row lights the car pool lanes where only they are active`() {
        val row = requireNotNull(
            laneInfoOf(
                listOf(
                    indicator(true, "straight", designated = listOf("hov"), used = "straight"),
                    indicator(false, "straight"),
                    indicator(false, "straight", designated = listOf("bus")),
                ),
            ),
        ).lanes
        assertEquals(listOf(true, false, false), row.map { it.isRecommended })
        assertEquals(TurnModifier.STRAIGHT, row[0].activeIndication)
        assertTrue(row[0].designated)
        assertNull(laneInfoOf(emptyList()), "no lanes, no row")
    }

    @Test
    fun `roundabouts and rotaries are recognised`() {
        assertTrue(ManeuverMapping.isRoundabout(ManeuverType.ROTARY))
        assertFalse(ManeuverMapping.isRoundabout(ManeuverType.TURN))
    }
}
