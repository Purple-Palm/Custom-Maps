package io.github.purplepalm.custommaps.nav.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Invented clocks: the phone's elapsed realtime from boot, and wall clock times from 2000-01-01 UTC. */
class StaleStartTest {

    /** 2000-01-01 00:00:00 UTC. */
    private val wallAtBoot = 946_684_800_000L

    /** A position measured [seconds] after boot, on both clocks. */
    private fun at(seconds: Double) = FixTime(elapsedNanos = (seconds * 1e9).toLong(), wallMillis = wallAtBoot + (seconds * 1e3).toLong())

    /** A position with the wall clock only, as from a source without elapsed realtime. */
    private fun wallOnly(seconds: Double) = FixTime(elapsedNanos = null, wallMillis = wallAtBoot + (seconds * 1e3).toLong())

    @Test
    fun `outside a trip nothing is stale`() {
        val rule = StaleStart()
        assertFalse(rule.isStale(at(10.0)))
        assertFalse(rule.isStale(null))
        rule.begin()
        rule.end()
        assertFalse(rule.isStale(at(1.0)))
    }

    @Test
    fun `before the trip's first position every update carries the old one`() {
        val rule = StaleStart()
        rule.begin()
        assertTrue(rule.isStale(at(500.0)))
        assertTrue(rule.isStale(at(10_000.0)))
        assertTrue(rule.isStale(null))
    }

    @Test
    fun `two old updates and then one of the trip, the case of the drive logs`() {
        // The trip that ended left the car at 1,000 s; this trip starts at 1,300 s, 2 km from there.
        // The rule never looks at where a position is, only at when it was measured.
        val rule = StaleStart()
        rule.begin()
        val left = at(1_000.0)
        val decisions = mutableListOf<Boolean>()
        repeat(2) {
            val stale = rule.isStale(left)
            if (stale) rule.countStaleUpdate()
            decisions += stale
        }
        rule.onSourceFix(at(1_300.4))
        decisions += rule.isStale(at(1_300.4)).also { if (it) rule.countStaleUpdate() }
        // The old position once more, after the trip's own: still the old one.
        decisions += rule.isStale(left)

        assertEquals(listOf(true, true, false, true), decisions)
        assertEquals(2, rule.staleUpdates)
    }

    @Test
    fun `a position measured with or after the first one is the trip's`() {
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(at(100.0))
        assertFalse(rule.isStale(at(100.0)))
        assertFalse(rule.isStale(at(101.0)))
        assertFalse(rule.isStale(at(5_000.0)))
    }

    @Test
    fun `up to a second before the first one still counts as the trip's`() {
        assertEquals(1_000L, StaleStart.SLACK_MILLIS)
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(at(100.0))
        assertFalse(rule.isStale(at(99.5)))
        assertFalse(rule.isStale(at(99.0)))
        assertTrue(rule.isStale(at(98.999)))
        assertTrue(rule.isStale(at(40.0)))
    }

    @Test
    fun `a first position older than the trip, as the last one the phone knew, is the trip's`() {
        // The trip starts at 600 s; the phone's newest position then is from 480 s, and nothing
        // newer comes for a while, as in a garage. The old trip's position is from 300 s.
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(at(480.0))
        assertFalse(rule.isStale(at(480.0)))
        assertTrue(rule.isStale(at(300.0)))
    }

    @Test
    fun `a simulated drive is compared on the replayer's clock`() {
        // The replayer stamps its positions about 100 s ahead of the phone's clock. The trip starts
        // at 2,000 s; the last real trip ended at 1,900 s.
        val rule = StaleStart()
        rule.begin()
        assertTrue(rule.isStale(at(1_900.0)))
        rule.onSourceFix(at(2_100.0))
        assertFalse(rule.isStale(at(2_100.0)))
        assertFalse(rule.isStale(at(2_101.0)))
        assertTrue(rule.isStale(at(1_900.0)))
    }

    @Test
    fun `without elapsed realtime the wall clock decides`() {
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(wallOnly(100.0))
        assertFalse(rule.isStale(wallOnly(100.0)))
        assertTrue(rule.isStale(wallOnly(50.0)))
        // One side without elapsed realtime is enough: the wall clocks are compared.
        assertTrue(rule.isStale(FixTime(elapsedNanos = 200_000_000_000L, wallMillis = wallAtBoot + 50_000L)))
        // Zero is no time.
        assertTrue(rule.isStale(FixTime(elapsedNanos = 0L, wallMillis = wallAtBoot + 50_000L)))
        assertFalse(rule.isStale(FixTime(elapsedNanos = 0L, wallMillis = wallAtBoot + 100_000L)))
    }

    @Test
    fun `only the trip's first position is the mark`() {
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(at(100.0))
        rule.onSourceFix(at(200.0))
        assertFalse(rule.isStale(at(150.0)))
    }

    @Test
    fun `an update without a position is the trip's once its positions have begun`() {
        val rule = StaleStart()
        rule.begin()
        rule.onSourceFix(at(100.0))
        assertFalse(rule.isStale(null))
    }

    @Test
    fun `positions before begin are not the trip's first one`() {
        val rule = StaleStart()
        rule.onSourceFix(at(100.0))
        rule.begin()
        assertTrue(rule.isStale(at(100.0)))
    }

    @Test
    fun `each trip starts over`() {
        val rule = StaleStart()
        rule.begin()
        rule.countStaleUpdate()
        rule.onSourceFix(at(100.0))
        rule.end()
        rule.begin()
        assertEquals(0, rule.staleUpdates)
        // The first position of the trip before is no mark for this one.
        assertTrue(rule.isStale(at(150.0)))
        rule.onSourceFix(at(400.0))
        assertTrue(rule.isStale(at(150.0)))
        assertFalse(rule.isStale(at(400.0)))
    }
}
