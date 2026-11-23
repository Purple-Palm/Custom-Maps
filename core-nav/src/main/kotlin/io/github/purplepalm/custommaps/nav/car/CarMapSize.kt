package io.github.purplepalm.custommaps.nav.car

import java.math.BigDecimal

/**
 * How large the car's map draws, against the size the car's host asks for: the five sizes of Settings > Car map size.
 * The host's density sets the car's own size, [X1]; the map's pixel ratio is that density times [factor]
 * ([pixelRatio]), a value a map is made with. Every size of the map's style grows with the ratio: Mapbox Standard's
 * roads, labels and icons, the route line and the app's own lanes, and the camera's dp, which are the map's logical
 * pixels. An image a map is given as a bitmap keeps its own pixels whatever the ratio, so it takes [factor] as its
 * scale to grow with the rest. The size is the car's alone: the phone's map keeps the phone's density.
 *
 * [X1_5] when never chosen, the owner's choice ([DEFAULT]). Stored as its percent ([stored]), a number that never
 * changes; a number no size has reads as the default ([of]).
 */
enum class CarMapSize(
    /** The size in percent of the car's own: 100 to 200. */
    val percent: Int,
) {
    /** The size the car's host asks for. */
    X1(100),
    X1_25(125),
    X1_5(150),
    X1_75(175),
    X2(200),
    ;

    /** How much larger than the car's own size the map draws: 1, 1.25, 1.5, 1.75 or 2. */
    val factor: Double get() = percent / PERCENT

    /** The factor as the settings row writes it, without trailing zeros: "1", "1.25", "1.5", "1.75" or "2". */
    val factorText: String get() = BigDecimal(percent).movePointLeft(2).stripTrailingZeros().toPlainString()

    /** The number stored for this size: its percent. */
    val stored: Int get() = percent

    /**
     * The pixel ratio of the car's map on a host of [density], its dpi over 160 (1.0 at 160 dpi): the density times
     * [factor]. A density that is not a positive number counts as 1.
     */
    fun pixelRatio(density: Float): Float = (hostDensity(density) * factor).toFloat()

    companion object {
        /** The size when never chosen: x1.5, the owner's choice. */
        val DEFAULT = X1_5

        private const val PERCENT = 100.0

        /** The size stored as [stored]: the one with that percent; the default for any other number, and for none. */
        fun of(stored: Int?): CarMapSize = entries.firstOrNull { it.percent == stored } ?: DEFAULT

        /** [density] as the host's: itself when it is a positive number, else 1. */
        fun hostDensity(density: Float): Double = if (density > 0f && density.isFinite()) density.toDouble() else 1.0
    }
}
