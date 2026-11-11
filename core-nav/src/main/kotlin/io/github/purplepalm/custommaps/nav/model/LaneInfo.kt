package io.github.purplepalm.custommaps.nav.model

/** One lane at the next intersection. */
data class Lane(
    /** Arrows painted on the lane. */
    val indications: Set<TurnModifier>,
    /** True when the route can be followed from this lane. */
    val isRecommended: Boolean,
    /** The arrow the route takes from this lane; set only for recommended lanes. */
    val activeIndication: TurnModifier? = null,
    /**
     * Kept for buses, car pools, taxis and the like. Such a lane is drawn dim, and is recommended
     * only where the route marks no lane that every car may use. The maps that read the route
     * decide that; nothing here checks it, so odd lane data never stops guidance.
     */
    val designated: Boolean = false,
) {
    init {
        require(activeIndication == null || isRecommended) { "Only a recommended lane has an active indication" }
    }
}

/** Lane guidance for the next maneuver, ordered from the leftmost lane. */
data class LaneInfo(val lanes: List<Lane>) {
    init {
        require(lanes.isNotEmpty()) { "Lane guidance needs at least one lane" }
    }

    val recommendedLaneCount: Int get() = lanes.count { it.isRecommended }
}
