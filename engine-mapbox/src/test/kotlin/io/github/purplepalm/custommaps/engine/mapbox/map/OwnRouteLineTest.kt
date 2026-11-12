package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.geometry.PuckProjection
import io.github.purplepalm.custommaps.nav.lanes.geometry.StationTable
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app's own route line on a fake style, with made-up routes due north from 0, 0 (a point every 100 m) and invented
 * route ids.
 */
class OwnRouteLineTest {

    /** A style with room for the own line, and the navigation SDK's primary line in it; every call recorded. */
    private class Style : OwnRouteLine.Layers, OwnRouteLine.Primary {
        override var usable = true
        val calls = ArrayList<String>()

        /** The call that throws, as a style that refuses it. */
        var refuse: String? = null

        var added: List<OwnRouteLine.Layer>? = null
        var addedLook: Look? = null

        /** The path the source holds, as the map has parsed it; null for none. */
        var sourceData: List<GeoPoint>? = null

        /** Paths sent to the source and not parsed yet, by their data ids, oldest first. */
        val sent = LinkedHashMap<String, List<GeoPoint>>()

        /** Whether the map is asked to say when the source's data has loaded. */
        var watching = false
        var stops: List<OwnRouteLine.ColourStop>? = null
        var casing: Int? = null
        var trim: Double? = null
        var layersVisible = false
        var primaryShown = true

        override fun exist(): Boolean = added != null

        override fun add(layers: List<OwnRouteLine.Layer>, look: Look) {
            record("add")
            added = layers
            addedLook = look
            sourceData = null
            sent.clear()
            layersVisible = true
        }

        override fun setPath(points: List<GeoPoint>, dataId: String) {
            record("setPath")
            sent[dataId] = points
        }

        override fun watchLoads(watch: Boolean) {
            record("watchLoads")
            watching = watch
        }

        override fun clearPath() {
            record("clearPath")
            sent.clear()
            sourceData = null
        }

        override fun setColours(stops: List<OwnRouteLine.ColourStop>, casing: Int) {
            record("setColours")
            this.stops = stops
            this.casing = casing
        }

        override fun setTrim(fraction: Double) {
            record("setTrim")
            trim = fraction
        }

        override fun setVisible(visible: Boolean) {
            record("setVisible")
            layersVisible = visible
        }

        override fun remove() {
            record("remove")
            added = null
            sourceData = null
            sent.clear()
        }

        override fun show() {
            record("show")
            primaryShown = true
        }

        override fun hide() {
            record("hide")
            primaryShown = false
        }

        private fun record(call: String) {
            calls += call
            if (call == refuse) throw IllegalStateException("the style refused")
        }

        /** What a style load does: the new style has none of the own layers, and the SDK's line made anew, shown. */
        fun reload() {
            added = null
            sourceData = null
            sent.clear()
            layersVisible = false
            primaryShown = true
        }

        /**
         * The map parses the data sent as [dataId]: the source holds that path from now on, and a path sent before it,
         * which the newer one replaced before it was parsed, never loads.
         */
        fun parse(dataId: String) {
            val keys = sent.keys.toList()
            val k = keys.indexOf(dataId)
            sourceData = sent.getValue(dataId)
            for (older in keys.subList(0, k + 1)) sent.remove(older)
        }

        /** Whether the driver sees the own line: its layers in the style, with a path, shown. */
        val ownShown: Boolean get() = added != null && sourceData != null && layersVisible
    }

    /** A worker whose jobs run when the test says, on the test's own thread. */
    private class Jobs : OwnRouteLine.Worker {
        private val waiting = ArrayList<() -> Unit>()

        override fun <T> run(work: () -> T, done: (Result<T>) -> Unit): OwnRouteLine.Task {
            var cancelled = false
            waiting += {
                if (!cancelled) {
                    val result = try {
                        Result.success(work())
                    } catch (e: RuntimeException) {
                        Result.failure(e)
                    }
                    if (!cancelled) done(result)
                }
            }
            return OwnRouteLine.Task { cancelled = true }
        }

        val pending: Int get() = waiting.size

        fun runAll() {
            while (waiting.isNotEmpty()) waiting.removeAt(0)()
        }
    }

    private class Lines : DebugLog {
        val lines = ArrayList<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }

        fun types(): List<String> = lines.map { it.first }
    }

    private val style = Style()
    private val jobs = Jobs()
    private val log = Lines()
    private val line = OwnRouteLine(style, style, jobs, log)

    private val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    private fun at(station: Double) = GeoPoint(station / metersPerDegree, 0.0)

    /**
     * A drawing of the route [id], 1,000 m due north from 0, 0, a point every 100 m, as path [version]: its path runs
     * [east] metres east of the route's line from 300 to 700 m, as in a lane, with [runs] of traffic.
     */
    private fun drawing(
        id: String,
        version: Int,
        east: Double = 7.32,
        runs: List<TrafficRun> = listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN)),
    ): OwnLineDrawing {
        val along = (0..10).map { it * 100.0 }
        val route = along.map(::at)
        val path = along.map { s -> if (s in 300.0..700.0) GeoPoint(s / metersPerDegree, east / metersPerDegree) else at(s) }
        val stations = DoubleArray(route.size)
        for (i in 1 until route.size) stations[i] = stations[i - 1] + Geo.meters(route[i - 1], route[i])
        val table = StationTable.of(id, version, route, stations, path, stations)
        return OwnLineDrawing(id, version, path, table, runs, PuckProjection.of(route, stations), listOf(stations))
    }

    /** The paths built, in the order they were. */
    private val built = ArrayList<OwnLineDrawing>()

    private fun build(id: String, runs: List<TrafficRun> = listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN))) =
        OwnRouteLine.Build { version -> drawing(id, version, runs = runs).also { built += it } }

    private val noTraffic = OwnRouteLine.Traffic { it.runs }

    /** The map parses the path sent last and, while it is asked to, says so: as the source's own worker thread does. */
    private fun load(): String {
        val dataId = style.sent.keys.last()
        style.parse(dataId)
        if (style.watching) line.onSourceLoaded(dataId)
        return dataId
    }

    /** The switch on, the style loaded, and [id]'s path drawn. */
    private fun drawn(id: String = "route-a#0"): OwnLineDrawing {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute(id, refresh = false, build(id), noTraffic)
        jobs.runAll()
        load()
        assertTrue(line.drawn)
        return built.last()
    }

    // ------------------------------------------------------------------ the switch

    @Test
    fun `with the switch off nothing touches the style or the SDK's line`() {
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        line.onPlan("route-a#0", build("route-a#0"))
        line.onPuck(0.001, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.5, tracking = true)
        line.setLook(Look.DAY)
        line.onRoute("route-a#0", refresh = true, null, noTraffic)
        line.onGuidanceEnded()
        assertEquals(0, jobs.pending, "no path is even built")
        assertEquals(emptyList<String>(), style.calls)
        assertEquals(emptyList<String>(), log.types())
        assertFalse(line.drawn)
    }

    @Test
    fun `the switch adds the source and two line layers empty at every style load`() {
        line.setEnabled(true)
        assertEquals(emptyList<String>(), style.calls, "no style yet")
        line.onStyleLoaded(Look.NIGHT)
        assertEquals(OwnRouteLine.LAYERS, style.added)
        assertEquals(Look.NIGHT, style.addedLook)
        assertNull(style.sourceData, "nothing drawn without a route")
        style.reload()
        line.onStyleLoaded(Look.DAY)
        assertEquals(OwnRouteLine.LAYERS, style.added, "again in the new style")
        assertEquals(Look.DAY, style.addedLook)
        assertNull(style.sourceData)
        assertEquals(listOf("show", "add", "show", "add"), style.calls)
    }

    @Test
    fun `the casing lies right above the SDK's route line and the line above it, both in the middle slot`() {
        val (casing, main) = OwnRouteLine.LAYERS
        assertEquals(listOf(OwnRouteLine.CASING, OwnRouteLine.LINE), OwnRouteLine.LAYERS.map { it.id })
        assertTrue(casing.casing)
        assertFalse(main.casing)
        assertEquals("mapbox-top-level-route-layer", casing.above)
        assertEquals(OwnRouteLine.CASING, main.above)
        assertEquals(listOf("middle", "middle"), OwnRouteLine.LAYERS.map { it.slot })
        assertEquals(listOf(1.0, 1.0), OwnRouteLine.LAYERS.map { it.emissiveStrength })
    }

    @Test
    fun `the line and its casing are the SDK's widths up to zoom 16_5 and never narrower closer in`() {
        val (casing, main) = OwnRouteLine.LAYERS
        var zoom = 3.0
        while (zoom <= 16.5) {
            assertEquals(RouteLineWidths.widthAt(RouteLineWidths.LINE, zoom), RouteLineWidths.widthAt(main.widths, zoom), 1e-9)
            assertEquals(RouteLineWidths.widthAt(RouteLineWidths.CASING, zoom), RouteLineWidths.widthAt(casing.widths, zoom), 1e-9)
            zoom += 0.25
        }
        for (closer in listOf(16.6, 17.5, 18.9, 20.0, 22.0)) {
            assertEquals(10.378, RouteLineWidths.widthAt(main.widths, closer), 0.001)
            assertEquals(15.5, RouteLineWidths.widthAt(casing.widths, closer), 1e-9)
        }
    }

    // ------------------------------------------------------------------ drawing a route

    @Test
    fun `a route's path is drawn once built, and the SDK's primary line hidden`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        style.calls.clear()
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        assertTrue(style.primaryShown, "the SDK's line until the path is drawn")
        assertFalse(style.ownShown)
        assertEquals(1, jobs.pending)
        jobs.runAll()
        val drawing = built.single()
        assertEquals(listOf(drawing.points), style.sent.values.toList(), "sent, with a data id of its own")
        assertTrue(style.watching)
        assertFalse(style.ownShown, "not drawn before the map has loaded it")
        assertTrue(style.primaryShown)
        assertFalse(line.drawn)
        load()
        assertEquals(drawing.points, style.sourceData)
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
        assertTrue(line.drawn)
        assertFalse(style.watching, "nothing waits any more")
        assertEquals(
            listOf("show", "setVisible", "watchLoads", "setPath", "watchLoads", "setColours", "setTrim", "setVisible", "hide"),
            style.calls,
        )
        assertEquals(0.0, style.trim)
    }

    @Test
    fun `a route whose plan is on its way is built once the plan is there`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build = null, traffic = noTraffic)
        assertEquals(0, jobs.pending)
        line.onPlan("route-b#0", build("route-b#0"))
        assertEquals(0, jobs.pending, "another route's plan")
        line.onPlan("route-a#0", build("route-a#0"))
        jobs.runAll()
        load()
        assertEquals("route-a#0", built.single().routeId)
        assertTrue(style.ownShown)
        line.onPlan("route-a#0", build("route-a#0"))
        assertEquals(0, jobs.pending, "built once")
    }

    @Test
    fun `a path built for a route the drive has left is dropped`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        line.onRoute("route-b#0", refresh = false, build("route-b#0"), noTraffic)
        jobs.runAll()
        load()
        assertEquals(listOf("route-b#0"), built.map { it.routeId }, "the first route's job was cancelled")
        assertEquals(built.single().points, style.sourceData)
    }

    @Test
    fun `a path that cannot be built costs the own line and one error line`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, OwnRouteLine.Build { throw IllegalArgumentException("no line") }, noTraffic)
        jobs.runAll()
        assertTrue(style.primaryShown)
        assertFalse(style.ownShown)
        assertFalse(line.drawn)
        assertEquals(listOf("error"), log.types())
        assertEquals(OwnRouteLine.WHERE, log.lines.single().second["where"])
    }

    @Test
    fun `a style that refuses shows the SDK's line again, with one error line`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        style.refuse = "setColours"
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        jobs.runAll()
        load()
        assertTrue(style.primaryShown)
        assertFalse(style.layersVisible)
        assertFalse(line.drawn)
        assertEquals(listOf("error"), log.types())
    }

    @Test
    fun `a style load shows the SDK's line until the path has loaded again on the new style`() {
        val drawing = drawn()
        style.reload()
        line.onStyleLoaded(Look.NIGHT)
        assertTrue(style.primaryShown, "the SDK's line at once, whatever the reload did to its layers")
        assertFalse(style.ownShown)
        assertFalse(line.drawn)
        assertEquals(listOf(drawing.points), style.sent.values.toList())
        load()
        assertEquals(drawing.points, style.sourceData)
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
        assertEquals(1, built.size, "the path is not built again")
    }

    // ------------------------------------------------------------------ the swap at a change of route

    @Test
    fun `a reroute shows the SDK's line until the new path has loaded, never the old path`() {
        val first = drawn("route-a#0")
        line.onPuck(at(300.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = first.table.routeFraction(300.0), tracking = true)
        val trimOnA = style.trim
        line.onRoute("route-b#0", refresh = false, build("route-b#0"), noTraffic)
        assertTrue(style.primaryShown, "the SDK's line at once, trimmed, as it was fed all along")
        assertFalse(style.layersVisible)
        assertFalse(line.drawn)
        // The SDK has the new route: its points are never taken through the old route's table.
        line.onPuck(at(20.0).latitude, 0.0, accepted = true, sdkRouteId = "route-b#0", offset = 0.02, tracking = true)
        assertEquals(trimOnA, style.trim)
        // Built and sent, but not loaded: the source still holds the old path, and it stays hidden.
        jobs.runAll()
        val second = built.last()
        assertEquals("route-b#0", second.routeId)
        assertEquals(first.points, style.sourceData)
        assertFalse(style.layersVisible)
        assertTrue(style.primaryShown)
        line.onPuck(at(30.0).latitude, 0.0, accepted = true, sdkRouteId = "route-b#0", offset = 0.03, tracking = true)
        assertEquals(trimOnA, style.trim)
        // Loaded: the new path, its table and its trim together, and the SDK's line hidden again.
        load()
        assertEquals(second.points, style.sourceData)
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
        assertEquals(second.table.pathFraction(second.table.stationAt(0.03)), style.trim!!, 1e-12)
        // A point of the old route from now on goes through no table.
        val trimOnB = style.trim
        line.onPuck(at(500.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.5, tracking = false)
        assertEquals(trimOnB, style.trim)
    }

    @Test
    fun `a path whose data loads after a newer one was sent is never drawn`() {
        drawn("route-a#0")
        line.onRoute("route-b#0", refresh = false, build("route-b#0"), noTraffic)
        jobs.runAll()
        val forB = style.sent.keys.single()
        line.onRoute("route-c#0", refresh = false, build("route-c#0"), noTraffic)
        jobs.runAll()
        assertEquals(2, style.sent.size, "both sent, neither parsed yet")
        // The map parsed the second route's path late, after the third route's was sent.
        style.parse(forB)
        line.onSourceLoaded(forB)
        assertFalse(style.layersVisible, "the second route's path is not drawn")
        assertTrue(style.primaryShown)
        load()
        assertEquals(built.last().points, style.sourceData)
        assertEquals("route-c#0", built.last().routeId)
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
    }

    @Test
    fun `a style load while a path waits sends it again, and only the new style's data is drawn`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        jobs.runAll()
        val beforeReload = style.sent.keys.single()
        style.reload()
        line.onStyleLoaded(Look.NIGHT)
        val afterReload = style.sent.keys.single()
        assertNotEquals(beforeReload, afterReload)
        line.onSourceLoaded(beforeReload)
        assertFalse(line.drawn, "the old style's data")
        load()
        assertTrue(line.drawn)
        assertTrue(style.ownShown)
    }

    @Test
    fun `data that loads after guidance ended draws nothing, and the source is cleared`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        jobs.runAll()
        val waiting = style.sent.keys.single()
        assertTrue(style.watching)
        line.onGuidanceEnded()
        assertFalse(style.watching, "the map is no longer asked about loads")
        assertNull(style.sourceData)
        assertTrue(style.sent.isEmpty(), "the source cleared")
        assertTrue(style.primaryShown)
        line.onSourceLoaded(waiting)
        assertFalse(line.drawn)
        assertFalse(style.layersVisible)
        assertTrue(style.primaryShown)
    }

    @Test
    fun `data the own line never sent changes nothing`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        jobs.runAll()
        line.onSourceLoaded("own-99")
        line.onSourceLoaded("")
        assertFalse(line.drawn)
        assertTrue(style.watching, "still waiting for its own")
    }

    @Test
    fun `the switch off forgets the route, and on again draws the route the drive is on at that time`() {
        drawn("route-a#0")
        line.setEnabled(false)
        // The map tells the own line nothing while it is off; a reroute meanwhile is told when it is on again.
        line.setEnabled(true)
        assertEquals(0, jobs.pending, "the old route is not built again")
        line.onRoute("route-b#0", refresh = false, build("route-b#0"), noTraffic)
        jobs.runAll()
        load()
        assertEquals(listOf("route-a#0", "route-b#0"), built.map { it.routeId })
        assertEquals(built.last().points, style.sourceData)
        assertTrue(style.ownShown)
    }

    // ------------------------------------------------------------------ the trim

    @Test
    fun `the trim is the SDK's vanishing point taken onto the path through its station table`() {
        val drawing = drawn()
        val table = drawing.table
        // The SDK's point at 400 m, where the path lies in the lane, 7.32 m east of the route's line.
        val sdk = table.routeFraction(400.0)
        line.onPuck(at(400.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = sdk, tracking = true)
        assertEquals(table.pathFraction(400.0), style.trim!!, 1e-12)
        assertEquals(table.pathFractionAt("route-a#0", drawing.version, sdk)!!, style.trim!!, 1e-12)
        // The path's moves into the lane and out of it make it longer than the route: the fractions differ.
        assertNotEquals(sdk, style.trim!!, 1e-9)
    }

    @Test
    fun `another route's vanishing point never goes through this route's table`() {
        drawn()
        line.onPuck(at(500.0).latitude, 0.0, accepted = true, sdkRouteId = "route-b#0", offset = 0.9, tracking = false)
        assertEquals(0.0, style.trim)
    }

    @Test
    fun `where the SDK skips its point while the drive is tracked, the app's own projection trims the line`() {
        val drawing = drawn()
        line.onPuck(at(620.0).latitude, 0.0, accepted = false, sdkRouteId = null, offset = Double.NaN, tracking = true)
        assertEquals(drawing.table.pathFraction(620.0), style.trim!!, 1e-9)
        // While the drive is not tracked, as the SDK does, the line stays as it is.
        line.onPuck(at(820.0).latitude, 0.0, accepted = false, sdkRouteId = null, offset = Double.NaN, tracking = false)
        assertEquals(drawing.table.pathFraction(620.0), style.trim!!, 1e-9)
        // Another route's point in the SDK, as before it has the drive's new route: the projection stands in.
        line.onPuck(at(700.0).latitude, 0.0, accepted = true, sdkRouteId = "route-z#0", offset = 0.1, tracking = true)
        assertEquals(drawing.table.pathFraction(700.0), style.trim!!, 1e-9)
    }

    @Test
    fun `a path drawn after the SDK took its point is trimmed there at once`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        line.onPuck(at(400.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.4, tracking = true)
        assertNull(style.trim, "nothing drawn yet")
        jobs.runAll()
        load()
        val table = built.single().table
        assertEquals(table.pathFraction(table.stationAt(0.4)), style.trim!!, 1e-12)
    }

    // ------------------------------------------------------------------ colours

    @Test
    fun `the line takes the route line's colours of the look by traffic level, and the casing its own`() {
        val runs = listOf(
            TrafficRun(0.0, 0.2, TrafficLevel.UNKNOWN),
            TrafficRun(0.2, 0.3, TrafficLevel.LOW),
            TrafficRun(0.3, 0.5, TrafficLevel.MODERATE),
            TrafficRun(0.5, 0.6, TrafficLevel.HEAVY),
            TrafficRun(0.6, 0.7, TrafficLevel.SEVERE),
            TrafficRun(0.7, 0.8, TrafficLevel.CLOSED),
            TrafficRun(0.8, 1.0, TrafficLevel.LOW),
        )
        val night = MapPalette.routeLine(Look.NIGHT)
        assertEquals(
            listOf(
                // Unknown and low are both the amber of the route: one stop.
                OwnRouteLine.ColourStop(0.0, night.getValue(RouteLineColor.ROUTE_UNKNOWN)),
                OwnRouteLine.ColourStop(0.3, night.getValue(RouteLineColor.ROUTE_MODERATE)),
                OwnRouteLine.ColourStop(0.5, night.getValue(RouteLineColor.ROUTE_HEAVY)),
                OwnRouteLine.ColourStop(0.6, night.getValue(RouteLineColor.ROUTE_SEVERE)),
                OwnRouteLine.ColourStop(0.7, night.getValue(RouteLineColor.ROUTE_CLOSED)),
                OwnRouteLine.ColourStop(0.8, night.getValue(RouteLineColor.ROUTE_LOW)),
            ),
            OwnRouteLine.colourStops(runs, Look.NIGHT),
        )
        assertEquals(MapPalette.CASING_NIGHT, OwnRouteLine.casingColour(Look.NIGHT))
        assertEquals(MapPalette.CASING_DAY, OwnRouteLine.casingColour(Look.DAY))
        assertEquals(MapPalette.TRAFFIC_HEAVY_DAY, OwnRouteLine.colourStops(runs, Look.DAY)[2].colour)
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, MapPalette.AMBER)), OwnRouteLine.colourStops(emptyList(), Look.DAY))
    }

    @Test
    fun `a change of look gives the drawn line and its casing the new look's colours`() {
        drawn()
        assertEquals(MapPalette.CASING_NIGHT, style.casing)
        line.setLook(Look.DAY)
        assertEquals(MapPalette.CASING_DAY, style.casing)
        assertEquals(OwnRouteLine.colourStops(built.single().runs, Look.DAY), style.stops)
    }

    @Test
    fun `a refresh gives the line its new traffic and draws nothing else`() {
        drawn()
        style.calls.clear()
        val heavy = OwnRouteLine.Traffic { listOf(TrafficRun(0.0, 1.0, TrafficLevel.HEAVY)) }
        line.onRoute("route-a#0", refresh = true, build("route-a#0"), heavy)
        jobs.runAll()
        assertEquals(listOf("setColours"), style.calls)
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, MapPalette.TRAFFIC_HEAVY_NIGHT)), style.stops)
        assertEquals(1, built.size, "the path is not built again")
    }

    @Test
    fun `a refresh while the path is built is read once it is`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        val severe = OwnRouteLine.Traffic { listOf(TrafficRun(0.0, 1.0, TrafficLevel.SEVERE)) }
        line.onRoute("route-a#0", refresh = true, null, severe)
        jobs.runAll()
        load()
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, MapPalette.TRAFFIC_SEVERE_NIGHT)), style.stops)
    }

    // ------------------------------------------------------------------ the end

    @Test
    fun `guidance end shows the SDK's line and clears the own source`() {
        drawn()
        line.onGuidanceEnded()
        assertTrue(style.primaryShown)
        assertNull(style.sourceData)
        assertFalse(style.layersVisible)
        assertFalse(line.drawn)
        assertEquals(OwnRouteLine.LAYERS, style.added, "the layers stay, empty, for the next drive")
        // A late puck draws nothing.
        line.onPuck(at(500.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.5, tracking = true)
        assertNull(style.sourceData)
    }

    @Test
    fun `the switch off during a drive shows the SDK's line and takes the own layers out`() {
        drawn()
        line.setEnabled(false)
        assertTrue(style.primaryShown)
        assertNull(style.added)
        assertFalse(line.drawn)
        style.calls.clear()
        line.onPuck(at(500.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.5, tracking = true)
        line.onRoute("route-b#0", refresh = false, build("route-b#0"), noTraffic)
        jobs.runAll()
        assertEquals(emptyList<String>(), style.calls)
    }

    @Test
    fun `the switch on during a drive draws the route the drive is on`() {
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        assertEquals(0, jobs.pending)
        line.setEnabled(true)
        jobs.runAll()
        load()
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
        assertEquals(built.single().points, style.sourceData)
    }

    @Test
    fun `a map that went away builds and sets nothing more`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, build("route-a#0"), noTraffic)
        line.release()
        style.calls.clear()
        jobs.runAll()
        assertEquals(emptyList<String>(), style.calls)
    }
}
