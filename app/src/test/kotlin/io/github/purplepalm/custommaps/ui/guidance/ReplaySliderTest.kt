package io.github.purplepalm.custommaps.ui.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The replay slider's dot between the drive's positions, and where on its track a finger is. Frame times in ns. */
class ReplaySliderTest {

    private val second = 1_000_000_000L

    @Test
    fun `the first position is shown at once`() {
        val glide = DotGlide()
        assertFalse(glide.started)
        glide.snapTo(0.2f)
        assertTrue(glide.started)
        assertEquals(0.2f, glide.at(0L))
        assertEquals(0.2f, glide.at(5 * second))
        assertTrue(glide.arrived(0L))
    }

    @Test
    fun `the dot glides to each new position at a steady pace, and stays there`() {
        val glide = DotGlide()
        glide.snapTo(0.2f)
        glide.glideTo(0.3f, 10 * second)
        assertEquals(0.2f, glide.at(10 * second), 1e-6f)
        assertEquals(0.25f, glide.at(10 * second + second / 2), 1e-6f)
        assertFalse(glide.arrived(10 * second + second / 2))
        assertEquals(0.3f, glide.at(11 * second), 1e-6f)
        assertTrue(glide.arrived(11 * second))
        // No newer position: it stays, and no frame is needed for it.
        assertEquals(0.3f, glide.at(30 * second), 1e-6f)
    }

    @Test
    fun `a position that comes while the dot is still on its way glides on from where it is drawn`() {
        val glide = DotGlide()
        glide.snapTo(0.2f)
        glide.glideTo(0.3f, 10 * second)
        // Half a second later, the next position: no jump back or forward.
        glide.glideTo(0.4f, 10 * second + second / 2)
        assertEquals(0.25f, glide.at(10 * second + second / 2), 1e-6f)
        assertEquals(0.325f, glide.at(11 * second), 1e-6f)
        assertEquals(0.4f, glide.at(11 * second + second / 2), 1e-6f)
    }

    @Test
    fun `a seek puts the dot where it goes at once`() {
        val glide = DotGlide()
        glide.snapTo(0.2f)
        glide.glideTo(0.3f, 10 * second)
        glide.snapTo(0.8f)
        assertEquals(0.8f, glide.at(10 * second + 1))
        assertTrue(glide.arrived(10 * second + 1))
    }

    @Test
    fun `a finger anywhere on the row is a place on the track, its ends included`() {
        // A row 300 px wide whose track starts and ends 36 px in.
        assertEquals(0f, trackFraction(36f, 300f, 36f))
        assertEquals(0.5f, trackFraction(150f, 300f, 36f))
        assertEquals(1f, trackFraction(264f, 300f, 36f))
        assertEquals(0f, trackFraction(10f, 300f, 36f))
        assertEquals(1f, trackFraction(299f, 300f, 36f))
        // A row with no room for a track.
        assertEquals(0f, trackFraction(20f, 60f, 36f))
    }
}
