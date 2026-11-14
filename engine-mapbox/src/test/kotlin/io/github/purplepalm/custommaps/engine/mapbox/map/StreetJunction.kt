package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.api.directions.v5.models.IntersectionLanes
import com.mapbox.api.directions.v5.models.LegAnnotation
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MaxSpeed
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import com.mapbox.geojson.Point

/**
 * A made-up street for the own lanes' tests on streets: due north along longitude 0 from 0, 0, a point every [STEP] degrees
 * (about 100 m), a road crossing it at every point; lane points of two lanes where a test asks, each at a traffic signal;
 * and at [route]'s turn a right turn at a traffic signal from the right of two lanes onto a street due east, with a lane
 * point of two lanes two points on and the arrival four points on. A posted 30 mph all along. No classes: every road is a
 * street. Invented roads and route ids.
 */
internal object StreetJunction {

    const val STEP = 0.0009

    /** About how far apart the points lie along the street, in metres: [STEP] degrees of latitude, a little over 100 m. */
    const val STEP_METERS = 101.0

    private fun lane(valid: Boolean, arrows: List<String> = listOf("straight")): IntersectionLanes =
        IntersectionLanes.builder().valid(valid).active(valid).indications(arrows).build()

    private val two = listOf(lane(true), lane(true))

    /** An intersection at [lng], [lat] degrees, the route in on [into] and out on [out] of [bearings], every road but the one in open. */
    private fun node(
        lng: Double,
        lat: Double,
        bearings: List<Int>,
        into: Int?,
        out: Int?,
        index: Int,
        lanes: List<IntersectionLanes>? = null,
        signal: Boolean = false,
    ): StepIntersection = StepIntersection.builder()
        .rawLocation(doubleArrayOf(lng, lat))
        .bearings(bearings)
        .entry(bearings.indices.map { it != into })
        .`in`(into)
        .out(out)
        .lanes(lanes)
        .trafficSignal(signal.takeIf { it })
        .geometryIndex(index)
        .build()

    private fun legStep(type: String, modifier: String?, lng: Double, lat: Double, intersections: List<StepIntersection>): LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(lng, lat)).type(type).modifier(modifier).build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(intersections)
        .build()

    /** The route's legs and its steps' points: the right turn at point [turn], lane points of two lanes at [points] before it. */
    fun route(turn: Int, points: List<Int>): Pair<List<RouteLeg>, List<List<List<Point>>>> {
        val lat = turn * STEP
        val north = listOf(node(0.0, 0.0, listOf(0), null, 0, 0)) +
            (1 until turn).map { i -> node(0.0, i * STEP, listOf(180, 0, 90, 270), 0, 1, i, if (i in points) two else null, signal = i in points) }
        val turnLanes = listOf(lane(false), lane(true, listOf("right")))
        val east = listOf(node(0.0, lat, listOf(180, 0, 90, 270), 0, 2, turn, turnLanes, signal = true)) +
            (1..3).map { k -> node(k * STEP, lat, listOf(270, 90, 0, 180), 0, 1, turn + k, if (k == 2) two else null) }
        val end = node(4 * STEP, lat, listOf(270), 0, null, turn + 4)
        val steps = listOf(
            legStep("depart", null, 0.0, 0.0, north),
            legStep("turn", "right", 0.0, lat, east),
            legStep("arrive", "right", 4 * STEP, lat, listOf(end)),
        )
        val shapes = listOf(
            listOf(
                (0..turn).map { Point.fromLngLat(0.0, it * STEP) },
                (0..4).map { Point.fromLngLat(it * STEP, lat) },
                listOf(Point.fromLngLat(4 * STEP, lat), Point.fromLngLat(4 * STEP, lat)),
            ),
        )
        val maxSpeeds = LegAnnotation.builder().maxspeed(List(turn + 4) { MaxSpeed.builder().speed(30).unit("mph").build() }).build()
        return listOf(RouteLeg.builder().steps(steps).annotation(maxSpeeds).build()) to shapes
    }
}
