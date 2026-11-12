package io.github.purplepalm.custommaps.nav.model

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind

/**
 * One lane of a point on the route as the route lists it, read as it is: its arrows and the route's
 * own flags. Lanes come from the leftmost. The card works out from these which lanes to light on
 * the way to an exit (`RunUpRule`); the [Lane] it draws is made from them.
 */
data class RouteLane(
    /** Arrows painted on the lane; empty for a lane without arrows. */
    val indications: Set<TurnModifier>,
    /** Whether the route can be followed from this lane; null where the route does not say. */
    val valid: Boolean? = null,
    /** Whether the route prefers this lane; null where the route does not say. */
    val active: Boolean? = null,
    /** The arrow the route takes from this lane; null where the route does not say. */
    val validIndication: TurnModifier? = null,
    /**
     * The kinds of vehicle the lane is kept for, as the route's lane access lists them: car pools,
     * buses, taxis and the like, a kind the route names that the app does not know as
     * [DesignatedKind.OTHER]. Empty for a lane open to every car.
     */
    val kinds: Set<DesignatedKind> = emptySet(),
) {
    /** Kept for some kinds of vehicle only: buses, car pools, taxis and the like. */
    val designated: Boolean get() = kinds.isNotEmpty()

    /** The route marks this lane for itself: valid, else preferred, else not. */
    val marked: Boolean get() = valid ?: active ?: false

    /** Open to every car, and marked for the route: a lane this car can follow the route from. */
    val usable: Boolean get() = !designated && marked
}
