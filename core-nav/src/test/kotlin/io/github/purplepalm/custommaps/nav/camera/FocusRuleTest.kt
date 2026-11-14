package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which junctions the view looks down onto, by the step's own maneuver. */
class FocusRuleTest {

    private fun kind(type: ManeuverType?, modifier: TurnModifier? = null, onFreeway: Boolean = false, roads: Int = 3) =
        FocusRule.kind(type, modifier, onFreeway, roads)

    private fun why(type: ManeuverType?, modifier: TurnModifier? = null, onFreeway: Boolean = false, roads: Int = 3) =
        FocusRule.why(type, modifier, onFreeway, roads)

    @Test
    fun `a step that goes straight on keeps the view up, a name change with a turn is a junction`() {
        for (type in listOf(ManeuverType.NEW_NAME, ManeuverType.CONTINUE)) {
            for (onFreeway in listOf(false, true)) {
                assertEquals(FocusKind.NONE, kind(type, TurnModifier.STRAIGHT, onFreeway), "$type freeway $onFreeway")
                assertEquals(FocusKind.NONE, kind(type, null, onFreeway), "$type freeway $onFreeway")
                // Five roads meeting where the route goes straight on is no junction the driver turns at.
                assertEquals(FocusKind.NONE, kind(type, TurnModifier.STRAIGHT, onFreeway, roads = 6), "$type freeway $onFreeway")
            }
        }
        for (modifier in listOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.RIGHT, TurnModifier.SLIGHT_RIGHT)) {
            assertEquals(FocusKind.TURN, kind(ManeuverType.NEW_NAME, modifier), "$modifier")
            assertEquals(FocusKind.TURN, kind(ManeuverType.NEW_NAME, modifier, onFreeway = true), "$modifier")
        }
        assertEquals(FocusKind.COMPLEX, kind(ManeuverType.NEW_NAME, TurnModifier.SHARP_RIGHT))
    }

    @Test
    fun `a split off a street is complex, the same on a freeway keeps the view up`() {
        for (type in listOf(ManeuverType.FORK, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP)) {
            for (modifier in listOf(null, TurnModifier.SLIGHT_RIGHT, TurnModifier.LEFT)) {
                assertEquals(FocusKind.COMPLEX, kind(type, modifier), "$type $modifier")
                assertEquals(FocusWhy.SPLIT, why(type, modifier), "$type $modifier")
                assertEquals(FocusKind.NONE, kind(type, modifier, onFreeway = true), "$type $modifier")
                assertNull(why(type, modifier, onFreeway = true), "$type $modifier")
            }
        }
        for (modifier in listOf(TurnModifier.LEFT, TurnModifier.SLIGHT_RIGHT)) {
            assertEquals(FocusKind.COMPLEX, kind(ManeuverType.CONTINUE, modifier))
            assertEquals(FocusWhy.SPLIT, why(ManeuverType.CONTINUE, modifier))
            assertEquals(FocusKind.NONE, kind(ManeuverType.CONTINUE, modifier, onFreeway = true))
        }
    }

    @Test
    fun `a merge, the departure and a notification keep the view up`() {
        for (type in listOf(ManeuverType.MERGE, ManeuverType.DEPART, ManeuverType.NOTIFICATION)) {
            for (onFreeway in listOf(false, true)) {
                assertEquals(FocusKind.NONE, kind(type, TurnModifier.SLIGHT_LEFT, onFreeway, roads = 6), "$type freeway $onFreeway")
                assertNull(why(type, TurnModifier.SLIGHT_LEFT, onFreeway, roads = 6))
            }
        }
        assertEquals(FocusKind.NONE, kind(null))
        assertNull(why(null))
    }

    @Test
    fun `the way into a roundabout is complex, the way out a plain junction`() {
        for (type in listOf(ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN)) {
            for (onFreeway in listOf(false, true)) {
                assertEquals(FocusKind.COMPLEX, kind(type, TurnModifier.RIGHT, onFreeway), "$type freeway $onFreeway")
                assertEquals(FocusWhy.ROUNDABOUT, why(type, TurnModifier.RIGHT, onFreeway), "$type freeway $onFreeway")
            }
        }
        for (type in listOf(ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)) {
            assertEquals(FocusKind.TURN, kind(type, TurnModifier.RIGHT, roads = 6), "$type")
            assertEquals(FocusKind.TURN, kind(type, TurnModifier.SHARP_LEFT), "$type")
            assertNull(why(type, TurnModifier.RIGHT))
        }
    }

    @Test
    fun `five roads or a sharp turn make a junction complex, four roads do not`() {
        for (type in listOf(ManeuverType.TURN, ManeuverType.END_OF_ROAD, ManeuverType.UNKNOWN)) {
            assertEquals(FocusKind.TURN, kind(type, TurnModifier.RIGHT, roads = FocusRule.MANY_ROADS - 1), "$type")
            assertNull(why(type, TurnModifier.RIGHT, roads = FocusRule.MANY_ROADS - 1), "$type")
            assertEquals(FocusKind.COMPLEX, kind(type, TurnModifier.RIGHT, roads = FocusRule.MANY_ROADS), "$type")
            assertEquals(FocusWhy.MANY_ROADS, why(type, TurnModifier.RIGHT, roads = FocusRule.MANY_ROADS), "$type")
            for (modifier in listOf(TurnModifier.SHARP_LEFT, TurnModifier.SHARP_RIGHT, TurnModifier.U_TURN)) {
                assertEquals(FocusKind.COMPLEX, kind(type, modifier), "$type $modifier")
                // A sharp turn at a junction of many roads is named for its turn.
                assertEquals(FocusWhy.SHARP, why(type, modifier, roads = 6), "$type $modifier")
            }
        }
        // An unknown number of roads is no reason.
        assertEquals(FocusKind.TURN, kind(ManeuverType.TURN, TurnModifier.LEFT, roads = 0))
    }

    @Test
    fun `the arrival and a maneuver of an unknown kind are plain junctions`() {
        assertEquals(FocusKind.TURN, kind(ManeuverType.ARRIVE, null))
        assertEquals(FocusKind.TURN, kind(ManeuverType.ARRIVE, TurnModifier.LEFT, roads = 6))
        assertEquals(FocusKind.TURN, kind(ManeuverType.UNKNOWN, null))
        assertEquals(FocusKind.TURN, kind(ManeuverType.TURN, TurnModifier.LEFT, onFreeway = true))
    }

    @Test
    fun `without the facts the kind is the navigation SDK's own rule on the banner's type`() {
        for (type in listOf(ManeuverType.CONTINUE, ManeuverType.MERGE, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK)) {
            assertEquals(FocusKind.NONE, FocusRule.fallback(type), "$type")
        }
        assertEquals(FocusKind.NONE, FocusRule.fallback(null))
        for (type in ManeuverType.entries) {
            if (type in setOf(ManeuverType.CONTINUE, ManeuverType.MERGE, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK)) continue
            assertEquals(FocusKind.TURN, FocusRule.fallback(type), "$type")
        }
    }

    @Test
    fun `facts past a step the view stays up for name the junction after it`() {
        val turn = ManeuverFacts("r/0/4", FocusKind.TURN, turnDegrees = 90.0)
        val nameChange = ManeuverFacts("r/0/3", FocusKind.NONE, next = turn, nextMeters = 100.0)
        assertEquals("r/0/4", nameChange.nextKey)
        assertEquals(FocusKind.TURN, nameChange.nextKind)
        val alone = ManeuverFacts("r/0/3", FocusKind.NONE)
        assertNull(alone.nextKey)
        assertEquals(FocusKind.NONE, alone.nextKind)
        assertEquals(0.0, alone.chainedMeters)
        assertNull(alone.turnDegrees)
    }
}
