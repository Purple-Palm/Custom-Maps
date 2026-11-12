package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Made-up routes only: stations in metres from the route's start, an invented route id. */
class LaneEvidenceLineTest {

    private fun s(valid: Boolean = true) = RouteLane(setOf(TurnModifier.STRAIGHT), valid = valid)
    private fun sr(valid: Boolean = true) = RouteLane(setOf(TurnModifier.SLIGHT_RIGHT), valid = valid)
    private fun option() = RouteLane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), valid = true)
    /** A lane kept for car pools, or for a kind of vehicle the app does not know ([DesignatedKind.OTHER]). */
    private fun hov(kind: DesignatedKind = DesignatedKind.HOV) = RouteLane(setOf(TurnModifier.STRAIGHT), valid = false, kinds = setOf(kind))

    private fun node(
        at: Double,
        vararg lanes: RouteLane,
        road: RoadKind = RoadKind.FREEWAY,
        maneuver: NodeManeuver? = null,
        exitRight: Boolean = false,
    ) = RouteNode(
        station = at,
        lanes = lanes.toList(),
        bearings = if (exitRight) listOf(180, 0, 30) else listOf(180, 0),
        entry = if (exitRight) listOf(false, true, true) else listOf(false, true),
        inIndex = 0,
        outIndex = 1,
        freeway = road == RoadKind.FREEWAY,
        ramp = road == RoadKind.RAMP,
        maneuver = maneuver,
    )

    private val offRamp = NodeManeuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true)

    /** The IA scene of LaneEvidenceTest: five lanes at four points, the gore 5,000 m along, a turn and the arrival after it. */
    private fun ia(routeId: String = "route-ia#0"): LaneRoute {
        val nodes = listOf(
            node(0.0, maneuver = NodeManeuver(ManeuverType.DEPART)),
            node(2_820.0, s(false), s(), s(), s(), s()),
            node(3_670.0, s(), s(), s(), s(), s(false)),
            node(4_310.0, s(false), s(false), s(), s(), s()),
            node(4_360.0, s(false), s(false), s(), s(), s()),
            node(5_000.0, s(false), s(false), s(false), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRamp),
            node(5_300.0, road = RoadKind.RAMP),
            node(5_500.0, road = RoadKind.STREET, maneuver = NodeManeuver(ManeuverType.TURN, TurnModifier.RIGHT)),
            node(5_600.0, road = RoadKind.STREET, maneuver = NodeManeuver(ManeuverType.ARRIVE)),
        )
        return LaneRoute(routeId, steps = 4, lengthMeters = 5_600.0, nodes = nodes)
    }

    @Test
    fun `the line of a route counts what its lane data covers, in numbers`() {
        val fields = LaneEvidenceLine.fields(LaneEvidence.of(ia()))
        assertEquals(
            listOf(
                "routeId", "lengthM", "steps", "lanePoints", "freewayM", "rampM", "streetM", "gaps", "unexplained",
                "exitSideAdded", "unknownExits", "windows", "streetTurns", "designated", "noLanes",
            ),
            fields.keys.toList(),
        )
        assertEquals("route-ia#0", fields["routeId"])
        assertEquals(5_600L, fields["lengthM"])
        assertEquals(4, fields["steps"])
        assertEquals(mapOf("freeway" to 5, "ramp" to 0, "street" to 0), fields["lanePoints"])
        assertEquals(mapOf("known" to 3_180L, "bridged" to 0L, "none" to 1_820L), fields["freewayM"])
        assertEquals(mapOf("known" to 0L, "bridged" to 0L, "none" to 500L), fields["rampM"])
        assertEquals(mapOf("known" to 0L, "bridged" to 0L, "none" to 100L), fields["streetM"])
        // Counts are Int and metres Long, as the line writes them.
        assertEquals(mapOf<String, Any>("count" to 0, "m" to 0L, "inWindow" to 0, "inWindowM" to 0L, "shortOutside" to 0), fields["gaps"])
        assertEquals(0, fields["unexplained"])
        assertEquals(mapOf("count" to 1, "sideUnknown" to 0), fields["exitSideAdded"])
        assertEquals(0, fields["unknownExits"])
        assertEquals(mapOf("runUps" to 1, "street" to 1), fields["windows"])
        assertEquals(mapOf("withLanes" to 0, "withoutLanes" to 1), fields["streetTurns"])
        assertEquals(
            mapOf("hov" to 0, "bus" to 0, "taxi" to 0, "motorcycle" to 0, "moped" to 0, "bicycle" to 0, "other" to 0),
            fields["designated"],
        )
        assertEquals(false, fields["noLanes"])
    }

    @Test
    fun `gaps, unexplained changes, unknown exits and designated lanes are counted`() {
        // A split 3,000 m before the exit takes two lanes. From the next point on a car-pool lane runs beside three general
        // lanes; it and the exit agree, and a road leaves on the exit's side between them.
        val nodes = listOf(
            node(0.0, maneuver = NodeManeuver(ManeuverType.DEPART)),
            node(1_000.0, s(), s(), s(), sr(false), sr(false), exitRight = true),
            node(3_200.0, hov(), s(), s(), s()),
            node(3_500.0, exitRight = true),
            node(4_000.0, hov(DesignatedKind.OTHER), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRamp),
            node(4_300.0, road = RoadKind.RAMP, maneuver = NodeManeuver(ManeuverType.ARRIVE)),
        )
        val evidence = LaneEvidence.of(LaneRoute("route-2#0", 3, 4_300.0, nodes))
        val fields = LaneEvidenceLine.fields(evidence)
        // Past the split three lanes go on and the next point lists four: 1,200 m without lane data, up to 2,200 m. The run-up,
        // planned at the freeway's setting, starts 2,659.888 m before the gore: 860 m of the gap lie in it.
        val gaps = fields["gaps"] as Map<*, *>
        assertEquals(1, gaps["count"])
        assertEquals(1_200L, gaps["m"])
        assertEquals(1, gaps["inWindow"])
        assertEquals(860L, gaps["inWindowM"])
        assertEquals(0, gaps["shortOutside"])
        assertEquals(1, fields["unexplained"])
        assertEquals(mapOf("count" to 1, "sideUnknown" to 1), fields["exitSideAdded"])
        assertEquals(1, fields["unknownExits"])
        assertEquals(1, (fields["designated"] as Map<*, *>)["hov"], "the car-pool lane")
        assertEquals(1, (fields["designated"] as Map<*, *>)["other"], "the gore's lane, kept for a kind the app does not know")
        assertEquals(
            mapOf("known" to 2_500L, "bridged" to 300L, "none" to 1_200L),
            fields["freewayM"],
            "bridged from 3,200 m to the exit's reach at 3,500 m",
        )
        assertEquals(mapOf("runUps" to 1, "street" to 0), fields["windows"])
    }

    @Test
    fun `a route without lane points says so`() {
        val nodes = listOf(node(0.0, maneuver = NodeManeuver(ManeuverType.DEPART)), node(900.0, maneuver = NodeManeuver(ManeuverType.ARRIVE)))
        val fields = LaneEvidenceLine.fields(LaneEvidence.of(LaneRoute("route-3#0", 2, 900.0, nodes)))
        assertEquals(true, fields["noLanes"])
        assertEquals(mapOf("known" to 0L, "bridged" to 0L, "none" to 900L), fields["freewayM"])
    }

    @Test
    fun `the line is a few hundred bytes`() {
        // A route id as long as the navigator's: a response id of 56 characters and the route's index.
        val routeId = "Abc0Def1Ghi2Jkl3Mno4Pqr5Stu6Vwx7Yz89Abc0Def1Ghi2Jkl3Mno4#0"
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LaneEvidenceLine.TYPE, LaneEvidenceLine.fields(LaneEvidence.of(ia(routeId))))
        val bytes = line.toByteArray(Charsets.UTF_8).size
        println("lane_evidence line: $bytes bytes: $line")
        assertTrue(bytes in 450..750, "$bytes bytes")
        assertFalse(line.substringAfter(LaneEvidenceLine.TYPE).contains("."), "whole numbers only: no coordinate, no fraction")
    }

    // ------------------------------------------------------------------ which routes get a line

    @Test
    fun `the route a drive starts on gets its line once the navigator has it`() {
        val routes = LaneEvidenceLine.Routes()
        routes.begin()
        assertFalse(routes.onRoutesChanged("a#0", refresh = false), "the navigator tells of its new routes before the commit's answer")
        assertTrue(routes.onCommitted("a#0"))
        assertFalse(routes.onRoutesChanged("a#0", refresh = false), "the same primary route: only its alternatives changed")
    }

    @Test
    fun `a reroute and a route switched to get a line, a refresh never`() {
        val routes = LaneEvidenceLine.Routes()
        routes.begin()
        routes.onCommitted("a#0")
        assertTrue(routes.onRoutesChanged("b#0", refresh = false), "a reroute")
        assertFalse(routes.onRoutesChanged("b#0", refresh = true), "its refresh")
        assertFalse(routes.onRoutesChanged("c#0", refresh = true), "whatever it says")
        assertTrue(routes.onRoutesChanged("b#1", refresh = false), "an alternative the driver took")
        assertFalse(routes.onRoutesChanged(null, refresh = false), "the routes cleared")
    }

    @Test
    fun `nothing is written outside a drive, and a new drive starts over`() {
        val routes = LaneEvidenceLine.Routes()
        assertFalse(routes.onRoutesChanged("a#0", refresh = false), "before any drive")
        routes.begin()
        assertTrue(routes.onCommitted("a#0"))
        routes.end()
        assertFalse(routes.onRoutesChanged("b#0", refresh = false), "after the drive ended")
        routes.begin()
        assertTrue(routes.onCommitted("a#0"), "the same route on a new drive has a line of its own")
        assertFalse(routes.onCommitted(null))
    }

    // ------------------------------------------------------------------ with the lane plan (the second pass)

    /** The fields of [route]'s line with its plan, as the engine writes it once the plan is made. */
    private fun withPlan(route: LaneRoute): Map<String, Any?> {
        val first = LaneEvidence.of(route)
        val plan = LanePlanner.plan(LaneTopology.of(first), CardWindows.latched(route), LaneScenes.sideBranches(route))
        return LaneEvidenceLine.fields(first, plan, plan.evidence())
    }

    @Test
    fun `with the plan the line counts its zones, its possible lanes, its aligned changes and what is left`() {
        val ia = withPlan(LaneScenes.ia())
        assertEquals(2, ia["zones"], "the careful gains before IA1 and IA2a")
        assertEquals(0, ia["zonesBridged"])
        assertEquals(1, ia["possibleLanes"], "the exit-only lane from IA2b")
        assertEquals(1, ia["aligned"], "the exit's lane by its arrow")
        val iw31 = withPlan(LaneScenes.iw31())
        assertEquals(0, iw31["zones"])
        assertEquals(1, iw31["zonesBridged"], "the stretch between P1 and the entrance")
        assertEquals(1, iw31["possibleLanes"], "the exit-only lane from P1")
        // The metres left without anything of the app's own: before the first lane point's reach and after the last.
        val route = LaneScenes.iw31()
        val second = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route)).evidence()
        assertEquals(Math.round(second.stretches.filter { it.kind == LaneEvidence.Kind.NONE }.sumOf { it.meters }), iw31["noneM"])
        assertTrue((iw31["noneM"] as Long) < route.lengthMeters.toLong())
    }

    @Test
    fun `without the plan the line is the first pass's alone, the new fields after the old ones`() {
        val first = LaneEvidence.of(LaneScenes.ia())
        val alone = LaneEvidenceLine.fields(first)
        assertTrue(listOf("zones", "zonesBridged", "possibleLanes", "aligned", "noneM").none { it in alone })
        val keys = withPlan(LaneScenes.ia()).keys.toList()
        assertEquals(alone.keys.toList(), keys.take(alone.size))
        assertEquals(listOf("zones", "zonesBridged", "possibleLanes", "aligned", "noneM"), keys.drop(alone.size))
        val line = JsonLines.event(Instant.parse("2000-01-01T00:00:00Z"), LaneEvidenceLine.TYPE, withPlan(LaneScenes.iw31()))
        println("lane_evidence with the plan's fields: ${line.length} bytes")
        assertTrue(line.length < 900, "${line.length}")
    }
}
