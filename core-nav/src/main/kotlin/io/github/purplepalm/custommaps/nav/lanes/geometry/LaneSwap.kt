package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology

/**
 * The swap between the route line and the lit lane (the own lanes' design 5.3 and 8; T5, E5, D18). Over a stretch where
 * a lane is lit, the route line's pair of layers is drawn whole or not at all, never in between: it goes when the close
 * view or the junction view is latched ([onLatched]) and the drawn lane at the car ([onLane]) is at least [SWAP_IN_DP]
 * wide on the screen ([onZoom]), a street's narrower lane [SWAP_IN_STREET_DP], and it comes back when the view lets go or
 * the lane is under [SWAP_OUT_DP]. Between the two widths the state holds, so a view that rests there never makes the line
 * blink, and a zoom that only nears the swap's width never swaps at all: the opaque line stays in the named lane, as at a
 * high peak speed. Mapbox tuned's views are never latched
 * ([io.github.purplepalm.custommaps.nav.camera.CloseView.mapboxTunedLatched]), so there the line stays at every zoom.
 *
 * At the swap the lit lane's amber ([amberDp]) is a little narrower than the held route line it replaces, at the same
 * colour and place, at least [AMBER_SHARE] of it on a freeway's lane and a street's, and from there on only wider. Pure;
 * the map feeds it the latched state and the drawn lane's width at the car once per progress update and the zoom at each
 * camera change, and writes the line's opacity only when a call says the state flipped.
 */
class LaneSwap {

    /** Whether the lit lane shows in the route line's place: its pair of layers is not drawn. */
    var swapped = false
        private set

    /** How often the state flipped, either way, since the last [takeFlips]. */
    private var flips = 0

    private var latched = false
    private var laneDp = 0.0
    private var laneMeters = LaneTopology.FREEWAY_LANE_METERS
    private var zoom = Double.NaN
    private var latitude = 0.0

    /** The route line's opacity over the lit stretches: 1 or 0, never between. */
    val lineOpacity: Double get() = if (swapped) 0.0 else 1.0

    /** The camera's word, once per progress update, whether a close view or the junction view is latched. True where the state flipped. */
    fun onLatched(latched: Boolean): Boolean {
        this.latched = latched
        return update()
    }

    /** The camera's zoom at a change of the camera, where its center is at [latitude]. True where the state flipped. */
    fun onZoom(zoom: Double, latitude: Double): Boolean {
        this.zoom = zoom
        this.latitude = latitude
        laneDp = laneDp(zoom, latitude, laneMeters)
        return update()
    }

    /**
     * The drawn lane's width at the car on the ground, [meters], once per progress update: a freeway's or a ramp's lane, or a
     * street's narrower one. True where the state flipped.
     */
    fun onLane(meters: Double): Boolean {
        if (meters == laneMeters) return false
        laneMeters = meters
        if (!zoom.isNaN()) laneDp = laneDp(zoom, latitude, meters)
        return update()
    }

    /** No view is latched and no zoom known: as at the start of a drive. True where the state flipped. */
    fun reset(): Boolean {
        latched = false
        laneDp = 0.0
        zoom = Double.NaN
        laneMeters = LaneTopology.FREEWAY_LANE_METERS
        return update()
    }

    /** How often the state flipped since the last call. */
    fun takeFlips(): Int = flips.also { flips = 0 }

    private fun update(): Boolean {
        val want = when {
            latched && laneDp >= swapInDp(laneMeters) -> true
            !latched || laneDp < SWAP_OUT_DP -> false
            else -> swapped
        }
        if (want == swapped) return false
        swapped = want
        flips++
        return true
    }

    companion object {
        /** A drawn lane at least this wide on the screen lets the lit lane take the line's place... */
        const val SWAP_IN_DP = 10.4

        /**
         * ...and a street's narrower lane ([LaneTopology.STREET_LANE_METERS]) at least this wide, so its amber, too, is at
         * least [AMBER_SHARE] of the held route line from the swap on.
         */
        const val SWAP_IN_STREET_DP = 10.45

        /** Under this the line comes back. */
        const val SWAP_OUT_DP = 9.6

        /** The share of the held route line the lit lane's amber is at least as wide as from the swap on (the design's 10.1). */
        const val AMBER_SHARE = 0.94

        /** The width a drawn lane [laneMeters] wide must show for the lit lane to take the line's place. */
        fun swapInDp(laneMeters: Double): Double =
            if (laneMeters < LaneTopology.FREEWAY_LANE_METERS - 1e-9) SWAP_IN_STREET_DP else SWAP_IN_DP

        /** How wide a drawn lane [laneMeters] wide, a freeway's unless given, is on the screen at [zoom] where the camera's center is at [latitude]. */
        fun laneDp(zoom: Double, latitude: Double, laneMeters: Double = LaneTopology.FREEWAY_LANE_METERS): Double =
            laneMeters / MapScale.metersPerDp(zoom, latitude)

        /** How wide the lit lane's amber shows at [zoom] and [latitude]: a drawn lane [laneMeters] wide less the inner halves of its two edges. */
        fun amberDp(zoom: Double, latitude: Double, laneMeters: Double = LaneTopology.FREEWAY_LANE_METERS): Double =
            (laneMeters - LaneDrawing.LIT_EDGE_METERS) / MapScale.metersPerDp(zoom, latitude)
    }
}
