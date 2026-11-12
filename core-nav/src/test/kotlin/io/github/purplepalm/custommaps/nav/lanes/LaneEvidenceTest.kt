package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence.Kind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence.Kind.BRIDGED
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence.Kind.KNOWN
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence.Kind.NONE
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up routes only: stations in metres from the route's start, round synthetic numbers, no place.
 * Lanes are written from the left: s for straight on, sr and sl for slight right and left, H for a
 * lane kept for car pools; a lane the route cannot be followed from is written with false.
 */
class LaneEvidenceTest {

    private val straight = TurnModifier.STRAIGHT
    private val slightRight = TurnModifier.SLIGHT_RIGHT
    private val slightLeft = TurnModifier.SLIGHT_LEFT

    private fun s(valid: Boolean = true) = RouteLane(setOf(straight), valid = valid)
    private fun sr(valid: Boolean = true) = RouteLane(setOf(slightRight), valid = valid)
    private fun sl(valid: Boolean = true) = RouteLane(setOf(slightLeft), valid = valid)
    private fun option(valid: Boolean = true) = RouteLane(setOf(straight, slightRight), valid = valid)
    private fun hov(valid: Boolean = false) = RouteLane(setOf(straight), valid = valid, designated = true)

    /**
     * The roads at an intersection other than the two the route takes: it comes in from the south
     * (bearing 180) and leaves to the north (0). Each is its bearing and whether a car may take it.
     */
    private class Roads(vararg val others: Pair<Int, Boolean>)

    private val through = Roads()
    private val exitRight = Roads(30 to true)
    private val exitLeft = Roads(330 to true)
    private val bothSides = Roads(30 to true, 330 to true)
    private val joinRight = Roads(160 to false)

    private fun node(
        at: Double,
        vararg lanes: RouteLane,
        roads: Roads = through,
        road: RoadKind = RoadKind.FREEWAY,
        maneuver: NodeManeuver? = null,
        kinds: List<Set<DesignatedKind>> = emptyList(),
    ) = RouteNode(
        station = at,
        lanes = lanes.toList(),
        laneKinds = kinds,
        bearings = listOf(180, 0) + roads.others.map { it.first },
        entry = listOf(false, true) + roads.others.map { it.second },
        inIndex = 0,
        outIndex = 1,
        freeway = road == RoadKind.FREEWAY,
        ramp = road == RoadKind.RAMP,
        maneuver = maneuver,
    )

    private val depart = NodeManeuver(ManeuverType.DEPART)
    private val arrive = NodeManeuver(ManeuverType.ARRIVE)
    private val offRampRight = NodeManeuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true)
    private val offRampLeft = NodeManeuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_LEFT, viewStaysUp = true)

    private fun start(road: RoadKind = RoadKind.FREEWAY) = node(0.0, road = road, maneuver = depart)

    private fun route(vararg nodes: RouteNode, length: Double = nodes.last().station) =
        LaneRoute("route-1", steps = nodes.count { it.maneuver != null }, lengthMeters = length, nodes = nodes.toList())

    /** The evidence without card windows, unless given. */
    private fun evidence(route: LaneRoute, windows: List<CardWindow> = emptyList()) = LaneEvidence.of(route, windows)

    /** The stretches as (from, to, kind, lanes), rounded to the centimetre. */
    private fun LaneEvidence.drawn(): List<String> =
        stretches.map { "${round(it.from)}..${round(it.to)} ${it.kind}" + if (it.kind == NONE) "" else " ${it.lanes}" }

    private fun round(meters: Double): String = String.format(java.util.Locale.ROOT, "%.0f", meters)

    private fun LaneEvidence.meters(kind: Kind): Double = stretches.filter { it.kind == kind }.sumOf { it.meters }

    // ------------------------------------------------------------------ rules 1 and 2: a point and its reach

    @Test
    fun `a lane point holds at its node and reaches back to the intersection before it`() {
        val lanes = arrayOf(s(), s(), s())
        val route = route(start(), node(4_000.0), node(4_600.0, *lanes), node(6_000.0, maneuver = arrive))
        val evidence = evidence(route)
        assertEquals(listOf(2), evidence.lanePoints)
        assertEquals(listOf("0..4000 NONE", "4000..4600 KNOWN 3", "4600..6000 NONE"), evidence.drawn())
        assertEquals(2, evidence.stretches[1].point, "the point whose lanes they are")
    }

    @Test
    fun `on a freeway the reach is 1,000 m at most`() {
        val inside = evidence(route(start(), node(999.0, s(), s()), node(2_000.0, maneuver = arrive)))
        assertEquals(listOf("0..999 KNOWN 2", "999..2000 NONE"), inside.drawn())
        val beyond = evidence(route(start(), node(1_001.0, s(), s()), node(2_000.0, maneuver = arrive)))
        assertEquals(listOf("0..1 NONE", "1..1001 KNOWN 2", "1001..2000 NONE"), beyond.drawn())
        assertEquals(1_000.0, beyond.meters(KNOWN), 1e-9)
    }

    @Test
    fun `on a street the reach is 250 m at most`() {
        val street = RoadKind.STREET
        val inside = evidence(route(start(street), node(249.0, s(), s(), road = street), node(900.0, road = street, maneuver = arrive)))
        assertEquals(249.0, inside.meters(KNOWN), 1e-9)
        val beyond = evidence(route(start(street), node(251.0, s(), s(), road = street), node(900.0, road = street, maneuver = arrive)))
        assertEquals(250.0, beyond.meters(KNOWN), 1e-9)
        assertEquals(listOf("0..1 NONE", "1..251 KNOWN 2", "251..900 NONE"), beyond.drawn())
    }

    @Test
    fun `a ramp reaches as far as a freeway`() {
        val ramp = RoadKind.RAMP
        val evidence = evidence(route(start(ramp), node(900.0, s(), road = ramp), node(1_000.0, road = ramp, maneuver = arrive)))
        assertEquals(900.0, evidence.meters(KNOWN), 1e-9)
    }

    @Test
    fun `the reach is the road's that leads to the point`() {
        // A street before the intersection, a freeway after it: the point's reach is the freeway's.
        val evidence = evidence(route(start(RoadKind.STREET), node(100.0), node(800.0, s(), s()), node(1_000.0, maneuver = arrive)))
        assertEquals(listOf("0..100 NONE", "100..800 KNOWN 2", "800..1000 NONE"), evidence.drawn())
        // A street leads to the point and a freeway leaves it: the street's 250 m, not the freeway's.
        val street = RoadKind.STREET
        val onto = evidence(route(start(street), node(100.0, road = street), node(700.0, s(), s()), node(1_000.0, maneuver = arrive)))
        assertEquals(listOf("0..450 NONE", "450..700 KNOWN 2", "700..1000 NONE"), onto.drawn())
    }

    // ------------------------------------------------------------------ rule 3: past a point nothing is known

    @Test
    fun `past a lane point nothing is known until the next point's reach`() {
        val route = route(start(), node(1_000.0, s(), s(), s()), node(3_000.0, s(), s(), s(), s()), node(4_000.0, maneuver = arrive))
        assertEquals(
            listOf("0..1000 KNOWN 3", "1000..2000 NONE", "2000..3000 KNOWN 4", "3000..4000 NONE"),
            evidence(route).drawn(),
        )
    }

    // ------------------------------------------------------------------ rule 4: bridges

    @Test
    fun `agreeing points are bridged across a road that joins`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(listOf("0..1000 KNOWN 4", "1000..2500 BRIDGED 4", "2500..3500 KNOWN 4", "3500..5000 NONE"), evidence.drawn())
        assertEquals(1, evidence.stretches[1].point, "a bridge's lanes are the first point's, going on")
        assertTrue(evidence.gaps.isEmpty())
        assertTrue(evidence.changes.isEmpty())
        assertTrue(evidence.unknownExits.isEmpty())
    }

    @Test
    fun `agreeing points are bridged across a road leaving on the exit's side, an unknown exit`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = exitRight),
            node(3_500.0, s(), s(), s(), s()),
            node(4_500.0, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(4_900.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(BRIDGED, evidence.stretches[1].kind)
        assertEquals(1_500.0, evidence.meters(BRIDGED), 1e-9)
        val exit = evidence.unknownExits.single()
        assertEquals(2, exit.node)
        assertEquals(2_000.0, exit.station)
        assertEquals(LaneEdge.RIGHT, exit.edge)
    }

    @Test
    fun `a road leaving on the far side of the exit ends the bridge`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = exitLeft),
            node(3_500.0, s(), s(), s(), s()),
            node(4_500.0, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(4_900.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(0.0, evidence.meters(BRIDGED))
        assertTrue(evidence.unknownExits.isEmpty())
        val gap = evidence.gaps.single()
        assertEquals(1_000.0, gap.from)
        assertEquals(2_500.0, gap.to)
        // Roads that leave on both sides end it as well.
        val both = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = bothSides),
            node(3_500.0, s(), s(), s(), s()),
            node(4_500.0, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(4_900.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        assertEquals(1, evidence(both).gaps.size)
    }

    @Test
    fun `a left exit bridges across roads leaving on the left`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = exitLeft),
            node(3_500.0, s(), s(), s(), s()),
            node(4_500.0, sl(), s(false), s(false), s(false), road = RoadKind.RAMP, maneuver = offRampLeft),
            node(4_900.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(LaneEdge.LEFT, evidence.unknownExits.single().edge)
        assertTrue(evidence.gaps.isEmpty())
    }

    @Test
    fun `without a side for the next exit only roads that join are bridged across`() {
        // The arrival has no side: a road that leaves on either side ends the bridge.
        fun withRoads(roads: Roads) = route(
            start(),
            node(1_000.0, s(), s(), s()),
            node(2_000.0, roads = roads),
            node(3_500.0, s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        assertEquals(1_500.0, evidence(withRoads(joinRight)).meters(BRIDGED), 1e-9)
        assertEquals(0.0, evidence(withRoads(exitRight)).meters(BRIDGED))
        assertEquals(0.0, evidence(withRoads(exitLeft)).meters(BRIDGED))
    }

    @Test
    fun `points with different counts are not bridged`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s()),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(0.0, evidence.meters(BRIDGED))
        assertEquals(1_500.0, evidence.gaps.single().meters, 1e-9)
    }

    @Test
    fun `points whose car-pool lanes differ are not bridged`() {
        val route = route(
            start(),
            node(1_000.0, hov(), s(), s(), s()),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(0.0, evidence.meters(BRIDGED))
        assertEquals(1, evidence.gaps.size)
        assertTrue(evidence.changes.isEmpty(), "as many lanes at both: no count change")
    }

    @Test
    fun `a node where the route turns is never bridged across`() {
        val turn = NodeManeuver(ManeuverType.TURN, TurnModifier.RIGHT)
        val route = route(
            start(),
            node(1_000.0, s(), s(), s()),
            node(2_000.0, road = RoadKind.STREET, maneuver = turn),
            node(2_100.0, s(), s(), s(), road = RoadKind.STREET),
            node(3_000.0, road = RoadKind.STREET, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(0.0, evidence.meters(BRIDGED))
        assertTrue(evidence.gaps.isEmpty(), "two carriageways: no gap between them")
        assertEquals(listOf("0..1000 KNOWN 3", "1000..2000 NONE", "2000..2100 KNOWN 3", "2100..3000 NONE"), evidence.drawn())
    }

    @Test
    fun `the route turns at every maneuver but continue, new name, merge and notification`() {
        for (type in ManeuverType.entries) {
            val route = route(
                start(),
                node(1_000.0, s(), s(), s()),
                node(2_000.0, maneuver = NodeManeuver(type)),
                node(3_500.0, s(), s(), s()),
                node(5_000.0, maneuver = arrive),
            )
            val goesOn = type in setOf(ManeuverType.CONTINUE, ManeuverType.NEW_NAME, ManeuverType.MERGE, ManeuverType.NOTIFICATION)
            assertEquals(!goesOn, LaneEvidence.turnsAt(route.nodes[2]), "$type")
            assertEquals(if (goesOn) 1_500.0 else 0.0, evidence(route).meters(BRIDGED), 1e-9, "$type")
        }
        assertFalse(LaneEvidence.turnsAt(node(0.0)), "an intersection the route goes through")
    }

    @Test
    fun `a point where the route turns starts no bridge`() {
        // The fork's own lanes are those of its approach: past it the route is on another road.
        val fork = NodeManeuver(ManeuverType.FORK, TurnModifier.SLIGHT_LEFT, viewStaysUp = true)
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), maneuver = fork),
            node(3_500.0, s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(0.0, evidence.meters(BRIDGED))
        assertTrue(evidence.gaps.isEmpty())
        assertTrue(evidence.changes.isEmpty())
    }

    @Test
    fun `a street bridge reaches 399 m but not 401 m`() {
        val street = RoadKind.STREET
        fun apart(meters: Double) = route(
            start(street),
            node(1_000.0, s(), s(), road = street),
            node(1_200.0, roads = joinRight, road = street),
            node(1_000.0 + meters, s(), s(), road = street),
            node(2_000.0, road = street, maneuver = arrive),
        )
        assertEquals(200.0, evidence(apart(399.0)).meters(BRIDGED), 1e-9)
        assertEquals(200.0, evidence(apart(400.0)).meters(BRIDGED), 1e-9, "at most 400 m")
        val far = evidence(apart(401.0))
        assertEquals(0.0, far.meters(BRIDGED))
        assertEquals(200.0, far.gaps.single().meters, 1e-9)
        // On a freeway the same points are bridged however far apart.
        val freeway = route(start(), node(1_000.0, s(), s()), node(2_000.0, roads = joinRight), node(4_500.0, s(), s()), node(5_000.0, maneuver = arrive))
        assertEquals(2_500.0, evidence(freeway).meters(BRIDGED), 1e-9)
    }

    // ------------------------------------------------------------------ rule 6: lanes that leave at a point

    @Test
    fun `lanes the route cannot be followed from leave only where a road may be entered on their side`() {
        fun withRoads(roads: Roads) = route(
            start(),
            node(1_000.0, s(), s(), s(), s(), sr(false), roads = roads),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        assertEquals(4, LaneEvidence.goingOn(withRoads(exitRight).nodes[1]).size)
        assertEquals(1_500.0, evidence(withRoads(exitRight)).meters(BRIDGED), 1e-9, "the right lane left: four go on")
        assertEquals(5, LaneEvidence.goingOn(withRoads(through).nodes[1]).size, "no road to leave by: a constraint only")
        assertEquals(5, LaneEvidence.goingOn(withRoads(exitLeft).nodes[1]).size, "a road on the other side")
        val stays = evidence(withRoads(through))
        assertEquals(0.0, stays.meters(BRIDGED))
        val change = stays.changes.single()
        assertEquals(5, change.lanesBefore)
        assertEquals(4, change.lanesAfter)
        assertFalse(change.explained, "a lane is lost and no road leaves")
    }

    @Test
    fun `lanes leave at the left edge by a road on the left`() {
        val point = node(1_000.0, sl(false), sl(false), s(), s(), roads = exitLeft)
        assertEquals(listOf(s(), s()), LaneEvidence.goingOn(point))
        val bothEdges = node(1_000.0, sl(false), s(), s(), sr(false), roads = bothSides)
        assertEquals(listOf(s(), s()), LaneEvidence.goingOn(bothEdges))
    }

    @Test
    fun `a point that marks no lane says nothing of where its lanes go`() {
        val point = node(1_000.0, s(false), s(false), s(false), roads = bothSides)
        assertEquals(3, LaneEvidence.goingOn(point).size)
        val unflagged = node(1_000.0, RouteLane(setOf(straight)), RouteLane(setOf(straight)), roads = bothSides)
        assertEquals(2, LaneEvidence.goingOn(unflagged).size, "neither valid nor active given")
    }

    @Test
    fun `lanes in the middle that cannot be used go on`() {
        val point = node(1_000.0, s(), s(false), s(), roads = bothSides)
        assertEquals(3, LaneEvidence.goingOn(point).size)
    }

    // ------------------------------------------------------------------ rules 5 and 2a: count changes and added lanes

    @Test
    fun `a gain where a road joins is explained and its lane is an added lane`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s()),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        val change = evidence(route).changes.single()
        assertEquals(1, change.before)
        assertEquals(3, change.after)
        assertTrue(change.byNode)
        assertTrue(change.explained)
        val added = evidence(route).added.single()
        assertEquals(3, added.point)
        assertEquals(1, added.previous)
        assertEquals(1, added.lanes)
        assertNull(added.side, "a straight arrow tells no side")
    }

    @Test
    fun `a gain where no road joins and no arrow tells the side is unexplained`() {
        val route = route(start(), node(1_000.0, s(), s(), s()), node(3_500.0, s(), s(), s(), s()), node(5_000.0, maneuver = arrive))
        val change = evidence(route).changes.single()
        assertFalse(change.byNode)
        assertNull(change.side)
        assertFalse(change.explained)
    }

    @Test
    fun `a loss where a road leaves is explained, and unexplained where none does`() {
        fun withRoads(roads: Roads) = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = roads),
            node(3_500.0, s(), s(), s()),
            node(5_000.0, maneuver = arrive),
        )
        assertTrue(evidence(withRoads(exitRight)).changes.single().explained)
        assertTrue(evidence(withRoads(exitLeft)).changes.single().explained)
        assertFalse(evidence(withRoads(joinRight)).changes.single().explained, "a road that joins explains no loss")
        assertTrue(evidence(withRoads(exitRight)).added.isEmpty(), "no lane added")
    }

    @Test
    fun `a lane the exit's arrows put at its edge is an added lane on the exit's side`() {
        val route = route(
            start(),
            node(1_000.0, s(), s(), s(), s()),
            node(3_500.0, s(false), s(false), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(3_800.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val evidence = evidence(route)
        val added = evidence.added.single()
        assertEquals(2, added.point)
        assertEquals(1, added.previous)
        assertEquals(LaneEdge.RIGHT, added.side)
        assertTrue(evidence.changes.single().explained, "the side is known from the arrows")
        assertEquals(1_000.0, added.possibleFrom, "from the point before, which does not list it")
    }

    @Test
    fun `an added lane carrying a straight arrow too tells no side`() {
        // Two lanes added: the second from the edge is an option lane, so the arrows do not tell.
        val lanes = arrayOf(s(), s(), s(false), option(), sr())
        val route = route(
            start(),
            node(1_000.0, s(), s(), s()),
            node(3_500.0, *lanes, road = RoadKind.RAMP, maneuver = offRampRight),
            node(4_000.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val added = evidence(route).added.single()
        assertEquals(2, added.lanes)
        assertNull(added.side)
        assertNull(LaneEvidence.addedSide(lanes.toList(), 2, LaneEdge.RIGHT))
        assertEquals(LaneEdge.RIGHT, LaneEvidence.addedSide(lanes.toList(), 1, LaneEdge.RIGHT))
        assertNull(LaneEvidence.addedSide(lanes.toList(), 1, LaneEdge.LEFT), "slight right is no arrow toward a left exit")
        assertNull(LaneEvidence.addedSide(lanes.toList(), 1, null))
    }

    @Test
    fun `an added lane may exist from the point before, at most 3,000 m back`() {
        fun previousAt(metersBack: Double): LaneEvidence.AddedLanes {
            val route = route(
                start(),
                node(5_000.0 - metersBack, s(), s(), s()),
                node(5_000.0, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
                node(5_300.0, road = RoadKind.RAMP, maneuver = arrive),
            )
            return evidence(route).added.single()
        }
        assertEquals(2_001.0, previousAt(2_999.0).possibleFrom, 1e-9)
        assertEquals(2_000.0, previousAt(3_000.0).possibleFrom, 1e-9)
        assertEquals(2_000.0, previousAt(3_001.0).possibleFrom, 1e-9, "3,000 m before the point that lists it")
    }

    // ------------------------------------------------------------------ rule 7a: gaps

    @Test
    fun `a gap inside a card window counts its metres inside it`() {
        val route = route(start(), node(1_000.0, s(), s(), s()), node(4_000.0, s(), s(), s(), s()), node(5_000.0, maneuver = arrive))
        val window = CardWindow(2_500.0, 4_000.0, CardWindow.Kind.RUN_UP, 2)
        val gap = evidence(route, listOf(window)).gaps.single()
        assertEquals(1_000.0, gap.from)
        assertEquals(3_000.0, gap.to)
        assertEquals(500.0, gap.inWindowMeters, 1e-9)
        assertTrue(gap.inWindow)
        assertFalse(gap.shortOutside)
        assertEquals(1, gap.before)
        assertEquals(2, gap.after)
    }

    @Test
    fun `a gap outside every window is short up to 600 m`() {
        fun gapOf(meters: Double): LaneEvidence.Gap {
            val route = route(
                start(),
                node(1_000.0, s(), s(), s()),
                node(2_000.0 + meters, s(), s(), s(), s()),
                node(5_000.0, maneuver = arrive),
            )
            return evidence(route).gaps.single()
        }
        assertTrue(gapOf(600.0).shortOutside)
        assertEquals(600.0, gapOf(600.0).meters, 1e-9)
        assertFalse(gapOf(601.0).shortOutside)
        assertFalse(gapOf(601.0).inWindow)
    }

    @Test
    fun `a short gap inside a window is no short gap outside one`() {
        val route = route(start(), node(1_000.0, s(), s(), s()), node(2_300.0, s(), s(), s(), s()), node(5_000.0, maneuver = arrive))
        val gap = evidence(route, listOf(CardWindow(1_100.0, 1_200.0, CardWindow.Kind.STREET, 2))).gaps.single()
        assertEquals(300.0, gap.meters, 1e-9)
        assertTrue(gap.inWindow)
        assertFalse(gap.shortOutside)
    }

    // ------------------------------------------------------------------ rule 7: nothing else is known

    @Test
    fun `before the first point and after the last nothing is known and nothing is a gap`() {
        val route = route(start(), node(2_000.0), node(3_000.0, s(), s()), node(4_000.0), node(9_000.0, maneuver = arrive))
        val evidence = evidence(route)
        assertEquals(listOf("0..2000 NONE", "2000..3000 KNOWN 2", "3000..9000 NONE"), evidence.drawn())
        assertTrue(evidence.gaps.isEmpty())
    }

    @Test
    fun `a route without lane points is unknown from end to end`() {
        val route = route(start(), node(2_000.0, roads = exitRight), node(3_000.0, maneuver = arrive), length = 3_200.0)
        val evidence = evidence(route)
        assertTrue(evidence.lanePoints.isEmpty())
        assertEquals(listOf("0..3200 NONE"), evidence.drawn())
    }

    @Test
    fun `the stretches cover the route end to end, one after the other`() {
        val route = route(
            start(),
            node(500.0),
            node(1_000.0, s(), s(), s(), s()),
            node(2_000.0, roads = joinRight),
            node(3_500.0, s(), s(), s(), s()),
            node(6_000.0, s(), s(), s()),
            node(7_000.0, maneuver = arrive),
            length = 7_050.0,
        )
        val stretches = evidence(route).stretches
        assertEquals(0.0, stretches.first().from)
        assertEquals(7_050.0, stretches.last().to)
        for (i in 1 until stretches.size) assertEquals(stretches[i - 1].to, stretches[i].from)
        assertEquals(7_050.0, stretches.sumOf { it.meters }, 1e-9)
    }

    // ------------------------------------------------------------------ odd lists

    @Test
    fun `a point with no lane the route can be followed from still holds its count`() {
        val route = route(start(), node(1_000.0, s(false), s(false), s(false)), node(2_000.0, maneuver = arrive))
        assertEquals(listOf("0..1000 KNOWN 3", "1000..2000 NONE"), evidence(route).drawn())
    }

    @Test
    fun `lanes without arrows count but tell no side`() {
        val bare = RouteLane(emptySet(), valid = true)
        val route = route(
            start(),
            node(1_000.0, bare, bare),
            node(2_000.0, bare, bare, bare, road = RoadKind.RAMP, maneuver = offRampRight),
            node(2_300.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val evidence = evidence(route)
        assertEquals(2_000.0, evidence.meters(KNOWN), 1e-9)
        assertNull(evidence.added.single().side)
        assertFalse(evidence.changes.single().explained)
    }

    @Test
    fun `an intersection with an empty lane list is no lane point`() {
        val route = route(start(), node(1_000.0), node(2_000.0, maneuver = arrive))
        assertTrue(evidence(route).lanePoints.isEmpty())
    }

    // ------------------------------------------------------------------ what the line counts besides

    @Test
    fun `designated lanes are counted by kind`() {
        val kinds = listOf(setOf(DesignatedKind.HOV), emptySet(), setOf(DesignatedKind.BUS, DesignatedKind.TAXI), emptySet())
        val route = route(
            start(),
            node(1_000.0, hov(), s(), hov(), s(), kinds = kinds),
            node(2_000.0, hov(), s()),
            node(3_000.0, maneuver = arrive),
        )
        val counts = evidence(route).designated
        assertEquals(DesignatedKind.entries.toList(), counts.keys.toList(), "every kind, in order")
        assertEquals(1, counts[DesignatedKind.HOV])
        assertEquals(1, counts[DesignatedKind.BUS])
        assertEquals(1, counts[DesignatedKind.TAXI])
        assertEquals(1, counts[DesignatedKind.OTHER], "a designated lane whose kinds were not read")
        assertEquals(0, counts[DesignatedKind.BICYCLE])
    }

    @Test
    fun `street turns are the junction turns off freeways`() {
        val street = RoadKind.STREET
        val turn = NodeManeuver(ManeuverType.TURN, TurnModifier.LEFT)
        val newName = NodeManeuver(ManeuverType.NEW_NAME, TurnModifier.STRAIGHT)
        val onRamp = NodeManeuver(ManeuverType.ON_RAMP, TurnModifier.RIGHT, viewStaysUp = true)
        val route = route(
            start(street),
            node(500.0, s(), s(), road = street, maneuver = turn),
            node(900.0, road = street, maneuver = turn),
            node(1_300.0, road = street, maneuver = newName),
            node(1_700.0, road = RoadKind.RAMP, maneuver = onRamp),
            node(2_500.0, maneuver = NodeManeuver(ManeuverType.MERGE, viewStaysUp = true)),
            node(5_000.0, road = street, maneuver = turn),
            node(5_400.0, road = street, maneuver = arrive),
        )
        // The turn reached from the freeway, the new name, the on ramp, the merge and the arrival are none.
        assertEquals(listOf(1, 2), evidence(route).streetTurns)
    }

    // ------------------------------------------------------------------ the walks of the progress log (synthetic scenes)

    /**
     * IA: five general lanes at every point, the exit on the right 5,000 m along. IA0 2,180 m before
     * the gore, where the left lane cannot be used; IA1 at 1,330 m, the right lane; IA2a and IA2b at
     * 690 and 640 m, the two left lanes; the exit takes the two right lanes of six, the fifth an
     * option lane and the sixth exit-only. No road leaves at the points: their lanes go on.
     */
    private fun sceneIa(farSide: Boolean = false): LaneRoute {
        val gore = 5_000.0
        val ia0 = if (farSide) arrayOf(s(), s(), s(), s(), s(false)) else arrayOf(s(false), s(), s(), s(), s())
        val ia1 = if (farSide) arrayOf(s(false), s(), s(), s(), s()) else arrayOf(s(), s(), s(), s(), s(false))
        val ia2 = arrayOf(s(false), s(false), s(), s(), s())
        return route(
            start(),
            node(gore - 2_180.0, *ia0),
            node(gore - 1_330.0, *ia1),
            node(gore - 690.0, *ia2),
            node(gore - 640.0, *ia2),
            node(gore, s(false), s(false), s(false), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(gore + 300.0, road = RoadKind.RAMP),
            node(gore + 500.0, road = RoadKind.STREET, maneuver = NodeManeuver(ManeuverType.TURN, TurnModifier.RIGHT)),
            node(gore + 600.0, road = RoadKind.STREET, maneuver = arrive),
        )
    }

    @Test
    fun `IA is known from 1,000 m before its first point to the gore, its sixth lane added on the right`() {
        for (farSide in listOf(false, true)) {
            val evidence = evidence(sceneIa(farSide))
            assertEquals(
                listOf(
                    "0..1820 NONE",
                    "1820..2820 KNOWN 5",
                    "2820..3670 KNOWN 5",
                    "3670..4310 KNOWN 5",
                    "4310..4360 KNOWN 5",
                    "4360..5000 KNOWN 6",
                    "5000..5500 NONE",
                    "5500..5600 NONE",
                ),
                evidence.drawn(),
                "far side $farSide",
            )
            assertTrue(evidence.gaps.isEmpty())
            assertTrue(evidence.unknownExits.isEmpty())
            val added = evidence.added.single()
            assertEquals(5, added.point)
            assertEquals(LaneEdge.RIGHT, added.side)
            assertEquals(4_360.0, added.possibleFrom)
            assertTrue(evidence.changes.single().explained)
        }
    }

    /**
     * S13: a split 3,000 m before an exit, nothing between: the two right lanes leave at the split.
     * The exit is IA's: it takes the two right lanes of six, the fifth an option lane.
     */
    private fun sceneS13() = route(
        start(),
        node(1_000.0, s(), s(), s(), sr(false), sr(false), roads = exitRight),
        node(4_000.0, s(false), s(false), s(false), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
        node(4_300.0, road = RoadKind.RAMP, maneuver = arrive),
    )

    @Test
    fun `S13 leaves a 2,000 m gap between the split and the exit's reach`() {
        val window = CardWindow(1_720.0, 4_000.0, CardWindow.Kind.RUN_UP, 2)
        val evidence = evidence(sceneS13(), listOf(window))
        assertEquals(listOf("0..1000 KNOWN 5", "1000..3000 NONE", "3000..4000 KNOWN 6", "4000..4300 NONE"), evidence.drawn())
        val gap = evidence.gaps.single()
        assertEquals(2_000.0, gap.meters, 1e-9)
        assertEquals(1_280.0, gap.inWindowMeters, 1e-9)
        // Three lanes go on past the split, the exit lists six: the arrows place none of the three added lanes.
        val change = evidence.changes.single()
        assertEquals(3, change.lanesBefore)
        assertEquals(6, change.lanesAfter)
        assertFalse(change.explained)
        assertEquals(3, evidence.added.single().lanes)
        assertNull(evidence.added.single().side)
    }

    /** S15: two points where lanes leave on the right, no lane added between: at 1,400 m one, at 800 m two. */
    private fun sceneS15() = route(
        start(),
        node(2_600.0, s(), s(), s(), s(), sr(false), roads = exitRight),
        node(3_200.0, s(), s(), sr(false), sr(false), roads = exitRight),
        node(4_000.0, s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
        node(4_300.0, road = RoadKind.RAMP, maneuver = arrive),
    )

    @Test
    fun `S15 is known from 1,000 m before its first point to the gore`() {
        val evidence = evidence(sceneS15())
        assertEquals(listOf("0..1600 NONE", "1600..2600 KNOWN 5", "2600..3200 KNOWN 4", "3200..4000 KNOWN 3", "4000..4300 NONE"), evidence.drawn())
        assertTrue(evidence.gaps.isEmpty())
        // Past 800 m two lanes go on and the exit lists three: a lane added on the right.
        assertEquals(1, evidence.changes.size)
        assertEquals(LaneEdge.RIGHT, evidence.added.single().side)
    }

    /**
     * S17: no lane points on the way, a road without lane data leaving on the right 300 m before the
     * exit; five general lanes, as the run-up rule's S17 has them.
     */
    private fun sceneS17(pointAt: Double? = null) = route(
        *listOfNotNull(
            start(),
            pointAt?.let { node(4_000.0 - it, s(), s(), s(), s(), s()) },
            node(3_700.0, roads = exitRight),
            node(4_000.0, s(false), s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
            node(4_300.0, road = RoadKind.RAMP, maneuver = arrive),
        ).toTypedArray(),
    )

    @Test
    fun `S17's exit is known back to the road leaving 300 m before it, and nothing is bridged`() {
        val evidence = evidence(sceneS17())
        assertEquals(listOf("0..3700 NONE", "3700..4000 KNOWN 5", "4000..4300 NONE"), evidence.drawn())
        assertTrue(evidence.unknownExits.isEmpty(), "no bridge reaches across it")
        assertTrue(evidence.gaps.isEmpty())
        // With a point 900 m before the exit that agrees with it, the bridge reaches across: an unknown exit.
        val bridged = evidence(sceneS17(pointAt = 900.0))
        assertEquals(listOf("0..2100 NONE", "2100..3100 KNOWN 5", "3100..3700 BRIDGED 5", "3700..4000 KNOWN 5", "4000..4300 NONE"), bridged.drawn())
        assertEquals(3_700.0, bridged.unknownExits.single().station)
    }

    /** S20: two points where the right lane leaves, a lane added between them: at 1,600 m and at 700 m, four lanes each. */
    private fun sceneS20() = route(
        start(),
        node(2_400.0, s(), s(), s(), sr(false), roads = exitRight),
        node(3_300.0, s(), s(), s(), sr(false), roads = exitRight),
        node(4_000.0, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRampRight),
        node(4_300.0, road = RoadKind.RAMP, maneuver = arrive),
    )

    @Test
    fun `S20's added lanes are placed on the right by their arrows`() {
        val evidence = evidence(sceneS20())
        assertEquals(listOf("0..1400 NONE", "1400..2400 KNOWN 4", "2400..3300 KNOWN 4", "3300..4000 KNOWN 4", "4000..4300 NONE"), evidence.drawn())
        assertEquals(listOf(LaneEdge.RIGHT, LaneEdge.RIGHT), evidence.added.map { it.side })
        assertEquals(2_400.0, evidence.added.first().possibleFrom)
        assertTrue(evidence.changes.all { it.explained })
        assertTrue(evidence.gaps.isEmpty())
    }
}
