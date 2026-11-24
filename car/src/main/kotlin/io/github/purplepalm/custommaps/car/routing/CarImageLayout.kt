package io.github.purplepalm.custommaps.car.routing

import kotlin.math.roundToInt

/** A box in pixels: from [left] and [top], to [right] and [bottom], those two not included. */
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where the lanes image puts each lane, in pixels of the car's screen. The image is the size Google
 * asks a lanes image to fill, [LanesImageLayout.WIDTH_DP] by [LanesImageLayout.HEIGHT_DP] dp, and
 * the host scales it down where its card is smaller.
 *
 * The row is the phone's card row ([CardLanes][io.github.purplepalm.custommaps.nav.guidance.CardLanes]),
 * in the phone's proportions: a lane is 44 parts wide with 4 parts empty at each side of its arrows.
 * A lane to take is a tile inside its own square, so nothing lies under the lanes: a lane at full
 * size fills the image's height, its arrows' square 60 px of the 74 dp high image at a density of 1,
 * for up to six lanes. Every lane is shown: where more lanes than fit at full size come, all of them
 * get narrower (59 px at seven lanes, 50 px at eight), standing in the middle of the image's height.
 * The row stands in the middle of the image.
 *
 * @property image the whole image
 * @property slot how wide each lane is, its empty sides included
 * @property start where the first lane begins
 * @property lanesTop the top of the lanes
 * @property gap the empty side of each lane, left and right and above and below its arrows
 */
data class LanesImageLayout(
    val image: PixelBox,
    val lanes: Int,
    val slot: Int,
    val start: Int,
    val lanesTop: Int,
    val gap: Int,
) {
    /** The square the arrows of lane [index], counted from the left, are drawn into. */
    fun arrows(index: Int): PixelBox {
        val side = (slot - 2 * gap).coerceAtLeast(0)
        val left = start + index * slot + gap
        val top = lanesTop + gap
        return PixelBox(left, top, left + side, top + side)
    }

    companion object {
        const val WIDTH_DP = 500f
        const val HEIGHT_DP = 74f

        /** The phone's lane and its gap, in its parts ([LANE_PARTS] to a lane). */
        private const val LANE_PARTS = 44f
        private const val GAP_PARTS = 4f

        /** The layout of a row of [lanes] lanes on a screen of [density] pixels to a dp. */
        fun of(lanes: Int, density: Float): LanesImageLayout {
            require(lanes > 0) { "A lanes image needs at least one lane" }
            require(density > 0f && density.isFinite()) { "Density must be positive: $density" }
            val width = (WIDTH_DP * density).roundToInt().coerceAtLeast(1)
            val height = (HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
            // A lane at full size fills the height.
            val slot = minOf(height, width / lanes).coerceAtLeast(1)
            val gap = (slot * GAP_PARTS / LANE_PARTS).roundToInt()
            return LanesImageLayout(
                image = PixelBox(0, 0, width, height),
                lanes = lanes,
                slot = slot,
                start = (width - slot * lanes) / 2,
                lanesTop = (height - slot) / 2,
                gap = gap,
            )
        }
    }
}

/** The maneuver icon's size: the square Google asks a maneuver icon to fill, the arrow drawn across all of it. */
object ManeuverIconLayout {
    const val SIDE_DP = 128f

    /** The icon's side in pixels on a screen of [density] pixels to a dp. */
    fun side(density: Float): Int {
        require(density > 0f && density.isFinite()) { "Density must be positive: $density" }
        return (SIDE_DP * density).roundToInt().coerceAtLeast(1)
    }
}
