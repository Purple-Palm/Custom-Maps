package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.guidance.CardLanes.Source
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CardLanesTest {

    private val on = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, TurnModifier.STRAIGHT)
    private val off = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false)
    private val exit = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = true, TurnModifier.SLIGHT_RIGHT)

    private val maneuverLanes = LaneInfo(listOf(off, off, exit))
    private val choiceLanes = LaneInfo(listOf(on, on, off, off))
    private val runUpLanes = LaneInfo(listOf(off, on, off))
    private val choice = LanesAhead(Distance.miles(0.4), choiceLanes)
    private val runUp = RunUpLanes(runUpLanes, LaneTarget(LaneEdge.RIGHT, 1, 1), key = "r/0/2/3", odd = null, need = 1)

    private fun snapshot(
        lanes: LaneInfo? = null,
        laneChoiceAhead: LanesAhead? = null,
        runUpLanes: RunUpLanes? = null,
        pending: Boolean = false,
    ) = RouteProgressSnapshot(
        nextManeuver = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Oak Street"),
        distanceToNextManeuver = Distance.meters(900.0),
        lanes = lanes,
        distanceRemaining = Distance.miles(12.0),
        durationRemaining = 14.minutes,
        stepKey = "r/0/2",
        laneChoiceAhead = laneChoiceAhead,
        pending = pending,
        runUpLanes = runUpLanes,
    )

    @Test
    fun `a run-up's row comes first, then a lane choice ahead, then the maneuver's lanes`() {
        val all = CardLanes.of(snapshot(maneuverLanes, choice, runUp))
        assertEquals(Source.RUN_UP, all.source)
        assertSame(runUpLanes, all.lanes)
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), all.target)
        assertNull(all.distance, "a run-up's row has no distance")

        val noRunUp = CardLanes.of(snapshot(maneuverLanes, choice))
        assertEquals(Source.CHOICE, noRunUp.source)
        assertSame(choiceLanes, noRunUp.lanes)
        assertEquals(Distance.miles(0.4), noRunUp.distance)
        assertNull(noRunUp.target)

        val maneuver = CardLanes.of(snapshot(maneuverLanes))
        assertEquals(Source.MANEUVER, maneuver.source)
        assertSame(maneuverLanes, maneuver.lanes)
        assertNull(maneuver.distance)
        assertNull(maneuver.target)

        // A run-up row without words is still the run-up's row.
        val quiet = CardLanes.of(snapshot(maneuverLanes, runUpLanes = runUp.copy(target = null, odd = RunUpRule.ODD_COMPOSED)))
        assertEquals(Source.RUN_UP, quiet.source)
        assertNull(quiet.target)
    }

    @Test
    fun `a run-up's row from the lane plan comes as it is, its lanes still to come and its want of words included`() {
        val toCome = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false, outline = true)
        val option = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
        val lanes = LaneInfo(listOf(off, off, off, option, toCome))
        val fromPlan = RunUpLanes(lanes, target = null, key = "r/0/2/end", odd = RunUpRule.ODD_OPENS_AHEAD, need = 0, edge = LaneEdge.RIGHT)
        val row = CardLanes.of(snapshot(maneuverLanes, choice, fromPlan))
        assertEquals(Source.RUN_UP, row.source)
        assertSame(lanes, row.lanes)
        assertNull(row.target)
        // A pending card shows none of it.
        assertEquals(Source.NONE, CardLanes.of(snapshot(maneuverLanes, choice, fromPlan, pending = true)).source)
    }

    @Test
    fun `no lanes at all is no row`() {
        val none = CardLanes.of(snapshot())
        assertEquals(Source.NONE, none.source)
        assertNull(none.lanes)
        assertNull(none.distance)
        assertNull(none.target)
    }

    @Test
    fun `a pending card shows no row, whatever it carries`() {
        for (pending in listOf(snapshot(maneuverLanes, choice, runUp, pending = true), snapshot(maneuverLanes, pending = true))) {
            val row = CardLanes.of(pending)
            assertEquals(Source.NONE, row.source)
            assertNull(row.lanes)
        }
    }
}
