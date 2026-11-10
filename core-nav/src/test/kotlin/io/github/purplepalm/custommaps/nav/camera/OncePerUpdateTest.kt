package io.github.purplepalm.custommaps.nav.camera

import java.util.Random
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OncePerUpdateTest {

    @Test
    fun `the location leaves the frame to the progress of the same update`() {
        val pacing = OncePerUpdate()
        pacing.onProgress(10_005)
        assertFalse(pacing.locationComputes(11_000, isTeleport = false))
        pacing.onProgress(11_004)
        assertFalse(pacing.locationComputes(12_000, isTeleport = false))
    }

    @Test
    fun `before the first progress the location computes the frame`() {
        val pacing = OncePerUpdate()
        assertTrue(pacing.locationComputes(0, isTeleport = false))
        assertTrue(pacing.locationComputes(1_000, isTeleport = false))
    }

    @Test
    fun `the location takes over while no progress comes and hands back when it does`() {
        val pacing = OncePerUpdate()
        pacing.onProgress(10_005)
        // The navigator replaces its routes: locations at 11, 12 and 13 s come without a progress.
        assertFalse(pacing.locationComputes(11_000, isTeleport = false))
        assertTrue(pacing.locationComputes(12_000, isTeleport = false))
        assertTrue(pacing.locationComputes(13_000, isTeleport = false))
        pacing.onProgress(14_005)
        assertFalse(pacing.locationComputes(15_000, isTeleport = false))
    }

    @Test
    fun `a jump of the position computes the frame at once`() {
        val pacing = OncePerUpdate()
        pacing.onProgress(10_005)
        assertTrue(pacing.locationComputes(11_000, isTeleport = true))
    }

    @Test
    fun `updates that come a little early or late never compute twice`() {
        val pacing = OncePerUpdate()
        val random = Random(1)
        var now = 0L
        pacing.onProgress(now)
        repeat(1_000) {
            // Made-up intervals from 0.977 to 1.07 s; the progress follows its location within 20 ms.
            now += 977 + random.nextInt(94)
            assertFalse(pacing.locationComputes(now, isTeleport = false), "at $now ms")
            pacing.onProgress(now + random.nextInt(20))
        }
    }

    @Test
    fun `the wait can be set`() {
        val pacing = OncePerUpdate(waitMillis = 3_000)
        pacing.onProgress(0)
        assertFalse(pacing.locationComputes(3_000, isTeleport = false))
        assertTrue(pacing.locationComputes(3_001, isTeleport = false))
    }
}
