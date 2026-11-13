package io.github.purplepalm.custommaps.car.routing

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The car's lanes image: the phone's lane row in the box Google asks a lanes image to fill. */
class LanesImageLayoutTest {

    private val densities = listOf(1f, 1.5f, 2f, 2.75f, 3f)

    @Test
    fun `the image is 500 by 74 dp at the car's density`() {
        assertEquals(PixelBox(0, 0, 500, 74), LanesImageLayout.of(3, 1f).image)
        assertEquals(PixelBox(0, 0, 1000, 148), LanesImageLayout.of(3, 2f).image)
        assertEquals(PixelBox(0, 0, 750, 111), LanesImageLayout.of(3, 1.5f).image)
    }

    @Test
    fun `a lane at full size and its bar fill the height, in the phone's proportions`() {
        for (density in densities) {
            val layout = LanesImageLayout.of(4, density)
            assertEquals(layout.image.height, layout.lanesTop + layout.slot + layout.barGap + layout.barThickness, "$density")
            // 44 parts to a lane, 4 at each side of its arrows, a bar 3 parts thick 2 under it.
            assertTrue(abs(layout.gap / layout.slot.toFloat() - 4f / 44f) < 0.02f, "$density: ${layout.gap} of ${layout.slot}")
            assertTrue(abs(layout.barThickness - layout.slot * 3f / 44f) <= 1f, "$density")
            assertTrue(abs(layout.barGap - layout.slot * 2f / 44f) <= 1f, "$density")
            assertTrue(abs(layout.bar(0).width - layout.slot * 0.6f) <= 1f, "$density")
        }
    }

    @Test
    fun `seven lanes fit at full size, more get narrower and all of them show`() {
        for (density in densities) {
            val full = LanesImageLayout.of(1, density).slot
            for (lanes in 1..7) assertEquals(full, LanesImageLayout.of(lanes, density).slot, "$lanes lanes at $density")
            for (lanes in 8..16) {
                val layout = LanesImageLayout.of(lanes, density)
                assertTrue(layout.slot < full, "$lanes lanes at $density")
                assertTrue(layout.slot * lanes <= layout.image.width, "$lanes lanes at $density")
            }
        }
    }

    @Test
    fun `every lane, its arrows and its bar lie inside the image, side by side, in the middle`() {
        for (density in densities) {
            for (lanes in 1..16) {
                val layout = LanesImageLayout.of(lanes, density)
                val image = layout.image
                // The row stands in the middle, within a pixel.
                val right = image.width - (layout.start + layout.slot * lanes)
                assertTrue(abs(right - layout.start) <= 1, "$lanes lanes at $density")
                var lastRight = Int.MIN_VALUE
                for (index in 0 until lanes) {
                    val arrows = layout.arrows(index)
                    val bar = layout.bar(index)
                    for (box in listOf(arrows, bar)) {
                        assertTrue(box.left >= 0 && box.top >= 0 && box.right <= image.width && box.bottom <= image.height, "$lanes lanes at $density: $box")
                    }
                    assertEquals(arrows.width, arrows.height, "square arrows")
                    assertTrue(arrows.left >= lastRight, "lanes do not overlap")
                    lastRight = arrows.right
                    // The bar lies under its lane's arrows, in the middle of them.
                    assertTrue(bar.top > arrows.bottom - 1, "$lanes lanes at $density")
                    assertTrue(abs((bar.left + bar.right) - (arrows.left + arrows.right)) <= 2, "$lanes lanes at $density")
                }
            }
        }
    }

    @Test
    fun `narrower lanes stand in the middle of the lanes' height with their bars right under them`() {
        val layout = LanesImageLayout.of(12, 1f)
        val full = LanesImageLayout.of(1, 1f)
        assertEquals((full.slot - layout.slot) / 2, layout.lanesTop)
        assertEquals(layout.lanesTop + layout.slot + layout.barGap, layout.bar(0).top)
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
