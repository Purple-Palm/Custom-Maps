package io.github.purplepalm.custommaps.ui.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where the compass goes under the top of the screen. All values are pixels from the top of the map. */
class CompassInsetTest {

    private val band = 168

    @Test
    fun `before a drive the compass sits under the top column`() {
        assertEquals(240, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 240))
        // Also under a list that reaches far down.
        assertEquals(1_200, compassTopInset(guiding = false, maneuverPanelBottom = 0, band = band, topPanelBottom = 1_200))
    }

    @Test
    fun `during guidance the compass sits under the band`() {
        assertEquals(600 + band, compassTopInset(guiding = true, maneuverPanelBottom = 600, band = band, topPanelBottom = 600))
    }

    @Test
    fun `during guidance the compass follows the card when lanes make it taller`() {
        assertEquals(780 + band, compassTopInset(guiding = true, maneuverPanelBottom = 780, band = band, topPanelBottom = 780))
    }

    @Test
    fun `guidance without its first progress has no card and no band yet`() {
        assertEquals(108, compassTopInset(guiding = true, maneuverPanelBottom = 0, band = band, topPanelBottom = 108))
    }
}
