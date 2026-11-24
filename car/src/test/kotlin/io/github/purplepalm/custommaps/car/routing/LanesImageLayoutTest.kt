package io.github.purplepalm.custommaps.car.routing

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's lanes image: the phone's lane row in the box Google asks a lanes image to fill, a lane to take its tile
 * inside its own square, with nothing under the lanes.
 */
class LanesImageLayoutTest {

    private val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)

    @Test
    fun `the image is 500 by 74 dp at the car's density`() {
        assertEquals(PixelBox(0, 0, 500, 74), LanesImageLayout.of(3, 1f).image)
        assertEquals(PixelBox(0, 0, 1000, 148), LanesImageLayout.of(3, 2f).image)
        assertEquals(PixelBox(0, 0, 750, 111), LanesImageLayout.of(3, 1.5f).image)
    }

    @Test
    fun `a lane at full size fills the height, in the phone's proportions, with no band under it`() {
        for (density in densities) {
            val layout = LanesImageLayout.of(4, density)
            assertEquals(0, layout.lanesTop, "$density")
            assertEquals(layout.image.height, layout.slot, "$density")
            // 44 parts to a lane, 4 at each side of its arrows.
            assertTrue(abs(layout.gap / layout.slot.toFloat() - 4f / 44f) < 0.02f, "$density: ${layout.gap} of ${layout.slot}")
            // As much room under the arrows as above them: no band for a bar.
            val arrows = layout.arrows(0)
            assertEquals(arrows.top, layout.image.height - arrows.bottom, "$density")
        }
    }

    @Test
    fun `the arrows' square is 60 px up to six lanes, 59 at seven and 50 at eight`() {
        for (lanes in 1..6) assertEquals(60, LanesImageLayout.of(lanes, 1f).arrows(0).width, "$lanes lanes")
        assertEquals(59, LanesImageLayout.of(7, 1f).arrows(0).width)
        assertEquals(50, LanesImageLayout.of(8, 1f).arrows(0).width)
        // Its edge stays at most 1.5 dp or 3 % of the square: 1.5 px on all of them.
        for (lanes in 1..8) assertEquals(1.5f, CarImageColors.edgePx(1f, LanesImageLayout.of(lanes, 1f).arrows(0).width.toFloat()), 1e-6f, "$lanes lanes")
    }

    @Test
    fun `six lanes fit at full size, more get narrower and all of them show`() {
        for (density in densities) {
            val full = LanesImageLayout.of(1, density).slot
            for (lanes in 1..6) assertEquals(full, LanesImageLayout.of(lanes, density).slot, "$lanes lanes at $density")
            for (lanes in 7..16) {
                val layout = LanesImageLayout.of(lanes, density)
                assertTrue(layout.slot < full, "$lanes lanes at $density")
                assertTrue(layout.slot * lanes <= layout.image.width, "$lanes lanes at $density")
            }
        }
    }

    @Test
    fun `every lane and its arrows lie inside the image, side by side, in the middle`() {
        for (density in densities) {
            for (lanes in 1..16) {
                val layout = LanesImageLayout.of(lanes, density)
                val image = layout.image
                // The row stands in the middle, within a pixel, across and up and down.
                val right = image.width - (layout.start + layout.slot * lanes)
                assertTrue(abs(right - layout.start) <= 1, "$lanes lanes at $density")
                assertTrue(abs(image.height - (layout.lanesTop + layout.slot) - layout.lanesTop) <= 1, "$lanes lanes at $density")
                var lastRight = Int.MIN_VALUE
                for (index in 0 until lanes) {
                    val arrows = layout.arrows(index)
                    assertTrue(arrows.left >= 0 && arrows.top >= 0 && arrows.right <= image.width && arrows.bottom <= image.height, "$lanes lanes at $density: $arrows")
                    assertEquals(arrows.width, arrows.height, "square arrows")
                    assertTrue(arrows.left >= lastRight, "lanes do not overlap")
                    lastRight = arrows.right
                }
            }
        }
    }

    @Test
    fun `narrower lanes stand in the middle of the image's height`() {
        val layout = LanesImageLayout.of(12, 1f)
        assertEquals((74 - layout.slot) / 2, layout.lanesTop)
        assertEquals(layout.lanesTop + layout.gap, layout.arrows(0).top)
    }

    @Test
    fun `a row needs a lane and a screen needs a density`() {
        assertThrows(IllegalArgumentException::class.java) { LanesImageLayout.of(0, 1f) }
        assertThrows(IllegalArgumentException::class.java) { LanesImageLayout.of(2, 0f) }
        assertThrows(IllegalArgumentException::class.java) { ManeuverIconLayout.side(Float.NaN) }
    }

    @Test
    fun `the maneuver icon is 128 dp square`() {
        assertEquals(128, ManeuverIconLayout.side(1f))
        assertEquals(256, ManeuverIconLayout.side(2f))
        assertEquals(352, ManeuverIconLayout.side(2.75f))
    }
}
