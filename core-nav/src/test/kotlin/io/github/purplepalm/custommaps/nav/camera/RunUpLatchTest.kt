package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ------------------------------------------------------------------ the ramp bridge (D20)

    /** IW31's ramp as its lane plan holds through it: the split 260 m past the gore, and the ramp's planning speed. */
    private val iw31Ramp = LaneScenes.iw31().let { route ->
        val plan = LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)))
        requireNotNull(plan.rampAfter(route.nodes.indexOfFirst { it.station == LaneScenes.GORE }))
    }

    /** IW31's ramp's planning speed. */
    private val rampMps = iw31Ramp.planningMps

    /** The ramp step's split ahead: a fork of two lanes, no lane choice of its own. */
    private fun split(ahead: Double, route: String = "r1") =
        LaneChoice("$route/0/4/end", ahead, laneCount = 2, usableLanes = 1, designatedLanes = 0, onFreeway = true, atManeuver = true)

    /** One update of [rampDrive]: how far past the gore the car is, and what the latch held. */
    private class RampUpdate(val t: Int, val pastGore: Double, val exitKey: String?, val startKey: String?, val rampAhead: Double)

    /** Where the exit's gore lies along the route in [rampDrive]. */
    private val gore = 1_000.0

    /**
     * The run-up to the exit `r1/0/3/end`, whose gore lies [gore] metres along the route, driven at 20 m/s from the route's start
     * with one update a second, and on along the ramp past it to its split [splitAfter] metres past the gore. While the run-up
     * to that exit is on, the latch is handed the plan's point [rampMeters] past the gore on a ramp planned at [planningMps], as
     * the engine hands it ([io.github.purplepalm.custommaps.nav.lanes.LanePlan.rampAfter]).
     */
    private fun rampDrive(rampMeters: Double, planningMps: Double = rampMps, splitAfter: Double = rampMeters): List<RampUpdate> {
        val latch = RunUpLatch(tuning)
        val out = ArrayList<RampUpdate>()
        for (t in 0..75) {
            val car = 20.0 * t
            val choices = if (car <= gore) listOf(exit(gore - car)) else listOfNotNull(split(gore + splitAfter - car).takeIf { it.isAhead })
            val handed = latch.exitKey == "r1/0/3/end"
            latch.onProgress(
                t.toDouble(),
                choices,
                20.0,
                routeMeters = car,
                rampMeters = if (handed) rampMeters else Double.NaN,
                rampMps = if (handed) planningMps else Double.NaN,
            )
            out.add(RampUpdate(t, car - gore, latch.exitKey, latch.startKey, latch.rampAhead))
        }
        return out
    }

    @Test
    fun `past the gore it holds through the ramp to the split the plan constrains within 15 s`() {
        assertEquals(15.0, tuning.rampBridgeSeconds)
        assertEquals(LanePlanner.RAMP_BRIDGE_SECONDS, tuning.rampBridgeSeconds, "the plan's ramp windows and the camera's hold agree")
        // IW31: the ramp splits 260 m past the gore, 12.9 s at its planning speed.
        assertEquals(260.0, iw31Ramp.meters, 1e-9)
        assertEquals(12.9, 260.0 / rampMps, 0.05)
        val drive = rampDrive(rampMeters = 260.0)
        // From the exit's own window, 500 m before the gore at 20 m/s, to the gore, as before.
        val runUp = drive.filter { it.pastGore in -500.0..0.0 }
        assertEquals(26, runUp.size)
        assertTrue(runUp.all { it.exitKey == "r1/0/3/end" && it.startKey == "r1/0/3/end" && it.rampAhead.isNaN() })
        // On through the ramp, the exit and its start kept, with how far the split still is.
        val ramp = drive.filter { it.pastGore > 0.0 && it.pastGore < 260.0 }
        assertEquals(12, ramp.size)
        for (u in ramp) {
            assertEquals("r1/0/3/end", u.exitKey, "${u.pastGore} m past the gore")
            assertEquals("r1/0/3/end", u.startKey)
            assertEquals(260.0 - u.pastGore, u.rampAhead, 1e-9)
        }
        // At the split it lets go, as at a gore without a ramp to hold through.
        assertTrue(drive.filter { it.pastGore >= 260.0 }.all { it.exitKey == null && it.startKey == null && it.rampAhead.isNaN() })
    }

    @Test
    fun `a split farther than 15 s at the ramp's planning speed, or none the plan constrains, is not held to`() {
        fun ramp(rampMeters: Double, planningMps: Double = rampMps) =
            rampDrive(rampMeters, planningMps, splitAfter = 300.0).filter { it.pastGore > 0.0 && it.exitKey != null }.size
        // 15 s at the ramp's planning speed: held to its very end; a centimetre farther, not at all.
        val border = tuning.rampBridgeSeconds * rampMps
        assertEquals(15, ramp(border), "held over the ramp's 15 updates")
        assertEquals(0, ramp(border + 0.01))
        // A split a second beyond the limit, as the plan would never hand one: let go at the gore.
        assertEquals(0, ramp((tuning.rampBridgeSeconds + 1.0) * rampMps))
        // No point the plan constrains (a split without lane data), and a ramp with no planning speed.
        assertEquals(0, ramp(Double.NaN))
        assertEquals(0, ramp(260.0, planningMps = Double.NaN))
        assertEquals(0, ramp(260.0, planningMps = 0.0))
        // Nor without the car's metres along the route, as a caller that does not give them.
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(exit(300.0)), 20.0)
        latch.onProgress(15.0, listOf(split(240.0)), 20.0, rampMeters = 260.0, rampMps = rampMps)
        assertNull(latch.exitKey)
        assertTrue(latch.rampAhead.isNaN())
    }

    @Test
    fun `on the ramp it holds whatever the speed does, and its numbers alone hold nothing`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(exit(300.0)), 20.0, routeMeters = 700.0)
        latch.onProgress(15.0, listOf(split(240.0)), 20.0, routeMeters = 1_020.0, rampMeters = 260.0, rampMps = rampMps)
        // Slowed to a stop, and with no speed at all: still held, still 120 m short of the split.
        for ((t, speed) in listOf(22.0 to 0.0, 40.0 to 0.0, 41.0 to null)) {
            assertNull(latch.onProgress(t, listOf(split(120.0)), speed, routeMeters = 1_140.0, rampMeters = 260.0, rampMps = rampMps))
            assertEquals("r1/0/3/end", latch.exitKey, "at $speed m/s")
            assertEquals(120.0, latch.rampAhead, 1e-9)
        }
        // Outside a run-up the plan's numbers begin nothing.
        val idle = RunUpLatch(tuning)
        assertNull(idle.onProgress(0.0, listOf(split(200.0)), 20.0, routeMeters = 1_060.0, rampMeters = 260.0, rampMps = rampMps))
        assertNull(idle.exitKey)
        assertTrue(idle.rampAhead.isNaN())
    }

    @Test
    fun `a reroute on the ramp ends it, and a new route's exit in reach goes on as after any end`() {
        /** On the ramp 80 m past the gore, held to its split 260 m past it. */
        fun onRamp(): RunUpLatch = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(300.0)), 20.0, routeMeters = 700.0)
            it.onProgress(19.0, listOf(split(180.0)), 20.0, routeMeters = 1_080.0, rampMeters = 260.0, rampMps = rampMps)
            assertEquals(180.0, it.rampAhead, 1e-9)
        }
        // The navigator puts the car on a new route, whose metres start anew: the ramp is not held on.
        val rerouted = onRamp()
        assertNull(rerouted.onProgress(20.0, listOf(split(160.0, route = "r2")), 20.0, routeMeters = 0.0))
        assertNull(rerouted.exitKey)
        assertTrue(rerouted.rampAhead.isNaN())
        // Nor later on the new route, whose numbers the engine no longer hands.
        assertNull(rerouted.onProgress(21.0, listOf(split(140.0, route = "r2")), 20.0, routeMeters = 20.0))
        assertTrue(rerouted.rampAhead.isNaN())
        // A new route whose own exit lies within reach: its run-up goes on at once, as after a reroute before a gore.
        val again = onRamp()
        assertEquals("r2/0/3/end", again.onProgress(20.0, listOf(exit(1_200.0, route = "r2")), 20.0, routeMeters = 0.0)?.key)
        assertEquals(RunUpLatch.RESUME, again.startKey)
        assertTrue(again.rampAhead.isNaN())
        // Later than 3 s after the end, on its reach alone, it would not.
        val late = onRamp()
        assertNull(late.onProgress(20.0, listOf(split(160.0, route = "r2")), 20.0, routeMeters = 0.0))
        assertNull(late.onProgress(23.0, listOf(exit(1_200.0, route = "r2")), 20.0, routeMeters = 60.0))
        // Off the route on the ramp: ended as well, and back on it the ramp is not held again.
        val off = onRamp()
        assertNull(off.onProgress(20.0, emptyList(), 20.0, onRoute = false, routeMeters = 1_080.0))
        assertNull(off.onProgress(21.0, listOf(split(160.0)), 20.0, routeMeters = 1_100.0))
        assertNull(off.exitKey)
        assertTrue(off.rampAhead.isNaN())
    }

    @Test
    fun `a seek on the ramp ends it, and one back onto the approach within 3 s goes on through the ramp again`() {
        val latch = RunUpLatch(tuning)
        latch.onProgress(0.0, listOf(exit(300.0)), 20.0, routeMeters = 700.0)
        latch.onProgress(15.0, listOf(split(240.0)), 20.0, routeMeters = 1_020.0, rampMeters = 260.0, rampMps = rampMps)
        latch.reset(15.4, seek = true)
        assertNull(latch.exitKey)
        assertTrue(latch.rampAhead.isNaN())
        // Landed on the ramp: no exit ahead, nothing to go on with.
        assertNull(latch.onProgress(15.8, listOf(split(120.0)), 20.0, routeMeters = 1_140.0))
        assertTrue(latch.rampAhead.isNaN())
        // A seek back 600 m before the gore within 3 s: the run-up goes on on its reach alone, and through the ramp at its gore.
        latch.reset(17.0, seek = true)
        assertEquals("r1/0/3/end", latch.onProgress(17.5, listOf(exit(600.0)), 20.0, routeMeters = 400.0)?.key)
        assertEquals(RunUpLatch.RESUME, latch.startKey)
        latch.onProgress(47.5, listOf(exit(0.0)), 20.0, routeMeters = 1_000.0)
        latch.onProgress(48.5, listOf(split(240.0)), 20.0, routeMeters = 1_020.0, rampMeters = 260.0, rampMps = rampMps)
        assertEquals("r1/0/3/end", latch.exitKey)
        assertEquals(240.0, latch.rampAhead, 1e-9)
        // A seek back onto the approach later than 3 s after the run-up ended: nothing before the exit's own window.
        latch.reset(49.0, seek = true)
        assertNull(latch.onProgress(53.0, listOf(exit(600.0)), 20.0, routeMeters = 400.0))
    }

    @Test
    fun `an exit or fork that counts ahead on the ramp within reach takes over, one beyond reach does not`() {
        fun onRamp(): RunUpLatch = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(300.0)), 20.0, routeMeters = 700.0)
            it.onProgress(15.0, listOf(split(240.0)), 20.0, routeMeters = 1_020.0, rampMeters = 260.0, rampMps = rampMps)
        }
        // A fork of four lanes, two of them the route's, 200 m ahead: its own run-up, begun again at once.
        val fork = LaneChoice("r1/0/4/end", 200.0, laneCount = 4, usableLanes = 2, designatedLanes = 0, onFreeway = true, atManeuver = true)
        val near = onRamp()
        assertSame(fork, near.onProgress(15.4, listOf(fork), 20.0, routeMeters = 1_040.0, rampMeters = 260.0, rampMps = rampMps))
        assertEquals("r1/0/4/end", near.exitKey)
        assertEquals(RunUpLatch.RESUME, near.startKey)
        assertTrue(near.rampAhead.isNaN())
        // Beyond the reach of 2,000 m at 20 m/s, it begins nothing yet: the ramp is held on.
        val far = onRamp()
        assertNull(far.onProgress(15.4, listOf(fork.copy(metersAhead = 2_000.5)), 20.0, routeMeters = 1_040.0, rampMeters = 260.0, rampMps = rampMps))
        assertEquals("r1/0/3/end", far.exitKey)
        assertEquals(220.0, far.rampAhead, 1e-9)
    }

    // ------------------------------------------------------------------ the lane plan's seam (D15)

    @Test
    fun `the plan's seam begins the run-up where it lies, also beyond the reach`() {
        // 25 m/s: the reach is 2,125 m; the plan begins this run-up 2,600 m before the gore.
        for ((exitAt, begins) in listOf(2_600.0 to true, 2_600.5 to false, 2_400.0 to true)) {
            val latch = RunUpLatch(tuning)
            val choices = listOf(exit(exitAt))
            assertEquals(if (begins) choices[0] else null, latch.onProgress(0.0, choices, 25.0, seamMeters = 2_600.0), "exit at $exitAt m")
            assertEquals(if (begins) "r1/0/3/end" else null, latch.exitKey)
            assertEquals(if (begins) RunUpLatch.SEAM else null, latch.startKey)
        }
        // Within the reach, before the latch would begin on its own: the seam begins it too.
        val latch = RunUpLatch(tuning)
        assertNull(latch.onProgress(0.0, listOf(exit(1_900.0)), 25.0), "the latch alone waits for the exit's window")
        assertEquals("r1/0/3/end", RunUpLatch(tuning).also { it.onProgress(0.0, listOf(exit(1_900.0)), 25.0, seamMeters = 1_950.0) }.exitKey)
        // Once begun it holds as any run-up does, without the seam's number.
        latch.onProgress(1.0, listOf(exit(1_880.0)), 25.0, seamMeters = 1_950.0)
        assertEquals(RunUpLatch.SEAM, latch.startKey)
        assertEquals("r1/0/3/end", latch.exitKey)
        for ((t, speed) in listOf(2.0 to 10.0, 3.0 to null)) {
            latch.onProgress(t, listOf(exit(1_850.0)), speed)
            assertEquals("r1/0/3/end", latch.exitKey, "at $speed m/s")
        }
    }

    @Test
    fun `the seam is the start only where nothing else begins the run-up, and a later seam changes nothing`() {
        // An edge point ahead begins it at the reach, as before, whatever seam the plan has.
        val edge = RunUpLatch(tuning)
        edge.onProgress(0.0, listOf(edge(1_500.0, "e"), exit(2_100.0)), 25.0, seamMeters = 2_400.0)
        assertEquals(RunUpLatch.EDGE, edge.startKey)
        // A counting choice in its window, likewise.
        val window = RunUpLatch(tuning)
        window.onProgress(0.0, listOf(point(600.0, "a"), exit(2_000.0)), 25.0, seamMeters = 2_200.0)
        assertEquals("r1/0/3/a", window.startKey)
        // Begun again within 3 s of an end.
        val resumed = RunUpLatch(tuning)
        resumed.onProgress(0.0, listOf(point(600.0, "a"), exit(2_000.0)), 25.0)
        resumed.onProgress(1.0, emptyList(), 25.0, onRoute = false)
        resumed.onProgress(2.0, listOf(exit(1_960.0)), 25.0, seamMeters = 2_200.0)
        assertEquals(RunUpLatch.RESUME, resumed.startKey)
        // A seam nearer the gore than the latch's own begin: the latch begins where it would on its own.
        val later = RunUpLatch(tuning)
        later.onProgress(0.0, listOf(edge(1_500.0, "e"), exit(2_100.0)), 25.0, seamMeters = 900.0)
        assertEquals(RunUpLatch.EDGE, later.startKey)
        // No seam, as for a route without a plan: the interim's latch.
        assertNull(RunUpLatch(tuning).onProgress(0.0, listOf(exit(1_900.0)), 25.0, seamMeters = Double.NaN))
    }

    @Test
    fun `a new route goes on with a run-up the seam began beyond the reach, before its own plan gives a seam`() {
        // 25 m/s: the reach is 2,125 m; the plan of the first route begins the run-up 2,600 m before the gore.
        fun begun() = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(2_600.0)), 25.0, routeMeters = 1_000.0, seamMeters = 2_600.0)
            it.onProgress(1.0, listOf(exit(2_575.0)), 25.0, routeMeters = 1_025.0)
            assertEquals(RunUpLatch.SEAM, it.startKey)
        }
        // A new route at once: its exit lies within the seam the run-up began with, and no seam of its own yet.
        val rerouted = begun()
        assertEquals("r2/0/3/end", rerouted.onProgress(2.0, listOf(exit(2_550.0, route = "r2")), 25.0, routeMeters = 0.0)?.key)
        assertEquals(RunUpLatch.RESUME, rerouted.startKey)
        // And again on a second new route, still from that seam.
        assertEquals("r2/0/3/end", rerouted.onProgress(3.0, listOf(exit(2_525.0, route = "r2")), 25.0, routeMeters = 25.0)?.key)
        assertEquals("r3/0/3/end", rerouted.onProgress(4.0, listOf(exit(2_500.0, route = "r3")), 25.0, routeMeters = 0.0)?.key)
        assertEquals(RunUpLatch.RESUME, rerouted.startKey)
        // After a moment off the route, within the resume time.
        val off = begun()
        assertNull(off.onProgress(2.0, emptyList(), 25.0, onRoute = false, routeMeters = 1_025.0))
        assertEquals("r2/0/3/end", off.onProgress(4.0, listOf(exit(2_500.0, route = "r2")), 25.0, routeMeters = 0.0)?.key)
        // Not beyond that seam, nor after the resume time.
        assertNull(begun().onProgress(2.0, listOf(exit(2_601.0, route = "r2")), 25.0, routeMeters = 0.0))
        val late = begun()
        late.onProgress(2.0, emptyList(), 25.0, onRoute = false, routeMeters = 1_025.0)
        assertNull(late.onProgress(5.0, listOf(exit(2_500.0, route = "r2")), 25.0, routeMeters = 0.0))
        // A run-up begun without a seam carries none: beyond the reach a new route waits as before.
        val plain = RunUpLatch(tuning)
        plain.onProgress(0.0, listOf(point(600.0, "a"), exit(2_100.0)), 25.0, routeMeters = 1_000.0)
        assertNull(plain.onProgress(1.0, listOf(exit(2_200.0, route = "r2")), 25.0, routeMeters = 0.0))
    }

    @Test
    fun `the seam of a run-up past its gore or on the ramp after it, or before a seek or a new drive, begins nothing for the next exit`() {
        fun begun() = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(2_600.0)), 25.0, routeMeters = 1_000.0, seamMeters = 2_600.0)
            it.onProgress(104.0, listOf(exit(0.0)), 25.0, routeMeters = 3_600.0)
        }
        // The next step's exit, beyond the reach, right after the gore: the plan's own seam for it decides.
        val gore = begun()
        assertNull(gore.onProgress(105.0, emptyList(), 25.0, routeMeters = 3_625.0))
        assertNull(gore.onProgress(106.0, listOf(exit(2_400.0, route = "r1")), 25.0, routeMeters = 3_650.0))
        assertNull(gore.onProgress(107.0, listOf(exit(2_375.0, route = "r1")), 25.0, routeMeters = 3_675.0))
        // A seek, and a new drive, forget the route's metres: the next update is no new route.
        for (seek in listOf(true, false)) {
            val reset = begun()
            reset.reset(104.5, seek = seek)
            assertNull(reset.onProgress(105.0, listOf(exit(2_400.0, route = "r2")), 25.0, routeMeters = 0.0), "seek $seek")
        }
        // At 24 m/s the reach is 2,100 m; the plan began the run-up 2,500 m before the gore. A new route right after the gore,
        // whose exit lies beyond the reach but within that seam: nothing, the new route's own seam decides.
        fun seamBegun(): RunUpLatch = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(2_500.0)), 24.0, routeMeters = 1_000.0, seamMeters = 2_500.0)
            assertEquals(RunUpLatch.SEAM, it.startKey)
        }
        val past = seamBegun()
        past.onProgress(90.0, listOf(exit(0.0)), 24.0, routeMeters = 3_500.0)
        assertNull(past.onProgress(91.0, emptyList(), 24.0, routeMeters = 3_524.0))
        assertNull(past.onProgress(92.0, listOf(exit(2_300.0, route = "r2")), 24.0, routeMeters = 0.0))
        assertNull(past.exitKey)
        // Nor after a seek, also where a new route comes within the resume time.
        val sought = seamBegun()
        sought.reset(1.0, seek = true)
        assertNull(sought.onProgress(1.5, listOf(exit(2_450.0)), 24.0, routeMeters = 1_050.0))
        assertNull(sought.onProgress(2.5, listOf(exit(2_300.0, route = "r2")), 24.0, routeMeters = 0.0))
        assertNull(sought.exitKey)
        // On the ramp the run-up held through after its gore, at 20 m/s (a reach of 2,000 m) with a seam of 2,400 m: off the
        // route there and then a new route, or a new route on the ramp itself, whose exit lies within that seam: nothing.
        fun onRamp(): RunUpLatch = RunUpLatch(tuning).also {
            it.onProgress(0.0, listOf(exit(2_400.0)), 20.0, routeMeters = 1_000.0, seamMeters = 2_400.0)
            it.onProgress(120.0, listOf(exit(0.0)), 20.0, routeMeters = 3_400.0)
            it.onProgress(121.0, listOf(split(240.0)), 20.0, routeMeters = 3_420.0, rampMeters = 260.0, rampMps = rampMps)
            assertEquals(240.0, it.rampAhead, 1e-9)
        }
        val off = onRamp()
        assertNull(off.onProgress(122.0, emptyList(), 20.0, onRoute = false, routeMeters = 3_440.0))
        assertNull(off.onProgress(124.0, listOf(exit(2_200.0, route = "r2")), 20.0, routeMeters = 0.0))
        assertNull(off.exitKey)
        val rerouted = onRamp()
        assertNull(rerouted.onProgress(122.0, listOf(exit(2_200.0, route = "r2")), 20.0, routeMeters = 0.0))
        assertNull(rerouted.exitKey)
        // Within the reach the new route's exit goes on at once, as after any end.
        assertEquals("r2/0/3/end", onRamp().onProgress(122.0, listOf(exit(1_900.0, route = "r2")), 20.0, routeMeters = 0.0)?.key)
    }

    @Test
    fun `no seam begins a run-up for an exit that does not count, nor one behind the car`() {
        for (exit in listOf(exit(1_000.0, lanes = 2), exit(1_000.0, usable = 0), exit(1_000.0, freeway = false), exit(-5.0))) {
            val latch = RunUpLatch(tuning)
            assertNull(latch.onProgress(0.0, listOf(exit), 25.0, seamMeters = 2_000.0))
            assertNull(latch.exitKey)
        }
    }
}
