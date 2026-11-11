package io.github.purplepalm.custommaps.nav.replay

import io.github.purplepalm.custommaps.nav.guidance.FixTime
import io.github.purplepalm.custommaps.nav.guidance.StaleStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Invented clocks: the phone's elapsed realtime from boot, and wall clock times from 2000-01-01 UTC.
 * A simulated drive's positions are stamped by its replayer about 100 s ahead of the phone's clock,
 * also after a seek, forward or back.
 */
class SeekHoldTest {

    /** 2000-01-01 00:00:00 UTC. */
    private val wallAtBoot = 946_684_800_000L

    /** A position stamped [seconds] after boot, on both clocks. */
    private fun at(seconds: Double) = FixTime(elapsedNanos = (seconds * 1e9).toLong(), wallMillis = wallAtBoot + (seconds * 1e3).toLong())

    @Test
    fun `without a seek nothing is held`() {
        val hold = SeekHold()
        assertFalse(hold.isHeld(at(100.0)))
        assertFalse(hold.isHeld(null))
        assertEquals(0, hold.seeks)
    }

    @Test
    fun `while a seek waits to be carried out every update is held`() {
        val hold = SeekHold()
        hold.ask()
        assertTrue(hold.isHeld(at(100.0)))
        assertTrue(hold.isHeld(at(10_000.0)))
        assertTrue(hold.isHeld(null))
        // A position handed over before the replay plays from the new place is not the new place's.
        hold.onSourceFix(at(101.0))
        assertTrue(hold.isHeld(at(101.0)))
        assertEquals(0, hold.seeks)
    }

    @Test
    fun `after the seek, updates are held until the new place's first position, then only older ones`() {
        // The drive played up to 1,000 s; the seek was asked at 1,000.2 s and carried out at 1,000.3 s.
        val hold = SeekHold()
        hold.ask()
        hold.resume()
        assertEquals(1, hold.seeks)
        assertTrue(hold.isHeld(at(1_000.0)))
        assertTrue(hold.isHeld(null))

        hold.onSourceFix(at(1_000.4))
        // The new place's own positions, and later ones.
        assertFalse(hold.isHeld(at(1_000.4)))
        assertFalse(hold.isHeld(at(1_001.4)))
        assertFalse(hold.isHeld(null))
        // An old one, from long before: held. Within StaleStart's second it is taken as the new place's.
        assertTrue(hold.isHeld(at(990.0)))
        assertFalse(hold.isHeld(at(1_000.4 - StaleStart.SLACK_MILLIS / 1e3 + 0.1)))
        // Only the first position after the seek marks the new place.
        hold.onSourceFix(at(1_005.0))
        assertFalse(hold.isHeld(at(1_002.0)))
    }

    @Test
    fun `a seek back is held and let go by the same clock, which never runs back`() {
        // Back along the route the replayer still stamps its positions ahead of the phone's clock.
        val hold = SeekHold()
        hold.ask()
        hold.resume()
        hold.onSourceFix(at(2_000.0))
        assertFalse(hold.isHeld(at(2_001.0)))
        assertTrue(hold.isHeld(at(1_990.0)))
    }

    @Test
    fun `a seek given up holds nothing and counts as none`() {
        val hold = SeekHold()
        hold.ask()
        hold.cancel()
        assertFalse(hold.isHeld(at(100.0)))
        assertFalse(hold.isHeld(null))
        assertEquals(0, hold.seeks)
    }

    @Test
    fun `each seek holds again, and counts once`() {
        val hold = SeekHold()
        hold.ask()
        hold.resume()
        hold.onSourceFix(at(100.0))
        assertFalse(hold.isHeld(at(101.0)))

        hold.ask()
        assertTrue(hold.isHeld(at(101.0)))
        hold.resume()
        assertTrue(hold.isHeld(at(101.0)))
        hold.onSourceFix(at(110.0))
        assertTrue(hold.isHeld(at(101.0)))
        assertFalse(hold.isHeld(at(110.0)))
        assertEquals(2, hold.seeks)
    }

    @Test
    fun `the end of the drive lets everything go, and the count stays`() {
        val hold = SeekHold()
        hold.ask()
        hold.resume()
        hold.end()
        assertFalse(hold.isHeld(at(1.0)))
        assertFalse(hold.isHeld(null))
        assertEquals(1, hold.seeks)
    }
}
