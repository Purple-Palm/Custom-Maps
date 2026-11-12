package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.engine.mapbox.navigation.RouteLanePlan
import io.github.purplepalm.custommaps.engine.mapbox.navigation.toGeoPoint
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection
import io.github.purplepalm.custommaps.nav.lanes.geometry.LegTraffic
import io.github.purplepalm.custommaps.nav.lanes.geometry.PuckProjection
import io.github.purplepalm.custommaps.nav.lanes.geometry.RoutePath
import io.github.purplepalm.custommaps.nav.lanes.geometry.StationTable
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRuns
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/**
 * One path of a route as the app's own route line draws it ([OwnRouteLine]): built once for the route, off the main
 * thread, and kept in memory with it, never stored.
 */
internal class OwnLineDrawing(
    /** The navigator's id of the route. */
    val routeId: String,
    /** Which path of the route this is; a new path of the same route gets a new one. */
    val version: Int,
    /** The path's points, as the source gets them: the route's own line, moved into the lanes the lane plan names. */
    val points: List<GeoPoint>,
    /** The path's station table: the trim and every colour stop are its fractions ([StationTable]). */
    val table: StationTable,
    /** The traffic along the path, covering it once, in the navigation SDK's ranges. */
    val runs: List<TrafficRun>,
    /** The app's own projection of the puck onto the route's line, for a trim where the SDK skips its own. */
    val projection: PuckProjection,
    /** Each leg's points' stations along the route, as its annotations count its segments: for a refresh's traffic. */
    val legStations: List<DoubleArray>,
    /** The app's own lanes along the path, where they were asked for and the route has a lane plan; null otherwise. */
    val lanes: OwnLanes? = null,
) {
    /** The same path with the traffic of a refresh. */
    fun withRuns(runs: List<TrafficRun>): OwnLineDrawing = OwnLineDrawing(routeId, version, points, table, runs, projection, legStations, lanes)
}

/**
 * The drawing of the route [routeId], whose [legs] and steps' points [shapes] (as the steps' geometry decodes to) the
 * navigator holds, as path [version], through the lanes of its lane [plan] where one was made; without one the path is
 * the route's own line. With [lanes] and a plan, the app's own lanes along the path too ([OwnLanes]). The route's own
 * line is every point of every step, leg after leg, as the navigation SDK draws the route, and its stations are
 * measured along it as the plan's are. For the route lines' worker or the map's own: it reads every step's shape.
 * Throws where the route has no line to draw.
 */
internal fun ownLineDrawing(
    routeId: String,
    legs: List<RouteLeg>,
    shapes: List<List<List<Point>>>,
    plan: RouteLanePlan?,
    version: Int,
    lanes: Boolean = false,
): OwnLineDrawing {
    val line = ArrayList<GeoPoint>()
    val lineStations = ArrayList<Double>()
    // Each point's station, step by step, as the legs' annotations need them; NaN for a point that is no coordinate.
    val pointStations = ArrayList<List<DoubleArray>>(shapes.size)
    var along = 0.0
    var last: GeoPoint? = null
    for (leg in shapes) {
        val steps = ArrayList<DoubleArray>(leg.size)
        for (step in leg) {
            val stations = DoubleArray(step.size)
            for ((k, point) in step.withIndex()) {
                val at = point.toGeoPoint()
                if (at == null) {
                    stations[k] = Double.NaN
                    continue
                }
                last?.let { along += Geo.meters(it, at) }
                last = at
                line.add(at)
                lineStations.add(along)
                stations[k] = along
            }
            steps.add(stations)
        }
        pointStations.add(steps)
    }
    val stations = lineStations.toDoubleArray()
    // One cross-section for the path and the lanes, so the two agree by construction.
    val section = plan?.let { CrossSection.of(it.plan, it.evidence) }
    val path = plan?.let { RoutePath.of(it.plan, line, version, it.evidence, requireNotNull(section)) }
    check(path == null || path.routeId == routeId) { "The plan is another route's" }
    val table = if (path != null) StationTable.of(path) else StationTable.of(routeId, version, line, stations, line, stations)
    val legStations = pointStations.map(::legPointStations)
    val ownLanes = if (lanes && plan != null && path != null && section != null) {
        // Where each step begins, as the plan measures its stations: a maneuver at a gore gets its arrow along the path.
        val steps = legs.mapIndexed { l, leg -> DoubleArray(leg.steps().orEmpty().size) { k -> plan.station(l, k, 0.0) ?: Double.NaN } }
        OwnLanes.of(plan.plan, path, plan.evidence, section, table, steps)
    } else {
        null
    }
    return OwnLineDrawing(
        routeId = routeId,
        version = version,
        points = path?.points ?: line,
        table = table,
        runs = TrafficRuns.of(table, legTraffic(legs, legStations)),
        projection = PuckProjection.of(line, stations),
        legStations = legStations,
        lanes = ownLanes,
    )
}

/** The traffic runs of a refresh of [drawing]'s route, whose [legs] carry the new annotations. */
internal fun ownLineRuns(legs: List<RouteLeg>, drawing: OwnLineDrawing): List<TrafficRun> =
    TrafficRuns.of(drawing.table, legTraffic(legs, drawing.legStations))

/**
 * A leg's points' stations as its annotations count the leg's segments, the way the navigation SDK lays its traffic
 * along a leg: every point of the first step, the points after the first of every later step, and nothing of the leg's
 * last step, the arrival, when the leg has more than one.
 */
private fun legPointStations(steps: List<DoubleArray>): DoubleArray {
    val out = ArrayList<Double>()
    for ((j, step) in steps.withIndex()) {
        when {
            j == 0 -> step.forEach { out.add(it) }
            j == steps.lastIndex -> Unit
            else -> for (k in 1 until step.size) out.add(step[k])
        }
    }
    return out.toDoubleArray()
}

/** Each leg's traffic: its numeric congestion, or its congestion's words where the route has no numbers, and its closures. */
private fun legTraffic(legs: List<RouteLeg>, legStations: List<DoubleArray>): List<LegTraffic> =
    legStations.mapIndexed { i, stations ->
        val leg = legs.getOrNull(i)
        val annotation = leg?.annotation()
        val numeric = annotation?.congestionNumeric()
        val congestion = if (!numeric.isNullOrEmpty()) numeric else annotation?.congestion().orEmpty().map(::congestionOfWord)
        val closures = leg?.closures().orEmpty().map { it.geometryIndexStart()..it.geometryIndexEnd() }
        LegTraffic(stations, congestion, closures)
    }

/** A congestion word of the route as a number in the navigation SDK's range for it; null for unknown and other words. */
private fun congestionOfWord(word: String?): Int? = when (word) {
    "low" -> TrafficRuns.LOW.first
    "moderate" -> TrafficRuns.MODERATE.first
    "heavy" -> TrafficRuns.HEAVY.first
    "severe" -> TrafficRuns.SEVERE.first
    else -> null
}
