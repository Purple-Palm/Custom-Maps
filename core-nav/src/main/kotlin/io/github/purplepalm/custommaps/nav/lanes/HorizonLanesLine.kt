package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.Motion
import kotlin.math.abs
import kotlin.math.roundToLong

/** Under which reading of a lane point's list the horizon's count of lanes agrees with it, if under any. */
enum class HorizonConvention(
    /** How the debug log names it. */
    val logName: String,
) {
    /** The count is every lane the point lists, the ones kept for car pools and other kinds of vehicle too. */
    ALL("all"),

    /** The count is the lanes open to every car only: the point lists more, kept for some kinds of vehicle. */
    GENERAL("general"),

    /** Neither, or no count to compare. */
    NONE("none"),
    ;

    companion object {
        /**
         * The reading under which [count], the horizon's lanes, agrees with a lane point that lists [listed] lanes, [general]
         * of them open to every car. [ALL] where both readings give the same number.
         */
        fun of(count: Int?, listed: Int, general: Int): HorizonConvention = when (count) {
            null -> NONE
            listed -> ALL
            general -> GENERAL
            else -> NONE
        }
    }
}

/** A lane point of the route as the horizon's comparison reads it. */
data class HorizonPoint(
    /** Its intersection, as an index into the route's nodes. */
    val node: Int,
    /** Along the route, in metres. */
    val station: Double,
    /** The lanes the point lists. */
    val listed: Int,
    /** Of those, the lanes open to every car: none kept for car pools or another kind of vehicle. */
    val general: Int,
    /** The kind of road that leads to it. */
    val road: RoadKind,
    /** The lane choices' key of the point; null where it has none. */
    val key: String?,
    /** How far ahead of it the route next turns as the lane plan counts it (an exit's gore, a fork, a turn); null where it does not. */
    val toGore: Double?,
)

/**
 * The "horizon_lanes" line of the debug log: each lane point of the route the electronic horizon covers, once, its lane
 * list against the lane count the horizon gives the road that leads to it (the design's OL9, E17): from the line a drive
 * tells under which reading the counts agree, whether they count lanes kept for car pools, and how well the horizon's
 * edges and the route's stations line up. An intersection's lanes describe its approach, so the edge compared is the one
 * that ends at the point, within [MATCH_TOLERANCE_M]. Counts, metres relative to the point, a gore or the car, booleans,
 * the app's own keys and kinds only: never a coordinate, a shape, a road's name or an edge's id.
 */
object HorizonLanesLine {

    const val TYPE = "horizon_lanes"

    /**
     * An edge that ends at most this far from a lane point, before or after it, is the one that leads to it: the horizon's
     * edges and the route's steps measure the same road, laid along the route from the car's station, so their ends lie
     * close where they are one node.
     */
    const val MATCH_TOLERANCE_M = 20.0

    /** One lane point against the horizon of one update. */
    data class Comparison(
        val point: HorizonPoint,
        /** How far ahead of the car the point lay at the update, in metres. */
        val aheadM: Double,
        /** Where the edge end nearest the point lay, in metres after it (before it below zero); null without any edge. */
        val endOffsetM: Double?,
        /** The edge that ends within [MATCH_TOLERANCE_M] of the point; null where none does. */
        val edge: HorizonEdge?,
        val convention: HorizonConvention,
    ) {
        /** The horizon's count of lanes on the edge that leads to the point; null where no edge does or it gives none. */
        val count: Int? get() = edge?.laneCount
    }

    /**
     * The lane points of [route], in route order, with [keyOf] the lane choices' key of each by its intersection's index:
     * built once for a route, off the main thread.
     */
    fun points(route: LaneRoute, keyOf: (Int) -> String?): List<HorizonPoint> {
        val nodes = route.nodes
        // Where the route next turns from each intersection on, itself included.
        val turnAt = DoubleArray(nodes.size)
        var next = Double.NaN
        for (i in nodes.indices.reversed()) {
            if (LaneEvidence.planTurns(nodes[i])) next = nodes[i].station
            turnAt[i] = next
        }
        val out = ArrayList<HorizonPoint>()
        for ((i, node) in nodes.withIndex()) {
            if (!node.isLanePoint) continue
            out.add(
                HorizonPoint(
                    node = i,
                    station = node.station,
                    listed = node.lanes.size,
                    general = node.lanes.count { !it.designated },
                    road = route.approachRoad(i),
                    key = keyOf(i),
                    toGore = turnAt[i].takeIf { !it.isNaN() }?.let { it - node.station },
                ),
            )
        }
        return out
    }

    /**
     * The points of [points] (in route order) that [ahead] covers and [logged] has not taken yet, each against the edge
     * that ends at it, and each added to [logged]: a point at or ahead of the car whose station the stretches reach past by
     * [MATCH_TOLERANCE_M] at least, so every edge end that could lead to it is known.
     */
    fun compare(points: List<HorizonPoint>, ahead: HorizonAhead, logged: MutableSet<Int>): List<Comparison> {
        if (ahead.stretches.isEmpty()) return emptyList()
        val out = ArrayList<Comparison>()
        // The first point at or ahead of the car.
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].station < ahead.from) low = mid + 1 else high = mid
        }
        for (i in low until points.size) {
            val point = points[i]
            if (point.station + MATCH_TOLERANCE_M > ahead.to) break
            if (point.node in logged) continue
            logged.add(point.node)
            val nearest = ahead.endNearest(point.station)
            val offset = nearest?.let { it.end - point.station }
            val edge = nearest?.takeIf { offset != null && abs(offset) <= MATCH_TOLERANCE_M }?.edge
            out.add(Comparison(point, point.station - ahead.from, offset, edge, HorizonConvention.of(edge?.laneCount, point.listed, point.general)))
        }
        return out
    }

    /**
     * The line's fields for [comparison]: the point's "key" and how far before the route next turns it lies ("toGoreM"),
     * how far ahead of the car it was ("aheadM"), the kind of road that leads to it ("road"), its listed lanes ("lanes") and
     * those open to every car ("general"); whether an edge ends within the tolerance ("matched"), where the nearest edge
     * end lay from it ("endM"), and of the matched edge its lane "count", "motorway", "ramp", "oneWay" and "roadClass"; and
     * the reading that agrees ("convention": all, general or none).
     */
    fun fields(comparison: Comparison): Map<String, Any?> {
        val point = comparison.point
        val edge = comparison.edge
        return linkedMapOf(
            "key" to point.key,
            "toGoreM" to point.toGore?.takeIf { it.isFinite() }?.roundToLong(),
            "aheadM" to comparison.aheadM.takeIf { it.isFinite() }?.roundToLong(),
            "road" to point.road.logName,
            "lanes" to point.listed,
            "general" to point.general,
            "matched" to (edge != null),
            "endM" to comparison.endOffsetM?.takeIf { it.isFinite() }?.let { Motion.round(it, 1) },
            "count" to edge?.laneCount,
            "motorway" to edge?.motorway,
            "ramp" to edge?.ramp,
            "oneWay" to edge?.oneWay,
            "roadClass" to edge?.roadClass?.logName,
            "convention" to comparison.convention.logName,
        )
    }
}
