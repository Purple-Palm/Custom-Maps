package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.ARRIVE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.CROSSING
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.DEPART
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.TURN_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.right
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.street
import io.github.purplepalm.custommaps.nav.lanes.RouteHorizon
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.lanes.StreetWays
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection.Carriageway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * How the drawing takes the electronic horizon's one-way flag for the route's streets ([HorizonEvidence.streets], the own
 * lanes' design 3.3): past the window on screen, window by window, and never changing what the window on screen may hold. On
 * made-up streets of [LaneScenes] with invented edges laid along them, no place.
 */
class HorizonStreetsTest {

    /**
     * One street with lane points of two lanes 1 km apart, at 1,000, 2,000 and 3,000 m, a road crossing at each and at an
     * intersection before each (700, 1,800 and 2,600 m); the arrival at 3,600 m. Each point's lanes reach back 250 m or to the
     * intersection before it, and the gaps between are too long to bridge: three street stretches of one road.
     */
    private fun threeStreets(access: Boolean? = null): LaneRoute = LaneScenes.route(
        street(0.0, maneuver = DEPART),
        street(700.0, roads = CROSSING).copy(oneWay = access),
        street(1_000.0, s(), s(), roads = CROSSING, signal = true),
        street(1_800.0, roads = CROSSING),
        street(2_000.0, s(), s(), roads = CROSSING),
        street(2_600.0, roads = CROSSING),
        street(3_000.0, s(), s(), roads = CROSSING),
        street(3_600.0, maneuver = ARRIVE),
        id = "three-streets",
    )

    private fun edge(length: Double, oneWay: Boolean) =
        HorizonEdge(length, 2, motorway = false, ramp = false, oneWay = oneWay, roadClass = HorizonRoadClass.SECONDARY)

    /**
     * The horizon of [route] from a car at [car]: an edge for each street from intersection to intersection, one-way where
     * [oneWay] says, up to the first that ends [reach] metres or more past the car.
     */
    private fun horizon(
        route: LaneRoute,
        car: Double,
        routeId: String = route.routeId,
        reach: Double = Double.POSITIVE_INFINITY,
        oneWay: (Int) -> Boolean,
    ): HorizonAhead {
        val nodes = route.nodes
        val i = nodes.indexOfLast { it.station <= car }
        val edges = ArrayList<HorizonEdge>()
        for (k in i until nodes.size - 1) {
            edges.add(edge(nodes[k + 1].station - nodes[k].station, oneWay(k)))
            if (nodes[k + 1].station - car >= reach) break
        }
        val along = (car - nodes[i].station) / (nodes[i + 1].station - nodes[i].station)
        return HorizonAhead.of(routeId, car, along, edges)
    }

    private fun section(plan: LanePlan, held: HorizonEvidence) = CrossSection.of(plan, held.evidence, held.streets)

    private val twoWay = StreetWays.Way(Carriageway.TWO_WAY, StreetWays.Source.HORIZON)

    @Test
    fun `the street stretches a drawing would draw, each with the intersections its streets leave`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val stretches = CrossSection.streetStretches(plan)
        assertEquals(listOf(750.0 to 1_000.0, 1_800.0 to 2_000.0, 2_750.0 to 3_000.0), stretches.map { it.from to it.to })
        assertEquals(listOf(listOf(1), listOf(3), listOf(5)), stretches.map { it.nodes })
        // One road of one run, from the run's start to its end.
        assertEquals(1, stretches.map { it.run to it.roadFrom }.distinct().size)
        // What the cross-section draws with every street told: those stretches, and nothing between them.
        val all = CrossSection.of(plan, plan.evidence(), LaneScenes.ways(route, oneWay = false))
        assertEquals(stretches.map { it.from..it.to }, all.drawnStreets)
        assertEquals(emptyList<ClosedFloatingPointRange<Double>>(), CrossSection.of(plan).drawnStreets)
    }

    @Test
    fun `a send takes the horizon's way for every street stretch past its start`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.streets.known.getValue(StreetWays.Source.HORIZON))
        val first = empty.next(horizon(route, 0.0) { false }, 500.0, emptyList())
        for (n in listOf(1, 3, 5)) assertEquals(twoWay, first.streets.at(n), "intersection $n")
        // The street leaving a lane point, toward a gap, is no stretch's: not read.
        assertNull(first.streets.at(2))
        assertFalse(first.isEmpty)
        val drawn = section(plan, first)
        assertTrue(drawn.drawsAt(900.0))
        assertEquals(Carriageway.TWO_WAY, drawn.carriagewayAt(900.0))
        assertFalse(drawn.drawsAt(1_500.0))
        assertTrue(drawn.drawsAt(2_900.0))
        // What the next send must drop with the switch off: the stretches from its start on.
        assertTrue(first.streetsFrom(2_750.0))
        assertFalse(first.streetsFrom(2_750.1))
        // Without the horizon, nothing of a street is drawn.
        assertFalse(section(plan, empty).drawsAt(900.0))
    }

    @Test
    fun `a stretch the window on screen may hold keeps what it had, an answer or none`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        // The window on screen ends past the first stretch's start: that stretch keeps having no answer, the others take one.
        val late = empty.next(horizon(route, 0.0) { false }, 800.0, emptyList())
        assertNull(late.streets.at(1))
        assertEquals(twoWay, late.streets.at(3))
        assertFalse(section(plan, late).drawsAt(900.0))
        assertTrue(section(plan, late).drawsAt(1_900.0))
        // A stretch that begins right at the start is the send's; one a centimetre before it is not.
        val start = HorizonEvidence.startFor(750.0 - HorizonEvidence.START_PAST_WINDOW_M, emptyList())
        assertEquals(750.0, start)
        assertEquals(twoWay, empty.next(horizon(route, 0.0) { false }, start - HorizonEvidence.START_PAST_WINDOW_M, emptyList()).streets.at(1))
        assertNull(empty.next(horizon(route, 0.0) { false }, start - HorizonEvidence.START_PAST_WINDOW_M + 0.01, emptyList()).streets.at(1))
        // A later update that reads otherwise changes nothing before the start: the first two stretches stay two-way.
        val first = empty.next(horizon(route, 0.0) { false }, 500.0, emptyList())
        val later = first.next(horizon(route, 1_000.0) { true }, 1_850.0, emptyList())
        assertEquals(twoWay, later.streets.at(1))
        assertEquals(twoWay, later.streets.at(3))
        assertTrue(section(plan, later).drawsAt(900.0) && section(plan, later).drawsAt(1_900.0))
    }

    @Test
    fun `a stretch told otherwise than its road's first told stretch is not drawn, and changes nothing before it`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        val oneWay = StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.HORIZON)
        val first = empty.next(horizon(route, 0.0) { false }, 500.0, emptyList())
        // The third stretch reads one-way now, past the start; its road sits as its first told stretch does, two-way.
        val later = first.next(horizon(route, 1_000.0) { it >= 5 }, 1_850.0, emptyList())
        assertEquals(oneWay, later.streets.at(5))
        assertTrue(later.streetsFrom(1_890.0))
        val drawn = section(plan, later)
        assertFalse(drawn.drawsAt(2_900.0))
        assertTrue(drawn.drawsAt(900.0) && drawn.drawsAt(1_900.0))
        assertEquals(listOf(CrossSection.UndrawnStreet(2_750.0, 3_000.0, mixed = true)), drawn.undrawnStreets)
        // Read two-way again, it is drawn; where nothing of the road is told before it, one-way is drawn.
        assertTrue(section(plan, first.next(horizon(route, 1_000.0) { false }, 1_850.0, emptyList())).drawsAt(2_900.0))
        val none = empty.next(horizon(route, 1_000.0) { it >= 5 }, 1_850.0, emptyList())
        assertEquals(oneWay, none.streets.at(5))
        assertEquals(Carriageway.ONE_WAY, section(plan, none).carriagewayAt(2_900.0))
        assertTrue(section(plan, none).drawsAt(2_900.0))
        // The first stretch told decides however the later ones are told: the first one-way, the later ones two-way.
        val mixed = empty.next(horizon(route, 0.0) { it == 1 }, 500.0, emptyList())
        val section = section(plan, mixed)
        assertTrue(section.drawsAt(900.0))
        assertEquals(Carriageway.ONE_WAY, section.carriagewayAt(900.0))
        assertFalse(section.drawsAt(1_900.0) || section.drawsAt(2_900.0))
        assertTrue(section.undrawnStreets.all { it.mixed })
    }

    @Test
    fun `with the switch off a send drops the answers past its start and keeps those before it`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val first = HorizonEvidence.of(plan).next(horizon(route, 0.0) { false }, 500.0, emptyList())
        val start = HorizonEvidence.startFor(1_850.0, emptyList())
        assertTrue(first.streetsFrom(start))
        val off = first.next(null, 1_850.0, emptyList())
        assertEquals(twoWay, off.streets.at(1))
        assertEquals(twoWay, off.streets.at(3))
        assertNull(off.streets.at(5))
        assertFalse(off.streetsFrom(start))
        assertFalse(section(plan, off).drawsAt(2_900.0))
        assertFalse(off.drawsAs(first))
        // Another route's update is no update of this one's.
        val other = first.next(horizon(route, 1_000.0, routeId = "another") { false }, 1_850.0, emptyList())
        assertTrue(other.streets.sameAs(off.streets))
    }

    @Test
    fun `the route's own access field tells first and is never read from the horizon`() {
        val route = threeStreets(access = true)
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        // Before any send the access field draws its stretch: one-way.
        assertEquals(StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.ACCESS), empty.streets.at(1))
        assertTrue(section(plan, empty).drawsAt(900.0))
        assertEquals(Carriageway.ONE_WAY, section(plan, empty).carriagewayAt(900.0))
        // A horizon that reads two-way leaves it so; the road's later stretches it reads two-way are not drawn, since the
        // road sits as its first told stretch does. Read one-way, they are.
        val sent = empty.next(horizon(route, 0.0) { false }, 500.0, emptyList())
        assertEquals(StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.ACCESS), sent.streets.at(1))
        assertEquals(twoWay, sent.streets.at(3))
        assertTrue(section(plan, sent).drawsAt(900.0))
        assertFalse(section(plan, sent).drawsAt(1_900.0))
        val alike = empty.next(horizon(route, 0.0) { true }, 500.0, emptyList())
        assertEquals(StreetWays.Way(Carriageway.ONE_WAY, StreetWays.Source.HORIZON), alike.streets.at(3))
        assertTrue(section(plan, alike).drawsAt(1_900.0))
        assertEquals(mapOf(StreetWays.Source.ACCESS to 1, StreetWays.Source.HORIZON to 2), alike.streets.known)
    }

    @Test
    fun `a send that takes the same ways draws as the holder before it, another one does not`() {
        val route = threeStreets()
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        val first = empty.next(horizon(route, 0.0) { false }, 500.0, emptyList())
        assertFalse(first.drawsAs(empty))
        assertTrue(first.next(horizon(route, 300.0) { false }, 500.0, emptyList()).drawsAs(first))
        assertFalse(first.next(horizon(route, 300.0) { it == 5 }, 500.0, emptyList()).drawsAs(first))
        // A horizon that says nothing of the streets: the same as none.
        assertTrue(empty.next(horizon(route, 3_400.0) { false }, 500.0, emptyList()).drawsAs(empty))
    }

    /**
     * One long street with an intersection every 100 m, each a lane point of two lanes from 500 m to 2,500 m, and a right turn
     * at 2,600 m with its junction view's card window; the arrival at 3,000 m. Its lanes are one stretch of evidence from 400 m
     * to the turn, over 22 streets.
     */
    private fun longStretch(): LaneRoute {
        val nodes = ArrayList<RouteNode>()
        nodes.add(street(0.0, maneuver = DEPART))
        for (k in 1..25) nodes.add(if (k >= 5) street(k * 100.0, s(), s(), roads = CROSSING) else street(k * 100.0, roads = CROSSING))
        nodes.add(street(2_600.0, s(false), right(), roads = CROSSING, maneuver = TURN_RIGHT, signal = true))
        nodes.add(street(3_000.0, maneuver = ARRIVE))
        return LaneScenes.route(*nodes.toTypedArray(), id = "long-stretch")
    }

    @Test
    fun `a stretch the window on screen ends inside is drawn street by street past the send's start`() {
        val route = longStretch()
        val plan = SceneLines.planOf(route)
        val window = plan.windows.single()
        assertEquals(2_600.0 - CardWindows.streetWindowMeters()..2_600.0, window.from..window.to)
        // Each street alone, but the three over the card window's approach together, from 60 m before the window.
        val stretches = CrossSection.streetStretches(plan)
        assertEquals(400.0, stretches.first().from)
        assertEquals(listOf(2_300.0 to 2_600.0), stretches.filter { it.nodes.size > 1 }.map { it.from to it.to })
        assertTrue(stretches.filter { it.nodes.size == 1 }.all { it.to - it.from == 100.0 })
        val empty = HorizonEvidence.of(plan)
        // The window on screen ends at 1,200 m: the streets from 1,300 m on take the horizon's way; the one from 1,200 m begins
        // before the send's start and keeps having none.
        val first = empty.next(horizon(route, 0.0) { false }, 1_200.0, emptyList())
        val drawn = section(plan, first)
        assertFalse(drawn.drawsAt(1_250.0))
        assertTrue(drawn.drawsAt(1_350.0) && drawn.drawsAt(2_550.0))
        assertEquals(listOf(1_300.0..2_600.0), drawn.drawnStreets)
        assertEquals(listOf(CrossSection.UndrawnStreet(400.0, 1_300.0, mixed = false)), drawn.undrawnStreets)
        // A later send keeps them: the window on screen now ends at 2,320 m, inside the card window's approach, which keeps
        // what it had as a whole however the horizon reads its streets now.
        val later = first.next(horizon(route, 1_500.0) { it >= 23 }, 2_320.0, emptyList())
        assertTrue(section(plan, later).drawsAt(2_550.0))
        assertEquals(Carriageway.TWO_WAY, section(plan, later).carriagewayAt(2_550.0))
        // From nothing told, a send whose start lies past the approach's first street leaves the whole approach undrawn,
        // though two of its streets begin past the start.
        val late = empty.next(horizon(route, 1_500.0) { false }, 2_320.0, emptyList())
        assertTrue(HorizonEvidence.startFor(2_320.0, plan.windows.map { it.from..it.to }) in 2_300.0..2_400.0)
        assertFalse(section(plan, late).drawsAt(2_450.0) || section(plan, late).drawsAt(2_550.0))
        assertTrue(section(plan, late).drawnStreets.isEmpty())
    }

    /**
     * A long street route for the sweep: an intersection every 150 m with a road crossing; a lane point of two or three lanes
     * (by [random]) every three to nine intersections, so some gaps between points are bridged as zones and some are not; a
     * right turn at a lane point every 3 to 6 km, with its junction view's card window. Invented stations, no place.
     */
    private fun longStreets(random: Random, id: String): LaneRoute {
        val nodes = ArrayList<RouteNode>()
        nodes.add(street(0.0, maneuver = DEPART))
        var station = 150.0
        var next = 150.0 * (3 + random.nextInt(7))
        var turn = 3_000.0 + 150.0 * random.nextInt(20)
        while (station < 16_000.0) {
            when {
                station >= turn && station >= next -> {
                    nodes.add(street(station, s(false), right(), roads = CROSSING, maneuver = TURN_RIGHT, signal = true))
                    turn = station + 3_000.0 + 150.0 * random.nextInt(20)
                    next = station + 150.0 * (3 + random.nextInt(7))
                }
                station >= next -> {
                    val lanes = if (random.nextBoolean()) arrayOf(s(), s()) else arrayOf(s(), s(), s())
                    nodes.add(street(station, *lanes, roads = CROSSING, signal = random.nextBoolean()))
                    next = station + 150.0 * (3 + random.nextInt(7))
                }
                else -> nodes.add(street(station, roads = CROSSING))
            }
            station += 150.0
        }
        nodes.add(street(station, maneuver = ARRIVE))
        return LaneScenes.route(*nodes.toTypedArray(), id = id)
    }

    /** Whether each street of [route] is one-way: a run of streets alike, which turns the other way at an intersection now and then. */
    private fun ways(route: LaneRoute, random: Random): BooleanArray {
        var oneWay = random.nextBoolean()
        return BooleanArray(route.nodes.size) {
            if (random.nextInt(9) == 0) oneWay = !oneWay
            oneWay
        }
    }

    @Test
    fun `on a drive no send changes what the window on screen holds, and the streets past it are drawn`() {
        var drawnRoutes = 0
        for (seed in 0 until 16) {
            val random = Random(seed)
            val route = longStreets(random, "streets-$seed")
            val plan = SceneLines.planOf(route)
            val oneWay = ways(route, random)
            val windows = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters)
            val held = RouteHorizon()
            var holder = HorizonEvidence.of(plan)
            var drawn = section(plan, holder)
            var shown = windows.windowAt(0.0)
            var taken: HorizonAhead? = null
            var car = 0.0
            while (car < route.lengthMeters - 400.0) {
                // The horizon reaches as far ahead as its options ask, the edges behind the car kept as the engine keeps them.
                held.take(horizon(route, car, reach = HorizonAhead.HORIZON_AHEAD_M) { oneWay[it] })
                if (windows.due(car, shown, changing = false)) {
                    val ahead = held.aheadOf(route.routeId)
                    if (shown.holds(car) && ahead != null && ahead !== taken) {
                        val next = holder.next(ahead, shown.to, windows.cards)
                        val again = section(plan, next)
                        var s = 0.0
                        while (s <= shown.to) {
                            assertEquals(drawn.drawsAt(s), again.drawsAt(s), "seed $seed: a send at $car changed $s, before the window's end ${shown.to}")
                            if (drawn.drawsAt(s)) {
                                assertEquals(drawn.carriagewayAt(s), again.carriagewayAt(s), "seed $seed at $s")
                                assertEquals(drawn.lanes(s), again.lanes(s), "seed $seed at $s")
                            }
                            s += 5.0
                        }
                        if (!next.drawsAs(holder)) {
                            holder = next
                            drawn = again
                        }
                        taken = ahead
                    }
                    shown = windows.windowAt(car)
                }
                car += 23.0
            }
            if (drawn.drawnStreets.isNotEmpty()) drawnRoutes++
        }
        // Past the first window the horizon reaches every street a later send may draw.
        assertTrue(drawnRoutes >= 14, "streets drawn on $drawnRoutes of 16 routes")
    }
}
