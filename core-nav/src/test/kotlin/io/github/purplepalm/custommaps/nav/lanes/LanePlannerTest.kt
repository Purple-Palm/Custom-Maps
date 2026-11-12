package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.ARRIVE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.DEPART
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.FORK_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.OFF_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.TURN_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.approach
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.at
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.node
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.option
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.route
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Made-up routes only ([LaneScenes]): stations in metres from the route's start, the gore at 6,000 m,
 * lanes from the left, lane ids of the topology. The golden values were worked out with a copy of the
 * design's prototype planner (GEOM's planner with FINAL's naming, its unknown exits and its seam), run on
 * the same lane topologies with the design's freeway floor, with lanes named only inside card windows and
 * with the ramp held to a split; they agree to the 5 m station.
 */
class LanePlannerTest {

    /** The plan of [route], with the roads without lane data that leave on an exit's side read as the engine reads them. */
    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** The last station of the grid where [lane] is in the band. */
    private fun LanePlan.lastInBand(lane: Int): Double? = segments.lastOrNull { lane in it.band }?.let { it.to - LanePlanner.GRID_METERS }

    private fun LanePlan.at(station: Double): PlanSegment = requireNotNull(segmentAt(station)) { "no plan at $station" }

    /** Where the plan first names a lane, and which. */
    private fun LanePlan.firstNamed(): Pair<Double, Int>? = segments.firstOrNull { it.named != null }?.let { it.from to requireNotNull(it.named) }

    // ------------------------------------------------------------------ design 5.1: the distances of lane changes

    @Test
    fun `lane changes take the distances of design 5_1's table`() {
        // mph to [latest, comfortable] for one to four changes, in whole metres.
        val table = mapOf(
            45.0 to listOf(203 to 237, 356 to 424, 509 to 612, 662 to 799),
            55.0 to listOf(248 to 290, 435 to 519, 622 to 747, 809 to 976),
            65.0 to listOf(293 to 343, 514 to 613, 735 to 883, 956 to 1_154),
            75.0 to listOf(339 to 396, 593 to 707, 848 to 1_019, 1_103 to 1_331),
        )
        for ((mph, row) in table) {
            val v = mph * PlanningSpeed.MPS_PER_MPH
            for ((k, pair) in row.withIndex()) {
                assertEquals(pair.first.toLong(), Math.round(LanePlanner.changeMeters(k + 1, v, comfortable = false)), "$mph mph, ${k + 1} latest")
                assertEquals(pair.second.toLong(), Math.round(LanePlanner.changeMeters(k + 1, v, comfortable = true)), "$mph mph, ${k + 1} comfortable")
            }
        }
    }

    /**
     * Two-lane points [gap] metres apart on a freeway whose next maneuver has no side: the first lets only
     * the left lane be used, the second only the right one. Planned at 20 m/s.
     */
    private fun pair(gap: Double) = route(
        node(0.0, maneuver = DEPART, planMps = 20.0),
        node(3_000.0, s(), s(false), planMps = 20.0),
        node(3_000.0 + gap, s(false), s(), planMps = 20.0),
        node(5_000.0, maneuver = ARRIVE, planMps = 20.0),
    )

    @Test
    fun `where a comfortable change no longer fits, the band is tight, and where not even the latest fits, a conflict`() {
        // A comfortable change at 20 m/s takes 186 m, the latest 152 m.
        assertTrue(plan(pair(190.0)).flags.isEmpty(), "190 m: a comfortable change fits")
        assertEquals(setOf(LanePlan.Flag.Kind.TIGHT), plan(pair(185.0)).flags.map { it.kind }.toSet(), "185 m: only the latest")
        assertEquals(setOf(LanePlan.Flag.Kind.TIGHT), plan(pair(155.0)).flags.map { it.kind }.toSet())
        val conflict = plan(pair(150.0))
        assertTrue(LanePlan.Flag.Kind.CONFLICT in conflict.flags.map { it.kind }, "150 m: not even the latest")
        assertTrue(conflict.flags.first { it.kind == LanePlan.Flag.Kind.CONFLICT }.deficitMeters > 0.0)
    }

    /** An exit whose two lanes go onto a ramp, which forks [forkAfter] m after the gore at 4,000 m; the route takes the right branch. */
    private fun rampFork() = route(
        node(0.0, maneuver = DEPART, planMps = 27.0),
        node(3_000.0, sr(), sr(), road = RoadKind.RAMP, maneuver = OFF_RIGHT, planMps = 20.0),
        node(4_000.0, s(false), sr(), road = RoadKind.RAMP, maneuver = FORK_RIGHT, planMps = 20.0),
        node(4_500.0, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0),
        node(4_600.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0),
    )

    @Test
    fun `a lane leaves the band a comfortable change and the reaction time before the point that needs it`() {
        // 20 m/s on the ramp: 186 m for the change and 50 m of reaction before the fork at 4,000 m.
        val plan = plan(rampFork())
        assertEquals(3_760.0, plan.lastInBand(0))
        assertTrue(0 in plan.at(3_760.0).band)
        assertFalse(0 in plan.at(3_765.0).band)
    }

    /** A freeway exit at 4,000 m whose two lanes are lit at a point 2,000 m before, the exit taking only the right one. */
    private fun twoLaneExit(planMps: Double = 27.0) = route(
        node(0.0, maneuver = DEPART, planMps = planMps),
        node(2_000.0, s(), s(), planMps = planMps),
        node(4_000.0, s(false), sr(), road = RoadKind.RAMP, maneuver = OFF_RIGHT, planMps = 20.0),
        node(4_400.0, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0),
        node(4_500.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0),
    )

    @Test
    fun `on a freeway the band narrows for an exit no later than 805 m before the gore`() {
        // Without the floor the left lane would stay until 319 m before the gore at 27 m/s.
        val plan = plan(twoLaneExit())
        assertEquals(4_000.0 - LanePlanner.FREEWAY_FLOOR_METERS - LanePlanner.GRID_METERS, plan.lastInBand(0))
        assertTrue(0 in plan.at(3_190.0).band)
        assertFalse(0 in plan.at(3_195.0).band)
        // Where the changes need more than 805 m, they decide: max(comfortable, 805 m).
        val v = 75.0 * PlanningSpeed.MPS_PER_MPH
        val five = plan(
            route(
                node(0.0, maneuver = DEPART, planMps = v),
                node(1_000.0, s(), s(), s(), s(), s(), planMps = v),
                node(4_000.0, s(false), s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = OFF_RIGHT, planMps = 20.0),
                node(4_400.0, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0),
                node(4_500.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0),
            ),
        )
        // Four comfortable changes and the reaction at 75 mph: 1,331 m, on the grid 1,345 m.
        assertEquals(4_000.0 - 1_345.0, five.lastInBand(0))
        assertEquals(4_000.0 - 810.0, five.lastInBand(3), "one change: the floor")
    }

    @Test
    fun `between two maneuvers the band only narrows, and a lane that left it does not come back`() {
        // IW31: g1 leaves the band for P1, 1,850 m before the gore, where it cannot be used. Past P1 it could reach the
        // exit's lanes again, and the costs alone would put it back; it stays out, at P1 and after it.
        val plan = plan(LaneScenes.iw31())
        assertEquals(listOf(1, 2, 3), plan.at(GORE - 1_850.0).band, "at P1")
        var before = 1_900.0
        while (before >= 1_400.0) {
            assertFalse(0 in plan.at(GORE - before).band, "$before m before the gore")
            before -= LanePlanner.GRID_METERS
        }
        // Only a lane that was not there before may join it: the exit-only lane where it opens.
        assertFalse(4 in plan.at(GORE - 700.0).band)
        assertTrue(4 in plan.at(GORE - 620.0).band)
    }

    // ------------------------------------------------------------------ P3, P6 and no move back

    @Test
    fun `outside card windows no lane is named, inside one a lane is named wherever the band has one`() {
        for ((name, route) in LaneScenes.all()) {
            val plan = plan(route)
            for (segment in plan.segments) {
                if (!segment.inWindow) {
                    assertNull(segment.named, "$name at ${segment.from}: outside every window")
                } else if (segment.band.isNotEmpty()) {
                    assertNotNull(segment.named, "$name at ${segment.from}: in a window")
                    assertTrue(segment.named in segment.band, "$name at ${segment.from}: a lane of the band")
                }
            }
        }
    }

    @Test
    fun `the plan never moves the named lane away from the exit's edge`() {
        for ((name, route) in LaneScenes.all()) {
            val plan = plan(route)
            assertTrue(plan.moves.none { it.back }, "$name: ${plan.moves}")
        }
    }

    @Test
    fun `P6 names the lane with the fewest changes first`() {
        // IA: at the run-up's start the option lane needs no change; the right lane, nearer the edge, leaves at IA1.
        val plan = plan(LaneScenes.ia())
        assertEquals(GORE - 2_660.0 to 3, plan.firstNamed())
        assertTrue(4 in plan.at(GORE - 2_660.0).band, "the right lane still works there")
    }

    @Test
    fun `P6 names the lane the next point prefers among equal ones`() {
        val route = approach(listOf(at(1_500.0, s(), s(), s())), listOf(s(false), option(true, true), sr(true, false)))
        val plan = plan(route)
        // From the window's first station on, not only once a later move has put it right.
        assertEquals(GORE - 965.0 to 1, plan.firstNamed(), "the option lane the exit prefers, not the edge lane")
        assertTrue(plan.moves.isEmpty(), "${plan.moves}")
        assertEquals(1, plan.at(GORE - 100.0).named)
        assertEquals(setOf(1, 2), plan.at(GORE - 100.0).band.toSet())
    }

    @Test
    fun `P6 counts lane changes before any preference`() {
        // The point 700 m before prefers the middle lanes, or the left ones, which need changes: the right lane needs
        // none, and is named from the window's start; the split after the gore needs the exit's other lane, not the
        // option lane the exit prefers.
        for ((scene, first) in listOf(LaneScenes.prefersMiddle() to 990.0, LaneScenes.prefersLeft() to 965.0, LaneScenes.splitRight() to 965.0)) {
            val plan = plan(scene)
            assertEquals(GORE - first to 3, plan.firstNamed(), scene.routeId)
            assertTrue(plan.moves.isEmpty(), "${scene.routeId}: ${plan.moves}")
            assertEquals(3, plan.at(GORE - 5.0).named, scene.routeId)
        }
    }

    @Test
    fun `P6 takes the maneuver's preferred lane before the next point's, and never moves back to it`() {
        // The point 1,200 m before prefers the exit-only lane, the exit the option lane: the option lane is named from
        // the window's start to the gore, the lane the card lights there.
        val plan = plan(LaneScenes.prefersOther())
        assertEquals(GORE - 2_190.0 to 3, plan.firstNamed())
        assertTrue(plan.moves.isEmpty(), "${plan.moves}")
        assertEquals(3, plan.at(GORE - 1_200.0).named)
        assertEquals(3, plan.at(GORE).named)
        assertTrue(4 in plan.at(GORE - 1_000.0).band, "the exit-only lane works all the way, and is never named")
    }

    @Test
    fun `P6 names the lane nearest the maneuver's edge among equal ones`() {
        assertEquals(4, plan(LaneScenes.s6()).at(GORE - 100.0).named, "a right exit: the right lane")
        assertEquals(0, plan(LaneScenes.s7()).at(GORE - 100.0).named, "a left fork: the left lane")
    }

    @Test
    fun `a move toward the edge is no move back, though a lane opens beyond the new lane there`() {
        // Two lanes open on the right 955 m before the gore; the named lane moves into the inner one, which the exit
        // takes: its count from the edge grows from 0 to 1, as the outer lane opens with it.
        val move = plan(LaneScenes.twoAdded()).moves.single()
        assertEquals(GORE - 950.0, move.station, 1e-9)
        assertEquals(3 to 4, move.from to move.to)
        assertEquals(0 to 1, move.fromEdge to move.toEdge)
        assertFalse(move.back)
    }

    @Test
    fun `the named lane stays while it works and moves early to the lane the next point prefers`() {
        // The street pocket: the left lane from the junction view's start, into the pocket where it opens.
        val plan = plan(LaneScenes.streetPocket())
        assertEquals(GORE - 180.0 to 0, plan.firstNamed())
        assertEquals(listOf(Triple(GORE - 55.0, 0, 2)), plan.moves.map { Triple(it.station, it.from, it.to) })
    }

    // ------------------------------------------------------------------ the scenes' golden values

    /** At [station], the plan names [lane] and counts it [fromEdge] lanes from the exit's edge. */
    private data class AtPoint(val station: Double, val lane: Int?, val fromEdge: Int?)

    private class Golden(val first: Pair<Double, Int>?, val points: List<AtPoint>, val moves: List<Triple<Double, Int, Int>> = emptyList())

    private fun g(before: Double, lane: Int?, fromEdge: Int?) = AtPoint(GORE - before, lane, fromEdge)

    private val golden = mapOf(
        // The option lane from the run-up's start, which the seam begins 6 s before the band narrows for IA0: second
        // from the right to IA1, the right lane past it, the second again from IA2b, where the exit-only lane may be.
        "ia" to Golden(GORE - 2_660.0 to 3, listOf(g(2_180.0, 3, 1), g(1_330.0, 3, 1), g(690.0, 3, 0), g(640.0, 3, 1), g(0.0, 3, 1))),
        "ia-far" to Golden(GORE - 2_660.0 to 3, listOf(g(2_180.0, 3, 1), g(1_330.0, 3, 0), g(690.0, 3, 0), g(640.0, 3, 1), g(0.0, 3, 1))),
        // The option lane from the seam, 6 s before the band narrows for P1: the second from the right, the exit-only
        // lane possible from P1; on the ramp, held to its split, the left lane for the split's left branch.
        "iw31" to Golden(GORE - 2_365.0 to 3, listOf(g(2_600.0, null, null), g(1_850.0, 3, 1), g(620.0, 3, 1), g(0.0, 3, 1), g(-260.0, 3, 0))),
        "s5" to Golden(GORE - 1_835.0 to 1, listOf(g(1_100.0, 1, 0), g(0.0, 1, 0))),
        "s6" to Golden(GORE - 2_010.0 to 4, listOf(g(1_200.0, 4, 0), g(0.0, 4, 0))),
        // The seam 6 s before the freeway floor narrows the band for the fork, 805 m before it.
        "s7" to Golden(GORE - 985.0 to 0, listOf(g(800.0, 0, 0), g(0.0, 0, 0))),
        "s8" to Golden(GORE - 2_550.0 to 2, listOf(g(500.0, 2, 1), g(0.0, 4, 0)), listOf(Triple(GORE - 210.0, 2, 4))),
        "s9" to Golden(GORE - 2_295.0 to 3, listOf(g(300.0, 3, 1), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 3, 5))),
        "s13" to Golden(GORE - 965.0 to 2, listOf(g(3_000.0, null, null), g(0.0, 2, 0))),
        "s15" to Golden(GORE - 2_295.0 to 1, listOf(g(1_400.0, 1, 3), g(800.0, 1, 2), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 1, 5))),
        "s16" to Golden(GORE - 2_295.0 to 2, listOf(g(1_200.0, 2, 2), g(0.0, 2, 0))),
        // The road without lane data 300 m before the gore: the lane beside the edge lane until the band holds the
        // edge lane alone, one comfortable change and the reaction time before the gore.
        "s17" to Golden(GORE - 1_245.0 to 3, listOf(g(0.0, 4, 0)), listOf(Triple(GORE - 320.0, 3, 4))),
        "s20" to Golden(GORE - 2_295.0 to 1, listOf(g(1_600.0, 1, 2), g(700.0, 1, 1), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 1, 5))),
        "geom-s2-400" to Golden(GORE - 975.0 to 2, listOf(g(0.0, 2, 1), g(-400.0, null, null))),
        "geom-s2-150" to Golden(GORE - 975.0 to 2, listOf(g(0.0, 2, 1), g(-150.0, 2, 0))),
        "geom-s5" to Golden(null, listOf(g(0.0, null, null))),
        "geom-s6" to Golden(null, listOf(g(1_500.0, null, null), g(500.0, null, null), g(0.0, null, null))),
        "street-pocket" to Golden(GORE - 180.0 to 0, listOf(g(200.0, null, null), g(0.0, 2, 0)), listOf(Triple(GORE - 55.0, 0, 2))),
        // C1: nothing named where only a lane beyond the car-pool lane leads on, at a roundabout, at a turn only a bus may take.
        "carpool-beyond" to Golden(null, listOf(g(1_500.0, null, null), g(0.0, null, null))),
        // The car-pool lane opens to every car from 800 m before: the left open lane, the former car-pool lane where it
        // opens late, and the exit lane along its taper.
        "carpool-ends" to Golden(
            GORE - 995.0 to 1,
            listOf(g(1_500.0, null, null), g(800.0, 4, 0), g(0.0, 5, 0)),
            listOf(Triple(GORE - 850.0, 1, 4), Triple(GORE - 210.0, 4, 5)),
        ),
        "roundabout" to Golden(null, listOf(g(150.0, null, null), g(0.0, null, null))),
        "bus-only-turn" to Golden(null, listOf(g(150.0, null, null), g(0.0, null, null))),
        // P6's order: the option lane the exit prefers, though the point before prefers the exit-only lane; the lane that
        // needs no change, though the point before prefers others; the lane the split after the gore needs.
        "prefers-other" to Golden(GORE - 2_190.0 to 3, listOf(g(1_200.0, 3, 1), g(0.0, 3, 1))),
        "prefers-middle" to Golden(GORE - 990.0 to 3, listOf(g(700.0, 3, 0), g(0.0, 3, 0))),
        "prefers-left" to Golden(GORE - 965.0 to 3, listOf(g(700.0, 3, 0), g(0.0, 3, 0))),
        "split-right" to Golden(GORE - 965.0 to 3, listOf(g(0.0, 3, 0), g(-150.0, 3, 0))),
        // Into the inner of two lanes added on the right, where they open.
        "two-added" to Golden(GORE - 995.0 to 3, listOf(g(2_000.0, null, null), g(900.0, 4, 1), g(0.0, 4, 1)), listOf(Triple(GORE - 950.0, 3, 4))),
        // P7: the lane that is the right lane where the road without lane data leaves is held from the run-up's start,
        // then named once past the road; a hold dropped where the band holds the edge lane alone stays dropped.
        "edge-then-road" to Golden(GORE - 2_295.0 to 2, listOf(g(1_650.0, 2, 2), g(0.0, 3, 0)), listOf(Triple(GORE - 950.0, 2, 3))),
        "dropped-then-wider" to Golden(
            GORE - 2_435.0 to 1,
            listOf(g(1_700.0, 2, 0), g(1_200.0, 2, 0), g(0.0, 2, 0)),
            listOf(Triple(GORE - 2_020.0, 1, 2)),
        ),
        // A keep left given as a continue: the left of the two lanes that lead on, counted from the left, as for a fork.
        "keep-left" to Golden(GORE - 2_295.0 to 1, listOf(g(1_200.0, 1, 1), g(0.0, 1, 0))),
    )

    @Test
    fun `every scene plans its golden values to the 5 m station`() {
        assertEquals(LaneScenes.all().keys, golden.keys)
        for ((name, route) in LaneScenes.all()) {
            val plan = plan(route)
            val want = requireNotNull(golden[name])
            val first = plan.firstNamed()
            if (want.first == null) {
                assertNull(first, name)
            } else {
                assertNotNull(first, name)
                assertEquals(want.first.first, requireNotNull(first).first, LanePlanner.GRID_METERS, "$name: first named")
                assertEquals(want.first.second, first.second, "$name: the lane first named")
            }
            for (p in want.points) {
                val segment = plan.at(p.station)
                assertEquals(p.lane, segment.named, "$name at ${p.station}")
                assertEquals(p.fromEdge, segment.namedFromEdge, "$name at ${p.station}: from the edge")
            }
            assertEquals(want.moves.size, plan.moves.size, "$name: ${plan.moves}")
            for ((move, expected) in plan.moves.zip(want.moves)) {
                assertEquals(expected.first, move.station, LanePlanner.GRID_METERS, "$name: where it moves")
                assertEquals(expected.second to expected.third, move.from to move.to, name)
            }
        }
    }

    // ------------------------------------------------------------------ the seam (D15)

    /** Where a scene's run-up begins, in metres before the gore: the band's first narrowing for it, and the seam's begin (null: the latch's own). */
    private class SeamAt(val narrowing: Double?, val begins: Double?)

    private val seams = mapOf(
        "ia" to SeamAt(2_500.0, 2_662.0),
        "ia-far" to SeamAt(2_500.0, 2_662.0),
        "iw31" to SeamAt(2_195.0, 2_195.0 + 6.0 * 65.0 * PlanningSpeed.MPS_PER_MPH),
        "s5" to SeamAt(1_675.0, 1_837.0),
        "s6" to SeamAt(1_830.0, 2_010.0),
        // The freeway floor narrows the band for the fork and S13's exit 805 m before the gore.
        "s7" to SeamAt(805.0, 985.0),
        "s13" to SeamAt(805.0, 967.0),
        "s17" to SeamAt(1_085.0, 1_247.0),
        "geom-s2-400" to SeamAt(805.0, 805.0 + 6.0 * 65.0 * PlanningSpeed.MPS_PER_MPH),
        // Where the band narrows later than 6 s after the latch's own begin, the latch's begin stays.
        "s8" to SeamAt(850.0, null),
        "s9" to SeamAt(835.0, null),
        "s15" to SeamAt(1_720.0, null),
        "s16" to SeamAt(1_600.0, null),
        "s20" to SeamAt(1_920.0, null),
    )

    @Test
    fun `each run-up begins 6 s before the band first narrows for a lane point within its reach, or where the latch begins it`() {
        for ((name, want) in seams) {
            val route = requireNotNull(LaneScenes.all()[name])
            val plan = plan(route)
            val seam = plan.seams.single()
            val latched = CardWindows.latched(route).single { it.kind == CardWindow.Kind.RUN_UP }
            assertEquals(latched.from, seam.latchFrom, 1e-9, name)
            assertEquals(latched.node, seam.node, name)
            assertEquals(GORE - requireNotNull(want.narrowing), requireNotNull(seam.narrowing), 1e-9, "$name: the first narrowing")
            val begins = want.begins?.let { GORE - it } ?: latched.from
            assertEquals(begins, seam.from, 1e-6, "$name: where the run-up begins")
            assertEquals(seam.from, plan.windows.single { it.kind == CardWindow.Kind.RUN_UP }.from, 1e-9, name)
            if (want.begins != null) {
                val speed = PlanningSpeed.of(route.nodes[seam.node - 1])
                assertEquals(seam.narrowing - LanePlanner.SEAM_SECONDS * speed, seam.from, 1e-6, "$name: 6 s before it")
                assertTrue(seam.earlierMeters > 0.0, name)
            }
        }
    }

    @Test
    fun `the seam moves a run-up only where 6 s before the narrowing lies before the latch's own begin`() {
        // IA with IA0 at 1,810 m: the band narrows for it 2,130 m before the gore, and 6 s (162 m) before that lies
        // after the reach, 2,295 m before the gore: the latch's begin stays.
        val later = plan(LaneScenes.ia(ia0At = 1_810.0)).seams.single()
        assertEquals(GORE - 2_130.0, requireNotNull(later.narrowing), 1e-9)
        assertEquals(later.latchFrom, later.from, 1e-9)
        assertEquals(0.0, later.earlierMeters, 1e-9)
        // With IA0 at 1,820 m the band narrows 2,140 m before the gore: the run-up begins 7 m before the reach.
        val earlier = plan(LaneScenes.ia(ia0At = 1_820.0)).seams.single()
        assertEquals(GORE - 2_140.0, requireNotNull(earlier.narrowing), 1e-9)
        assertEquals(GORE - 2_140.0 - LanePlanner.SEAM_SECONDS * 27.0, earlier.from, 1e-9)
        assertEquals(7.0, earlier.earlierMeters, 1e-9)
    }

    @Test
    fun `the seam never begins a run-up before its step`() {
        // IA whose step begins 2,400 m before the gore, at a maneuver straight on: the seam would begin 2,662 m before.
        val ia = LaneScenes.ia()
        val step = node(GORE - 2_400.0, maneuver = NodeManeuver(ManeuverType.CONTINUE), planMps = 27.0)
        val route = ia.copy(steps = ia.steps + 1, nodes = (ia.nodes + step).sortedBy { it.station })
        val seam = plan(route).seams.single()
        assertEquals(GORE - 2_500.0, requireNotNull(seam.narrowing), 1e-9)
        assertEquals(GORE - 2_400.0, seam.from, 1e-9)
    }

    @Test
    fun `no lane is named before the run-up begins, and the first named lane is named where it begins`() {
        for ((name, route) in LaneScenes.all()) {
            val plan = plan(route)
            for (seam in plan.seams) {
                val window = plan.windows.single { it.kind == CardWindow.Kind.RUN_UP && it.node == seam.node }
                val first = plan.segments.firstOrNull { it.named != null && it.to > window.from && it.from <= window.to } ?: continue
                assertTrue(first.from >= seam.from, "$name: named from ${first.from}, the run-up from ${seam.from}")
                assertTrue(first.from - seam.from < LanePlanner.GRID_METERS, "$name: at the first station of the window")
            }
        }
    }

    @Test
    fun `a keep left given as a continue is a maneuver of the plan, as a fork is`() {
        // The card runs up to both; the plan counts from the split's side, not from the arrival's on the right after it.
        val continued = plan(LaneScenes.keepLeft(ManeuverType.CONTINUE))
        val forked = plan(LaneScenes.keepLeft(ManeuverType.FORK))
        for (plan in listOf(continued, forked)) {
            val split = plan.topology.points.single { it.station == GORE }
            assertTrue(split.turns)
            assertEquals(LaneEdge.LEFT, split.edge)
            assertEquals(LaneEdge.LEFT, plan.at(GORE - 2_000.0).edge)
            assertEquals(GORE - 2_295.0 to 1, plan.firstNamed())
            assertEquals(0, plan.at(GORE).namedFromEdge)
        }
        assertEquals(forked.segments, continued.segments)
        assertEquals(forked.windows, continued.windows)
        // The arrival's side counts only past the split, and so does a road without lane data on that side past it: the
        // split ends the step before it, so nothing is held for that road and the words need not wait for it there.
        assertEquals(LaneEdge.RIGHT, LaneEvidence.planEdges(LaneScenes.keepLeft()).last())
        val keepLeft = LaneScenes.keepLeft()
        val withRoad = keepLeft.copy(nodes = (keepLeft.nodes + node(GORE + 500.0, roads = LaneScenes.EXIT_RIGHT, planMps = 27.0)).sortedBy { it.station })
        val past = plan(withRoad)
        assertEquals(withRoad.nodes.lastIndex, past.unknownExits.single().node)
        assertFalse(past.at(GORE - 1_000.0).unknownExitAhead)
        assertEquals(continued.segments.map { it.named }, past.segments.map { it.named })
        assertFalse(LaneEvidence.planTurns(LaneScenes.keepLeft().nodes.first()), "the departure ends no step")
        // A continue without lanes is no maneuver of the plan, as it is none of the card's run-ups.
        assertFalse(LaneEvidence.planTurns(node(0.0, maneuver = NodeManeuver(ManeuverType.CONTINUE, TurnModifier.SLIGHT_LEFT, viewStaysUp = true))))
    }

    // ------------------------------------------------------------------ the ramp bridge (D20)

    @Test
    fun `a run-up holds through the ramp to a split the plan constrains within 15 s, naming the lane for it`() {
        // IW31: the ramp splits 260 m after the gore, 12.9 s at 45 mph; the option lane goes on as the ramp's left
        // lane, which the split's left branch takes.
        val route = LaneScenes.iw31()
        val iw31 = plan(route)
        val ramp = iw31.windows.single { it.kind == CardWindow.Kind.RAMP }
        assertEquals(GORE, ramp.from, 1e-9)
        assertEquals(GORE + 260.0, ramp.to, 1e-9)
        assertEquals(route.nodes.indexOfFirst { it.station == GORE + 260.0 }, ramp.node)
        for (station in listOf(GORE + 5.0, GORE + 100.0, GORE + 255.0, GORE + 260.0)) {
            val segment = iw31.at(station)
            assertEquals(3, segment.named, "at $station")
            assertEquals(LaneEdge.LEFT, segment.edge, "the split's side")
            assertEquals(0, segment.namedFromEdge, "at $station: the left lane")
        }
        assertTrue(iw31.moves.isEmpty())
        // GEOM's S2: a split 150 m after the gore is held to; one 400 m after, 19.9 s, is not, and nothing is named there.
        assertEquals(listOf(GORE to GORE + 150.0), plan(LaneScenes.geomS2(150.0)).windows.filter { it.kind == CardWindow.Kind.RAMP }.map { it.from to it.to })
        val far = plan(LaneScenes.geomS2(400.0))
        assertTrue(far.windows.none { it.kind == CardWindow.Kind.RAMP })
        assertNull(far.at(GORE + 100.0).named)
    }

    @Test
    fun `the ramp is held to a split no more than 15 s of driving after the gore`() {
        // At 20 m/s on the ramp, 15 s is 300 m.
        val held = plan(LaneScenes.geomS2(300.0, rampMps = 20.0))
        assertEquals(GORE + 300.0, held.windows.single { it.kind == CardWindow.Kind.RAMP }.to, 1e-9)
        assertEquals(2, held.at(GORE + 300.0).named)
        val notHeld = plan(LaneScenes.geomS2(305.0, rampMps = 20.0))
        assertTrue(notHeld.windows.none { it.kind == CardWindow.Kind.RAMP })
        assertNull(notHeld.at(GORE + 5.0).named)
    }

    // ------------------------------------------------------------------ P7: roads without lane data on the exit's side

    @Test
    fun `before a road without lane data on the exit's side the edge lane is held back, to the road where there is time`() {
        // Five lanes, the exit from the right lane only; the road leaves on the right. The hold lasts to the road where it
        // lies at least one comfortable change and the reaction time before the gore: 295, 320, 340 and 390 m on the grid
        // at 25, 27, 29 and 33 m/s (design 5.2's P7). Nearer, the right lane is named from there, before the road.
        val limits = mapOf(25.0 to 295.0, 27.0 to 320.0, 29.0 to 340.0, 33.0 to 390.0)
        for ((mps, limit) in limits) {
            for (before in listOf(900.0, 370.0, 300.0, 200.0)) {
                val label = "$mps m/s, the road $before m before the gore"
                val plan = plan(LaneScenes.unknownExit(before, mps))
                val hold = plan.unknownExits.single()
                assertEquals(GORE - before, hold.station, 1e-9, label)
                assertEquals(2, hold.node, label)
                assertTrue(hold.inWindow, label)
                val edgeFrom = if (before >= limit) before else limit
                if (before >= limit) assertNull(hold.droppedAt, "$label: held") else assertEquals(GORE - limit, requireNotNull(hold.droppedAt), 1e-9, "$label: dropped")
                assertEquals(hold.droppedAt == null, hold.held, label)
                // The lane beside the edge lane up to there, the edge lane after: one move, toward the edge.
                assertEquals(listOf(Triple(GORE - edgeFrom, 3, 4)), plan.moves.map { Triple(it.station, it.from, it.to) }, label)
                assertTrue(plan.moves.none { it.back }, label)
                val beforeIt = plan.at(GORE - edgeFrom - LanePlanner.GRID_METERS)
                assertEquals(3, beforeIt.named, label)
                assertEquals(1, beforeIt.namedFromEdge, label)
                assertTrue(4 in beforeIt.band, "$label: the edge lane works, it is only not named")
                assertEquals(4, plan.at(GORE - edgeFrom).named, label)
            }
        }
    }

    @Test
    fun `the lane at the edge where the road leaves is held from the run-up's start, not only once it is at the edge`() {
        // The right lane leaves 1,650 m before the gore, the road 950 m before: the lane that is the right lane from 1,650
        // m on is held from the start, so the light moves once, to it past the road, and never away from the edge.
        val plan = plan(LaneScenes.edgeThenRoad())
        assertEquals(GORE - 2_295.0 to 2, plan.firstNamed())
        assertTrue(3 in plan.at(GORE - 2_000.0).band, "the lane works; it is only not named")
        assertEquals(listOf(Triple(GORE - 950.0, 2, 3)), plan.moves.map { Triple(it.station, it.from, it.to) })
        assertTrue(plan.moves.none { it.back })
        assertTrue(plan.unknownExits.single().held)
    }

    @Test
    fun `a hold dropped where the band holds the edge lane alone stays dropped, though the band widens again`() {
        // Only the right lane can be used 1,700 m before the gore, so the hold for the road 800 m before is dropped where
        // the band holds that lane alone; a lane added on the far side 1,200 m before widens the band again, and the
        // light stays on the right lane rather than moving away from it until the road.
        val plan = plan(LaneScenes.droppedThenWider())
        assertEquals(GORE - 2_020.0, requireNotNull(plan.unknownExits.single().droppedAt), 1e-9)
        assertEquals(listOf(Triple(GORE - 2_020.0, 1, 2)), plan.moves.map { Triple(it.station, it.from, it.to) })
        val wider = plan.at(GORE - 1_000.0)
        assertEquals(listOf(3, 2), wider.band)
        assertEquals(2, wider.named)
        assertTrue(wider.unknownExitAhead)
    }

    @Test
    fun `the card's words wait until the road without lane data is passed`() {
        val plan = plan(LaneScenes.unknownExit(200.0, 33.0))
        assertTrue(plan.at(GORE - 1_000.0).unknownExitAhead)
        assertTrue(plan.at(GORE - 205.0).unknownExitAhead)
        assertTrue(plan.at(GORE - 200.0).unknownExitAhead, "at the road itself")
        assertFalse(plan.at(GORE - 195.0).unknownExitAhead)
        // The hold is dropped 390 m before the gore, the words wait to the road all the same.
        assertEquals(GORE - 390.0, requireNotNull(plan.unknownExits.single().droppedAt), 1e-9)
        assertTrue(plan.at(GORE - 300.0).unknownExitAhead)
        assertEquals(4, plan.at(GORE - 300.0).named)
        // A route without such roads never waits; a road on the far side of the exit is no unknown exit.
        assertTrue(plan(LaneScenes.ia()).segments.none { it.unknownExitAhead })
        assertTrue(plan(LaneScenes.ia()).unknownExits.isEmpty())
    }

    @Test
    fun `the freeway floor begins past a road without lane data within it`() {
        // 27 m/s, the road 370 m before the gore: the hold lasts to the road, so the floor, which would leave the right
        // lane alone from 805 m before the gore, begins at the road.
        val route = LaneScenes.unknownExit(370.0, 27.0)
        val plan = plan(route)
        assertEquals(listOf(3, 4), plan.at(GORE - 400.0).band)
        assertEquals(listOf(4), plan.at(GORE - 370.0).band)
        assertNull(plan.unknownExits.single().droppedAt)
        // Without the road the floor leaves the right lane alone from 805 m before the gore.
        val without = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)))
        assertEquals(listOf(2, 3, 4), without.at(GORE - 810.0).band)
        assertEquals(listOf(4), without.at(GORE - 805.0).band)
        assertTrue(without.unknownExits.isEmpty())
    }

    @Test
    fun `a road without lane data holds only before the exit of its own step`() {
        // The road 370 m before the gore given as one past the exit, on the ramp's step: it holds nothing before the gore.
        val route = LaneScenes.unknownExit(370.0, 27.0)
        val plan = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), listOf(GORE + 100.0))
        assertEquals(GORE - 1_245.0 to 4, plan.firstNamed(), "the edge lane from the run-up's start")
        assertTrue(plan.segments.filter { it.to <= GORE }.none { it.unknownExitAhead })
        assertTrue(plan.unknownExits.single().held)
        assertFalse(plan.unknownExits.single().inWindow)
    }

    @Test
    fun `GEOM's S2 drops the lane to the ramp's wrong branch after the gore, or before it when the split is near`() {
        // Split 400 m after the gore: the dropped lane works until 160 m after it; split 150 m after: until 195 m before it.
        assertEquals(GORE + 155.0, requireNotNull(plan(LaneScenes.geomS2(400.0)).lastInBand(3)), LanePlanner.GRID_METERS)
        assertEquals(GORE - 200.0, requireNotNull(plan(LaneScenes.geomS2(150.0)).lastInBand(3)), LanePlanner.GRID_METERS)
    }

    @Test
    fun `where only a lane kept for car pools leads on, no lane works and the plan says so`() {
        val plan = plan(LaneScenes.geomS5())
        assertTrue(plan.segments.all { it.named == null })
        assertEquals(LanePlan.Flag.Kind.CONFLICT, plan.flags.single().kind)
        assertEquals(GORE + LaneTopology.LEAVE_PAST_POINT_METERS, plan.flags.single().to, "up to where the run's lanes end")
    }

    // ------------------------------------------------------------------ C1: where no lane open to every car can be named

    /** The approach's stretches of the plan: no lane named and nothing tinted, for [cause]. */
    private fun assertNoLane(plan: LanePlan, approach: LanePlan.NoLane, cause: LanePlan.NoLane.Cause, name: String) {
        assertEquals(cause, approach.cause, name)
        for (segment in plan.segments) {
            // A stretch's stations run from its start to one grid step before its end.
            if (segment.to - LanePlanner.GRID_METERS < approach.from || segment.from > approach.to) continue
            assertNull(segment.named, "$name at ${segment.from}")
            assertTrue(segment.band.isEmpty(), "$name at ${segment.from}: nothing tinted")
            assertEquals(cause, segment.noLane, "$name at ${segment.from}")
        }
    }

    /** The evidence's zones of the plan's approaches where no lane is named. */
    private fun LanePlan.noLaneZones() = evidence().zones.filter { it.cause == LaneEvidence.Zone.Cause.NO_LANE }

    @Test
    fun `where only a lane kept for car pools is valid at an exit no lane is named and its approach is a zone`() {
        // GEOM's S5: the exit counts no open lane it can be taken from, so the card has no run-up for it; the approach is
        // the close view's own window at the approach's planning speed.
        val route = LaneScenes.geomS5()
        val plan = plan(route)
        val approach = plan.noLane.single()
        val window = LaneFocus.windowMeters(CameraTuning.PHONE, 65.0 * PlanningSpeed.MPS_PER_MPH)
        assertEquals(GORE - window, approach.from, 1e-9)
        assertEquals(GORE, approach.to, 1e-9)
        assertTrue(approach.zone)
        assertNoLane(plan, approach, LanePlan.NoLane.Cause.DESIGNATED_ONLY, "geom-s5")
        assertTrue(plan.segments.all { it.named == null })
        assertEquals(LanePlan.Flag.Kind.CONFLICT, plan.flags.single().kind, "the conflict stays flagged")
        val zone = plan.noLaneZones().single()
        assertEquals(GORE - window, zone.from, 1e-9)
        assertEquals(GORE, zone.to, 1e-9, "a freeway approach to the gore")
        assertEquals(3, zone.lanes)
        assertEquals(LaneEvidence.Kind.ZONE, plan.evidence().stretches.single { it.from <= GORE - 100.0 && GORE - 100.0 < it.to }.kind)
    }

    @Test
    fun `before a left exit beyond the car-pool lane no lane is named, and the approach is a zone from the run-up's start`() {
        val route = LaneScenes.carpoolBeyond()
        val plan = plan(route)
        val window = plan.windows.single { it.kind == CardWindow.Kind.RUN_UP }
        val approach = plan.noLane.single()
        assertEquals(window.from, approach.from, 1e-9)
        assertEquals(window.node, approach.node)
        assertEquals(GORE, approach.to, 1e-9)
        assertNoLane(plan, approach, LanePlan.NoLane.Cause.DESIGNATED_BETWEEN, "carpool-beyond")
        assertTrue(plan.segments.all { it.named == null }, "nothing named before the gore")
        assertTrue(plan.flags.any { it.kind == LanePlan.Flag.Kind.CONFLICT })
        val zone = plan.noLaneZones().single()
        assertEquals(window.from to GORE, zone.from to zone.to)
        assertTrue(zone.inWindow)
    }

    @Test
    fun `where the car-pool lane opens to every car before the exit a lane is named and never moves back`() {
        val plan = plan(LaneScenes.carpoolEnds())
        assertTrue(plan.noLane.isEmpty())
        assertTrue(plan.noLaneZones().isEmpty())
        assertTrue(plan.flags.isEmpty())
        assertNotNull(plan.firstNamed())
        assertTrue(plan.moves.isNotEmpty() && plan.moves.none { it.back })
        assertEquals(5, plan.at(GORE - 5.0).named, "the exit lane at the gore")
    }

    @Test
    fun `no lane is named on a roundabout's approach or on the way round it, and only the approach is a zone`() {
        val plan = plan(LaneScenes.roundabout())
        assertEquals(2, plan.noLane.size)
        val (approach, round) = plan.noLane
        assertEquals(GORE - 180.0 to GORE, approach.from to approach.to, "the junction view's start to the roundabout")
        assertTrue(approach.zone)
        assertNoLane(plan, approach, LanePlan.NoLane.Cause.ROUNDABOUT, "roundabout")
        assertEquals(GORE to GORE + 60.0, round.from to round.to, "the way round to its exit")
        assertFalse(round.zone)
        assertTrue(plan.segments.all { it.named == null })
        val zone = plan.noLaneZones().single()
        assertEquals(GORE - 180.0, zone.from, 1e-9)
        assertEquals(GORE - LaneTopology.STOP_LINE_METERS, zone.to, 1e-9, "a street approach to its stop line")
        assertEquals(2, zone.lanes)
        // The same lanes before a plain turn: a lane is named there.
        val turn = plan(LaneScenes.roundabout(turnInstead = true))
        assertTrue(turn.noLane.isEmpty())
        assertNotNull(turn.at(GORE - 100.0).named)
    }

    @Test
    fun `a street turn only a bus lane is valid for names no lane, its approach a zone to the stop line`() {
        val plan = plan(LaneScenes.busOnlyTurn())
        val approach = plan.noLane.single()
        assertEquals(GORE - 180.0 to GORE, approach.from to approach.to)
        assertNoLane(plan, approach, LanePlan.NoLane.Cause.DESIGNATED_ONLY, "bus-only-turn")
        val zone = plan.noLaneZones().single()
        assertEquals(GORE - 180.0 to GORE - LaneTopology.STOP_LINE_METERS, zone.from to zone.to)
        assertEquals(3, zone.lanes)
    }

    @Test
    fun `an approach where no lane is named is a zone only where the drawing shows it`() {
        // GEOM's S5 with an intersection without lanes 500 m before the gore: the exit's lanes reach back to it only, so
        // the zone begins there, not at the start of the close view's window.
        val route = approach(
            listOf(LaneScenes.bare(500.0, LaneScenes.THROUGH)),
            exit = listOf(LaneScenes.hov(true), s(false), s(false)),
            maneuver = LaneScenes.OFF_LEFT,
            planMps = 65.0 * PlanningSpeed.MPS_PER_MPH,
        )
        val plan = plan(route)
        assertTrue(plan.noLane.single().from < GORE - 500.0)
        val zone = plan.noLaneZones().single()
        assertEquals(GORE - 500.0 to GORE, zone.from to zone.to)
        val before = plan.evidence().stretches.single { it.from <= GORE - 600.0 && GORE - 600.0 < it.to }
        assertEquals(LaneEvidence.Kind.NONE, before.kind, "nothing of the app's own before the lanes are known")
    }

    @Test
    fun `a ramp is held to a split, never to a roundabout, for which no lane is named`() {
        fun ramp(maneuver: NodeManeuver) = approach(
            emptyList(),
            exit = listOf(s(false), s(false), option(true, true), sr(true, false)),
            planMps = 65.0 * PlanningSpeed.MPS_PER_MPH,
            rampMps = 20.0,
            rampPoints = listOf(200.0 to LaneScenes.NodeAndLanes(listOf(s(), s(false)), maneuver = maneuver)),
            rampMeters = 500.0,
        )
        val roundabout = plan(ramp(NodeManeuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT)))
        assertTrue(roundabout.windows.none { it.kind == CardWindow.Kind.RAMP })
        assertTrue(roundabout.segments.none { it.named != null && it.from >= GORE + LanePlanner.GRID_METERS })
        assertEquals(LanePlan.NoLane.Cause.ROUNDABOUT, roundabout.noLane.single().cause)
        val split = plan(ramp(LaneScenes.FORK_LEFT))
        assertEquals(GORE + 200.0, split.windows.single { it.kind == CardWindow.Kind.RAMP }.to, 1e-9)
    }

    @Test
    fun `no other scene names no lane`() {
        val c1 = setOf("geom-s5", "carpool-beyond", "roundabout", "bus-only-turn")
        for ((name, route) in LaneScenes.all()) {
            if (name in c1) continue
            val plan = plan(route)
            assertTrue(plan.noLane.isEmpty(), name)
            assertTrue(plan.segments.none { it.noLane != null }, name)
        }
    }

    @Test
    fun `the log names the causes as the design does`() {
        assertEquals(listOf("designatedOnly", "designatedBetween", "roundabout"), LanePlan.NoLane.Cause.entries.map { it.logName })
    }

    // ------------------------------------------------------------------ the plan as an object

    @Test
    fun `a lookup in the plan gives the same object anywhere in a stretch`() {
        val plan = plan(LaneScenes.ia())
        val a = plan.at(GORE - 1_000.0)
        assertSame(a, plan.at(a.from))
        assertSame(a, plan.at(a.to - 0.01))
        assertNull(plan.segmentAt(-1.0))
        assertNull(plan.segmentAt(GORE + 1_000.0), "past the run's last point there is no plan")
        assertEquals(a, plan.segments.single { it.from <= GORE - 1_000.0 && GORE - 1_000.0 < it.to })
    }

    /**
     * A made-up freeway of [km] kilometres planned at 30 m/s with a lane point of five lanes every 1.5 km, the right
     * lane leaving by a road on the right at every other one and joining again at the next, so the run gains a lane
     * every 3 km; a right exit from the right lane at its end.
     */
    private fun longRun(km: Int): LaneRoute {
        val nodes = arrayListOf(node(0.0, maneuver = DEPART, planMps = 30.0))
        val end = km * 1_000.0
        var at = 1_500.0
        while (at < end - 1_000.0) {
            val leaves = nodes.size % 2 == 1
            nodes.add(node(at, s(), s(), s(), s(), s(!leaves), roads = if (leaves) LaneScenes.EXIT_RIGHT else LaneScenes.JOIN_RIGHT, planMps = 30.0))
            at += 1_500.0
        }
        nodes.add(node(end, s(false), s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = OFF_RIGHT, planMps = 20.0))
        nodes.add(node(end + 400.0, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0))
        nodes.add(node(end + 500.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0))
        return route(*nodes.toTypedArray(), id = "long-$km")
    }

    /** The bytes the plan of [route] takes to make, on this thread. */
    private fun bytesToPlan(route: LaneRoute, bean: com.sun.management.ThreadMXBean): Long {
        val topology = LaneTopology.of(LaneEvidence.of(route))
        val windows = CardWindows.latched(route)
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        val plan = LanePlanner.plan(topology, windows)
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(1, plan.topology.runs.size, "one run all the way")
        assertNotNull(plan.segmentAt(route.nodes.single { it.maneuver == OFF_RIGHT }.station)?.named, "a lane named at the exit")
        return bytes
    }

    @Test
    fun `a long run's plan takes room in step with its length, not with its length times its lanes`() {
        // Before, every station kept a cost for every lane the run ever had: 600 km of this freeway took about 490 MB
        // to plan, two tables of about 100 MB at once. Now each lane keeps costs only where it is there.
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled)
        bytesToPlan(longRun(30), bean)
        val half = bytesToPlan(longRun(300), bean)
        val full = bytesToPlan(longRun(600), bean)
        assertTrue(full < 80_000_000L, "600 km: $full bytes")
        assertTrue(full < 3 * half, "600 km: $full bytes, 300 km: $half bytes")
    }

    @Test
    fun `the same route gives the same plan`() {
        for ((name, route) in LaneScenes.all()) {
            val one = plan(route)
            val two = plan(route)
            assertEquals(one.segments, two.segments, name)
            assertEquals(one.moves, two.moves, name)
            assertEquals(one.flags, two.flags, name)
        }
    }

    @Test
    fun `the plan's named lanes decide which added lanes are possible in the evidence's second pass`() {
        val iw31 = plan(LaneScenes.iw31())
        assertFalse(4 in iw31.named, "the exit-only lane is never named")
        assertEquals(listOf(GORE - 1_850.0), iw31.evidence().possibleLanes.map { it.from })
        val s8 = plan(LaneScenes.s8())
        assertTrue(4 in s8.named, "the deceleration lane is named")
        assertTrue(s8.evidence().possibleLanes.isEmpty())
    }

    @Test
    fun `the planner takes the route's lanes, the card windows and the unknown exits' stations, nothing of the car`() {
        val plans = LanePlanner::class.java.methods.filter { it.name == "plan" }
        assertTrue(plans.isNotEmpty())
        for (method in plans) {
            assertEquals(listOf(LaneTopology::class.java, List::class.java, List::class.java), method.parameterTypes.toList())
        }
    }
}
