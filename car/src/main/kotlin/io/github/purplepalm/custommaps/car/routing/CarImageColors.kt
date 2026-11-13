package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.look.Look

/** A colour of the car's own images and the thin edge around it, as ARGB values (`0xAARRGGBB`). */
data class CarInk(val fill: Int, val edge: Int)

/** The colours of the car's maneuver arrows and lane rows in one look. */
data class CarImageColors(
    /** The maneuver to make now and the lanes to take: the route's amber. */
    val lit: CarInk,
    /** Every other lane arrow. */
    val dim: CarInk,
    /** The maneuver after it, the Then step, in the card's ink as on the phone: amber stays for what the driver does now. */
    val then: CarInk,
) {
    companion object {
        /**
         * The colours of [look]. The host draws the routing card behind the images in a colour of its
         * own, dark on the Android Auto versions this was built against, so the arrows are bright on
         * it, and each has a thin dark edge that keeps its shape on a light card too, as the car map's
         * attribution has. Amber is the route's amber of both looks with the route line's dark casing
         * of each look (DECISIONS 15). By day the other lanes are a lighter grey than at night, for
         * the sun; at night they are the phone's night grey.
         */
        fun of(look: Look): CarImageColors = when (look) {
            Look.DAY -> DAY
            Look.NIGHT -> NIGHT
        }

        private const val AMBER = 0xFFF5A524.toInt()
        private const val GRAPHITE = 0xFF0E1013.toInt()
        private const val PLATINUM = 0xFFE5E4E2.toInt()

        private val DAY = CarImageColors(
            lit = CarInk(AMBER, edge = 0xFF5C3B00.toInt()),
            dim = CarInk(0xFF9AA1AB.toInt(), edge = GRAPHITE),
            then = CarInk(PLATINUM, edge = GRAPHITE),
        )

        private val NIGHT = CarImageColors(
            lit = CarInk(AMBER, edge = 0xFF3A2800.toInt()),
            dim = CarInk(0xFF6E747C.toInt(), edge = GRAPHITE),
            then = CarInk(PLATINUM, edge = GRAPHITE),
        )

        /** How wide the edge round every arrow and bar is, in dp of the car's screen, at most. */
        const val EDGE_DP = 1.5f

        /** And at most this share of the glyph's side, so that the narrow lanes of a wide road keep their arrows. */
        const val EDGE_SHARE = 0.03f

        /** The edge round a glyph [side] pixels wide on a screen of [density] pixels to a dp, in pixels. */
        fun edgePx(density: Float, side: Float): Float = minOf(EDGE_DP * density, EDGE_SHARE * side).coerceAtLeast(0f)
    }
}
