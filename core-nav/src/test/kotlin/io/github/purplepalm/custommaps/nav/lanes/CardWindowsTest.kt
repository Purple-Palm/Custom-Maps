package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LaneFocus
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max

/** Made-up routes: stations in metres from the route's start; speeds in miles an hour as signs post them. */
class CardWindowsTest {

    private val tuning = CameraTuning.PHONE
    private val mph = PlanningSpeed.MPS_PER_MPH

    private fun s(valid: Boolean = true) = RouteLane(setOf(TurnModifier.STRAIGHT), valid = valid)
    private fun sr(valid: Boolean = true) = RouteLane(setOf(TurnModifier.SLIGHT_RIGHT), valid = valid)
    private fun option() = RouteLane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), valid = true)
    private fun hov() = RouteLane(setOf(TurnModifier.STRAIGHT), valid = false, kinds = setOf(DesignatedKind.HOV))

    private val exitLanes = arrayOf(s(false), s(false), s(false), sr())

    private fun node(at: Double, vararg lanes: RouteLane, road: RoadKind = RoadKind.FREEWAY, maneuver: NodeManeuver? = null, postedMph: Double? = null) =
        RouteNode(
            station = at,
            lanes = lanes.toList(),
            freeway = road == RoadKind.FREEWAY,
            ramp = road == RoadKind.RAMP,
            maneuver = maneuver,
            postedMps = postedMph?.let { it * mph },
        )

    private val depart = NodeManeuver(ManeuverType.DEPART)
    private val arrive = NodeManeuver(ManeuverType.ARRIVE, TurnModifier.RIGHT)
    private val offRamp = NodeManeuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true)
    private val turn = NodeManeuver(ManeuverType.TURN, TurnModifier.LEFT)

    private fun route(vararg nodes: RouteNode) = LaneRoute("route-1", nodes.count { it.maneuver != null }, nodes.last().station, nodes.toList())

    /** The reach of a run-up planned at [mps]: the close view's window and a minute of driving, at least 1,500 m. */
    private fun reach(mps: Double) = LaneFocus.windowMeters(tuning, mps) + max(mps * tuning.runUpSeconds, tuning.runUpMinMeters)

    @Test
    fun `a freeway exit that counts has a run-up from the reach at the planning speed`() {
        val route = route(
            node(0.0, maneuver = depart, postedMph = 70.0),
            node(3_000.0, postedMph = 70.0),
            node(5_000.0, *exitLanes, road = RoadKind.RAMP, maneuver = offRamp),
            node(5_400.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val window = CardWindows.of(route).single()
        assertEquals(CardWindow.Kind.RUN_UP, window.kind)
        assertEquals(2, window.node)
        assertEquals(5_000.0, window.to)
        // 70 mph posted: planned at 75 mph, 33.528 m/s; 838.2 m of window and 2,011.68 m of driving.
        assertEquals(5_000.0 - reach(75.0 * mph), window.from, 1e-9)
        assertEquals(2_849.88, window.meters, 1e-6)
    }

    @Test
    fun `an approach without a posted speed is planned at the freeway's setting`() {
        // The approach's own speed counts, not the departure's: 65 mph for a freeway, planned at 70 mph, 31.3 m/s.
        val route = route(
            node(0.0, maneuver = depart, postedMph = 45.0),
            node(3_000.0),
            node(5_000.0, *exitLanes, road = RoadKind.RAMP, maneuver = offRamp),
            node(5_400.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        assertEquals(2_659.888, CardWindows.of(route).single().meters, 1e-6)
    }

    @Test
    fun `a run-up never reaches back past the start of its step`() {
        val merge = NodeManeuver(ManeuverType.MERGE, viewStaysUp = true)
        val route = route(
            node(0.0, road = RoadKind.STREET, maneuver = depart),
            node(3_800.0, maneuver = merge),
            node(5_000.0, *exitLanes, road = RoadKind.RAMP, maneuver = offRamp),
            node(5_400.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val window = CardWindows.of(route).single()
        assertEquals(3_800.0, window.from)
        assertEquals(1_200.0, window.meters, 1e-9)
    }

    @Test
    fun `an exit that does not count as a lane choice has no run-up`() {
        fun windowsFor(exit: RouteNode, approachRoad: RoadKind = RoadKind.FREEWAY) = CardWindows.of(
            route(node(0.0, road = approachRoad, maneuver = depart), node(3_000.0, road = approachRoad), exit, node(5_400.0, road = RoadKind.RAMP, maneuver = arrive)),
        )
        val counts = node(5_000.0, *exitLanes, road = RoadKind.RAMP, maneuver = offRamp)
        assertEquals(1, windowsFor(counts).size)
        assertTrue(windowsFor(counts, approachRoad = RoadKind.RAMP).isEmpty(), "a ramp is no freeway approach")
        assertTrue(windowsFor(node(5_000.0, s(false), sr(), road = RoadKind.RAMP, maneuver = offRamp)).isEmpty(), "two lanes")
        assertTrue(windowsFor(node(5_000.0, s(), s(), sr(), road = RoadKind.RAMP, maneuver = offRamp)).isEmpty(), "every lane goes on")
        assertTrue(windowsFor(node(5_000.0, s(false), s(false), s(false), road = RoadKind.RAMP, maneuver = offRamp)).isEmpty(), "no lane used")
        assertTrue(windowsFor(node(5_000.0, road = RoadKind.RAMP, maneuver = offRamp)).isEmpty(), "no lanes")
        assertTrue(windowsFor(node(5_000.0, hov(), s(), s(), sr(), road = RoadKind.RAMP, maneuver = offRamp)).isEmpty(), "three open lanes, all used")
        assertEquals(1, windowsFor(node(5_000.0, hov(), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = offRamp)).size)
    }

    @Test
    fun `a street maneuver has the last 180 m of its step`() {
        val street = RoadKind.STREET
        val route = route(
            node(0.0, road = street, maneuver = depart),
            node(600.0, road = street, maneuver = turn),
            node(700.0, road = street, maneuver = turn),
            node(1_200.0, road = street, maneuver = arrive),
        )
        val windows = CardWindows.of(route)
        assertEquals(180.0, tuning.maneuverFramingMeters)
        assertEquals(2, windows.size, "the departure and the arrival have none")
        assertEquals(CardWindow(420.0, 600.0, CardWindow.Kind.STREET, 1), windows[0])
        assertEquals(CardWindow(600.0, 700.0, CardWindow.Kind.STREET, 2), windows[1], "a step of 100 m")
    }

    @Test
    fun `the camera frames no junction for maneuvers it stays up for, nor after a freeway`() {
        val onRamp = NodeManeuver(ManeuverType.ON_RAMP, TurnModifier.RIGHT, viewStaysUp = true)
        val street = node(0.0, road = RoadKind.STREET)
        val freeway = node(0.0)
        val ramp = node(0.0, road = RoadKind.RAMP)
        assertTrue(CardWindows.framesStreetManeuver(turn, street))
        assertTrue(CardWindows.framesStreetManeuver(turn, ramp), "the turn at the end of a ramp")
        assertFalse(CardWindows.framesStreetManeuver(turn, freeway))
        assertFalse(CardWindows.framesStreetManeuver(onRamp, street))
        assertFalse(CardWindows.framesStreetManeuver(depart, street))
        assertFalse(CardWindows.framesStreetManeuver(arrive, street))
        assertTrue(CardWindows.framesStreetManeuver(NodeManeuver(ManeuverType.NEW_NAME), street), "the camera frames a new name")
    }

    @Test
    fun `the S13 scene's run-up covers the last 2,850 m before its gore`() {
        // The exit is IA's: the two right lanes of six, the fifth an option lane. 70 mph posted, planned at 75 mph.
        val route = route(
            node(0.0, maneuver = depart, postedMph = 70.0),
            node(1_000.0, s(), s(), s(), sr(false), sr(false), postedMph = 70.0),
            node(4_000.0, s(false), s(false), s(false), s(false), option(), sr(), road = RoadKind.RAMP, maneuver = offRamp),
            node(4_300.0, road = RoadKind.RAMP, maneuver = arrive),
        )
        val window = CardWindows.of(route).single()
        assertEquals(1_150.12, window.from, 1e-6)
    }

    @Test
    fun `metres inside windows count once where windows overlap`() {
        val a = CardWindow(100.0, 300.0, CardWindow.Kind.RUN_UP, 1)
        val b = CardWindow(200.0, 400.0, CardWindow.Kind.STREET, 2)
        val inside = CardWindow(120.0, 150.0, CardWindow.Kind.STREET, 3)
        assertEquals(300.0, CardWindows.metersInside(listOf(b, a, inside), 0.0, 1_000.0), 1e-9)
        assertEquals(150.0, CardWindows.metersInside(listOf(a, b), 250.0, 450.0), 1e-9)
        assertEquals(0.0, CardWindows.metersInside(listOf(a, b), 400.0, 900.0))
        assertEquals(0.0, CardWindows.metersInside(emptyList(), 0.0, 900.0))
        assertEquals(0.0, CardWindows.metersInside(listOf(a), 300.0, 200.0), "an empty stretch")
    }
}
