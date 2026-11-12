package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/*
 * A route as the app's own lanes read it: every intersection of every step, in the order the car
 * reaches them, in plain values. The map engine builds it once for each route from the route the
 * navigator already holds, off the main thread; it lives in memory with its route and is never
 * stored.
 */

/** The kind of road that leaves an intersection. */
enum class RoadKind {
    FREEWAY,
    RAMP,
    STREET,
    ;

    /** How the debug log names it. */
    val logName: String get() = name.lowercase()

    companion object {
        /** A ramp is a ramp, whatever its class says; otherwise a freeway or a street. */
        fun of(freeway: Boolean, ramp: Boolean): RoadKind = when {
            ramp -> RAMP
            freeway -> FREEWAY
            else -> STREET
        }

        /**
         * Whether the road that leaves an intersection is a ramp. Mapbox Streets names a ramp's class
         * a link ([LINK_SUFFIX]: motorway_link, trunk_link and the like), and where the route gives a
         * class it decides. Where it gives none, the road is a ramp in a step that begins with an
         * off ramp or an on ramp next to a freeway, or with a fork next to one where the road is no
         * freeway itself (a fork between two freeways goes on as one).
         *
         * @param streetsClass the Mapbox Streets class of the road, as the route names it; null where it gives none
         * @param stepType the maneuver that begins the intersection's step
         * @param besideFreewayStep the step lies next to a freeway: the step before it ends on a freeway,
         *   which leads to the step's maneuver, or the step after it begins on one
         * @param freeway the road is a freeway by the route's own reading (`isFreeway`)
         */
        fun isRamp(streetsClass: String?, stepType: ManeuverType?, besideFreewayStep: Boolean, freeway: Boolean): Boolean {
            if (streetsClass != null) return streetsClass.endsWith(LINK_SUFFIX)
            if (!besideFreewayStep) return false
            return when (stepType) {
                ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP -> true
                ManeuverType.FORK -> !freeway
                else -> false
            }
        }

        /** The end of every link class in Mapbox Streets. */
        const val LINK_SUFFIX = "_link"
    }
}

/** What a designated lane is kept for, as the route's lane access lists it. */
enum class DesignatedKind(
    /** The route's own name for it, which the debug log writes too. */
    val logName: String,
) {
    HOV("hov"),
    BUS("bus"),
    TAXI("taxi"),
    MOTORCYCLE("motorcycle"),
    MOPED("moped"),
    BICYCLE("bicycle"),

    /** Any other kind the route names; the route may add kinds at any time. */
    OTHER("other"),
    ;

    companion object {
        /** The kind with the route's own [name]; [OTHER] for a name it has not listed so far. */
        fun of(name: String?): DesignatedKind = entries.firstOrNull { it != OTHER && it.logName == name } ?: OTHER
    }
}

/** The maneuver at the start of a step, as the route gives it. */
data class NodeManeuver(
    val type: ManeuverType,
    val modifier: TurnModifier? = null,
    /**
     * The camera stays up for it, by the navigation SDK's own list (continue, merge, on ramp, off
     * ramp and fork), which the app does not change: where such a maneuver has lanes it can be a lane
     * choice. The camera frames any other maneuver as a junction.
     */
    val viewStaysUp: Boolean = false,
)

/** One intersection of the route. */
data class RouteNode(
    /** Along the route from its start, in metres, as the steps' shapes measure it. */
    val station: Double,
    /**
     * Every lane of the intersection as the route lists it, from the leftmost, with the kinds of
     * vehicle each is kept for; empty where the route has no lanes for it.
     */
    val lanes: List<RouteLane> = emptyList(),
    /** The roads at the intersection, each as its bearing in degrees clockwise from north. */
    val bearings: List<Int?> = emptyList(),
    /** For each of [bearings], whether a car may take that road from here. */
    val entry: List<Boolean?> = emptyList(),
    /** Which of [bearings] the route comes in on; null at its start. */
    val inIndex: Int? = null,
    /** Which of [bearings] the route leaves on; null at its end. */
    val outIndex: Int? = null,
    /** The road that leaves the intersection is a freeway, as the lane choices read it. */
    val freeway: Boolean = false,
    /** The road that leaves the intersection is a ramp ([RoadKind.isRamp]). */
    val ramp: Boolean = false,
    /** The maneuver a step begins with, where one begins here; null where the route goes through. */
    val maneuver: NodeManeuver? = null,
    /** The posted speed of the road that leaves it, in metres a second; null where the route gives none. */
    val postedMps: Double? = null,
) {
    /** The kind of road that leaves the intersection. */
    val road: RoadKind get() = RoadKind.of(freeway, ramp)

    /** Whether the route has lanes for this intersection. */
    val isLanePoint: Boolean get() = lanes.isNotEmpty()
}

/** A route, leg after leg and step after step, as the app's own lanes read it. */
data class LaneRoute(
    /** The navigator's id of the route. */
    val routeId: String,
    /** How many steps its legs have. */
    val steps: Int,
    /** Its length along the steps' shapes, in metres. */
    val lengthMeters: Double,
    /** Every intersection of every step, in the order the car reaches them. */
    val nodes: List<RouteNode>,
) {
    init {
        for (i in 1 until nodes.size) {
            require(nodes[i].station >= nodes[i - 1].station) { "Intersection $i lies before intersection ${i - 1}" }
        }
    }

    /** The kind of road that leads to the intersection [index]: the one that leaves the intersection before it; at the route's start its own. */
    fun approachRoad(index: Int): RoadKind = nodes[if (index > 0) index - 1 else index].road
}
