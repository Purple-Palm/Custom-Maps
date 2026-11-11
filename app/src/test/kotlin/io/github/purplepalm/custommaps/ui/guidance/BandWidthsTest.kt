package io.github.purplepalm.custommaps.ui.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the band under the maneuver card is shared between the Then chip and the tab at its end side. */
class BandWidthsTest {

    @Test
    fun `the Then chip keeps its seven tenths of the card`() {
        // A card 336 dp wide at 3 pixels a dp, with a gap of 4 dp.
        assertEquals(706, bandWidths(cardWidth = 1_008, gap = 12).then)
        assertEquals(700, bandWidths(cardWidth = 1_000, gap = 0).then)
        // The gap is never taken from the chip.
        assertEquals(bandWidths(1_008, gap = 0).then, bandWidths(1_008, gap = 200).then)
    }

    @Test
    fun `the end tab gets what is left, less the gap`() {
        assertEquals(1_008 - 706 - 12, bandWidths(cardWidth = 1_008, gap = 12).endTab)
        assertEquals(290, bandWidths(cardWidth = 1_000, gap = 10).endTab)
    }

    @Test
    fun `the end tab never takes more than three tenths of the card`() {
        for (width in 1..2_000) {
            assertTrue(bandWidths(width, gap = 0).endTab <= width * 0.3f, "card $width")
        }
        assertEquals(300, bandWidths(cardWidth = 1_000, gap = 0).endTab)
    }

    @Test
    fun `the chip and the tab never meet, on a card of any width`() {
        for (width in 0..2_000) {
            for (gap in listOf(0, 4, 12, 16)) {
                val widths = bandWidths(width, gap)
                assertTrue(widths.endTab >= 0, "card $width, gap $gap")
                if (widths.endTab > 0) assertTrue(widths.then + gap + widths.endTab <= width, "card $width, gap $gap")
            }
        }
    }

    @Test
    fun `a card too narrow for the gap has no end tab`() {
        assertEquals(0, bandWidths(cardWidth = 10, gap = 12).endTab)
        assertEquals(0, bandWidths(cardWidth = 0, gap = 12).endTab)
    }
}
