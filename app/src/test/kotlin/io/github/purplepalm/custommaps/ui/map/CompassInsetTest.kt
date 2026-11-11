package io.github.purplepalm.custommaps.ui.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where the compass goes under the top of the screen. All values are pixels from the top of the map. */
class CompassInsetTest {

    private val band = 168

    @Test
    fun `before a drive the compass sits under the top column`() {
        assertEquals(240, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 240, offlineChipBottom = 0))
    }

    @Test
    fun `the offline chip under the search bar moves the compass below it`() {
        assertEquals(456, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 240, offlineChipBottom = 456))
        // And back when the chip is gone.
        assertEquals(240, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 240, offlineChipBottom = 0))
    }

    @Test
    fun `a list that reaches further down than the chip did keeps the compass under the list`() {
        assertEquals(1_200, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 1_200, offlineChipBottom = 456))
    }

    @Test
    fun `during guidance the compass sits under the band, whatever hangs in it`() {
        val withTab = compassTopInset(guiding = true, maneuverPanelBottom = 600, band = band, topPanelBottom = 600, offlineChipBottom = 0)
        assertEquals(600 + band, withTab)
        // The chip under the search bar is not on screen during guidance; a value left over does not count.
        assertEquals(withTab, compassTopInset(guiding = true, maneuverPanelBottom = 600, band = band, topPanelBottom = 600, offlineChipBottom = 2_000))
    }

    @Test
    fun `during guidance the compass follows the card when lanes make it taller`() {
        assertEquals(780 + band, compassTopInset(guiding = true, maneuverPanelBottom = 780, band = band, topPanelBottom = 780, offlineChipBottom = 0))
    }

    @Test
    fun `guidance without its first progress has no card and no band yet`() {
        assertEquals(108, compassTopInset(guiding = true, maneuverPanelBottom = 0, band = band, topPanelBottom = 108, offlineChipBottom = 0))
    }
}
