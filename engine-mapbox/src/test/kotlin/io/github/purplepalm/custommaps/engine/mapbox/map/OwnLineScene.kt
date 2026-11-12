package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.FeatureCollection
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.geometry.PuckProjection
import io.github.purplepalm.custommaps.nav.lanes.geometry.StationTable
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The app's own route line on a fake style, for its tests: a fake style with the navigation SDK's primary line in it, a
 * worker that runs its jobs when a test says, a debug log that keeps its lines, and made-up routes due north from 0, 0
 * (a point every 100 m) with invented route ids. One scene for each test.
 */
internal class OwnLineScene {

    /** A style with room for the own line, and the navigation SDK's primary line in it; every call recorded. */
    class Style : OwnRouteLine.Layers, OwnRouteLine.Primary {
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

        /** The gradients set with the app's lanes, by layer. */
        val gradients = LinkedHashMap<String, List<OwnRouteLine.ColourStop>>()
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

        override fun setGradients(gradients: List<OwnRouteLine.Gradient>) {
            record("setGradients")
            for (g in gradients) this.gradients[g.layerId] = g.stops
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

    /** The layers of the app's own lanes in the same fake style: every call recorded in its [calls] as "lanes." and its name. */
    class Lanes(private val calls: MutableList<String>) : LaneLayers.Port {
        /** The call that throws, as a style that refuses it. */
        var refuse: String? = null

        /** Where the layers were put, as the last addition asked; null while they are not in the style. */
        var positions: List<LaneLayers.Position>? = null
        var shownColours: LaneColours? = null
        var latitude: Double? = null

        /** The window the source holds, as the map has parsed it; null for none. */
        var held: FeatureCollection? = null

        /** Windows sent to the source and not parsed yet, by their data ids, oldest first. */
        val sent = LinkedHashMap<String, FeatureCollection>()

        /** Whether the map is asked to say when the source's data has loaded. */
        var watching = false

        /** The feature states set: each piece's id with its state's key and value, in the order they were set. */
        val states = ArrayList<Triple<String, String, Any?>>()

        /** Whether what is lit for the route is shown. */
        var litShown = true

        override fun exist(): Boolean = positions != null

        override fun add(positions: List<LaneLayers.Position>, colours: LaneColours, latitude: Double) {
            record("add")
            this.positions = positions
            shownColours = colours
            this.latitude = latitude
        }

        override fun setColours(colours: LaneColours) {
            record("setColours")
            shownColours = colours
        }

        override fun setLatitude(latitude: Double) {
            record("setLatitude")
            this.latitude = latitude
        }

        override fun setWindow(features: FeatureCollection, dataId: String) {
            record("setWindow")
            sent[dataId] = features
        }

        override fun watchLoads(watch: Boolean) {
            record("watchLoads")
            watching = watch
        }

        override fun clear() {
            record("clear")
            sent.clear()
            held = null
        }

        override fun setStates(behind: List<String>, levels: List<Pair<String, String?>>) {
            record("setStates")
            for (id in behind) states += Triple(id, LaneLayers.Key.STATE_BEHIND, true)
            for ((id, level) in levels) states += Triple(id, LaneLayers.Key.STATE_LEVEL, level)
        }

        override fun setLitVisible(visible: Boolean) {
            record("setLitVisible")
            litShown = visible
        }

        override fun remove() {
            record("remove")
            positions = null
            held = null
            sent.clear()
        }

        /** What a style load does: the new style has none of the lanes' layers, nor their source's data. */
        fun reload() {
            positions = null
            held = null
            sent.clear()
        }

        /** The map parses the window sent as [dataId]: the source holds it, and windows sent before it never load. */
        fun parse(dataId: String) {
            val keys = sent.keys.toList()
            val k = keys.indexOf(dataId)
            held = sent.getValue(dataId)
            for (older in keys.subList(0, k + 1)) sent.remove(older)
        }

        private fun record(call: String) {
            calls += "lanes.$call"
            if (call == refuse) throw IllegalStateException("the style refused")
        }
    }

    /** A worker whose jobs run when the test says, on the test's own thread. */
    class Jobs : OwnRouteLine.Worker {
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

    class Lines : DebugLog {
        val lines = ArrayList<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }

        fun types(): List<String> = lines.map { it.first }
    }

    val style = Style()
    val lanes = Lanes(style.calls)
    val jobs = Jobs()
    val log = Lines()

    /** The test's clock, in nanoseconds. */
    var now = 0L

    val line = OwnRouteLine(style, style, jobs, log, lanes) { now }

    /** What a style load does to the fake style: none of the own layers, the lanes' neither, and the SDK's line shown. */
    fun reload() {
        style.reload()
        lanes.reload()
    }

    private val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    /** The place [station] metres north of 0, 0. */
    fun at(station: Double) = GeoPoint(station / metersPerDegree, 0.0)

    /**
     * A drawing of the route [id], 1,000 m due north from 0, 0, a point every 100 m, as path [version]: its path runs
     * [east] metres east of the route's line from 300 to 700 m, as in a lane, with [runs] of traffic.
     */
    fun drawing(
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
    val built = ArrayList<OwnLineDrawing>()

    fun build(id: String, runs: List<TrafficRun> = listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN))) =
        OwnRouteLine.Build { version, _ -> drawing(id, version, runs = runs).also { built += it } }

    val noTraffic = OwnRouteLine.Traffic { it.runs }

    /** The map parses the lanes' window sent last and, while it is asked to, says so. */
    fun loadWindow(): String {
        val dataId = lanes.sent.keys.last()
        lanes.parse(dataId)
        if (lanes.watching) line.onSourceLoaded(dataId)
        return dataId
    }

    /** The map parses the path sent last and, while it is asked to, says so: as the source's own worker thread does. */
    fun load(): String {
        val dataId = style.sent.keys.last()
        style.parse(dataId)
        if (style.watching) line.onSourceLoaded(dataId)
        return dataId
    }

    /** The switch on, the style loaded, and [id]'s path drawn. */
    fun drawn(id: String = "route-a#0"): OwnLineDrawing {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute(id, refresh = false, build(id), noTraffic)
        jobs.runAll()
        load()
        assertTrue(line.drawn)
        return built.last()
    }
}
