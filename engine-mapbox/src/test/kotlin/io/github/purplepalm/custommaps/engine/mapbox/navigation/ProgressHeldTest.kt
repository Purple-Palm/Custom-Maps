package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.guidance.FixTime
import io.github.purplepalm.custommaps.nav.guidance.StaleStart
import io.github.purplepalm.custommaps.nav.replay.SeekHold
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which progress updates the engine leaves alone, so that they move neither the run-up's latch nor
 * the watch of diverges: those the start guard or the seek guard holds back. Made-up times, on
 * both clocks from the phone's start, the wall clock from 2000-01-01 UTC.
 */
class ProgressHeldTest {

    private fun at(seconds: Double) = FixTime((seconds * 1e9).toLong(), 946_684_800_000L + (seconds * 1e3).toLong())

    @Test
    fun `an update from before a trip's own first position is held`() {
        val stale = StaleStart()
        val seek = SeekHold()
        assertFalse(progressHeld(at(1.0), stale, seek), "outside a trip")
        stale.begin()
        // Before the trip's own first position, every update still carries the trip before's.
        assertTrue(progressHeld(at(1.0), stale, seek))
        assertTrue(progressHeld(null, stale, seek))
        stale.onSourceFix(at(10.0))
        assertTrue(progressHeld(at(5.0), stale, seek), "measured before the trip's first position")
        assertFalse(progressHeld(at(10.0), stale, seek))
        assertFalse(progressHeld(at(12.0), stale, seek))
        stale.end()
        assertFalse(progressHeld(at(5.0), stale, seek), "after the trip")
    }

    @Test
    fun `an update from before a seek's new place is held`() {
        val stale = StaleStart()
        val seek = SeekHold()
        stale.begin()
        stale.onSourceFix(at(10.0))
        assertFalse(progressHeld(at(20.0), stale, seek))
        // Asked for: every update is held, whatever its position.
        seek.ask()
        assertTrue(progressHeld(at(20.0), stale, seek))
        // Carried out: held until the new place's first position, then only one from before it.
        seek.resume()
        assertTrue(progressHeld(at(21.0), stale, seek))
        assertTrue(progressHeld(null, stale, seek))
        seek.onSourceFix(at(30.0))
        assertTrue(progressHeld(at(25.0), stale, seek), "measured before the new place's first position")
        assertFalse(progressHeld(at(30.0), stale, seek))
        // Given up: nothing is held for the seek.
        seek.ask()
        seek.cancel()
        assertFalse(progressHeld(at(31.0), stale, seek))
    }
}
