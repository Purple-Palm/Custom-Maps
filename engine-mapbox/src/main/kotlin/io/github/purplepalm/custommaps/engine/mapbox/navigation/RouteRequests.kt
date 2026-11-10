package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.core.reroute.RerouteOptionsAdapter
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.Place

/** Instructions are asked for in English, whatever the phone's language is. */
private const val LANGUAGE = "en"

/** How far the road's direction at the origin may be from the car's own, to either side, in degrees. */
private const val ORIGIN_BEARING_RANGE = 45.0

private const val FULL_CIRCLE = 360.0

/**
 * The route request from [origin] to [destination], with alternatives.
 *
 * The routes end at the place's stop point when it has one. Without one they are asked to the place
 * itself, and the routing service moves that point to the nearest road, which for a large building
 * can be the service road behind it.
 *
 * With a stop point the place itself goes along as the arrival target of the destination
 * (waypoint_targets). That changes no road of the route: the arrival instruction then says on which
 * side the place is, not the stop point. The origin has no target; its place in the list stays
 * empty, as the Directions API asks. Without a stop point no targets are sent at all, and the
 * request is what it always was.
 *
 * Nothing is asked about the side of the road the car arrives on (approaches stays unset): the
 * routes are the ones the service finds best, and the car is never sent around the block to have
 * the place on its own side.
 *
 * @param originBearing the direction the car moves in, in degrees clockwise from north. Give it
 *   only for a car that is moving: the routes then start along the road in that direction, not
 *   with a U-turn. Null, or a value that is no number, sends no bearing.
 */
internal fun routeOptions(origin: Point, destination: Place, originBearing: Double? = null): RouteOptions {
    val builder = RouteOptions.builder()
        // Among them steps=true, which arrival targets need, and enableRefresh for traffic updates.
        .applyDefaultNavigationOptions()
        .coordinatesList(listOf(origin, destination.routePoint.toPoint()))
        .alternatives(true)
        .language(LANGUAGE)
        .voiceUnits(DirectionsCriteria.IMPERIAL)
    if (destination.stopPoint != null) builder.waypointTargetsList(listOf(null, destination.point.toPoint()))
    originBearing(originBearing)?.let { builder.bearingsList(listOf(it, null)) }
    return builder.build()
}

/** [degrees] as the bearing of a request's origin, brought into 0 to 360; null when it is no number. */
private fun originBearing(degrees: Double?): Bearing? {
    if (degrees == null || !degrees.isFinite()) return null
    val angle = ((degrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
    return Bearing.builder().angle(angle).degrees(ORIGIN_BEARING_RANGE).build()
}

/**
 * What the "route_request" line says about where a request ends, beyond the place itself:
 * "stopPoint", where the routes are asked to end when the place has a stop point; "targets", the
 * arrival targets as sent, one for each point of the request (null for the origin), or null when
 * none are sent; "bearings", the same for the directions of travel (angle, then the range to either
 * side), or null.
 */
internal fun routeRequestFields(destination: Place, options: RouteOptions): Map<String, Any?> = mapOf(
    "stopPoint" to destination.stopPoint?.let { listOf(it.latitude, it.longitude) },
    "targets" to options.waypointTargetsList()?.map { it?.debugFields() },
    "bearings" to options.bearingsList()?.map { bearing -> bearing?.let { listOf(it.angle(), it.degrees()) } },
)

/**
 * Looks at the request of every reroute, writes a "reroute_options" line about it, and hands it
 * back as it came. It changes nothing.
 *
 * A reroute is asked for by the navigator itself, from the request of the route it replaces. That
 * the new request ends at the same point is in the SDK's sources; whether it still carries the
 * arrival target of [routeOptions] is not. The line shows it on a drive: "coordinates" is the number
 * of points of the request and "targets" the number of arrival targets, null when the request has
 * none; "lastTargetSet" says whether the last of them, the destination's, holds a point;
 * "targetWanted" whether the place of the drive has a stop point, so that the first request sent
 * a target; "endMeters" is how far the request's last point is from where routes to the place end.
 *
 * The navigator may call it on a thread of its own. Nothing here may fail the reroute: whatever
 * goes wrong with the line is written to the log instead, and the request goes on unchanged.
 *
 * @param destination the place of the drive at this moment, or null
 */
internal class RerouteOptionsLogger(
    private val debugLog: DebugLog,
    private val destination: () -> Place?,
) : RerouteOptionsAdapter {

    override fun onRouteOptions(routeOptions: RouteOptions): RouteOptions {
        try {
            debugLog.log("reroute_options", rerouteOptionsFields(routeOptions, destination()))
        } catch (e: RuntimeException) {
            debugLog.error("reroute_options", e)
        }
        return routeOptions
    }
}

/** The fields of a "reroute_options" line; see [RerouteOptionsLogger]. */
internal fun rerouteOptionsFields(options: RouteOptions, destination: Place?): Map<String, Any?> {
    val coordinates = options.coordinatesList()
    val targets = options.waypointTargetsList()
    val end = coordinates.lastOrNull()
    val routePoint = destination?.routePoint
    return mapOf(
        "coordinates" to coordinates.size,
        "targets" to targets?.size,
        "lastTargetSet" to (targets?.lastOrNull() != null),
        "targetWanted" to destination?.let { it.stopPoint != null },
        "endMeters" to if (end != null && routePoint != null) {
            Motion.round(Geo.meters(end.latitude(), end.longitude(), routePoint.latitude, routePoint.longitude), 1)
        } else {
            null
        },
    )
}
