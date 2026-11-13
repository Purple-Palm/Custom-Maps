package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The card's lane row, as the interim card makes it ([CardDrive]), against the lane plan, on made-up routes
 * only ([LaneScenes]: stations in metres from the route's start, the gore at 6,000 m). The expected rows were
 * worked out with a copy of FINAL's prototype (`f_plan.check_interim` with `f_plan_v2.name_lanes_v2`) on the
 * same invented scenes: at every point the card shows, the plan names the card's lane, with no move back and
 * no lane named before the run-up; offsets count the lanes open to every car from the exit's edge.
 */
class CardPlanAgreementTest {

    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** A row the card shows: where it comes from, its point in metres before the gore, the lanes it lights and the plan's, by offsets from the exit's edge. */
    private data class Expected(val source: CardLanes.Source, val before: Double, val lit: List<Int>, val planLit: Int?, val amber: List<Int>)

    private val maneuver = CardLanes.Source.MANEUVER
    private val runUp = CardLanes.Source.RUN_UP
    private val choice = CardLanes.Source.CHOICE

    private fun row(source: CardLanes.Source, before: Double, lit: List<Int>, planLit: Int?, amber: List<Int> = lit) = Expected(source, before, lit, planLit, amber)

    /**
     * The freeway scenes of the design's agreement check (IA and its far side in place of the brief-shaped ones,
     * S5 to S9, S13 to S17 and S20 with S9, S14 and S17 at 300 m, IW31; S14 is S9 for the card and the plan), and a
     * keep left given as a continue: each row the card shows in order, the exit's own row (the navigation SDK's)
     * first, as before the run-up.
     */
    private val scenes = linkedMapOf(
        "ia" to listOf(row(maneuver, 0.0, listOf(0, 1), 1), row(runUp, 1_330.0, listOf(1), 1, listOf(1, 2, 3, 4)), row(runUp, 0.0, listOf(0, 1), 1)),
        "ia-far" to listOf(row(maneuver, 0.0, listOf(0, 1), 1), row(runUp, 2_180.0, listOf(1), 1, listOf(1, 2, 3, 4)), row(runUp, 0.0, listOf(0, 1), 1)),
        "iw31" to listOf(row(maneuver, 0.0, listOf(0, 1), 1), row(runUp, 0.0, listOf(1), 1, listOf(0, 1)), row(maneuver, -260.0, listOf(0), 0)),
        "s5" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 0.0, listOf(0), 0)),
        "s6" to listOf(row(maneuver, 0.0, listOf(0, 1), 0), row(runUp, 0.0, listOf(0, 1), 0)),
        "s7" to listOf(row(maneuver, 0.0, listOf(0, 1, 2), 0), row(runUp, 0.0, listOf(0, 1, 2), 0)),
        "s8" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 500.0, listOf(1), 1), row(runUp, 0.0, listOf(0), 0)),
        "s9" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 300.0, listOf(1), 1), row(runUp, 0.0, listOf(0), 0)),
        "s13" to listOf(
            row(maneuver, 0.0, listOf(0, 1), 0),
            row(choice, 3_000.0, listOf(2, 3, 4), null),
            row(maneuver, 0.0, listOf(0, 1), 0),
            row(runUp, 0.0, listOf(0, 1), 0),
        ),
        "s15" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 1_400.0, listOf(3), 3, listOf(1, 2, 3, 4)), row(runUp, 800.0, listOf(2), 2), row(runUp, 0.0, listOf(0), 0)),
        "s16" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 1_200.0, listOf(2), 2, listOf(2, 3, 4)), row(runUp, 0.0, listOf(0), 0)),
        "s17" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 0.0, listOf(0), 0), row(runUp, 0.0, listOf(0), 0)),
        "s20" to listOf(row(maneuver, 0.0, listOf(0), 0), row(runUp, 1_600.0, listOf(2), 2, listOf(1, 2, 3)), row(runUp, 700.0, listOf(1), 1), row(runUp, 0.0, listOf(0), 0)),
        // Counted from the left, the split's side.
        "keep-left" to listOf(row(maneuver, 0.0, listOf(0, 1), 0), row(runUp, 1_200.0, listOf(1), 1, listOf(1, 2, 3, 4)), row(runUp, 0.0, listOf(0, 1), 0)),
    )

    /** The plan's comparison of every row [route]'s card shows, with the row. */
    private fun compared(route: LaneRoute, plan: LanePlan = plan(route)): List<Pair<CardDrive.Shown, CardPlanAgreement.Result>> =
        CardDrive(route).drive().map { shown ->
            val point = requireNotNull(plan.topology.points.firstOrNull { it.node == shown.node }) { "no lane point at ${shown.node}" }
            shown to CardPlanAgreement.of(shown.row, plan, point, shown.station)
        }

    private fun edgeOf(plan: LanePlan, node: Int): LaneEdge =
        requireNotNull(plan.segmentAt(plan.topology.evidence.route.nodes[node].station)?.edge)

    @Test
    fun `at every lane point the card shows, the plan lights the card's lane or its lit set`() {
        for ((name, want) in scenes) {
            val route = requireNotNull(LaneScenes.all()[name])
            val plan = plan(route)
            val rows = compared(route, plan)
            assertEquals(want.size, rows.size, "$name: ${rows.map { it.first.row.source }}")
            for ((k, pair) in rows.withIndex()) {
                val (shown, result) = pair
                val expected = want[k]
                val node = route.nodes[shown.node]
                val edge = edgeOf(plan, shown.node)
                val lanes = shown.row.lanes
                assertEquals(expected.source, shown.row.source, "$name row $k")
                assertEquals(expected.before, GORE - node.station, 1e-6, "$name row $k: its point")
                assertEquals(expected.lit, CardDrive.offsets(lanes, result.lit, edge), "$name row $k: the card's lit lanes")
                assertEquals(expected.planLit, result.planLit?.let { CardDrive.offsets(lanes, listOf(it), edge).single() }, "$name row $k: the plan's lane")
                assertEquals(expected.amber, CardDrive.offsets(lanes, result.amber, edge), "$name row $k: the plan's amber set")
                assertTrue(result.why == null || result.why == CardPlanAgreement.Why.WORDS, "$name row $k: ${result.why}")
            }
        }
    }

    @Test
    fun `no lane is named before the run-up, never moves back, and the plan's run-up begins no later than the card's`() {
        for (name in scenes.keys) {
            val route = requireNotNull(LaneScenes.all()[name])
            val plan = plan(route)
            assertTrue(plan.moves.none { it.back }, "$name: ${plan.moves}")
            val runUp = plan.windows.first { it.kind == CardWindow.Kind.RUN_UP }
            val first = requireNotNull(plan.segments.firstOrNull { it.named != null }) { name }
            assertTrue(first.from >= runUp.from - 1e-9, "$name: first named at ${first.from}, the run-up from ${runUp.from}")
            for (segment in plan.segments) {
                if (segment.named == null) continue
                assertTrue(plan.windows.any { segment.from >= it.from - 1e-9 && segment.from <= it.to + 1e-9 }, "$name: named outside a window at ${segment.from}")
            }
            val cardRunUp = CardDrive(route).drive().first { it.row.source == CardLanes.Source.RUN_UP }.station
            assertTrue(runUp.from <= cardRunUp + 1e-9, "$name: the plan's run-up ${runUp.from}, the card's $cardRunUp")
        }
    }

    @Test
    fun `the words agree except where a lane the plan does not name opens ahead or a lane drawn at the car is gone by the point`() {
        for ((name, _) in scenes) {
            val route = requireNotNull(LaneScenes.all()[name])
            for ((shown, result) in compared(route)) {
                val waits = shown.row.source == CardLanes.Source.RUN_UP && shown.row.words && result.opensAhead
                if (waits) {
                    assertEquals(CardPlanAgreement.Why.WORDS, result.why, name)
                    // S16: past the point where the left lane leaves, the lane then at the left edge becomes a car-pool lane
                    // 50 m on, so the interim's "Left lane" names another lane there than at the exit.
                    assertTrue(name in setOf("ia", "ia-far", "iw31", "s16"), "$name: words waiting at ${shown.station}")
                } else {
                    assertTrue(result.agree, "$name at ${shown.station}: ${result.why}")
                }
            }
        }
    }

    @Test
    fun `a keep left given as a continue agrees with the card as a fork does`() {
        for (type in listOf(ManeuverType.CONTINUE, ManeuverType.FORK)) {
            val route = LaneScenes.keepLeft(type)
            val rows = compared(route)
            assertEquals(3, rows.size, "$type")
            for ((shown, result) in rows) {
                assertTrue(result.agree, "$type at ${shown.station}: ${result.why}")
                assertEquals(LaneEdge.LEFT, shown.target?.edge ?: LaneEdge.LEFT, "$type: the card's words count from the left")
            }
        }
    }

    // ------------------------------------------------------------------ the words (3)

    /** IW31's exit row as the card shows it: the option lane lit, "2nd from right". */
    private fun iw31() = LaneScenes.iw31().let { route ->
        val plan = plan(route)
        val shown = CardDrive(route).drive().first { it.row.source == CardLanes.Source.RUN_UP }
        Triple(plan, shown, requireNotNull(plan.topology.points.firstOrNull { it.node == shown.node }))
    }

    @Test
    fun `on the W31 scene the words wait until the exit-only lane is known`() {
        val (plan, shown, gore) = iw31()
        assertTrue(shown.row.words)
        val a5 = plan.topology.lanes.single { it.possibleFrom != null && it.id !in plan.named }
        // Known (open) where its taper ends late at P2, 620 m before the gore.
        for (before in listOf(1_346.0, 1_000.0, 700.0)) {
            val result = CardPlanAgreement.of(shown.row, plan, gore, GORE - before)
            assertTrue(result.opensAhead, "at $before m: the exit-only lane opens ahead")
            assertFalse(result.agree)
            assertEquals(CardPlanAgreement.Why.WORDS, result.why)
        }
        val known = CardPlanAgreement.of(shown.row, plan, gore, a5.opens + 1.0)
        assertFalse(known.opensAhead, "from its known start")
        assertTrue(known.agree)
        assertTrue(CardPlanAgreement.of(shown.row, plan, gore, a5.opens - 1.0).opensAhead)
    }

    @Test
    fun `the words wait for a road without lane data until it is passed, by the road's own station`() {
        val route = LaneScenes.s17()
        val plan = plan(route)
        val road = plan.unknownExits.single().station
        val exitRow = CardDrive(route).drive().last()
        val gore = requireNotNull(plan.topology.points.firstOrNull { it.node == exitRow.node })
        assertTrue(exitRow.row.words, "the card shows words once the road is passed")
        val before = CardPlanAgreement.of(exitRow.row, plan, gore, road - 1.0)
        assertTrue(before.unknownAhead)
        assertFalse(before.opensAhead, "nothing else holds the words back")
        assertFalse(before.agree, "words with the road still ahead")
        assertEquals(CardPlanAgreement.Why.WORDS, before.why)
        assertTrue(CardPlanAgreement.of(exitRow.row, plan, gore, road).unknownAhead, "at the road itself")
        val past = CardPlanAgreement.of(exitRow.row, plan, gore, road + 1.0)
        assertFalse(past.unknownAhead, "1 m past it, though the plan's 5 m stretch there still says ahead")
        assertTrue(plan.segmentAt(road + 1.0)!!.unknownExitAhead)
        assertTrue(past.agree)
    }

    @Test
    fun `the lane that left at the point just passed does not change the count, a planned move does`() {
        val route = LaneScenes.s9()
        val plan = plan(route)
        val exitRow = CardDrive(route).drive().last()
        val gore = requireNotNull(plan.topology.points.firstOrNull { it.node == exitRow.node })
        // A's right lane leaves 300 m before the gore and is there 5 m more; the plan moves along the exit lane's taper.
        val justPast = CardPlanAgreement.of(exitRow.row, plan, gore, GORE - 299.0)
        assertFalse(justPast.opensAhead)
        assertTrue(justPast.agree)
        // In S17 the plan holds the lane next to the edge until 320 m before the gore: its count changes ahead.
        val s17 = LaneScenes.s17()
        val held = plan(s17)
        val row = CardDrive(s17).drive().last()
        val point = requireNotNull(held.topology.points.firstOrNull { it.node == row.node })
        assertTrue(CardPlanAgreement.of(row.row, held, point, GORE - 400.0).opensAhead)
        assertFalse(CardPlanAgreement.of(row.row, held, point, GORE - 310.0).opensAhead)
    }

    @Test
    fun `words the card's own reading leaves in doubt are none, wherever the plan agrees`() {
        val (plan, shown, gore) = iw31()
        val inexact = shown.row.copy(exact = false)
        val result = CardPlanAgreement.of(inexact, plan, gore, GORE - 300.0)
        assertFalse(result.exact)
        assertEquals(CardPlanAgreement.Why.WORDS, result.why)
        assertTrue(CardPlanAgreement.of(inexact.copy(words = false), plan, gore, GORE - 300.0).agree, "no words, nothing to doubt")
    }

    // ------------------------------------------------------------------ outline lanes (4)

    @Test
    fun `a lane of the row's point the map does not draw at the car yet is an outline`() {
        val (plan, shown, gore) = iw31()
        val a5 = plan.topology.lanes.single { it.possibleFrom != null && it.id !in plan.named }
        val index = gore.lanes.indexOf(a5.id)
        assertEquals(listOf(index), CardPlanAgreement.of(shown.row, plan, gore, requireNotNull(a5.possibleFrom) - 1.0).outline, "before the previous lane point")
        assertTrue(CardPlanAgreement.of(shown.row, plan, gore, requireNotNull(a5.possibleFrom) + 1.0).outline.isEmpty(), "possible from there")
        assertTrue(CardPlanAgreement.of(shown.row, plan, gore, GORE - 100.0).outline.isEmpty())
    }

    // ------------------------------------------------------------------ the card's row from the plan

    /**
     * The freeway scenes of the design's scenario line as main has them, through the card's row from the plan: IA and its
     * far side, IW31, the rig's S5 to S9, S11 (IA's exit with a turn 400 m past it; S12 is the same for the card), S13,
     * S15 to S17 and S20 (S14 is S9 for the card), GEOM's S2, the car-pool case that has a run-up, the hold cases, a keep
     * left, and lanes gone before an edge point, or that come and go before it or before an exit lane added at the gore.
     * GEOM's S6 has no run-up (its exit is no lane choice), and the cases where no lane can be named are below.
     */
    private val freeway = linkedMapOf(
        "s11" to LaneScenes.approach(emptyList(), LaneScenes.iaExit(), id = "s11"),
        "lane-ends-before-edge" to LaneScenes.laneEndsBeforeEdge(),
        "short-lane-before-edge" to LaneScenes.shortLaneBeforeEdge(),
        "short-lane-before-exit-lane" to LaneScenes.shortLaneBeforeExitLane(),
    ).apply {
        val all = LaneScenes.all()
        for (name in listOf(
            "ia", "ia-far", "iw31", "s5", "s6", "s7", "s8", "s9", "s13", "s15", "s16", "s17", "s20", "geom-s2-400", "geom-s2-150",
            "carpool-ends", "prefers-other", "prefers-middle", "prefers-left", "split-right", "two-added",
            "edge-before-road", "dropped-hold-stays", "keep-left",
        )) {
            put(name, requireNotNull(all[name]) { name })
        }
    }

    @Test
    fun `the card's row from the plan agrees with the plan at every update, words and outlines included`() {
        for ((name, route) in freeway) {
            val plan = plan(route)
            var runUps = 0
            for (shown in CardDrive(route, plan = plan).drive(everyUpdate = true)) {
                if (shown.row.source != CardLanes.Source.RUN_UP) continue
                runUps++
                val point = requireNotNull(plan.topology.points.firstOrNull { it.node == shown.node }) { "$name: no lane point at ${shown.node}" }
                val result = CardPlanAgreement.of(shown.row, plan, point, shown.station)
                val at = "$name at ${GORE - shown.station} m before the gore"
                assertTrue(result.agree, "$at: ${result.why}")
                // The lanes the map does not draw yet are the row's outlines, never lit.
                assertEquals(result.outline, shown.row.lanes.indices.filter { shown.row.lanes[it].outline }, at)
                assertTrue(shown.row.lanes.none { it.outline && it.isRecommended }, at)
                // Words only where nothing opens ahead and no road without lane data is left before the exit.
                if (shown.target != null) {
                    assertFalse(result.opensAhead, at)
                    assertFalse(result.unknownAhead, at)
                    assertTrue(result.exact, at)
                }
            }
            assertTrue(runUps > 0, "$name: no run-up")
        }
    }

    @Test
    fun `where the card's run-up row has words, the plan counts its lane the same from the car all the way to the row's point`() {
        val grid = LanePlanner.GRID_METERS
        val last = LaneTopology.LEAVE_PAST_POINT_METERS
        val worded = HashMap<String, Int>()
        for ((name, route) in freeway) {
            val plan = plan(route)
            for (shown in CardDrive(route, plan = plan).drive(everyUpdate = true)) {
                if (shown.row.source != CardLanes.Source.RUN_UP || shown.target == null) continue
                worded[name] = (worded[name] ?: 0) + 1
                val point = plan.topology.points.first { it.node == shown.node }
                val atPoint = requireNotNull(plan.segmentAt(point.station))
                val car = shown.station
                // The plan counts on its 5 m grid: for a few metres a lane in its last ones, as the one that left at the point
                // just passed, is still in the count of the stretch at the car, and one that opens or may begin not yet.
                val lanes = plan.topology.lanes.filter { it.run == atPoint.run }
                val settling = lanes.any { lane ->
                    (lane.closes > car - grid && lane.closes <= car + last) || (lane.opens > car - grid && lane.opens <= car) ||
                        lane.possibleFrom.let { it != null && it > car - grid && it <= car }
                }
                for (segment in plan.segments) {
                    if (segment.run != atPoint.run || segment.to <= car || segment.from > point.station) continue
                    if (segment.from <= car && settling) continue
                    assertEquals(
                        atPoint.namedFromEdge,
                        segment.namedFromEdge,
                        "$name at ${GORE - car} m before the gore: the stretch from ${GORE - segment.from} m",
                    )
                }
            }
        }
        // Words come on each of these, once nothing differs any more.
        for (name in listOf("ia", "iw31", "s16", "lane-ends-before-edge", "short-lane-before-edge", "short-lane-before-exit-lane")) {
            assertTrue((worded[name] ?: 0) > 0, name)
        }
    }

    @Test
    fun `the card's run-up comes in at the plan's seam, before the latch would on its own where the plan narrows lanes earlier`() {
        val earlier = HashMap<String, Double>()
        for (name in listOf("ia", "ia-far", "iw31", "s5", "s6", "s7", "s8", "s9", "s11", "s13", "s15", "s16", "s17", "s20")) {
            val route = requireNotNull(freeway[name])
            val plan = plan(route)
            val seam = plan.seams.single()
            val withPlan = CardDrive(route, plan = plan).drive().first { it.row.source == CardLanes.Source.RUN_UP }
            val alone = CardDrive(route).drive().first { it.row.source == CardLanes.Source.RUN_UP }
            assertEquals(seam.from, withPlan.station, 1.0, "$name with its plan")
            assertEquals(seam.latchFrom, alone.station, 1.0, "$name, the latch on its own")
            earlier[name] = seam.earlierMeters
        }
        // IW31's first narrowing, where its leftmost lane cannot be used, lies far before the option lane's window.
        assertTrue(earlier.getValue("iw31") > 1_000.0)
        // An edge point within the reach begins the run-up there on its own: the seam moves nothing.
        for (name in listOf("s8", "s9", "s15", "s16", "s20")) assertEquals(0.0, earlier.getValue(name), name)
    }

    @Test
    fun `where no lane can be named the card's row from the plan has no words`() {
        // A left exit beyond the car-pool lane (where only the car-pool lane can be used, as in GEOM's S5, the exit is no
        // lane choice and there is no run-up).
        for (route in listOf(LaneScenes.carpoolBeyond())) {
            val plan = plan(route)
            val rows = CardDrive(route, plan = plan).drive(everyUpdate = true).filter { it.row.source == CardLanes.Source.RUN_UP }
            assertTrue(rows.isNotEmpty(), route.routeId)
            for (shown in rows) {
                assertNull(shown.target, route.routeId)
                assertEquals(CardPlanAgreement.Why.NO_LANE, CardPlanAgreement.of(shown.row, plan, plan.topology.points.first { it.node == shown.node }, shown.station).why)
            }
        }
    }

    @Test
    fun `on the W31 scene the card lights the option lane, the exit-only lane an outline until it may be there, words only once it is`() {
        val route = LaneScenes.iw31()
        val plan = plan(route)
        val exitNode = route.nodes.indexOfFirst { it.station == GORE }
        val gore = plan.topology.points.first { it.node == exitNode }
        val a5 = plan.topology.lanes.single { it.possibleFrom != null && it.id !in plan.named }
        val option = gore.lanes.indexOf(requireNotNull(plan.segmentAt(gore.station)?.named))
        val toCome = gore.lanes.indexOf(a5.id)
        val window = plan.windows.first { it.kind == CardWindow.Kind.RUN_UP }
        // The exit's own row, as the rule makes it all through the run-up: no point on the way is one where the right lane leaves.
        val rule = requireNotNull(
            RunUpRule.row(LaneEdge.RIGHT, emptyList(), exitNode.toString(), route.nodes[exitNode].lanes, branchAhead = false),
        )
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), rule.target)
        fun at(car: Double) = CardPlanRow.of(rule, plan, gore, car)
        fun offset(row: RunUpLanes) = CardDrive.offsets(row.lanes.lanes.filter { !it.outline }, listOf(row.lanes.lanes.filter { !it.outline }.indexOfFirst { it.isRecommended }), LaneEdge.RIGHT).single()
        val possibleFrom = requireNotNull(a5.possibleFrom)
        // The plan names the option lane from its first stretch in the run-up's window.
        val named = plan.segments.first { it.inWindow && it.named != null && it.from >= window.from - 1e-9 }.from
        for (car in listOf(named + 1.0, possibleFrom - 1.0)) {
            // From the run-up's start to the lane point before the one that first lists it: the option lane is the rightmost
            // lane drawn, the exit-only lane an outline beside it; no words.
            val row = at(car)
            assertTrue(row.lanes.lanes[toCome].outline, "$car")
            assertEquals(listOf(option), row.lanes.lanes.indices.filter { row.lanes.lanes[it].isRecommended })
            assertEquals(0, offset(row), "the rightmost at $car")
            assertNull(row.target)
            assertEquals(RunUpRule.ODD_OPENS_AHEAD, row.odd)
            val result = CardPlanAgreement.of(CardPlanAgreement.Row(CardLanes.Source.RUN_UP, row.lanes.lanes, words = false), plan, gore, car)
            assertTrue(result.agree)
            assertEquals(listOf(toCome), result.outline)
            assertTrue(result.opensAhead)
        }
        for (car in listOf(possibleFrom, a5.opens - 1.0)) {
            // From where it may be there: a lane of the row, the option lane the rightmost but one, still no words.
            val row = at(car)
            assertFalse(row.lanes.lanes[toCome].outline, "$car")
            assertEquals(1, offset(row), "the rightmost but one at $car")
            assertNull(row.target)
            assertEquals(RunUpRule.ODD_OPENS_AHEAD, row.odd)
        }
        for (car in listOf(a5.opens, GORE - 1.0)) {
            // From its known start: "2nd from right".
            val row = at(car)
            assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 1), row.target, "$car")
            assertNull(row.odd)
            assertTrue(CardPlanAgreement.of(CardPlanAgreement.Row(CardLanes.Source.RUN_UP, row.lanes.lanes, words = true), plan, gore, car).agree)
        }
        // Past the gore the close view holds through the ramp to its split, and the card shows the split's row from the
        // plan: the left lane, in words.
        val split = CardDrive(route, plan = plan).drive().last { it.row.source == CardLanes.Source.RUN_UP }
        assertEquals(route.nodes.indexOfFirst { it.station > GORE && it.maneuver?.type == ManeuverType.FORK }, split.node)
        assertEquals(LaneTarget(LaneEdge.LEFT, 1, 0), split.target)
    }

    // ------------------------------------------------------------------ the other outcomes

    @Test
    fun `where no lane can be named the plan names none and tints none, and says why`() {
        for (route in listOf(LaneScenes.geomS5(), LaneScenes.carpoolBeyond())) {
            val plan = plan(route)
            for ((_, result) in compared(route, plan)) {
                assertFalse(result.agree)
                assertEquals(CardPlanAgreement.Why.NO_LANE, result.why, route.routeId)
                assertNull(result.planLit)
                assertTrue(result.amber.isEmpty())
            }
        }
    }

    @Test
    fun `outside its windows the plan's lanes that work stand for its lit lanes`() {
        // S13's split 3,000 m before the exit, a lane choice the card shows before the run-up: the plan names none there.
        val route = LaneScenes.s13()
        val (shown, result) = compared(route).single { it.first.row.source == CardLanes.Source.CHOICE }
        assertNull(result.planLit)
        assertEquals(result.lit, result.amber)
        assertTrue(result.agree)
        // One lit lane where the plan names none and more lanes work: they differ.
        val one = shown.row.copy(lanes = shown.row.lanes.mapIndexed { i, lane -> lane.copy(isRecommended = i == result.lit.first(), activeIndication = if (i == result.lit.first()) TurnModifier.STRAIGHT else null) })
        val point = plan(route).topology.points.first { it.node == shown.node }
        assertEquals(CardPlanAgreement.Why.NOT_NAMED, CardPlanAgreement.of(one, plan(route), point, shown.station).why)
    }

    @Test
    fun `the navigation SDK's own row lighting a lane the plan drops before a near split differs`() {
        // GEOM's S2 with the split 150 m after the gore: only the option lane leads to the route's branch.
        val route = LaneScenes.geomS2(150.0)
        val (_, result) = compared(route).first()
        assertEquals(CardPlanAgreement.Why.AMBER, result.why)
        assertEquals(2, result.lit.size)
        assertEquals(1, result.amber.size)
    }

    @Test
    fun `one lit lane other than the plan's is a lit lane disagreement`() {
        val (plan, shown, gore) = iw31()
        val other = shown.row.copy(
            lanes = shown.row.lanes.mapIndexed { i, lane ->
                val on = i == shown.row.lanes.size - 1
                Lane(lane.indications, isRecommended = on, activeIndication = if (on) lane.indications.first() else null, designated = lane.designated)
            },
        )
        val result = CardPlanAgreement.of(other, plan, gore, GORE - 100.0)
        assertEquals(CardPlanAgreement.Why.LIT, result.why)
        assertFalse(result.agree)
    }

    @Test
    fun `a row of another count, or of no lane point, is said so and never throws`() {
        val (plan, shown, gore) = iw31()
        val short = shown.row.copy(lanes = shown.row.lanes.drop(1))
        assertEquals(CardPlanAgreement.Why.ROW, CardPlanAgreement.of(short, plan, gore, GORE - 100.0).why)
        val empty = CardPlanAgreement.of(shown.row.copy(lanes = emptyList()), plan, gore, Double.NaN)
        assertEquals(CardPlanAgreement.Why.ROW, empty.why)
        val none = CardPlanAgreement.noPoint(shown.row)
        assertEquals(CardPlanAgreement.Why.NO_POINT, none.why)
        assertEquals(listOf(3), none.lit)
        // A car past the point, or whose station is no number: nothing opens ahead of it.
        assertFalse(CardPlanAgreement.of(shown.row, plan, gore, GORE + 10.0).opensAhead)
        assertFalse(CardPlanAgreement.of(shown.row, plan, gore, Double.NaN).opensAhead)
    }

    @Test
    fun `the log names the reasons in short words`() {
        assertEquals(
            listOf("lit", "amber", "not_named", "no_lane", "row", "no_point", "words"),
            CardPlanAgreement.Why.entries.map { it.logName },
        )
    }
}
