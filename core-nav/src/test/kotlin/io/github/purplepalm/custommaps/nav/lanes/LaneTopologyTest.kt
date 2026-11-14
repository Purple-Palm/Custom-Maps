package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.JOIN_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.approach
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.at
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.bare
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.hov
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.left
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.node
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.option
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.route
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sl
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology.Alignment.Reason
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up approaches only ([LaneScenes]): stations in metres from the route's start, the gore at 6,000 m,
 * lanes from the left. Counts from the exit's edge are of lanes open to every car, 0 at the edge.
 */
class LaneTopologyTest {

    private fun topology(route: LaneRoute) = LaneTopology.of(LaneEvidence.of(route))

    /** The lane ids a point lists, from the left. */
    private fun LaneTopology.idsAt(station: Double): List<Int> = points.single { it.station == station }.lanes

    private fun LaneTopology.right(lane: Int, station: Double, named: Set<Int>? = null) = fromEdge(lane, station, LaneEdge.RIGHT, named)

    // ------------------------------------------------------------------ the scenes of the step

    @Test
    fun `IA reads lanes leaving at the edges, lanes added on the far side, and the exit-only lane beside the option lane`() {
        val t = topology(LaneScenes.ia())
        val ia0 = GORE - 2_180.0
        val ia1 = GORE - 1_330.0
        val ia2a = GORE - 690.0
        val ia2b = GORE - 640.0
        assertEquals(listOf(0, 1, 2, 3, 4), t.idsAt(ia0))
        assertEquals(0.0, t.lanes[0].opens, "the first point's lanes begin where the road began, at the departure")
        // The left lane leaves at IA0 by the road on the left; IA1 lists five lanes again: one added, on the far side.
        assertEquals(ia0 + LaneTopology.LEAVE_PAST_POINT_METERS, t.lanes[0].closes)
        assertEquals(listOf(5, 1, 2, 3, 4), t.idsAt(ia1))
        val taper = LaneTopology.addedTaperMeters(27.0, RoadKind.FREEWAY)
        assertEquals(15.0 * 3.66, taper, 1e-9)
        assertEquals(ia1 - taper, t.lanes[5].opens, 1e-9, "added lanes open late: their taper ends at the point")
        assertNull(t.lanes[5].possibleFrom, "a lane added on the far side is never a possible lane")
        // The right lane leaves at IA1; IA2a adds one on the far side again; IA2b is the same junction.
        assertEquals(listOf(6, 5, 1, 2, 3), t.idsAt(ia2a))
        assertEquals(listOf(6, 5, 1, 2, 3), t.idsAt(ia2b))
        assertEquals(t.points[2].cluster, t.points[3].cluster)
        // No road leaves at IA2: its two left lanes go on, and the exit's sixth lane is added at its edge.
        assertEquals(listOf(6, 5, 1, 2, 3, 7), t.idsAt(GORE))
        val exitOnly = t.lanes[7]
        assertEquals(GORE - 210.0, exitOnly.opens, "a deceleration lane opens late: 150 m full and a 60 m taper before the gore")
        assertEquals(3, exitOnly.fedBy, "entered from the option lane along its taper")
        assertEquals(LaneTopology.DECEL_TAPER_METERS, exitOnly.taperMeters)
        assertEquals(ia2b, exitOnly.possibleFrom, "possible from the lane point before, which does not list it")
        assertEquals(
            listOf(Reason.CAREFUL to LaneEdge.LEFT, Reason.CAREFUL to LaneEdge.LEFT, Reason.ARROWS to LaneEdge.RIGHT),
            t.alignments.map { it.by to it.side },
        )
        assertEquals(setOf(3, 7), t.points.last().allowed)
        // The option lane counted from the exit's edge: the second from the right up to IA1, the right lane past it,
        // the second again from where the exit-only lane may be there, unless the plan names that lane.
        assertEquals(1, t.right(3, ia1))
        assertEquals(0, t.right(3, ia1 + 100.0, emptySet()))
        assertEquals(1, t.right(3, ia2b + 10.0, emptySet()))
        assertEquals(0, t.right(3, ia2b + 10.0, setOf(7)))
        assertEquals(0, t.right(3, ia2b + 10.0), "only the lanes that are there")
        assertEquals(1, t.right(3, GORE - 100.0, setOf(7)), "once it opens it is there, named or not")
    }

    @Test
    fun `IA's far-side variant reads the same lanes at the exit`() {
        val t = topology(LaneScenes.ia(farSide = true))
        assertEquals(listOf(0, 1, 2, 3, 4), t.idsAt(GORE - 2_180.0))
        assertEquals(GORE - 2_180.0 + LaneTopology.LEAVE_PAST_POINT_METERS, t.lanes[4].closes, "the right lane leaves at IA0")
        assertEquals(listOf(5, 0, 1, 2, 3), t.idsAt(GORE - 1_330.0))
        assertEquals(GORE - 1_330.0 + LaneTopology.LEAVE_PAST_POINT_METERS, t.lanes[5].closes, "the added left lane leaves at IA1")
        assertEquals(listOf(6, 0, 1, 2, 3, 7), t.idsAt(GORE))
        assertEquals(1, t.right(3, GORE - 2_180.0), "the lane the exit's option lane is: second from the right at IA0")
        assertEquals(0, t.right(3, GORE - 1_330.0))
    }

    @Test
    fun `IW31 reads the exit-only lane on the exit's side by its arrow, possible from the point before`() {
        val t = topology(LaneScenes.iw31())
        val p1 = GORE - 1_850.0
        val p2 = GORE - 620.0
        assertEquals(listOf(0, 1, 2, 3), t.idsAt(GORE - 2_600.0))
        assertEquals(listOf(0, 1, 2, 3), t.idsAt(p1), "every lane goes on past P0")
        // P1's left lane cannot be used, but no road leaves on the left: it goes on.
        assertEquals(listOf(0, 1, 2, 3, 4), t.idsAt(p2))
        val a5 = t.lanes[4]
        assertEquals(Reason.ARROWS, t.alignments.single().by)
        assertEquals(LaneEdge.RIGHT, t.alignments.single().side)
        assertEquals(p2 - LaneTopology.addedTaperMeters(65.0 * PlanningSpeed.MPS_PER_MPH, RoadKind.FREEWAY), a5.opens, 1e-9)
        assertEquals(p1, a5.possibleFrom)
        assertEquals(3, a5.fedBy)
        assertEquals(listOf(0, 1, 2, 3, 4), t.idsAt(GORE))
        assertEquals(setOf(3, 4), t.points.single { it.station == GORE }.allowed)
        assertEquals(setOf(3), t.points.single { it.station == GORE }.preferred)
        // Onto the ramp go the two lanes the exit can be taken from; the split lets only the option lane's.
        assertEquals(listOf(3, 4), t.idsAt(GORE + 260.0))
        assertEquals(setOf(3), t.points.last().allowed)
        assertEquals(GORE + 5.0, t.lanes[2].closes, "the lanes that go on along the freeway end at the gore")
        assertEquals(GORE + 265.0, a5.closes)
        assertEquals(GORE + 265.0, t.lanes[3].closes, "the run ends at its last point: the split")
        assertEquals(1, t.runs.size)
        // The option lane is second from the right wherever the exit-only lane may already be, unless the plan names it.
        assertEquals(0, t.right(3, p1 - 10.0, emptySet()))
        assertEquals(1, t.right(3, p1 + 10.0, emptySet()))
        assertEquals(0, t.right(3, p1 + 10.0, setOf(4)))
        assertEquals(1, t.right(3, GORE - 100.0, setOf(4)))
    }

    @Test
    fun `the interim's S20 takes the lane added between two edge points on the far side`() {
        // The right lane leaves at T1 (1,600 m) and at T2 (700 m) by roads on the right; four lanes at each.
        val route = approach(
            listOf(at(1_600.0, s(), s(), s(), sr(false), roads = EXIT_RIGHT), at(700.0, s(), s(), s(), sr(false), roads = EXIT_RIGHT)),
            exit = listOf(s(false), s(false), s(false), sr()),
        )
        val t = topology(route)
        val t1 = GORE - 1_600.0
        val t2 = GORE - 700.0
        assertEquals(listOf(0, 1, 2, 3), t.idsAt(t1))
        // T2's right lane carries the exit's arrow, but T2 has a road of its own on that side: the arrow tells nothing.
        assertEquals(listOf(4, 0, 1, 2), t.idsAt(t2))
        assertEquals(Reason.CAREFUL to LaneEdge.LEFT, t.alignments[0].by to t.alignments[0].side)
        // The exit adds its lane on the right, entered from the lane that goes on at T2's right.
        assertEquals(listOf(4, 0, 1, 5), t.idsAt(GORE))
        assertEquals(Reason.ARROWS to LaneEdge.RIGHT, t.alignments[1].by to t.alignments[1].side)
        assertEquals(1, t.lanes[5].fedBy)
        // The lane that leads to the exit is the card's careful one: third from the right at T1, second at T2.
        assertEquals(2, t.right(1, t1))
        assertEquals(1, t.right(1, t2))
        assertEquals(0, t.right(1, t2 + 100.0, setOf(5)))
        assertEquals(RunUpRule.Composed(2, false), cardAt(t1, route))
    }

    /** The interim card's composed lane at the first edge point ahead of a car just before [station]. */
    private fun cardAt(station: Double, route: LaneRoute): RunUpRule.Composed {
        val points = route.nodes.filter { it.isLanePoint && it.maneuver == null && it.station >= station }
            .map { RunUpRule.LanePoint("${it.station}", it.station - station + 1.0, it.lanes) }
        val edgePoints = RunUpRule.edgePoints(points, LaneEdge.RIGHT)
        val exit = route.nodes.single { it.station == GORE }.lanes
        return RunUpRule.compose(edgePoints, RunUpRule.edgeOffset(exit, LaneEdge.RIGHT) ?: 0, 0).first()
    }

    // ------------------------------------------------------------------ GEOM's alignments

    @Test
    fun `a car-pool lane at the same place on both lists anchors the change`() {
        // Car pool + 4, then car pool + 5 at a right exit; and car pool + 3, then car pool + 4 where nothing else tells.
        val atExit = topology(approach(listOf(at(1_000.0, hov(), s(), s(), s(), s())), listOf(hov(), s(false), s(false), s(false), s(false), sr())))
        assertEquals(Reason.ANCHOR to LaneEdge.RIGHT, atExit.alignments.single().by to atExit.alignments.single().side)
        assertEquals(0, atExit.idsAt(GORE).first(), "the car-pool lane is the same lane")
        val onTheWay = topology(approach(listOf(at(2_000.0, hov(), s(), s(), s()), at(1_000.0, hov(), s(), s(), s(), s())), listOf(hov(), s(false), s(false), s(false), sr())))
        assertEquals(Reason.ANCHOR to LaneEdge.RIGHT, onTheWay.alignments.first().by to onTheWay.alignments.first().side)
        assertTrue(onTheWay.alignments.first().explained)
    }

    @Test
    fun `a lane with only the exit's arrow at its edge is added there`() {
        val t = topology(approach(listOf(at(1_000.0, s(), s(), s(), s())), listOf(s(false), s(false), s(false), s(false), sr())))
        assertEquals(Reason.ARROWS to LaneEdge.RIGHT, t.alignments.single().by to t.alignments.single().side)
        assertEquals(listOf(0, 1, 2, 3, 4), t.idsAt(GORE))
        // A lane that also goes straight on tells nothing: the careful reading.
        val option = topology(approach(listOf(at(1_000.0, s(), s(), s(), s())), listOf(s(false), s(false), s(false), option(), option())))
        assertEquals(Reason.CAREFUL to LaneEdge.LEFT, option.alignments.single().by to option.alignments.single().side)
    }

    @Test
    fun `a left pocket at a street turn is added on the left and opens late`() {
        val street = RoadKind.STREET
        val streetMps = 30.0 * PlanningSpeed.MPS_PER_MPH
        val route = route(
            node(0.0, road = street, maneuver = LaneScenes.DEPART, planMps = streetMps),
            node(800.0, s(), s(), s(), road = street, planMps = streetMps),
            node(1_000.0, left(), s(false), s(false), s(false), road = street, maneuver = LaneScenes.TURN_LEFT, planMps = streetMps),
            node(1_300.0, road = street, maneuver = LaneScenes.ARRIVE, planMps = streetMps),
        )
        val t = topology(route)
        assertEquals(Reason.ARROWS to LaneEdge.LEFT, t.alignments.single().by to t.alignments.single().side)
        assertEquals(listOf(3, 0, 1, 2), t.idsAt(1_000.0))
        val pocket = t.lanes[3]
        assertEquals(1_000.0 - 55.0, pocket.opens, "full width 30 m before a stop line 10 m before the node, after a 15 m taper")
        assertEquals(0, pocket.fedBy)
        assertEquals(LaneTopology.POCKET_TAPER_METERS, pocket.taperMeters)
        assertTrue(pocket.pocket)
        // It may already be there at full width 30 m before the stop line at 30 mph, after an 8:1 taper, well inside half the
        // way back to the point 200 m before it.
        assertEquals(1_000.0 - 10.0 - 30.0 - 8.0 * 3.35, requireNotNull(pocket.possibleFrom), 1e-9)
        assertTrue(t.lanes.filter { it.id != 3 }.none { it.pocket })
    }

    /** The turn pocket of [t]: the one lane a street turn's point adds at the turn's side. */
    private fun pocketOf(t: LaneTopology): LaneTopology.Lane = t.lanes.single { it.pocket }

    @Test
    fun `a turn pocket may already be there at full width 30, 45 or 75 m before the stop line, by the planning speed`() {
        val mps = PlanningSpeed.MPS_PER_MPH
        // Both sides of 30 mph and of 45 mph.
        assertEquals(30.0, LaneTopology.pocketPossibleFullMeters(30.0 * mps))
        assertEquals(45.0, LaneTopology.pocketPossibleFullMeters(31.0 * mps))
        assertEquals(45.0, LaneTopology.pocketPossibleFullMeters(44.0 * mps))
        assertEquals(75.0, LaneTopology.pocketPossibleFullMeters(45.0 * mps))
        assertEquals(75.0, LaneTopology.pocketPossibleFullMeters(60.0 * mps))
        // Before that an added lane's taper, 8:1 below 50 mph and 15:1 from 50; the stop line 10 m before the node.
        val slowTaper = 8.0 * 3.35
        val fastTaper = 15.0 * 3.35
        for ((mph, from) in listOf(
            30.0 to GORE - 10.0 - 30.0 - slowTaper,
            31.0 to GORE - 10.0 - 45.0 - slowTaper,
            44.0 to GORE - 10.0 - 45.0 - slowTaper,
            45.0 to GORE - 10.0 - 75.0 - slowTaper,
            49.0 to GORE - 10.0 - 75.0 - slowTaper,
            50.0 to GORE - 10.0 - 75.0 - fastTaper,
        )) {
            val pocket = pocketOf(topology(LaneScenes.pocketAt(mph)))
            assertEquals(from, requireNotNull(pocket.possibleFrom) { "no possible part at $mph mph" }, 1e-9, "at $mph mph")
            assertTrue(requireNotNull(pocket.possibleFrom) < pocket.opens)
        }
    }

    @Test
    fun `a turn pocket opens late, full width 30 m before the stop line after 15 m up to 49 mph and 45 m after 30 m from 50`() {
        for ((mph, late) in listOf(25.0 to (55.0 to 15.0), 45.0 to (55.0 to 15.0), 49.0 to (55.0 to 15.0), 50.0 to (85.0 to 30.0), 55.0 to (85.0 to 30.0))) {
            val pocket = pocketOf(topology(LaneScenes.pocketAt(mph)))
            assertEquals(GORE - late.first, pocket.opens, 1e-9, "at $mph mph")
            assertEquals(late.second, pocket.taperMeters, 1e-9, "at $mph mph")
            assertEquals(0, pocket.fedBy, "entered from the left lane")
        }
        assertEquals(30.0, LaneTopology.POCKET_FULL_LATE_METERS)
        assertEquals(15.0, LaneTopology.POCKET_TAPER_METERS)
        assertEquals(45.0, LaneTopology.POCKET_FULL_LATE_FAST_METERS)
        assertEquals(30.0, LaneTopology.POCKET_TAPER_FAST_METERS)
    }

    @Test
    fun `a turn pocket reaches back no more than half the way to the junction before it, and never past its far side`() {
        val v = 35.0 * PlanningSpeed.MPS_PER_MPH
        val rule = 1_000.0 - 10.0 - 45.0 - 8.0 * 3.35
        // Half the way: 160 m back it holds the pocket, 170 m back the rule does.
        assertEquals(1_000.0 - 80.0, LaneTopology.pocketPossibleFrom(1_000.0, v, 1_000.0 - 160.0), 1e-9)
        assertEquals(rule, LaneTopology.pocketPossibleFrom(1_000.0, v, 1_000.0 - 170.0), 1e-9)
        // The far side, 10 m past the junction's node: 15 m back it holds, 25 m back half the way does.
        assertEquals(1_000.0 - 5.0, LaneTopology.pocketPossibleFrom(1_000.0, v, 1_000.0 - 15.0), 1e-9)
        assertEquals(1_000.0 - 12.5, LaneTopology.pocketPossibleFrom(1_000.0, v, 1_000.0 - 25.0), 1e-9)
        // On a route: a junction without lane data 160 m or 170 m before the turn.
        assertEquals(GORE - 80.0, requireNotNull(pocketOf(topology(LaneScenes.pocketAt(35.0, 160.0))).possibleFrom), 1e-9)
        assertEquals(GORE - 1_000.0 + rule, requireNotNull(pocketOf(topology(LaneScenes.pocketAt(35.0, 170.0))).possibleFrom), 1e-9)
        // Its late part never opens inside that junction either: 60 m back its far side holds the pocket's start; 70 m back the
        // late rule does. Half the way back lies past where it opens there: no possible part.
        val near = pocketOf(topology(LaneScenes.pocketAt(35.0, 60.0)))
        assertEquals(GORE - 50.0, near.opens, 1e-9)
        assertNull(near.possibleFrom)
        val farther = pocketOf(topology(LaneScenes.pocketAt(35.0, 70.0)))
        assertEquals(GORE - 55.0, farther.opens, 1e-9)
        assertNull(farther.possibleFrom)
        assertEquals(0.5, LaneTopology.POCKET_POSSIBLE_SHARE)
        assertEquals(LaneTopology.STOP_LINE_METERS, LaneTopology.FAR_SIDE_METERS)
    }

    @Test
    fun `where nothing tells, an added lane is on the far side and a lane gone is at the exit's edge, early`() {
        val gained = topology(approach(listOf(at(2_000.0, s(), s(), s()), at(1_000.0, s(), s(), s(), s())), listOf(s(false), s(false), s(false), sr(), sr())))
        assertEquals(Reason.CAREFUL to LaneEdge.LEFT, gained.alignments.first().by to gained.alignments.first().side)
        assertFalse(gained.alignments.first().explained)
        val left = topology(approach(listOf(at(2_000.0, s(), s(), s(), s()), at(1_000.0, s(), s(), s())), listOf(s(false), sl(), s(false)), maneuver = LaneScenes.OFF_LEFT))
        assertEquals(Reason.CAREFUL to LaneEdge.LEFT, left.alignments.first().by to left.alignments.first().side, "gone at a left exit's edge")
        assertEquals(GORE - 2_000.0 + LaneTopology.VANISH_EARLY_METERS, left.lanes[0].closes)
        assertEquals(listOf(1, 2, 3), left.idsAt(GORE - 1_000.0))
    }

    @Test
    fun `a road that joins or leaves on one side on the way tells the side`() {
        val joined = topology(approach(listOf(at(2_000.0, s(), s(), s()), bare(1_500.0, JOIN_RIGHT), at(1_000.0, s(), s(), s(), s())), listOf(s(false), s(false), s(false), sr(), sr())))
        assertEquals(Reason.NODE to LaneEdge.RIGHT, joined.alignments.first().by to joined.alignments.first().side)
        assertEquals(listOf(0, 1, 2, 3), joined.idsAt(GORE - 1_000.0))
        val leftExit = topology(approach(listOf(at(2_000.0, s(), s(), s(), s()), bare(1_500.0, EXIT_LEFT), at(1_000.0, s(), s(), s())), listOf(s(false), s(false), sr())))
        assertEquals(Reason.NODE to LaneEdge.LEFT, leftExit.alignments.first().by to leftExit.alignments.first().side)
        assertEquals(GORE - 1_500.0 + LaneTopology.LEAVE_PAST_POINT_METERS, leftExit.lanes[0].closes, "it leaves at the road that explains it")
    }

    // ------------------------------------------------------------------ what goes on past a point

    @Test
    fun `at the exit's edge lanes the route cannot be followed from leave, at the far edge only with a road`() {
        val t = topology(approach(listOf(at(1_500.0, s(false), s(), s(), s(false)), at(800.0, s(), s(), s())), listOf(s(false), s(false), sr())))
        assertEquals(GORE - 1_500.0 + LaneTopology.LEAVE_PAST_POINT_METERS, t.lanes[3].closes, "the right lane leaves: the card's careful reading")
        assertEquals(listOf(0, 1, 2), t.idsAt(GORE - 800.0), "the left lane goes on: no road leaves on its side")
        val withRoad = topology(approach(listOf(at(1_500.0, s(false), s(), s(), s(false), roads = EXIT_LEFT), at(800.0, s(), s())), listOf(s(false), sr())))
        assertEquals(listOf(1, 2), withRoad.idsAt(GORE - 800.0))
        // A point that lets no lane be used says nothing: every lane goes on.
        val unmarked = topology(approach(listOf(at(1_500.0, s(false), s(false), s(false)), at(800.0, s(), s(), s())), listOf(s(false), s(false), sr())))
        assertEquals(listOf(0, 1, 2), unmarked.idsAt(GORE - 800.0))
        assertFalse(unmarked.points.first().constrains)
    }

    @Test
    fun `on a street a lane kept for buses or bicycles at the far edge goes on past a crossing, on a freeway it leaves`() {
        val bike = LaneScenes.bike()
        // A left turn ahead: the right edge is the far one, where the lane only for bicycles lies and a road crosses.
        val left = topology(
            route(
                LaneScenes.street(0.0, maneuver = LaneScenes.DEPART),
                LaneScenes.street(GORE - 400.0, s(), s(), bike, roads = LaneScenes.CROSSING),
                LaneScenes.street(GORE - 200.0, s(), s(), bike),
                LaneScenes.street(GORE, left(), s(false), bike, roads = LaneScenes.CROSSING, maneuver = LaneScenes.TURN_LEFT),
                LaneScenes.street(GORE + 300.0, maneuver = LaneScenes.ARRIVE),
            ),
        )
        val strip = left.idsAt(GORE - 400.0).last()
        assertEquals(strip, left.idsAt(GORE - 200.0).last(), "the same lane at the next point")
        assertEquals(strip, left.idsAt(GORE).last())
        // A right turn ahead: the left edge is the far one, with a lane kept for buses.
        val bus = LaneScenes.bus()
        val right = topology(
            route(
                LaneScenes.street(0.0, maneuver = LaneScenes.DEPART),
                LaneScenes.street(GORE - 400.0, bus, s(), s(), roads = LaneScenes.CROSSING),
                LaneScenes.street(GORE - 200.0, bus, s(), s()),
                LaneScenes.street(GORE, bus, s(false), LaneScenes.right(), roads = LaneScenes.CROSSING, maneuver = LaneScenes.TURN_RIGHT),
                LaneScenes.street(GORE + 300.0, maneuver = LaneScenes.ARRIVE),
            ),
        )
        val kept = right.idsAt(GORE - 400.0).first()
        assertEquals(listOf(kept, kept), listOf(right.idsAt(GORE - 200.0).first(), right.idsAt(GORE).first()))
        assertTrue(right.lanes[kept].closes > GORE)
        // On a freeway a lane kept for car pools at the far edge leaves by the road on its side, as every unmarked lane there.
        val freeway = topology(approach(listOf(at(1_500.0, hov(), s(), s(), roads = EXIT_LEFT), at(800.0, hov(), s(), s())), listOf(s(false), s(false), sr())))
        assertEquals(GORE - 1_500.0 + LaneTopology.LEAVE_PAST_POINT_METERS, freeway.lanes[0].closes)
        // The rule itself: lanes kept for other vehicles at the far edge stay on a street only.
        val lanes = listOf(bus, s(false), s(), s(), bike)
        val usable = BooleanArray(lanes.size) { lanes[it].usable }
        assertEquals(listOf(0, 2, 3, 4), LaneTopology.goingOn(lanes, usable, null, branchLeft = true, branchRight = true, street = true))
        assertEquals(listOf(2, 3), LaneTopology.goingOn(lanes, usable, null, branchLeft = true, branchRight = true, street = false))
    }

    @Test
    fun `a lane kept for car pools at the exit's edge stays while the open lanes beside it leave`() {
        val lanes = RunUpRuleLanes.keptAtEdge()
        val kept = LaneTopology.goingOn(lanes, BooleanArray(lanes.size) { lanes[it].usable }, LaneEdge.RIGHT, branchLeft = false, branchRight = false)
        assertEquals(listOf(0, 1, 3), kept)
    }

    /** Lanes for the goingOn cases. */
    private object RunUpRuleLanes {
        /** Two lanes that can be used, an open one that cannot, and a car-pool lane at the right edge. */
        fun keptAtEdge(): List<RouteLane> = listOf(s(), s(), s(false), hov())
    }

    // ------------------------------------------------------------------ clusters and runs

    @Test
    fun `points no more than 100 m apart with the same lanes are one junction`() {
        fun scene(gap: Double) = topology(
            approach(listOf(at(1_500.0, s(), s(), s(), s(false)), at(1_500.0 - gap, s(), s(), s(), s())), listOf(s(false), s(false), sr())),
        )
        val within = scene(RunUpRule.EDGE_CLUSTER_METERS)
        assertEquals(within.points[0].cluster, within.points[1].cluster)
        assertEquals(0, within.alignments.size, "three lanes go on, and the exit lists three")
        assertEquals(within.points[0].lanes, within.points[1].lanes)
        assertEquals(GORE - 1_500.0 + RunUpRule.EDGE_CLUSTER_METERS + LaneTopology.LEAVE_PAST_POINT_METERS, within.lanes[3].closes, "the lane leaves after the last point of the junction")
        val apart = scene(RunUpRule.EDGE_CLUSTER_METERS + 1.0)
        assertNotEquals(apart.points[0].cluster, apart.points[1].cluster)
        assertEquals(GORE - 1_500.0 + LaneTopology.LEAVE_PAST_POINT_METERS, apart.lanes[3].closes)
        assertEquals(2, apart.alignments.size, "then the second point's fourth lane is one added, and one goes before the exit")
        // The exit is never part of a junction with a point before it.
        val beforeExit = topology(approach(listOf(at(50.0, s(), s(), sr())), listOf(s(false), s(false), sr())))
        assertNotEquals(beforeExit.points[0].cluster, beforeExit.points[1].cluster)
    }

    @Test
    fun `a turn without lane data ends a run and an exit's lanes go on onto its ramp`() {
        val streetMps = 30.0 * PlanningSpeed.MPS_PER_MPH
        val route = route(
            node(0.0, maneuver = LaneScenes.DEPART, planMps = 27.0),
            node(1_000.0, s(), s(), s(), planMps = 27.0),
            node(2_000.0, road = RoadKind.STREET, maneuver = LaneScenes.TURN_RIGHT, planMps = streetMps),
            node(2_200.0, s(), s(), road = RoadKind.STREET, planMps = streetMps),
            node(2_400.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = streetMps),
        )
        val t = topology(route)
        assertEquals(2, t.runs.size)
        assertEquals(0.0, t.runs[0].from)
        assertEquals(1_005.0, t.runs[0].to, "a run ends at its last point: past it nothing is known of its lanes")
        assertEquals(2_000.0, t.runs[1].from, "the second run's lanes begin at the turn")
        assertEquals(listOf(3, 4), t.idsAt(2_200.0))
        assertTrue(t.lanes.filter { it.run == 0 }.all { it.closes == 1_005.0 })
        // An exit with lanes keeps one run: see IW31's ramp; one whose lanes none can use ends it.
        val none = topology(
            approach(
                listOf(at(1_000.0, s(), s(), s())),
                listOf(s(false), s(false), s(false)),
                rampPoints = listOf(200.0 to LaneScenes.NodeAndLanes(listOf(s(), s()))),
            ),
        )
        assertEquals(2, none.runs.size)
    }

    // ------------------------------------------------------------------ counts and odd marks

    @Test
    fun `lanes kept for car pools are not counted from the edge`() {
        val t = topology(approach(listOf(at(1_000.0, s(), s(), hov())), listOf(s(false), sr(), hov())))
        assertEquals(0, t.right(1, GORE - 1_000.0), "the car-pool lane at the edge is passed over")
        assertEquals(1, t.fromEdge(1, GORE - 1_000.0, LaneEdge.LEFT))
        assertNull(t.fromEdge(99, GORE - 1_000.0, LaneEdge.LEFT), "no such lane")
        assertNull(t.fromEdge(1, GORE + 5_000.0, LaneEdge.LEFT), "not there")
    }

    @Test
    fun `odd marks are counted where the plan reads them`() {
        val preferredUnusable = s(false, active = true)
        val leftArrow = RouteLane(setOf(TurnModifier.SLIGHT_LEFT), valid = true, active = true)
        val t = topology(approach(listOf(at(1_000.0, s(), preferredUnusable, s())), listOf(s(false), s(false), leftArrow, sr())))
        assertEquals(1, t.points[0].activeOutsideValid)
        assertFalse(1 in t.points[0].preferred, "its preference is ignored")
        assertEquals(1, t.points.last().arrowContradicts, "a lane for a right exit whose one arrow points left")
        assertEquals(0, t.points[0].arrowContradicts, "only at the maneuver")
    }

    // ------------------------------------------------------------------ tapers (design 4.3)

    @Test
    fun `an added lane's taper is 8 to 1 below 50 mph and 15 to 1 from it`() {
        val below = (LaneTopology.FAST_FROM_MPH - 0.1) * PlanningSpeed.MPS_PER_MPH
        val from = LaneTopology.FAST_FROM_MPH * PlanningSpeed.MPS_PER_MPH
        assertEquals(8.0 * 3.66, LaneTopology.addedTaperMeters(below, RoadKind.FREEWAY), 1e-9)
        assertEquals(15.0 * 3.66, LaneTopology.addedTaperMeters(from, RoadKind.FREEWAY), 1e-9)
        assertEquals(8.0 * 3.35, LaneTopology.addedTaperMeters(below, RoadKind.STREET), 1e-9)
        assertEquals(15.0 * 3.35, LaneTopology.addedTaperMeters(from, RoadKind.STREET), 1e-9)
    }

    @Test
    fun `a lane that ends tapers over the width times the speed from 45 mph, at least 30 m`() {
        fun at(mph: Double) = LaneTopology.endingTaperMeters(mph * PlanningSpeed.MPS_PER_MPH, RoadKind.FREEWAY)
        // The design's table, for 12 ft lanes: 38 / 75 / 165 / 201 / 238 / 274 m at 25 / 35 / 45 / 55 / 65 / 75 mph; the
        // drawing's 3.66 m lane is a little wider than 12 ft, so within a metre.
        val table = listOf(25.0 to 38.0, 35.0 to 75.0, 45.0 to 165.0, 55.0 to 201.0, 65.0 to 238.0, 75.0 to 274.0)
        for ((mph, meters) in table) assertEquals(meters, at(mph), 1.0, "$mph mph")
        assertEquals(123.0, at(LaneTopology.ENDING_FORMULA_FROM_MPH - 0.1), 1.0, "below 45 mph: the width times the speed squared over 60")
        assertEquals(165.0, at(LaneTopology.ENDING_FORMULA_FROM_MPH), 1.0, "from 45 mph: the width times the speed")
        assertEquals(30.0, at(10.0), 1e-9, "never under 30 m")
        assertEquals(LaneTopology.ENDING_TAPER_MIN_METERS, LaneTopology.endingTaperMeters(0.0, RoadKind.STREET))
    }
}
