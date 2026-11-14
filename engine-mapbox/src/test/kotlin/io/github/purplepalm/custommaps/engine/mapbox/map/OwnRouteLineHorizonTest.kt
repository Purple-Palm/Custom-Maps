package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The electronic horizon's lane counts in the own line's next drawing window (OL9), on a fake style ([OwnLineScene]) along a
 * made-up freeway exit ([FreewayExit]) with lane points about 4,000 and 6,200 m along and a road without lane data leaving
 * on the left between them, so the route's own rules leave a gap of about 1,200 m there: the window that is due goes with a
 * path built anew, both switch together once both have loaded, the path up to the window on screen stays point for point,
 * and no window is sent again for the horizon's sake. Invented route ids and synthetic horizons.
 */
class OwnRouteLineHorizonTest {

    private val id = "route-h#0"
    private val route = FreewayExit.route(75, listOf(40, 62), leftRoad = 47)
    private val legs = route.first
    private val shapes = route.second
    private val plan = requireNotNull(FreewayExit.plan(id, legs, shapes))
    private val points = plan.plan.topology.points
    private val p = points[0].station
    private val q = points[1].station
    private val gore = points[2].station
    private val road = plan.input.route.nodes.first { it.station > p && it.station < q }.station

    /** The route's line and table as every path of it has them: where an SDK fraction lies. */
    private val reference = ownLineDrawing(id, legs, shapes, plan, 0, lanes = true)

    /** The horizon's newest update as the engine would hold it; how often the line asked for one. */
    private var ahead: HorizonAhead? = null
    private var asked = 0
    private val scene = OwnLineScene { routeId ->
        asked++
        ahead?.takeIf { it.routeId == routeId }
    }
    private val style = scene.style
    private val lanes = scene.lanes
    private val line = scene.line
    private val built = ArrayList<OwnLineDrawing>()
    private val horizonBuilds = ArrayList<HorizonEvidence>()
    private val prefixes = ArrayList<PathPrefix>()

    /** A build that takes the horizon's counts where asked, as the map's own does; [fail] makes the horizon's throw. */
    private var fail = false
    private fun build() = object : OwnRouteLine.Build {
        override fun drawing(version: Int, lanes: Boolean): OwnLineDrawing =
            ownLineDrawing(id, legs, shapes, plan, version, lanes).also { built += it }

        override fun drawing(version: Int, lanes: Boolean, horizon: HorizonEvidence, prefix: PathPrefix): OwnLineDrawing {
            if (fail) throw IllegalStateException("no drawing")
            horizonBuilds += horizon
            prefixes += prefix
            return ownLineDrawing(id, legs, shapes, plan, version, lanes, horizon, prefix).also { built += it }
        }
    }

    private val traffic = OwnRouteLine.Traffic { it.runs }
    private val windows = ArrayList<Int>()

    /** A horizon from a car at [car]: three lanes on every edge, an edge end at each intersection to the gore, the ramp after it. */
    private fun horizonFrom(car: Double, count: Int = 3) = HorizonAhead.of(
        id,
        car,
        0.0,
        listOf(edge(p - car, count), edge(road - p, count), edge(q - road, count), edge(gore - q, 3), HorizonEdge(200.0, 1, false, true, true, HorizonRoadClass.MOTORWAY)),
    )

    private fun edge(length: Double, lanes: Int) = HorizonEdge(length, lanes, true, false, true, HorizonRoadClass.MOTORWAY)

    private fun puck(station: Double) {
        val place = scene.at(station)
        line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = id, offset = reference.table.routeFraction(station), tracking = true)
    }

    /** The lanes on, the style loaded, the route given, its path and first window built and loaded. */
    private fun drawn() {
        line.onHorizonWindow = { taken -> windows += taken }
        line.setLanes(true)
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        puck(0.0)
        line.onRoute(id, refresh = false, build(), traffic)
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
    }

    /** Drives from [from] in steps of 20 m until something is sent, at most to [to]; the car's station then, null if nothing. */
    private fun driveUntilSent(from: Double, to: Double): Double? {
        var car = from
        while (car <= to) {
            puck(car)
            line.onProgress()
            scene.jobs.runAll()
            if (lanes.sent.isNotEmpty() || style.sent.isNotEmpty()) return car
            car += 20.0
        }
        return null
    }

    private fun laneLines() = scene.log.lines.filter { it.first == OwnRouteLine.LANE_DRAWING }.map { it.second }

    @Test
    fun `the next window goes with a path built with the horizon's counts, and both switch once both have loaded`() {
        drawn()
        val first = built.single()
        val windowEnd = first.lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        val car = requireNotNull(driveUntilSent(0.0, windowEnd))
        // Sent with the window that came due, together with the path, the line on screen as it was.
        assertEquals(windowEnd - 1_000.0, car, 20.0)
        assertEquals(1, style.sent.size)
        assertEquals(1, lanes.sent.size)
        assertTrue(line.drawn)
        assertFalse(style.primaryShown)
        val next = built.last()
        assertEquals(2, built.size)
        assertEquals(listOf(id), listOf(next.routeId))
        assertTrue(next.version > first.version)
        // The path up to the window on screen's end stays point for point: the build was handed the path drawn, to its end.
        val prefix = prefixes.single()
        assertSame(first.points, prefix.points)
        assertSame(first.stations, prefix.stations)
        assertEquals(windowEnd, prefix.at)
        val sentPath = style.sent.values.single()
        val kept = first.stations.count { it <= windowEnd }
        assertEquals(first.points.subList(0, kept), sentPath.subList(0, kept))
        // The gap between the lane points is bridged by the horizon's count, past the window only.
        val drawnWith = requireNotNull(next.horizon).evidence
        assertTrue(drawnWith.stretches.any { it.horizon && it.kind == LaneEvidence.Kind.BRIDGED && it.from == p })
        assertTrue(drawnWith.stretches.filter { it.horizon }.all { it.from >= windowEnd })
        // The plan is the route's own, untouched.
        assertSame(plan.plan, requireNotNull(next.horizon).plan)
        // Until both have loaded, the gradients and the trim are the old path's: nothing of them is written.
        val gradients = style.gradients.toMap()
        val trim = style.trim
        var calls = style.calls.size
        scene.load()
        assertEquals(gradients, style.gradients.toMap())
        assertEquals(trim, style.trim)
        assertTrue(style.calls.drop(calls).none { it == "setGradients" || it == "setTrim" || it == "show" }, "${style.calls.drop(calls)}")
        calls = style.calls.size
        scene.loadWindow()
        // Both loaded: written together, at once.
        assertTrue(style.calls.drop(calls).containsAll(listOf("setGradients", "setTrim", "lanes.setEraserTrim")), "${style.calls.drop(calls)}")
        // Both loaded: the new path's table, gradients and trim together, the SDK's line never shown on the way.
        assertFalse(style.primaryShown)
        assertTrue(line.drawn)
        assertEquals(next.table.pathFraction(car), requireNotNull(style.trim), 1e-12)
        val logged = laneLines().last()
        assertEquals(OwnRouteLine.REASON_HORIZON, logged["reason"])
        assertEquals(2, logged["window"])
        assertTrue((logged["horizonM"] as Long) > 0L, "$logged")
        assertEquals(0, logged["horizonZones"])
        // The trip summary hears of it, with the stretches its send took: both pairs past the window.
        assertEquals(listOf(requireNotNull(next.horizon).taken), windows)
        assertEquals(3, windows.single())
        // The first window's line had none.
        assertEquals(0L, laneLines().first()["horizonM"])
    }

    @Test
    fun `a path built anew keeps the one drawn up to the end of the window on screen, point for point, and is measured so`() {
        val plain = ownLineDrawing(id, legs, shapes, plan, 1, lanes = true)
        val at = plain.stations[plain.stations.size / 2]
        // A path drawn that lies a metre east of this one: what it keeps is its own, not the new build's.
        val shifted = plain.points.map { io.github.purplepalm.custommaps.nav.model.GeoPoint(it.latitude, it.longitude + 1.0 / 111_320.0) }
        val held = requireNotNull(plain.horizon)
        val kept = ownLineDrawing(id, legs, shapes, plan, 2, lanes = true, horizon = held, prefix = PathPrefix(shifted, plain.stations, at))
        val n = plain.stations.count { it <= at }
        assertEquals(shifted.subList(0, n), kept.points.subList(0, n))
        assertEquals(plain.points.drop(n), kept.points.drop(n))
        assertEquals(plain.stations.asList(), kept.stations.asList())
        assertEquals(2, kept.table.pathVersion)
        // The table measures the points sent: a fraction of the kept path, not of the build's own.
        val s = at / 2.0
        assertTrue(kotlin.math.abs(kept.table.pathFraction(s) - plain.table.pathFraction(s)) < 1e-6)
        assertSame(held, kept.horizon)
        // Without the lanes, no horizon is held.
        assertNull(ownLineDrawing(id, legs, shapes, plan, 3, lanes = false).horizon)
    }

    @Test
    fun `the horizon is asked for only when a window is due, and an update already taken builds nothing again`() {
        drawn()
        val windowEnd = built.single().lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        // Driving to just before the window is due: no update asked for, nothing sent.
        var car = 0.0
        while (car < windowEnd - 1_100.0) {
            puck(car)
            line.onProgress()
            scene.jobs.runAll()
            car += 20.0
        }
        assertEquals(0, asked)
        assertTrue(style.sent.isEmpty() && lanes.sent.isEmpty())
        requireNotNull(driveUntilSent(car, windowEnd))
        scene.load()
        scene.loadWindow()
        assertEquals(1, horizonBuilds.size)
        // The next send with the same update: the window from the drawing as it is, no path again.
        val nextEnd = built.last().lanes!!.windows.windowAt(windowEnd - 1_000.0).to
        requireNotNull(driveUntilSent(windowEnd - 980.0, nextEnd))
        assertTrue(style.sent.isEmpty())
        assertEquals(1, lanes.sent.size)
        scene.loadWindow()
        assertEquals(1, horizonBuilds.size)
        assertEquals(OwnRouteLine.REASON_AHEAD, laneLines().last()["reason"])
        // Windows are numbered one after the other: none was sent again.
        assertEquals((1..laneLines().size).toList(), laneLines().map { it["window"] })
    }

    @Test
    fun `with the switch off the next send takes nothing new from the horizon, and what was sent stays`() {
        drawn()
        val windowEnd = built.single().lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        requireNotNull(driveUntilSent(0.0, windowEnd))
        scene.load()
        scene.loadWindow()
        val withHorizon = built.last()
        // Off: the engine holds no update any more.
        ahead = null
        val nextEnd = withHorizon.lanes!!.windows.windowAt(windowEnd - 1_000.0).to
        requireNotNull(driveUntilSent(windowEnd - 980.0, nextEnd))
        // The second pair lay past this window: it is built again without it; the first pair, partly on screen, stays.
        assertEquals(1, style.sent.size)
        val off = built.last()
        assertTrue(off.version > withHorizon.version)
        val evidence = requireNotNull(off.horizon).evidence
        assertTrue(evidence.stretches.any { it.horizon && it.from == p })
        assertEquals(listOf(p), requireNotNull(off.horizon).lanes.between.map { it.from })
        assertEquals(0, requireNotNull(off.horizon).taken)
        scene.load()
        scene.loadWindow()
        // Nothing past the window: a later send with the switch still off builds no path again.
        val laterEnd = off.lanes!!.windows.windowAt(nextEnd - 1_000.0).to
        val sent = style.sent.size
        driveUntilSent(nextEnd - 980.0, laterEnd)
        assertEquals(sent, style.sent.size)
    }

    @Test
    fun `a new route while a path with the horizon's counts loads drops it, and the new route begins without them`() {
        drawn()
        val windowEnd = built.single().lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        requireNotNull(driveUntilSent(0.0, windowEnd))
        // A reroute: the SDK's line on the new route until its own path has loaded, never the path that waited.
        line.onRoute("route-i#0", refresh = false, scene.build("route-i#0"), scene.noTraffic)
        assertTrue(style.primaryShown)
        assertFalse(line.drawn)
        scene.jobs.runAll()
        assertTrue(style.sent.keys.size >= 1)
        val sentLast = style.sent.values.last()
        assertEquals(scene.built.last().points, sentLast)
        scene.load()
        assertTrue(line.drawn)
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `a style load while such a path loads sends it again with its window, and draws it`() {
        drawn()
        val windowEnd = built.single().lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        requireNotNull(driveUntilSent(0.0, windowEnd))
        val next = built.last()
        scene.reload()
        line.onStyleLoaded(Look.NIGHT)
        assertTrue(style.primaryShown)
        // The path built with the horizon goes again, with the window it was built for.
        assertEquals(next.points, style.sent.values.last())
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
        assertEquals(next.table.pathFraction(windowEnd - 1_000.0), requireNotNull(style.trim), 23.0 / next.table.pathLength / 40_075_016.686)
    }

    @Test
    fun `a build with the horizon's counts that fails costs them alone, with one error line, never the line`() {
        drawn()
        val windowEnd = built.single().lanes!!.windows.windowAt(0.0).to
        ahead = horizonFrom(windowEnd - 1_200.0)
        fail = true
        val car = requireNotNull(driveUntilSent(0.0, windowEnd).let { it ?: windowEnd })
        assertTrue(line.drawn)
        assertFalse(style.primaryShown)
        assertEquals(1, scene.log.types().count { it == "error" })
        // The next update builds the window as before, without the horizon, for the rest of the route.
        puck(car + 20.0)
        line.onProgress()
        scene.jobs.runAll()
        assertEquals(1, lanes.sent.size)
        assertTrue(style.sent.isEmpty())
        scene.loadWindow()
        assertEquals(OwnRouteLine.REASON_AHEAD, laneLines().last()["reason"])
        assertNull(scene.log.lines.firstOrNull { it.first == OwnRouteLine.ROUTE_LINE && it.second["reason"] == OwnRouteLine.REASON_FALLBACK })
    }
}
