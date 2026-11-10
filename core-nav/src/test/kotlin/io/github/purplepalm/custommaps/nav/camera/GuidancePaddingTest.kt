package io.github.purplepalm.custommaps.nav.camera

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidancePaddingTest {
    private val padding = GuidancePadding()

    /** The inset with nothing shown: the status bar and the screen's margin. */
    private val rest = 120

    @Test
    fun `outside guidance the inset is the resting one`() {
        assertEquals(rest, padding.topInset(guiding = false, panelBottom = 0, restTop = rest))
        // Nothing but the maneuver panel is an input, and outside guidance not even that.
        assertEquals(rest, padding.topInset(guiding = false, panelBottom = 900, restTop = rest))
    }

    @Test
    fun `guidance starts at the resting inset until the panel is shown`() {
        assertEquals(rest, padding.topInset(guiding = true, panelBottom = 0, restTop = rest))
        assertEquals(420, padding.topInset(guiding = true, panelBottom = 420, restTop = rest))
    }

    @Test
    fun `the inset follows the panel down and stays when the panel gets shorter`() {
        assertEquals(420, padding.topInset(guiding = true, panelBottom = 420, restTop = rest))
        // The lanes row appears.
        assertEquals(570, padding.topInset(guiding = true, panelBottom = 570, restTop = rest))
        // It goes again, and the instruction is back on one line.
        assertEquals(570, padding.topInset(guiding = true, panelBottom = 420, restTop = rest))
        assertEquals(570, padding.topInset(guiding = true, panelBottom = 380, restTop = rest))
        // Only a panel taller than ever moves the frame once more.
        assertEquals(640, padding.topInset(guiding = true, panelBottom = 640, restTop = rest))
    }

    @Test
    fun `a panel that is gone for a moment keeps the inset`() {
        padding.topInset(guiding = true, panelBottom = 570, restTop = rest)
        assertEquals(570, padding.topInset(guiding = true, panelBottom = 0, restTop = rest))
        assertEquals(570, padding.topInset(guiding = true, panelBottom = 420, restTop = rest))
    }

    @Test
    fun `the end of guidance starts over`() {
        padding.topInset(guiding = true, panelBottom = 640, restTop = rest)
        assertEquals(rest, padding.topInset(guiding = false, panelBottom = 0, restTop = rest))
        // The next drive grows from its own panel, not from the last drive's tallest.
        assertEquals(420, padding.topInset(guiding = true, panelBottom = 420, restTop = rest))
    }

    @Test
    fun `the band is added under the panel for the whole of guidance and never twice`() {
        val band = 147
        assertEquals(rest + band, padding.topInset(guiding = true, panelBottom = 0, restTop = rest, band = band))
        assertEquals(420 + band, padding.topInset(guiding = true, panelBottom = 420, restTop = rest, band = band))
        // Asking again with the same screen gives the same inset.
        assertEquals(420 + band, padding.topInset(guiding = true, panelBottom = 420, restTop = rest, band = band))
        assertEquals(570 + band, padding.topInset(guiding = true, panelBottom = 570, restTop = rest, band = band))
        assertEquals(570 + band, padding.topInset(guiding = true, panelBottom = 420, restTop = rest, band = band))
        // No band outside guidance.
        assertEquals(rest, padding.topInset(guiding = false, panelBottom = 0, restTop = rest, band = band))
    }

    @Test
    fun `during guidance the inset never shrinks and never leaves the panel uncovered`() {
        for (seed in 1L..20L) {
            val random = Random(seed)
            val drive = GuidancePadding()
            var last = 0
            repeat(200) {
                val panel = if (random.nextInt(10) == 0) 0 else 300 + random.nextInt(400)
                val inset = drive.topInset(guiding = true, panelBottom = panel, restTop = rest)
                assertTrue(inset >= last, "seed $seed: $inset after $last")
                assertTrue(inset >= panel && inset >= rest, "seed $seed: $inset for a panel down to $panel")
                last = inset
            }
        }
    }
}
