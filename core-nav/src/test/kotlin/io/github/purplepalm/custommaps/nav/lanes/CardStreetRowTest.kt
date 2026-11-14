package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.ARRIVE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.CROSSING
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.DEPART
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.TURN_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.right
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.street
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The card's own row at a maneuver on a street, lit from the lane plan ([CardStreetRow], the design's 6, E12), on the
 * made-up streets of [LaneScenes] (the maneuver at 6,000 m), against rows the navigation SDK might give: the plan's amber set
 * lit, lanes kept for others dim, a lane for bicycles never lit, and the SDK's row elsewhere.
 */
class CardStreetRowTest {

    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** The lane point at the maneuver at [GORE]. */
    private fun turn(plan: LanePlan): LaneTopology.Point = plan.topology.points.single { it.station == GORE }

    /** A row as the SDK might draw it: every lane with its arrows, [lit] lit on the arrow [arrow], those in [dim] kept for others. */
    private fun sdk(lanes: List<Set<TurnModifier>>, lit: List<Int>, arrow: TurnModifier? = null, dim: Set<Int> = emptySet()) = LaneInfo(
        lanes.mapIndexed { i, arrows ->
            Lane(arrows, isRecommended = i in lit, activeIndication = if (i in lit) arrow ?: arrows.first() else null, designated = i in dim)
        },
    )

    private fun lit(row: LaneInfo): List<Int> = row.lanes.indices.filter { row.lanes[it].isRecommended }

    private val straight = setOf(TurnModifier.STRAIGHT)
    private val rightArrow = setOf(TurnModifier.RIGHT)

    @Test
    fun `at a street turn the row lights the plan's amber set, on the arrow the route takes`() {
        val route = LaneScenes.streetTurn()
        val plan = plan(route)
        val point = turn(plan)
        val amber = CardPlanRow.amber(point, plan.segmentAt(point.station))
        assertEquals(listOf(1), amber, "the right lane turns")
        // The SDK lighting both lanes, or neither: the plan's lane lit, the other not, the arrows as the SDK drew them.
        for (shown in listOf(sdk(listOf(straight, rightArrow), lit = listOf(0, 1)), sdk(listOf(straight, rightArrow), lit = emptyList()))) {
            val row = CardStreetRow.of(shown, plan, point)
            assertEquals(amber, lit(row))
            assertEquals(TurnModifier.RIGHT, row.lanes[1].activeIndication)
            assertEquals(null, row.lanes[0].activeIndication)
            assertEquals(shown.lanes.map { it.indications }, row.lanes.map { it.indications })
            // The row agrees with the plan, the card's rules 1 and 2.
            val agreement = CardPlanAgreement.of(CardPlanAgreement.Row(CardLanes.Source.MANEUVER, row.lanes, words = false), plan, point, GORE - 150.0)
            assertTrue(agreement.agree, "$agreement")
        }
    }

    @Test
    fun `a lane kept for buses stays dim and unlit, a lane for bicycles is dim and never lit`() {
        val route = LaneScenes.streetTurn(bicycle = true, bus = true)
        val plan = plan(route)
        val point = turn(plan)
        // Bus, straight, right turn, bicycles: the SDK lights the bus lane, the right turn and the bicycle lane, and marks nothing
        // kept for others.
        val shown = sdk(listOf(straight, straight, rightArrow, straight), lit = listOf(0, 2, 3), arrow = null)
        val row = CardStreetRow.of(shown, plan, point)
        assertEquals(listOf(2), lit(row))
        assertTrue(row.lanes[0].designated && !row.lanes[0].isRecommended, "the bus lane dim and unlit")
        assertTrue(row.lanes[3].designated && !row.lanes[3].isRecommended, "the bicycle lane dim and unlit")
        assertFalse(row.lanes[1].designated || row.lanes[2].designated)
        assertTrue(CardStreetRow.bicycle(route.nodes[point.node].lanes[3]))
        assertFalse(CardStreetRow.bicycle(route.nodes[point.node].lanes[0]))
    }

    @Test
    fun `a left pocket is lit on its left arrow, and a turn two lanes make is lit on both`() {
        val pocket = LaneScenes.junctionPocket()
        val pocketPlan = plan(pocket)
        val point = turn(pocketPlan)
        val row = CardStreetRow.of(sdk(listOf(setOf(TurnModifier.LEFT), straight, straight), lit = listOf(1, 2)), pocketPlan, point)
        assertEquals(listOf(0), lit(row))
        assertEquals(TurnModifier.LEFT, row.lanes[0].activeIndication)
        // Both lanes turn right: both lit, as the plan's amber set has both.
        val two = LaneScenes.route(
            street(0.0, maneuver = DEPART),
            street(GORE - 300.0, s(), s()),
            street(GORE, right(), right(), roads = CROSSING, maneuver = TURN_RIGHT),
            street(GORE + 300.0, maneuver = ARRIVE),
            id = "two-right",
        )
        val twoPlan = plan(two)
        val twoPoint = turn(twoPlan)
        assertEquals(listOf(0, 1), CardPlanRow.amber(twoPoint, twoPlan.segmentAt(twoPoint.station)))
        assertEquals(listOf(0, 1), lit(CardStreetRow.of(sdk(listOf(rightArrow, rightArrow), lit = listOf(1)), twoPlan, twoPoint)))
    }

    @Test
    fun `elsewhere the SDK's row stands, on a freeway, where no lane can be named and where the counts differ`() {
        // A freeway exit: the SDK's row, the very same.
        val freeway = LaneScenes.ia()
        val freewayPlan = plan(freeway)
        val gore = freewayPlan.topology.points.first { freeway.nodes[it.node].maneuver != null }
        val freewayRow = sdk(freeway.nodes[gore.node].lanes.map { it.indications.ifEmpty { straight } }, lit = listOf(0))
        assertSame(freewayRow, CardStreetRow.of(freewayRow, freewayPlan, gore))
        // A roundabout and a turn only a bus lane makes: the plan names no lane there (C1).
        for (route in listOf(LaneScenes.roundabout(), LaneScenes.busOnlyTurn())) {
            val plan = plan(route)
            val point = turn(plan)
            assertTrue(plan.segmentAt(point.station)?.noLane != null, route.routeId)
            val shown = sdk(route.nodes[point.node].lanes.map { it.indications.ifEmpty { straight } }, lit = listOf(0))
            assertSame(shown, CardStreetRow.of(shown, plan, point), route.routeId)
        }
        // A row of another count than the route's lanes there.
        val route = LaneScenes.streetTurn()
        val plan = plan(route)
        val three = sdk(listOf(straight, straight, rightArrow), lit = listOf(2))
        assertSame(three, CardStreetRow.of(three, plan, turn(plan)))
    }

    @Test
    fun `one instance hands out the same row while it does not change, and the SDK's without a plan or a point`() {
        val route = LaneScenes.streetTurn()
        val plan = plan(route)
        val point = turn(plan)
        val rows = CardStreetRow()
        val first = requireNotNull(rows.row(sdk(listOf(straight, rightArrow), lit = listOf(0, 1)), plan, point))
        assertSame(first, rows.row(sdk(listOf(straight, rightArrow), lit = listOf(0, 1)), plan, point))
        assertEquals(listOf(1), lit(first))
        val alone = sdk(listOf(straight, rightArrow), lit = listOf(0))
        assertSame(alone, rows.row(alone, null, point))
        assertSame(alone, rows.row(alone, plan, null))
        assertEquals(null, rows.row(null, plan, point))
        rows.reset()
        assertNotSame(first, rows.row(sdk(listOf(straight, rightArrow), lit = listOf(0, 1)), plan, point))
    }
}
