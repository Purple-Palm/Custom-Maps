package io.github.purplepalm.custommaps.engine.mapbox.map

import com.google.gson.JsonObject
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.MultiPolygon
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.geometry.CarBar
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection
import io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows
import io.github.purplepalm.custommaps.nav.lanes.geometry.GoreArrow
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.lanes.geometry.RoutePath
import io.github.purplepalm.custommaps.nav.lanes.geometry.StationTable
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/**
 * The app's own lanes along one path of a route, as the map draws them ([LaneLayers]): the drawing ([LaneDrawing]), the
 * windows of it the map holds around the car ([DrawingWindows]), where along the path a lane is lit ([lit]), so the
 * route line lies in two pairs of layers, one over the lit stretches and one over the rest, and the maneuver arrows laid
 * along the path at its gores ([arrows]). Built once with the path, off the main thread, and kept in memory with it,
 * never stored; [window] makes a window's features off the main thread too.
 */
internal class OwnLanes(
    /** The features in metres. */
    val drawing: LaneDrawing,
    /** Which window the map holds, and when the next is due. */
    val windows: DrawingWindows,
    /** The route stations where a lane is lit. */
    val litStations: List<ClosedFloatingPointRange<Double>>,
    /** The same stretches as fractions of the path's projected length, as the route line's gradients take them. */
    val lit: List<ClosedFloatingPointRange<Double>>,
    /**
     * The arrow of each maneuver that lies at a gore of the drawing, by its leg and the step it begins ([StepAt]): the
     * path's points around the gore ([GoreArrow]), for the navigation SDK's arrow API in place of its own arrow.
     */
    val arrows: Map<StepAt, List<GeoPoint>> = emptyMap(),
) {

    /** The step [step] of the leg [leg] of a route, both counted from 0 as the navigation SDK counts them. */
    data class StepAt(val leg: Int, val step: Int)

    /**
     * The features of [window] as the drawing's source takes them, with the traffic of each lit piece from [runs] along
     * [table]'s path. The tint pieces of the lanes that end at one station are one feature ([mergeTints]). With them the car's
     * bar along the window ([CarBar]), for the car drawn over the lanes in a close view.
     */
    fun window(window: DrawingWindows.Window, table: StationTable, runs: List<TrafficRun>): LaneWindow {
        val features = mergeTints(drawing.features(window.from, window.to))
        val out = ArrayList<Feature>(features.size)
        val tints = ArrayList<LaneWindow.Piece>()
        val levels = ArrayList<Pair<String, String>>()
        var coordinates = 0
        var latitude = Double.NaN
        for (f in features) {
            val geometry = geometryOf(f) ?: continue
            if (latitude.isNaN()) latitude = f.parts.first().first().latitude
            coordinates += f.coordinates
            val properties = JsonObject()
            properties.addProperty(LaneLayers.Key.KIND, f.kind.logName)
            f.paint?.let { properties.addProperty(LaneLayers.Key.PAINT, LaneLayers.paintName(it)) }
            f.width?.let { properties.addProperty(LaneLayers.Key.WIDTH, LaneLayers.widthName(it)) }
            val id = f.id?.toString()
            out.add(Feature.fromGeometry(geometry, properties, id))
            if (id == null) continue
            when (f.kind) {
                LaneDrawing.Kind.TINT -> tints.add(LaneWindow.Piece(id, f.to))
                LaneDrawing.Kind.LIT -> levelName(levelAt(runs, table.pathFraction((f.from + f.to) / 2.0)))?.let { levels.add(id to it) }
                else -> Unit
            }
        }
        val collection = FeatureCollection.fromFeatures(out)
        return LaneWindow(
            window = window,
            collection = collection,
            features = out.size,
            coordinates = coordinates,
            bytes = collection.toJson().length,
            tints = tints.sortedBy { it.to },
            levels = levels,
            latitude = latitude,
            carBar = CarBar.of(drawing, window.from, window.to),
        )
    }

    /** The traffic of [window]'s lit pieces again, from the [runs] of a refresh. */
    fun levels(window: LaneWindow, table: StationTable, runs: List<TrafficRun>): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        for (f in drawing.features(window.window.from, window.window.to)) {
            if (f.kind != LaneDrawing.Kind.LIT) continue
            val id = f.id?.toString() ?: continue
            out.add(id to levelName(levelAt(runs, table.pathFraction((f.from + f.to) / 2.0))))
        }
        return out
    }

    companion object {
        /** A window's update should be no larger than this, as GeoJSON text, and have no more coordinates than [BUDGET_COORDINATES]. */
        const val BUDGET_BYTES = 600 * 1_024

        const val BUDGET_COORDINATES = 20_000

        /**
         * The lanes along [path] ([plan]'s, laid out by [section] from [evidence]), whose station table is [table], with an
         * arrow for each maneuver at a gore of theirs, where the steps of each leg begin at the route stations of [steps]
         * (a list per leg, a station per step). Throws where the drawing cannot be made: the route costs its own line.
         */
        fun of(
            plan: LanePlan,
            path: RoutePath,
            evidence: LaneEvidence,
            section: CrossSection,
            table: StationTable,
            steps: List<DoubleArray> = emptyList(),
        ): OwnLanes {
            val drawing = LaneDrawing.of(plan, path, evidence, section)
            val litStations = drawing.litStretches()
            return OwnLanes(
                drawing = drawing,
                windows = DrawingWindows(plan.windows.map { it.from..it.to }, path.routeStations.last()),
                litStations = litStations,
                lit = litStations.map { table.pathFraction(it.start)..table.pathFraction(it.endInclusive) },
                arrows = arrowsOf(drawing.gores(), steps, path),
            )
        }

        /**
         * The arrows along [path] of the steps that begin at one of [gores], where the steps of each leg begin at the route
         * stations of [steps]; a leg's first step begins no maneuver an arrow shows.
         */
        fun arrowsOf(gores: List<Double>, steps: List<DoubleArray>, path: RoutePath): Map<StepAt, List<GeoPoint>> {
            if (gores.isEmpty()) return emptyMap()
            val out = HashMap<StepAt, List<GeoPoint>>()
            for ((leg, starts) in steps.withIndex()) {
                for (step in 1 until starts.size) {
                    val gore = GoreArrow.goreAt(gores, starts[step]) ?: continue
                    val points = GoreArrow.along(path.points, path.stations, gore)
                    if (points.size >= 2) out[StepAt(leg, step)] = points
                }
            }
            return out
        }

        /**
         * [features] with the tint pieces that end at one station, one for each lane tinted there, merged into one
         * feature each, in the place of the first and with the smallest of their ids: as the car passes that station one
         * feature state hides the tints of every lane there, so a progress update makes a few calls at most, however many
         * lanes are tinted.
         */
        fun mergeTints(features: List<LaneDrawing.Feature>): List<LaneDrawing.Feature> {
            val groups = LinkedHashMap<Long, MutableList<LaneDrawing.Feature>>()
            for (f in features) if (f.kind == LaneDrawing.Kind.TINT && f.id != null) groups.getOrPut(stationKey(f.to)) { ArrayList() }.add(f)
            if (groups.values.all { it.size == 1 }) return features
            val out = ArrayList<LaneDrawing.Feature>(features.size)
            for (f in features) {
                if (f.kind != LaneDrawing.Kind.TINT || f.id == null) {
                    out.add(f)
                    continue
                }
                val group = groups.getValue(stationKey(f.to))
                if (group.first() !== f) continue
                out.add(
                    if (group.size == 1) {
                        f
                    } else {
                        f.copy(from = group.minOf { it.from }, parts = group.flatMap { it.parts }, lane = null, id = group.minOf { requireNotNull(it.id) })
                    },
                )
            }
            return out
        }

        /** A route station to the millimetre: the pieces that end at one station end there to the millimetre. */
        private fun stationKey(station: Double): Long = Math.round(station * MILLIMETRES)

        private const val MILLIMETRES = 1_000.0

        /** The traffic level of the run of [runs] at [fraction] of the path; unknown outside them. */
        fun levelAt(runs: List<TrafficRun>, fraction: Double): TrafficLevel =
            runs.firstOrNull { fraction >= it.from && fraction <= it.to }?.level ?: TrafficLevel.UNKNOWN

        /** The feature state a lit piece of [level] gets; null where it stays the route's amber. */
        fun levelName(level: TrafficLevel): String? = when (level) {
            TrafficLevel.UNKNOWN, TrafficLevel.LOW -> null
            TrafficLevel.MODERATE -> LaneLayers.Key.LEVEL_MODERATE
            TrafficLevel.HEAVY -> LaneLayers.Key.LEVEL_HEAVY
            TrafficLevel.SEVERE -> LaneLayers.Key.LEVEL_SEVERE
            TrafficLevel.CLOSED -> LaneLayers.Key.LEVEL_CLOSED
        }

        /** [f]'s geometry: a polygon of its rings, or a line of its dashes, one or several; null for one with no points. */
        fun geometryOf(f: LaneDrawing.Feature): Geometry? {
            val parts = f.parts.filter { it.size >= 2 }
            if (parts.isEmpty()) return null
            return if (f.kind.polygon) {
                val rings = parts.map { ring -> listOf(ring.map(::pointOf)) }
                if (rings.size == 1) Polygon.fromLngLats(rings[0]) else MultiPolygon.fromLngLats(rings)
            } else {
                val lines = parts.map { line -> line.map(::pointOf) }
                if (lines.size == 1) LineString.fromLngLats(lines[0]) else MultiLineString.fromLngLats(lines)
            }
        }

        private fun pointOf(p: GeoPoint): Point = Point.fromLngLat(p.longitude, p.latitude)
    }
}

/**
 * One window of the app's own lanes as the drawing's source takes it: [window], its [collection] of features, how many
 * [features], [coordinates] and [bytes] of GeoJSON text it has, the [tints]' pieces the map hides once the car is past
 * them, and each lit piece's traffic where it has some ([levels]), by feature state; the [latitude] it lies at (not a
 * number without features), for the layers' widths; and the car's bar along it ([carBar]), null where none was made.
 */
internal class LaneWindow(
    val window: DrawingWindows.Window,
    val collection: FeatureCollection,
    val features: Int,
    val coordinates: Int,
    val bytes: Int,
    val tints: List<Piece>,
    val levels: List<Pair<String, String>>,
    val latitude: Double,
    val carBar: CarBar? = null,
) {
    /** A piece of a tint: its feature's [id], and the route station where it ends. */
    data class Piece(val id: String, val to: Double)

    /** Whether the window is within the budget of one update ([OwnLanes.BUDGET_BYTES], [OwnLanes.BUDGET_COORDINATES]). */
    val withinBudget: Boolean get() = bytes <= OwnLanes.BUDGET_BYTES && coordinates <= OwnLanes.BUDGET_COORDINATES
}
