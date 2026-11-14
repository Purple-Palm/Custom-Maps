package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection

/**
 * Whether the route's own road on each street it follows is one-way or two-way, and what said so (the own lanes' design
 * 3.3). It decides where a street's lanes lie across the road: on a two-way street the route's lanes lie from the road's
 * line to the right, on a one-way street around it, half the lanes' width apart. The route's lane lists do not say, so the
 * app draws a street's lanes only where one of these sources does, for the road that leaves each intersection, in this
 * order:
 * 1. [Source.ACCESS]: the route's own access field for that road ([RouteNode.oneWay]), where the route carries it;
 * 2. [Source.HORIZON]: the one-way flag of Mapbox's electronic horizon for the edge the route takes there, where a lookup
 *    is given (while its switch is on) and it holds an edge there;
 * 3. none: nothing of the app's own is drawn on that street, and the route line is the one without the drawing.
 *
 * Freeways and ramps are one-way by their kind and are not looked up. Pure; made off the main thread with the route's
 * drawing, from the route and, for the horizon, the lookup the engine hands it; never stored.
 */
class StreetWays private constructor(
    /** The navigator's id of the route. */
    val routeId: String,
    /** For each intersection of the route, how the street that leaves it sits; null where it is no street or nothing tells. */
    private val ways: List<Way?>,
    /** How many intersections of the route have a street leave them that no source tells. */
    val unknown: Int,
) {

    /** What told how a street sits. */
    enum class Source(
        /** How the debug log names it. */
        val logName: String,
    ) {
        /** The route's own access field for the road ([RouteNode.oneWay]). */
        ACCESS("access"),

        /** The electronic horizon's one-way flag for the edge the route takes. */
        HORIZON("horizon"),
    }

    /** How a street sits on its road's line ([carriageway]), and what told it ([source]). */
    data class Way(val carriageway: CrossSection.Carriageway, val source: Source)

    /** How the street that leaves the intersection [node] sits; null where that road is no street or nothing tells. */
    fun at(node: Int): Way? = ways.getOrNull(node)

    /** How many intersections of the route have a street leave them that a source tells, by the source. */
    val known: Map<Source, Int> by lazy { Source.entries.associateWith { s -> ways.count { it?.source == s } } }

    companion object {

        /** No source for any street of [route]: none of its streets is drawn. */
        fun none(route: LaneRoute): StreetWays {
            val streets = route.nodes.indices.count { isStreet(route, it) }
            return StreetWays(route.routeId, List(route.nodes.size) { null }, streets)
        }

        /**
         * How every street of [route] sits, from the route's own access field and, where it says nothing, [horizon]: the
         * electronic horizon's one-way flag at a route station (true one-way, false two-way, null where it holds no edge
         * there), read at the middle of the street from each intersection to the next.
         */
        fun of(route: LaneRoute, horizon: ((station: Double) -> Boolean?)? = null): StreetWays {
            val nodes = route.nodes
            var unknown = 0
            val ways = nodes.indices.map { i ->
                if (!isStreet(route, i)) return@map null
                val node = nodes[i]
                val access = node.oneWay
                val way = when {
                    access != null -> Way(carriageway(access), Source.ACCESS)
                    horizon != null -> horizon(middle(route, i))?.let { Way(carriageway(it), Source.HORIZON) }
                    else -> null
                }
                if (way == null) unknown++
                way
            }
            return StreetWays(route.routeId, ways, unknown)
        }

        private fun carriageway(oneWay: Boolean): CrossSection.Carriageway =
            if (oneWay) CrossSection.Carriageway.ONE_WAY else CrossSection.Carriageway.TWO_WAY

        /** Whether a street leaves the intersection [i] of [route]: it is no freeway, no ramp, and not the route's end. */
        private fun isStreet(route: LaneRoute, i: Int): Boolean {
            val node = route.nodes[i]
            if (node.road != RoadKind.STREET) return false
            return middle(route, i) > node.station
        }

        /** The middle of the road from the intersection [i] of [route] to the next, or to the route's end after the last. */
        private fun middle(route: LaneRoute, i: Int): Double {
            val nodes = route.nodes
            val to = if (i + 1 < nodes.size) nodes[i + 1].station else route.lengthMeters
            return (nodes[i].station + to) / 2.0
        }
    }
}
