package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.LaneRowLog
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The card's run-up row read from the lane plan ([CardPlanRow]) on made-up routes only ([LaneScenes]: stations in metres
 * from the route's start, the gore at 6,000 m): the rule's row as the engine's lane choices make it for a car at a
 * station, then lit, outlined and worded from the plan. Rows are written as the lane_row line writes them.
 */
class CardPlanRowTest {

    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** The interim's row for the exit or split at [exitNode] with the car at [car], as the engine's lane choices make it. */
    private fun ruleAt(route: LaneRoute, exitNode: Int, car: Double): RunUpLanes {
        val nodes = route.nodes
        val exit = nodes[exitNode]
        val edge = RunUpRule.exitEdge(exit.maneuver?.modifier, exit.lanes)
        val stepFirst = (exitNode - 1 downTo 0).first { nodes[it].maneuver != null }
        val ahead = (stepFirst + 1 until exitNode).filter { nodes[it].isLanePoint && nodes[it].station >= car }
            .map { RunUpRule.LanePoint(it.toString(), nodes[it].station - car, nodes[it].lanes) }
        val branchAhead = (stepFirst + 1 until exitNode).any { b ->
            edge != null && !nodes[b].isLanePoint && nodes[b - 1].freeway && LaneEvidence.branchesOff(nodes[b], edge) && nodes[b].station >= car
        }
        return requireNotNull(RunUpRule.row(edge, ahead, exitNode.toString(), exit.lanes, branchAhead))
    }

    /** The row from [plan] for the car at [car] on the way to the exit at [exitNode]. */
    private fun rowAt(route: LaneRoute, plan: LanePlan, exitNode: Int, car: Double): RunUpLanes {
        val rule = ruleAt(route, exitNode, car)
        return CardPlanRow.of(rule, plan, plan.topology.points.first { it.node == rule.key.toInt() }, car)
    }

    private fun tokens(row: RunUpLanes) = row.lanes.lanes.map(LaneRowLog::token)

    private fun exitOf(route: LaneRoute) = route.nodes.indexOfFirst { it.station == GORE }

    // ------------------------------------------------------------------ IA

    @Test
    fun `IA lights the plan's lane at each point, shows the lanes still to come as outlines, and words the exit only once its lanes are known`() {
        val route = LaneScenes.ia()
        val plan = plan(route)
        val exit = exitOf(route)
        // IA1's row, 2,000 m before the gore: the lane added on the far side before IA1 is still to come; the words count
        // from the right and are the same with it or without it.
        val early = rowAt(route, plan, exit, GORE - 2_000.0)
        assertEquals("2", early.key)
        assertEquals(listOf("s~", "s", "s", "s*", "s"), tokens(early))
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), early.target)
        assertNull(early.odd)
        assertEquals(listOf("s", "s", "s", "s*", "s"), tokens(rowAt(route, plan, exit, GORE - 1_350.0)))
        // Past IA1, the exit's own row: the far-side lane added before IA2a and the exit-only lane are still to come, so the
        // option lane is lit alone and no words name it; the plan's amber set takes in the exit-only lane once it may be there.
        val exitRow = rowAt(route, plan, exit, GORE - 1_200.0)
        assertEquals("$exit", exitRow.key)
        assertEquals(listOf("s~", "s", "s", "s", "s+sr*>s", "sr~"), tokens(exitRow))
        assertNull(exitRow.target)
        assertEquals(RunUpRule.ODD_OPENS_AHEAD, exitRow.odd)
        assertEquals(0, exitRow.need)
        val possible = rowAt(route, plan, exit, GORE - 600.0)
        assertEquals(listOf("s", "s", "s", "s", "s+sr*>s", "sr*"), tokens(possible))
        assertNull(possible.target)
        assertEquals(RunUpRule.ODD_OPENS_AHEAD, possible.odd)
        // Known from 210 m before the gore: the two right lanes in words.
        val known = rowAt(route, plan, exit, GORE - 200.0)
        assertEquals(LaneTarget(LaneEdge.RIGHT, 2, 0), known.target)
        assertNull(known.odd)
        assertEquals(LaneEdge.RIGHT, known.edge)
    }

    @Test
    fun `where the rule lights another lane than the plan names, the row lights the plan's, without words`() {
        val route = LaneScenes.ia()
        val plan = plan(route)
        val exit = exitOf(route)
        val car = GORE - 120.0
        val rule = ruleAt(route, exit, car)
        val point = plan.topology.points.first { it.node == exit }
        // The rule lit the lane next to the option lane alone, with words of its own.
        val other = rule.copy(
            lanes = rule.lanes.copy(lanes = rule.lanes.lanes.mapIndexed { i, lane -> lane.copy(isRecommended = i == 3, activeIndication = if (i == 3) lane.indications.first() else null) }),
            target = LaneTarget(LaneEdge.RIGHT, 1, 2),
        )
        val row = CardPlanRow.of(other, plan, point, car)
        assertEquals(listOf(point.lanes.indexOf(requireNotNull(plan.segmentAt(point.station)?.named))), row.lanes.lanes.indices.filter { row.lanes.lanes[it].isRecommended })
        assertNull(row.target)
        assertEquals(RunUpRule.ODD_COMPOSED, row.odd)
    }

    // ------------------------------------------------------------------ S9, S17, S20

    @Test
    fun `a lane the plan names that opens on the way is lit, never an outline, and its words stand`() {
        // S9 at 300 m: exit A's gore 300 m before exit B, whose lane is entered along its taper from the right lane.
        val route = LaneScenes.s9()
        val plan = plan(route)
        val exit = exitOf(route)
        val laneB = plan.topology.lanes.single { it.id in plan.named && it.addedAt != null && it.fedBy != null }
        val car = laneB.opens - 50.0
        assertTrue(!laneB.exists(car))
        val row = rowAt(route, plan, exit, car)
        assertEquals(listOf("s", "s", "s", "s", "sr*"), tokens(row))
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), row.target)
        // A's own row before it: the lane next to the right one, in words.
        val atA = rowAt(route, plan, exit, GORE - 1_000.0)
        assertEquals(listOf("s", "s", "s", "s*", "sr"), tokens(atA))
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), atA.target)
    }

    @Test
    fun `no words while a road without lane data still leaves on the exit's side, and they come once it is passed`() {
        val route = LaneScenes.s17()
        val plan = plan(route)
        val exit = exitOf(route)
        val road = plan.unknownExits.single().station
        val before = rowAt(route, plan, exit, road - 1.0)
        assertEquals(listOf("s", "s", "s", "s", "sr*"), tokens(before))
        assertNull(before.target)
        assertEquals(RunUpRule.ODD_UNKNOWN_AHEAD, before.odd)
        assertEquals(RunUpRule.ODD_UNKNOWN_AHEAD, rowAt(route, plan, exit, road).odd, "at the road itself")
        val past = rowAt(route, plan, exit, road + 1.0)
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), past.target)
        assertNull(past.odd)
        // The rule's own reading of the road gives way to the plan's in the row's odd.
        assertEquals(RunUpRule.ODD_UNKNOWN_BRANCH, ruleAt(route, exit, road - 1.0).odd)
    }

    @Test
    fun `counts the rule leaves in doubt keep the row without words`() {
        // S20: a lane added between two points where the right lane leaves: the first row's counts leave doubt.
        val route = LaneScenes.s20()
        val plan = plan(route)
        val row = rowAt(route, plan, exitOf(route), GORE - 2_000.0)
        assertEquals(listOf("s", "s*", "s", "sr"), tokens(row))
        assertNull(row.target)
        assertEquals(RunUpRule.ODD_COMPOSED, row.odd)
    }

    // ------------------------------------------------------------------ where the plan names no lane

    @Test
    fun `a run-up begun before the plan names a lane lights the rule's lane without words`() {
        // S13's split 3,000 m before the exit takes the two right lanes: a point where the right lane leaves, as a run-up
        // begun there at a speed above the route's planning speed would show it, outside the plan's card window.
        val route = LaneScenes.s13()
        val plan = plan(route)
        val exit = exitOf(route)
        val car = GORE - 3_400.0
        val rule = ruleAt(route, exit, car)
        assertEquals("1", rule.key)
        val split = plan.topology.points.first { it.node == 1 }
        assertNull(plan.segmentAt(split.station)?.named)
        val row = CardPlanRow.of(rule, plan, split, car)
        assertEquals(rule.lanes, row.lanes)
        assertNull(row.target)
        assertEquals(RunUpRule.ODD_NOT_NAMED, row.odd)
        // The plan names a lane at the point but none at the car yet: the plan's lane, no words.
        val ia = LaneScenes.ia()
        val iaPlan = plan(ia)
        val window = iaPlan.windows.first { it.kind == CardWindow.Kind.RUN_UP }
        val early = rowAt(ia, iaPlan, exitOf(ia), window.from - 120.0)
        assertEquals(listOf("s~", "s", "s", "s*", "s"), tokens(early))
        assertNull(early.target)
        assertEquals(RunUpRule.ODD_NOT_NAMED, early.odd)
    }

    @Test
    fun `where no lane can be named the rule's lanes stay lit without words`() {
        // A left exit beyond the car-pool lane: the plan names none on its approach, so the words name none.
        val beyond = LaneScenes.carpoolBeyond()
        val plan = plan(beyond)
        val exit = exitOf(beyond)
        val rule = ruleAt(beyond, exit, GORE - 600.0)
        assertTrue(rule.target != null, "the interim's row has words")
        val row = CardPlanRow.of(rule, plan, plan.topology.points.first { it.node == exit }, GORE - 600.0)
        assertEquals(rule.lanes, row.lanes)
        assertNull(row.target)
        assertEquals(RunUpRule.ODD_NOT_NAMED, row.odd)
        // Only the car-pool lane can be used at the exit: the rule's row as it is, dim with its arrow lit.
        val only = LaneScenes.geomS5()
        val onlyPlan = plan(only)
        val onlyRule = ruleAt(only, exitOf(only), GORE - 600.0)
        assertSame(onlyRule, CardPlanRow.of(onlyRule, onlyPlan, onlyPlan.topology.points.first { it.node == exitOf(only) }, GORE - 600.0))
        assertEquals(RunUpRule.ODD_DESIGNATED_ONLY, onlyRule.odd)
    }

    // ------------------------------------------------------------------ the holder

    @Test
    fun `while the route's plan is on its way the rule's row has no words, and without a plan it stands`() {
        val route = LaneScenes.s9()
        val rule = ruleAt(route, exitOf(route), GORE - 1_000.0)
        val waiting = requireNotNull(CardPlanRow().row(rule, plan = null, point = null, car = Double.NaN, atCar = null, planComing = true))
        assertEquals(rule.lanes, waiting.lanes)
        assertNull(waiting.target)
        assertEquals(RunUpRule.ODD_NOT_NAMED, waiting.odd)
        // A plan that could not be made costs only the plan's reading: the rule's row, its words included.
        assertSame(rule, CardPlanRow().row(rule, plan = null, point = null, car = Double.NaN, atCar = null, planComing = false))
        // A plan whose route has no such point: no words.
        assertNull(CardPlanRow().row(rule, plan(route), point = null, car = GORE - 1_000.0, atCar = null)?.target)
        assertNull(CardPlanRow().row(null, plan(route), point = null, car = GORE - 1_000.0, atCar = null))
    }

    @Test
    fun `the row is the same object while it does not change, and a new drive starts over`() {
        val route = LaneScenes.ia()
        val plan = plan(route)
        val exit = exitOf(route)
        val rows = CardPlanRow()
        fun at(car: Double): RunUpLanes? {
            val rule = ruleAt(route, exit, car)
            return rows.row(rule, plan, plan.topology.points.first { it.node == rule.key.toInt() }, car, plan.segmentAt(car))
        }
        val first = at(GORE - 1_250.0)
        assertSame(first, at(GORE - 1_240.0))
        assertSame(first, at(GORE - 1_000.0))
        // The far-side lane comes: another row.
        val next = at(GORE - 700.0)
        assertNotSame(first, next)
        assertSame(next, at(GORE - 690.0))
        rows.reset()
        val again = at(GORE - 690.0)
        assertEquals(next, again)
        assertNotSame(next, again)
    }

    @Test
    fun `a row whose lanes are not its point's is the rule's without words, and nothing throws`() {
        val route = LaneScenes.ia()
        val plan = plan(route)
        val rule = ruleAt(route, exitOf(route), GORE - 1_000.0)
        val wrongPoint = plan.topology.points.first { it.node == 1 }
        val row = CardPlanRow.of(rule.copy(target = LaneTarget(LaneEdge.RIGHT, 2, 0)), plan, wrongPoint, GORE - 1_000.0)
        assertEquals(rule.lanes, row.lanes)
        assertNull(row.target)
        assertEquals(RunUpRule.ODD_NOT_NAMED, row.odd)
        // A station that is no number: no lane is named there, no words, no throw.
        assertNull(CardPlanRow.of(rule, plan, plan.topology.points.first { it.node == exitOf(route) }, Double.NaN).target)
    }
}
