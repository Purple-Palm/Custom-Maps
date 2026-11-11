package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Made-up keys and round synthetic distances, in metres ahead of the car. */
class RunUpLatchTest {
    private val tuning = CameraTuning.PHONE

    /** An exit that counts: six lanes, two of them usable. */
    private fun exit(ahead: Double, route: String = "r1", lanes: Int = 6, usable: Int = 2, freeway: Boolean = true) =
        LaneChoice("$route/0/3/end", ahead, lanes, usable, designatedLanes = 0, onFreeway = freeway, atManeuver = true)

    /** A point on the way: by default five lanes, three of them usable, which counts. */
    private fun point(ahead: Double, key: String, lanes: Int = 5, usable: Int = 3, edgeLeaves: Boolean = false) =
        LaneChoice("r1/0/3/$key", ahead, lanes, usable, designatedLanes = 0, onFreeway = true, atManeuver = false, edgeLeaves = edgeLeaves)

    /** A point on the way where one lane, the one at the exit's side, leaves: no lane choice of its own. */
    private fun edge(ahead: Double, key: String) = point(ahead, key, lanes = 5, usable = 4, edgeLeaves = true)

    @Test
    fun `the reach is the window and a minute of driving, at least 1,500 m`() {
        assertEquals(1_900.0, RunUpLatch.reachMeters(tuning, 15.0), 1e-9)
        assertEquals(2_125.0, RunUpLatch.reachMeters(tuning, 25.0), 1e-9)
        assertEquals(2_465.0, RunUpLatch.reachMeters(tuning, 29.0), 1e-9)
        assertEquals(2_805.0, RunUpLatch.reachMeters(tuning, 33.0), 1e-9)
        for (unknown in listOf(null, 0.0, -2.0, Double.NaN)) assertEquals(1_900.0, RunUpLatch.reachMeters(tuning, unknown), 1e-9)
    }

    @Test
    fun `a counting choice in its window begins the run-up only with the exit within reach`() {
        // 25 m/s: the window is 625 m and the reach 2,125 m.
        for ((exitAt, begins) in listOf(2_125.0 to true, 2_125.5 to false)) {
            val latch = RunUpLatch(tuning)
            val choices = listOf(point(600.0, "a"), exit(exitAt))
            assertEquals(if (begins) choices[1] else null, latch.onProgress(0.0, choices, 25.0), "exit at $exitAt m")
            assertEquals(if (begins) "r1/0/3/end" else null, latch.exitKey)
            assertEquals(if (begins) "r1/0/3/a" else null, latch.startKey)
        }
        // 15 m/s: the window is 400 m and the reach 1,900 m.
        for ((exitAt, begins) in listOf(1_900.0 to true, 1_900.5 to false)) {
            val latch = RunUpLatch(tuning)
            latch.onProgress(0.0, listOf(point(390.0, "a"), exit(exitAt)), 15.0)
            assertEquals(if (begins) "r1/0/3/end" else null, latch.exitKey, "exit at $exitAt m")
        }
        // A counting choice beyond its window begins nothing.
        val early = RunUpLatch(tuning)
        assertNull(early.onProgress(0.0, listOf(point(626.0, "a"), exit(2_000.0)), 25.0))
    }

    @Test
    fun `the exit's own window begins the run-up at the latest`() {
        val latch = RunUpLatch(tuning)
        assertNull(latch.onProgress(0.0, listOf(exit(626.0)), 25.0))
        latch.onProgress(1.0, listOf(exit(601.0)), 25.0)
        assertEquals("r1/0/3/end", latch.exitKey)
        assertEquals("r1/0/3/end", latch.startKey)
    }

    @Test
    fun `an edge point ahead begins the run-up at the reach`() {
        val latch = RunUpLatch(tuning)
        val choices = listOf(edge(1_500.0, "e"), exit(2_100.0))
        assertSame(choices[1], latch.onProgress(0.0, choices, 25.0))
        assertEquals(RunUpLatch.EDGE, latch.startKey)
        // Also when it is no lane choice of its own, and also when it is in its window.
        val near = RunUpLatch(tuning)
        near.onProgress(0.0, listOf(edge(300.0, "e"), exit(2_100.0)), 25.0)
        assertEquals(RunUpLatch.EDGE, near.startKey)
        // An edge point behind the car begins nothing.
        val behind = RunUpLatch(tuning)
        assertNull(behind.onProgress(0.0, listOf(edge(-5.0, "e"), exit(2_100.0)), 25.0))
        assertNull(behind.exitKey)
    }

    @Test
    fun `no run-up for an exit that does not count, off a freeway or beyond reach`() {
        val cases = mapOf(
            "two lanes" to exit(2_000.0, lanes = 2),
            "no usable lane" to exit(2_000.0, usable = 0),
            "every lane usable" to exit(2_000.0, usable = 6),
            "a town road" to exit(2_000.0, freeway = false),
            "beyond reach" to exit(2_200.0),
        )
        for ((case, exit) in cases) {
            val latch = RunUpLatch(tuning)
            assertNull(latch.onProgress(0.0, listOf(edge(1_500.0, "e"), point(500.0, "a"), exit), 25.0), case)
            assertNull(latch.exitKey, case)
        }
        // A straight-on choice is never the exit.
        assertNull(RunUpLatch(tuning).onProgress(0.0, listOf(point(500.0, "a")), 25.0))
    }

    @Test
    fun `once begun it holds whatever the speed does`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(edge(1_500.0, "e"), exit(2_100.0)), 25.0)
        // Slowed to a stop and with no speed at all, the exit is beyond the reach of that speed.
        for ((t, speed) in listOf(1.0 to 10.0, 2.0 to 0.0, 3.0 to null)) {
            val held = exit(2_050.0)
            assertSame(held, latch.onProgress(t, listOf(held), speed), "at $speed m/s")
            assertEquals("r1/0/3/end", latch.exitKey)
            assertEquals(RunUpLatch.EDGE, latch.startKey, "the start stays what it was")
        }
    }

    @Test
    fun `it ends past the gore, off the route and on a new route beyond reach`() {
        fun begun() = RunUpLatch(tuning).also { it.onProgress(0.0, listOf(point(600.0, "a"), exit(2_000.0)), 25.0) }
        // Past the gore the next step's lanes come, without the exit.
        val gore = begun()
        assertNull(gore.onProgress(80.0, listOf(point(900.0, "next")), 25.0))
        assertNull(gore.exitKey)
        assertNull(gore.startKey)
        // An exit behind the car is passed.
        val behind = begun()
        assertNull(behind.onProgress(80.0, listOf(exit(-3.0)), 25.0))
        assertNull(behind.exitKey)
        // Off the route.
        val off = begun()
        assertNull(off.onProgress(10.0, emptyList(), 25.0, onRoute = false))
        assertNull(off.exitKey)
        // A new route whose exit is beyond reach: the old exit's run-up is over.
        val reroute = begun()
        assertNull(reroute.onProgress(10.0, listOf(exit(3_000.0, route = "r2")), 25.0))
        assertNull(reroute.exitKey)
    }

    @Test
    fun `a new route that keeps the exit in reach goes on at once`() {
        for (at in listOf(1_300.0, 1_100.0)) {
            val latch = RunUpLatch(tuning)
            latch.onProgress(0.0, listOf(point(600.0, "a"), exit(2_000.0)), 25.0)
            // A choice 400 m before the gore, beyond its window, and no edge point: on its reach
            // alone, as the run-up just ended.
            val again = exit(at, route = "r2")
            assertSame(again, latch.onProgress(30.0, listOf(point(at - 400.0, "b"), again), 25.0), "at $at m")
            assertEquals("r2/0/3/end", latch.exitKey)
            assertEquals(RunUpLatch.RESUME, latch.startKey)
        }
    }

    @Test
    fun `back on the route after two updates off it goes on`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(exit(1_350.0)), 25.0)
        assertNull(latch.exitKey, "1,350 m with nothing in a window and no edge point: no run-up yet")
        latch.onProgress(1.0, listOf(edge(900.0, "e"), exit(1_325.0)), 25.0)
        assertEquals("r1/0/3/end", latch.exitKey)
        latch.onProgress(2.0, emptyList(), 25.0, onRoute = false)
        latch.onProgress(3.0, emptyList(), 25.0, onRoute = false)
        assertNull(latch.exitKey)
        // Back 2 s after it ended, past the edge point: the exit alone, within reach.
        latch.onProgress(4.0, listOf(exit(1_250.0)), 25.0)
        assertEquals("r1/0/3/end", latch.exitKey)
        assertEquals(RunUpLatch.RESUME, latch.startKey)
    }

    @Test
    fun `it goes on after a seek`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(edge(900.0, "e"), exit(1_400.0)), 25.0)
        latch.reset(10.0, seek = true)
        assertNull(latch.exitKey)
        assertNull(latch.startKey)
        latch.onProgress(11.0, listOf(exit(1_300.0)), 25.0)
        assertEquals("r1/0/3/end", latch.exitKey)
        assertEquals(RunUpLatch.RESUME, latch.startKey)
    }

    @Test
    fun `the gore of a fork goes on into the next exit of reach`() {
        // A fork, then the route's exit 1,500 m after the fork's gore: one approach.
        val latch = RunUpLatch(tuning)
        val fork = LaneChoice("r1/0/2/end", 20.0, laneCount = 4, usableLanes = 2, designatedLanes = 0, onFreeway = true, atManeuver = true)
        latch.onProgress(0.0, listOf(fork), 27.0)
        assertEquals("r1/0/2/end", latch.exitKey)
        val next = LaneChoice("r1/0/3/end", 1_480.0, laneCount = 3, usableLanes = 1, designatedLanes = 0, onFreeway = true, atManeuver = true)
        assertSame(next, latch.onProgress(1.0, listOf(next), 27.0))
        assertEquals(RunUpLatch.RESUME, latch.startKey)
    }

    @Test
    fun `it does not go on after 3 s, after a new drive or beyond reach`() {
        for ((gap, goesOn) in listOf(2.9 to true, 3.0 to false, 6.0 to false)) {
            val latch = RunUpLatch(tuning)
            latch.onProgress(0.0, listOf(edge(900.0, "e"), exit(1_400.0)), 25.0)
            latch.onProgress(1.0, emptyList(), 25.0, onRoute = false)
            latch.onProgress(1.0 + gap, listOf(exit(1_300.0)), 25.0)
            assertEquals(if (goesOn) "r1/0/3/end" else null, latch.exitKey, "back after $gap s")
        }
        // A new drive or the end of guidance forgets it.
        val forgotten = RunUpLatch(tuning)
        forgotten.onProgress(0.0, listOf(edge(900.0, "e"), exit(1_400.0)), 25.0)
        forgotten.reset(1.0)
        assertNull(forgotten.exitKey)
        forgotten.onProgress(1.5, listOf(exit(1_300.0)), 25.0)
        assertNull(forgotten.exitKey)
        // An exit beyond reach does not go on either.
        val far = RunUpLatch(tuning)
        far.onProgress(0.0, listOf(edge(900.0, "e"), exit(1_400.0)), 25.0)
        far.onProgress(1.0, listOf(exit(2_200.0, route = "r2")), 25.0)
        assertNull(far.exitKey)
    }

    @Test
    fun `a run-up that never was does not go on`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, emptyList(), 25.0, onRoute = false)
        latch.reset(1.0, seek = true)
        assertNull(latch.onProgress(1.5, listOf(exit(1_300.0)), 25.0))
    }
}
