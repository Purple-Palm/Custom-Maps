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
    /**
     * A lane of the row's point that the map does not draw where the car is yet: one that opens on the way there, or
     * may already be there only from farther on. Drawn as an outline, never lit and never counted in the lanes' words;
     * the run-up's row from the lane plan marks it, and nothing checks it, as for [designated].
     */
    val outline: Boolean = false,
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

    /** The lanes the route can be followed from; a lane drawn as an outline is never one of them. */
    val recommendedLaneCount: Int get() = lanes.count { it.isRecommended && !it.outline }
}
