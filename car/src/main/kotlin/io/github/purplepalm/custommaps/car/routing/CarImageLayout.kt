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
 * in the phone's proportions: a lane is 44 parts wide with 4 parts empty at each side of its arrows,
 * and a lane to take has a bar 3 parts thick 2 parts under it, 60 % of the lane's width, with round
 * ends. A lane at full size and its bar fill the image's height. Every lane is shown: where more lanes
 * than fit at full size come, all of them get narrower, standing in the middle of the lanes' height
 * with their bars right under them. The row stands in the middle of the image.
 *
 * @property image the whole image
 * @property slot how wide each lane is, its empty sides included
 * @property start where the first lane begins
 * @property lanesTop the top of the lanes
 * @property gap the empty side of each lane, left and right and above its arrows
 */
data class LanesImageLayout(
    val image: PixelBox,
    val lanes: Int,
    val slot: Int,
    val start: Int,
    val lanesTop: Int,
    val gap: Int,
    val barGap: Int,
    val barThickness: Int,
) {
    /** The square the arrows of lane [index], counted from the left, are drawn into. */
    fun arrows(index: Int): PixelBox {
        val side = (slot - 2 * gap).coerceAtLeast(0)
        val left = start + index * slot + gap
        val top = lanesTop + gap
        return PixelBox(left, top, left + side, top + side)
    }

    /** The bar under lane [index], counted from the left, should it be a lane to take. */
    fun bar(index: Int): PixelBox {
        val width = (slot * BAR_SHARE).roundToInt()
        val left = start + index * slot + (slot - width) / 2
        val top = lanesTop + slot + barGap
        return PixelBox(left, top, left + width, top + barThickness)
    }

    companion object {
        const val WIDTH_DP = 500f
        const val HEIGHT_DP = 74f

        /** The phone's lane, its gap, the bar's gap and thickness, in its parts ([LANE_PARTS] to a lane). */
        private const val LANE_PARTS = 44f
        private const val GAP_PARTS = 4f
        private const val BAR_GAP_PARTS = 2f
        private const val BAR_PARTS = 3f

        /** How much of its lane's width the bar takes, in the middle of it. */
        const val BAR_SHARE = 0.6f

        /** The layout of a row of [lanes] lanes on a screen of [density] pixels to a dp. */
        fun of(lanes: Int, density: Float): LanesImageLayout {
            require(lanes > 0) { "A lanes image needs at least one lane" }
            require(density > 0f && density.isFinite()) { "Density must be positive: $density" }
            val width = (WIDTH_DP * density).roundToInt().coerceAtLeast(1)
            val height = (HEIGHT_DP * density).roundToInt().coerceAtLeast(1)
            // One part in pixels: a lane at full size and its bar fill the height.
            val part = height / (LANE_PARTS + BAR_GAP_PARTS + BAR_PARTS)
            val barGap = (BAR_GAP_PARTS * part).roundToInt()
            val barThickness = (BAR_PARTS * part).roundToInt().coerceAtLeast(1)
            val fullSlot = (height - barGap - barThickness).coerceAtLeast(1)
            val slot = minOf(fullSlot, width / lanes).coerceAtLeast(1)
            val gap = (slot * GAP_PARTS / LANE_PARTS).roundToInt()
            return LanesImageLayout(
                image = PixelBox(0, 0, width, height),
                lanes = lanes,
                slot = slot,
                start = (width - slot * lanes) / 2,
                lanesTop = (fullSlot - slot) / 2,
                gap = gap,
                barGap = barGap,
                barThickness = barThickness,
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
