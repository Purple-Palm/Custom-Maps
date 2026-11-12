package io.github.purplepalm.custommaps.nav.lanes

/**
 * The speed the app's own lanes plan a road with: its posted speed plus [MARGIN_MPH], fixed for the
 * route and never the car's speed now, so nothing that is placed by it moves when the car speeds up
 * or slows down. Where the route gives no posted speed for a road, a first setting for its kind
 * stands in: [FREEWAY_MPH], [RAMP_MPH] or [STREET_MPH], plus the same margin. These are general
 * settings, to be tuned from the drives' logs.
 */
object PlanningSpeed {

    /** Added to the posted speed: traffic tends to run a little above it. */
    const val MARGIN_MPH = 5.0

    /** The posted speed taken for a freeway the route gives none for. */
    const val FREEWAY_MPH = 65.0

    /** The posted speed taken for a ramp the route gives none for. */
    const val RAMP_MPH = 45.0

    /** The posted speed taken for a street the route gives none for. */
    const val STREET_MPH = 30.0

    /** Metres a second in one mile an hour. */
    const val MPS_PER_MPH = 0.44704

    /** The posted speed taken for a road of [road]'s kind where the route gives none, in metres a second. */
    fun postedFor(road: RoadKind): Double = MPS_PER_MPH * when (road) {
        RoadKind.FREEWAY -> FREEWAY_MPH
        RoadKind.RAMP -> RAMP_MPH
        RoadKind.STREET -> STREET_MPH
    }

    /**
     * The planning speed, in metres a second, of a road of the kind [road] whose posted speed is
     * [postedMps]: null, a speed that is no number, zero or less, counts as none given.
     */
    fun mps(postedMps: Double?, road: RoadKind): Double {
        val posted = postedMps?.takeIf { it.isFinite() && it > 0.0 } ?: postedFor(road)
        return posted + MARGIN_MPH * MPS_PER_MPH
    }

    /** The planning speed of the road that leaves [node]. */
    fun of(node: RouteNode): Double = mps(node.postedMps, node.road)
}
