package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteLeg
import io.github.purplepalm.custommaps.nav.lanes.NodeManeuver
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.SideRoadsLine

/*
 * The "side_roads" line of a route ([SideRoadsLine]), read from the route the navigator or the preview already holds while
 * Debug's switch Side-road fields on routes is on: no request, nothing stored. It reads every intersection and writes the
 * whole route as JSON once to measure it, which is work for the route lines' worker, never for the main thread.
 */

/**
 * Every intersection of the route with [legs], leg after leg and step after step, as the "side_roads" line counts them:
 * the kind of road that leaves it as the lane evidence reads it ([isFreeway], [RoadKind.isRamp]), the maneuver its step
 * begins with at a step's first intersection, its roads by their bearings, and the two fields as the route gives them.
 */
internal fun sideRoadNodes(legs: List<RouteLeg>): List<SideRoadsLine.Node> {
    val nodes = ArrayList<SideRoadsLine.Node>()
    for (leg in legs) {
        val legSteps = leg.steps().orEmpty()
        val besideFreeway = stepsBesideFreeway(legSteps)
        for ((k, step) in legSteps.withIndex()) {
            val type = step.maneuver().type()
            val stepType = ManeuverMapping.type(type)
            var first = true
            for (intersection in step.intersections().orEmpty()) {
                if (intersection == null) continue
                val freeway = intersection.isFreeway()
                nodes.add(
                    SideRoadsLine.Node(
                        road = RoadKind.of(
                            freeway,
                            RoadKind.isRamp(intersection.mapboxStreetsV8()?.roadClass(), stepType, besideFreeway[k], freeway),
                        ),
                        maneuver = if (first) {
                            NodeManeuver(stepType, ManeuverMapping.modifier(step.maneuver().modifier()), LaneChoices.isLaneChoiceManeuver(type))
                        } else {
                            null
                        },
                        roads = intersection.bearings()?.size ?: 0,
                        access = intersection.access(),
                        formOfWay = intersection.formOfWay(),
                        inIndex = intersection.`in`(),
                        outIndex = intersection.out(),
                    ),
                )
                first = false
            }
        }
    }
    return nodes
}

/**
 * The fields of the "side_roads" line of the route [routeId], whose model is [route], for [reason] ([SideRoadsLine.fields]):
 * whether its own request asked for the two fields, its intersections counted, and its size as JSON ([json], the model's
 * own JSON by default). A size that cannot be measured, as when the memory left cannot hold the JSON, is null and goes to
 * [onSizeError]; the counts are written all the same. On the route lines' worker.
 */
internal fun sideRoadsFields(
    routeId: String,
    reason: SideRoadsLine.Reason,
    route: DirectionsRoute,
    onSizeError: (Throwable) -> Unit,
    json: (DirectionsRoute) -> String = DirectionsRoute::toJson,
): Map<String, Any?> {
    val asked = route.routeOptions()?.let { SideRoadsLine.Asked(asksLinkAccess(it), asksLinkFormOfWay(it)) }
    val nodes = sideRoadNodes(route.legs().orEmpty().filterNotNull())
    val jsonBytes = try {
        SideRoadsLine.utf8Bytes(json(route))
    } catch (e: RuntimeException) {
        onSizeError(e)
        null
    } catch (e: OutOfMemoryError) {
        // The JSON is let go here: it costs the size, never the line or the drive.
        onSizeError(e)
        null
    }
    return SideRoadsLine.fields(routeId, reason, asked, nodes, jsonBytes)
}
