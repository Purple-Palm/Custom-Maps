package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.GoreArrow
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneSwap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.log2

/**
 * The own line with the app's lanes on streets (OL6), on a fake style ([OwnLineScene]) along a made-up street
 * ([StreetJunction]) 6 km due north to a right turn at a traffic signal, with lane points of two lanes at signals from
 * 3,500 m on: a street's lanes are drawn only where a source tells whether it is one-way, here the electronic horizon's
 * one-way flag past the window on screen, else none of them, as the "lane_drawing" line counts; drawn, they come with
 * their stop lines, the car's bar across the route's own lanes and the turn's arrow along the lit lane. Invented route
 * ids and synthetic horizons.
 */
class OwnRouteLineStreetsTest {

    private val id = "street-s#0"
    private val route = StreetJunction.route(60, listOf(35, 40, 45, 50, 55, 58))
    private val legs = route.first
    private val shapes = route.second
    private val plan = requireNotNull(FreewayExit.plan(id, legs, shapes))
    private val nodes = plan.input.route.nodes
    private val turn = nodes.first { it.maneuver?.type == ManeuverType.TURN }.station

    /** The route's line and table as every path of it has them: where an SDK fraction lies. */
    private val reference = ownLineDrawing(id, legs, shapes, plan, 0, lanes = true)

    /** The horizon's newest update as the engine would hold it. */
    private var ahead: HorizonAhead? = null
    private val scene = OwnLineScene { routeId -> ahead?.takeIf { it.routeId == routeId } }
    private val lanes = scene.lanes
    private val line = scene.line

    private val built = ArrayList<OwnLineDrawing>()
    private val build = object : OwnRouteLine.Build {
        override fun drawing(version: Int, lanes: Boolean) = ownLineDrawing(id, legs, shapes, plan, version, lanes).also { built += it }

        override fun drawing(version: Int, lanes: Boolean, horizon: HorizonEvidence, prefix: PathPrefix) =
            ownLineDrawing(id, legs, shapes, plan, version, lanes, horizon, prefix).also { built += it }
    }

    /** The drawing whose path the map holds now. */
    private fun shown(): OwnLineDrawing = built.last { it.points == scene.style.sourceData }

    /**
     * An update from a car at [car] as the navigator cuts it: an edge from each intersection to the next, one-way where
     * [oneWay] says, the car's from where it is, up to the first that reaches [HorizonAhead.HORIZON_AHEAD_M] past it.
     */
    private fun horizonCut(car: Double, oneWay: Boolean): HorizonAhead {
        val edges = ArrayList<HorizonEdge>()
        var along = 0.0
        val i = nodes.indexOfLast { it.station <= car }
        for (k in i until nodes.size - 1) {
            val from = nodes[k].station
            val to = nodes[k + 1].station
            if (to <= from) continue
            if (edges.isEmpty()) along = (car - from) / (to - from)
            edges += HorizonEdge(to - from, 2, motorway = false, ramp = false, oneWay = oneWay, roadClass = HorizonRoadClass.SECONDARY)
            if (to - car >= HorizonAhead.HORIZON_AHEAD_M) break
        }
        return HorizonAhead.of(id, car, along, edges)
    }

    private fun puck(station: Double) {
        val place = scene.at(station)
        line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = id, offset = reference.table.routeFraction(station), tracking = true)
    }

    /** The lanes on, the style loaded, the route given, its path and first window built and loaded. */
    private fun drawn() {
        line.setLanes(true)
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        puck(0.0)
        line.onRoute(id, refresh = false, build, OwnRouteLine.Traffic { it.runs })
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
    }

    /** Drives from [from] to [to] in steps of 20 m, the horizon as [horizon] cuts it at the car, loading what is sent. */
    private fun drive(from: Double, to: Double, horizon: (Double) -> HorizonAhead?) {
        var car = from
        while (car <= to) {
            ahead = horizon(car)
            puck(car)
            line.onProgress()
            scene.jobs.runAll()
            if (scene.style.sent.isNotEmpty()) scene.load()
            if (lanes.sent.isNotEmpty()) scene.loadWindow()
            car += 20.0
        }
    }

    private fun laneLines() = scene.log.lines.filter { it.first == OwnRouteLine.LANE_DRAWING }.map { it.second }

    private fun kinds() = lanes.held?.features().orEmpty().map { it.getStringProperty("kind") }

    private fun north(p: GeoPoint) = Geo.meters(GeoPoint(0.0, 0.0), GeoPoint(p.latitude, 0.0))

    private fun east(p: GeoPoint) = Geo.meters(GeoPoint(p.latitude, 0.0), p) * if (p.longitude < 0.0) -1.0 else 1.0

    @Test
    fun `without a source that tells whether a street is one-way nothing of it is drawn, and each window counts it`() {
        drawn()
        drive(0.0, turn - 200.0) { null }
        val drawing = shown()
        val own = requireNotNull(drawing.lanes)
        // The lanes are laid out but not drawn: the path is the route's own line, and no feature, stop line or arrow is there.
        assertTrue(own.drawnStreets.isEmpty())
        assertTrue(own.undrawnStreets.isNotEmpty() && own.undrawnStreets.none { it.mixed })
        assertEquals(reference.points, drawing.points)
        assertTrue(own.drawing.features().isEmpty())
        assertTrue(own.arrows.isEmpty())
        assertNull(line.goreArrow(id, 0, 1))
        // Every window says so: no street drawn, and the stretches with lane data it holds counted, with their metres.
        val logged = laneLines()
        assertTrue(logged.size >= 2, "$logged")
        assertTrue(logged.all { it["streetM"] == 0L && it["streetMixed"] == 0 })
        assertTrue(logged.any { (it["streetNoOneWay"] as Int) >= 1 && (it["streetNoOneWayM"] as Long) > 0L }, "$logged")
        assertEquals(0, logged.first()["streetNoOneWay"], "the first window holds no lane data")
    }

    @Test
    fun `the horizon's two-way flag draws the streets past the window on screen, stop lines and the bar on the route's lanes`() {
        drawn()
        // The first window was built before any horizon: it draws nothing of a street.
        assertEquals(0L, laneLines().single()["streetM"])
        drive(0.0, turn - 400.0) { horizonCut(it, oneWay = false) }
        val own = requireNotNull(shown().lanes)
        assertTrue(own.drawnStreets.isNotEmpty())
        assertTrue(own.drawnStreets.any { turn - 100.0 in it })
        val sent = laneLines().filter { it["reason"] == OwnRouteLine.REASON_HORIZON }
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.all { (it["streetM"] as Long) > 0L }, "$sent")
        // The window on screen holds the turn: its stop lines where the route's flags give a signal, the lanes' lines, the
        // lit lane, and no line of the oncoming half.
        val kinds = kinds()
        assertTrue("stop_line" in kinds && "lane_line" in kinds && "lit" in kinds, "$kinds")
        assertTrue("edge_line" !in kinds, "no edge line on a two-way street: $kinds")
        // The car is a bar across the route's own lanes only, its tick on the outer side.
        val bar = requireNotNull(line.carBar)
        assertTrue(bar.at(turn - 100.0))
        assertTrue(bar.twoWay)
        assertEquals(2.0 * LaneTopology.STREET_LANE_METERS, bar.width, 1e-6)
        // The turn's arrow along the lit lane, the right one, from 4 m before the stop line's place.
        val arrow = requireNotNull(line.goreArrow(id, 0, 1))
        assertEquals(turn - LaneTopology.STOP_LINE_METERS - GoreArrow.STOP_LINE_BEFORE_METERS, north(arrow.first()), 0.05)
        assertEquals(1.5 * LaneTopology.STREET_LANE_METERS, east(arrow.first()), 0.05)
    }

    @Test
    fun `inside a junction the lit lane crosses no eraser lies over the map's road, and the lit pieces there hide as the car passes`() {
        drawn()
        drive(0.0, turn - 400.0) { horizonCut(it, oneWay = false) }
        val drawing = shown()
        val own = requireNotNull(drawing.lanes)
        // A junction the lit lane crosses in the turn's card window, where the map's own road shows.
        val junction = own.drawing.junctionInsides.first { j -> own.litStations.any { it.start < j.start && it.endInclusive > j.endInclusive } }
        val near = junction.start
        val far = junction.endInclusive
        // The eraser's asphalt on both sides of it, transparent inside it.
        assertTrue(own.erased.none { it.endInclusive > drawing.table.pathFraction(near) + 1e-9 && it.start < drawing.table.pathFraction(far) - 1e-9 }, "${own.erased}")
        assertTrue(own.erased.any { abs(it.endInclusive - drawing.table.pathFraction(near)) < 1e-9 })
        assertTrue(own.erased.any { abs(it.start - drawing.table.pathFraction(far)) < 1e-9 })
        assertEquals(OwnRouteLine.eraserGradient(own.erased, MapPalette.lanes(Look.NIGHT).asphalt), lanes.eraserStops)
        val stops = requireNotNull(lanes.eraserStops)
        val middle = drawing.table.pathFraction((near + far) / 2.0)
        assertEquals(OwnRouteLine.TRANSPARENT, stops.last { it.from <= middle }.colour)
        // Up to it, the window on screen holds the junction's own pieces of the lit lane and of its edges, each a few metres
        // long, ending where the drawing says.
        drive(turn - 380.0, near - 20.0) { horizonCut(it, oneWay = false) }
        val ends = HashMap<String, Double>()
        for (f in own.drawing.features()) {
            val id = f.id ?: continue
            if (!LaneDrawing.isInsideId(id) || f.from < near - 1e-6 || f.to > far + 1e-6) continue
            assertTrue(f.to - f.from <= LaneDrawing.JUNCTION_PIECE_METERS + 1e-6, "a piece over ${f.from}..${f.to}")
            ends[id.toString()] = f.to
        }
        assertTrue(ends.size >= 2 * ((far - near) / LaneDrawing.JUNCTION_PIECE_METERS).toInt(), "$ends")
        val shownIds = requireNotNull(lanes.held).features().orEmpty().mapNotNull { it.id() }.toSet()
        assertEquals(ends.keys, ends.keys.intersect(shownIds), "the junction's pieces in the window on screen")
        // Through it a metre at a time: what the car has passed is hidden at once, and nothing ahead of it.
        val behind = { lanes.states.filter { it.second == LaneLayers.Key.STATE_BEHIND }.map { it.first }.toSet() }
        var car = near - 2.0
        while (car <= far + 2.0) {
            puck(car)
            assertEquals(ends.filter { it.value <= car }.keys, behind().intersect(ends.keys), "at $car")
            car += 1.0
        }
        assertEquals(ends.keys, behind().intersect(ends.keys))
    }

    @Test
    fun `on a drawn street the swap reads the street's lane at the car, in at its own width`() {
        drawn()
        drive(0.0, turn - 400.0) { horizonCut(it, oneWay = false) }
        val own = requireNotNull(shown().lanes)
        val car = turn - 100.0
        assertTrue(own.drawnStreets.any { car in it })
        assertEquals(LaneTopology.STREET_LANE_METERS, own.laneMetersAt(car))
        assertEquals(LaneTopology.FREEWAY_LANE_METERS, own.laneMetersAt(-1.0))
        puck(car)
        // Where a freeway lane would show 10.42 dp the street's shows 9.54: no swap; at 10.5 dp of the street lane, the swap.
        fun zoomOf(dp: Double, meters: Double) = log2(MapScale.metersPerDp(0.0, 0.0) * dp / meters)
        line.onLatched(true)
        line.onCameraZoom(zoomOf(10.42, LaneTopology.FREEWAY_LANE_METERS), 0.0)
        line.onLatched(true)
        assertFalse(line.swapped)
        line.onCameraZoom(zoomOf(10.5, LaneTopology.STREET_LANE_METERS), 0.0)
        assertTrue(line.swapped)
        assertTrue(LaneSwap.laneDp(zoomOf(10.5, LaneTopology.STREET_LANE_METERS), 0.0, LaneTopology.STREET_LANE_METERS) >= LaneSwap.SWAP_IN_STREET_DP)
    }

    @Test
    fun `the horizon's one-way flag lays the lanes about the road's line, the bar with a tick on each side`() {
        drawn()
        drive(0.0, turn - 400.0) { horizonCut(it, oneWay = true) }
        val own = requireNotNull(shown().lanes)
        assertTrue(own.drawnStreets.any { turn - 100.0 in it })
        assertTrue("edge_line" in kinds())
        val bar = requireNotNull(line.carBar)
        assertTrue(bar.at(turn - 100.0))
        assertFalse(bar.twoWay)
        assertEquals(2.0 * LaneTopology.STREET_LANE_METERS, bar.width, 1e-6)
        val arrow = requireNotNull(line.goreArrow(id, 0, 1))
        assertEquals(0.5 * LaneTopology.STREET_LANE_METERS, east(arrow.first()), 0.05)
    }

    @Test
    fun `with the horizon switched off mid-drive the next send draws no street past the window on screen, and keeps the rest`() {
        drawn()
        // The horizon reaches the streets past the first window on screen, sent with the window due at 2,000 m; then it goes off.
        drive(0.0, 2_500.0) { horizonCut(it, oneWay = false) }
        val own = requireNotNull(shown().lanes)
        val before = own.drawnStreets
        val onScreen = own.windows.windowAt(2_000.0)
        assertEquals(OwnRouteLine.REASON_HORIZON, laneLines().last()["reason"])
        assertTrue(before.any { it.endInclusive > onScreen.to + 300.0 }, "$before")
        drive(2_520.0, turn - 400.0) { null }
        val after = requireNotNull(shown().lanes).drawnStreets
        // What the window on screen held when it went off is drawn as it was; nothing past the next send's start is, but the
        // street that begins before it and reaches past it, 100 m at most.
        val start = HorizonEvidence.startFor(onScreen.to, own.windows.cards)
        assertTrue(after.any { onScreen.to - 10.0 in it }, "$after")
        assertTrue(after.none { it.endInclusive > start + StreetJunction.STEP_METERS }, "$after past $start")
        assertTrue(laneLines().last()["streetNoOneWay"] as Int >= 1, "${laneLines().last()}")
    }
}
