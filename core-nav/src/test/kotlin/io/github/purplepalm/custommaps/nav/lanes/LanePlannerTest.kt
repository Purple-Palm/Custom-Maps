package io.github.purplepalm.custommaps.nav.lanes

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up routes only ([LaneScenes]): stations in metres from the route's start, the gore at 6,000 m,
 * lanes from the left, lane ids of the topology. The golden values were worked out with a copy of the
 * design's prototype planner (GEOM's planner with FINAL's naming), run on the same lane topologies with
 * the design's freeway floor and with lanes named only inside card windows; they agree to the 5 m station.
 */
class LanePlannerTest {

    private fun plan(route: LaneRoute): LanePlan = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)))

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
        assertEquals(GORE - 2_295.0 to 3, plan.firstNamed())
        assertTrue(4 in plan.at(GORE - 2_295.0).band, "the right lane still works there")
    }

    @Test
    fun `P6 names the lane the next point prefers among equal ones`() {
        val route = approach(listOf(at(1_500.0, s(), s(), s())), listOf(s(false), option(true, true), sr(true, false)))
        val plan = plan(route)
        assertEquals(1, plan.at(GORE - 100.0).named, "the option lane the exit prefers, not the edge lane")
        assertEquals(setOf(1, 2), plan.at(GORE - 100.0).band.toSet())
    }

    @Test
    fun `P6 names the lane nearest the maneuver's edge among equal ones`() {
        assertEquals(4, plan(LaneScenes.s6()).at(GORE - 100.0).named, "a right exit: the right lane")
        assertEquals(0, plan(LaneScenes.s7()).at(GORE - 100.0).named, "a left fork: the left lane")
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
        // The option lane from the run-up's start: second from the right to IA1, the right lane past it, the second
        // again from IA2b, where the exit-only lane may already be there.
        "ia" to Golden(GORE - 2_295.0 to 3, listOf(g(2_180.0, 3, 1), g(1_330.0, 3, 1), g(690.0, 3, 0), g(640.0, 3, 1), g(0.0, 3, 1))),
        "ia-far" to Golden(GORE - 2_295.0 to 3, listOf(g(2_180.0, 3, 1), g(1_330.0, 3, 0), g(690.0, 3, 0), g(640.0, 3, 1), g(0.0, 3, 1))),
        // The option lane from the run-up's start, at P2's window: the second from the right, the exit-only lane possible.
        "iw31" to Golden(GORE - 1_345.0 to 3, listOf(g(2_600.0, null, null), g(1_850.0, null, null), g(620.0, 3, 1), g(0.0, 3, 1))),
        "s5" to Golden(GORE - 1_775.0 to 1, listOf(g(1_100.0, 1, 0), g(0.0, 1, 0))),
        "s6" to Golden(GORE - 1_950.0 to 4, listOf(g(1_200.0, 4, 0), g(0.0, 4, 0))),
        "s7" to Golden(GORE - 750.0 to 0, listOf(g(800.0, null, null), g(0.0, 0, 0))),
        "s8" to Golden(GORE - 2_550.0 to 2, listOf(g(500.0, 2, 1), g(0.0, 4, 0)), listOf(Triple(GORE - 210.0, 2, 4))),
        "s9" to Golden(GORE - 2_295.0 to 3, listOf(g(300.0, 3, 1), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 3, 5))),
        "s13" to Golden(GORE - 675.0 to 2, listOf(g(3_000.0, null, null), g(0.0, 2, 0))),
        "s15" to Golden(GORE - 2_295.0 to 1, listOf(g(1_400.0, 1, 3), g(800.0, 1, 2), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 1, 5))),
        "s16" to Golden(GORE - 2_295.0 to 2, listOf(g(1_200.0, 2, 2), g(0.0, 2, 0))),
        "s17" to Golden(GORE - 675.0 to 4, listOf(g(0.0, 4, 0))),
        "s20" to Golden(GORE - 2_295.0 to 1, listOf(g(1_600.0, 1, 2), g(700.0, 1, 1), g(0.0, 5, 0)), listOf(Triple(GORE - 210.0, 1, 5))),
        "geom-s2-400" to Golden(GORE - 725.0 to 2, listOf(g(0.0, 2, 1), g(-400.0, null, null))),
        "geom-s2-150" to Golden(GORE - 725.0 to 2, listOf(g(0.0, 2, 1), g(-150.0, null, null))),
        "geom-s5" to Golden(null, listOf(g(0.0, null, null))),
        "geom-s6" to Golden(null, listOf(g(1_500.0, null, null), g(500.0, null, null), g(0.0, null, null))),
        "street-pocket" to Golden(GORE - 180.0 to 0, listOf(g(200.0, null, null), g(0.0, 2, 0)), listOf(Triple(GORE - 55.0, 0, 2))),
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
    fun `the planner takes the route's lanes and the card windows, nothing of the car`() {
        val plans = LanePlanner::class.java.methods.filter { it.name == "plan" }
        assertTrue(plans.isNotEmpty())
        for (method in plans) {
            assertEquals(listOf(LaneTopology::class.java, List::class.java), method.parameterTypes.toList())
        }
    }
}
