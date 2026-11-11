package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.camera.LaneChoice
import io.github.purplepalm.custommaps.nav.guidance.DivergeWatch.Companion.DIVERGE_TAKEN
import io.github.purplepalm.custommaps.nav.guidance.DivergeWatch.Companion.MISSED_MANEUVER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Diverges taken and missed on a made-up freeway step of a route "r": five lanes, points before an
 * exit on the right at the rounded, synthetic distances of the lane design (2,000 m, 1,500 m,
 * 1,470 m, 1,460 m, 1,440 m and 400 m before the gore), at 25 rising to 29 m/s. The route starts
 * 5,000 m before the gore.
 */
class DivergeWatchTest {

    /** A point of the step, [beforeGore] meters before the exit's gore. */
    private class Spot(val key: String, val beforeGore: Double, val usable: Int, val edge: Boolean)

    private val spots = listOf(
        // One lane at the exit's side leaves: an edge point, no lane choice.
        Spot("r/0/2/1", 2_000.0, usable = 4, edge = true),
        // Two lanes at the exit's side leave: a lane choice and an edge point, four points as one.
        Spot("r/0/2/2", 1_500.0, usable = 3, edge = true),
        Spot("r/0/2/3", 1_470.0, usable = 3, edge = true),
        Spot("r/0/2/4", 1_460.0, usable = 3, edge = true),
        Spot("r/0/2/5", 1_440.0, usable = 3, edge = true),
        // Three lanes leave on the far side: a lane choice only.
        Spot("r/0/2/6", 400.0, usable = 2, edge = false),
    )

    private fun choice(key: String, metersAhead: Double, usable: Int, edge: Boolean = false, atManeuver: Boolean = false, lanes: Int = 5) =
        LaneChoice(key, metersAhead, laneCount = lanes, usableLanes = usable, designatedLanes = 0, onFreeway = true, atManeuver = atManeuver, edgeLeaves = edge)

    /** The points ahead of a car [toGore] meters before the gore, nearest first, and the exit. */
    private fun ahead(toGore: Double): List<LaneChoice> =
        spots.filter { toGore - it.beforeGore >= 0.0 }.map { choice(it.key, toGore - it.beforeGore, it.usable, it.edge) } +
            choice("r/0/2/end", toGore, usable = 1, atManeuver = true, lanes = 6)

    /** An update on the step before the exit. */
    private fun before(
        t: Double,
        toGore: Double,
        speed: Double = 25.0,
        probability: Double? = 0.9,
        side: Double? = -0.5,
        card: String? = "run_up",
        words: String? = "R1",
        runUp: String? = "r/0/2/end",
        branches: List<DivergeWatch.Branch> = emptyList(),
        uncertain: Boolean = false,
    ) = DivergeWatch.Update(
        nowSeconds = t,
        routeId = "r",
        stepKey = "r/0/2",
        stepType = "continue",
        stepModifier = "straight",
        endsOnFreeway = true,
        metersDone = 5_000.0 - toGore,
        offRoute = false,
        uncertain = uncertain,
        points = ahead(toGore),
        branches = branches,
        probability = probability,
        sideM = side,
        speedMps = speed,
        teleport = false,
        card = card,
        words = words,
        runUp = runUp,
    )

    /** An update on the exit's ramp, the next step, [pastGore] meters past the gore. */
    private fun onRamp(
        t: Double,
        pastGore: Double,
        speed: Double = 29.0,
        probability: Double? = 0.9,
        side: Double? = -4.0,
        offRoute: Boolean = false,
        uncertain: Boolean = false,
        routeId: String = "r",
        stepType: String = "off ramp",
    ) = DivergeWatch.Update(
        nowSeconds = t,
        routeId = routeId,
        stepKey = "$routeId/0/3",
        stepType = stepType,
        stepModifier = "slight right",
        endsOnFreeway = false,
        metersDone = 5_000.0 + pastGore,
        offRoute = offRoute,
        uncertain = uncertain,
        points = emptyList(),
        branches = emptyList(),
        probability = probability,
        sideM = side,
        speedMps = speed,
        teleport = false,
        card = "maneuver",
        words = null,
        runUp = null,
    )

    /** Feeds [updates] and collects the lines. */
    private fun DivergeWatch.feed(updates: List<DivergeWatch.Update>): List<DivergeWatch.Line> = updates.mapNotNull { onProgress(it) }

    /**
     * The approach from [from] meters before the gore, one update a second, 25 rising to 29 m/s;
     * the last update is the last one before the gore, and its time is in [end].
     */
    private fun approach(from: Double, end: DoubleArray = DoubleArray(1)): List<DivergeWatch.Update> {
        val updates = ArrayList<DivergeWatch.Update>()
        var t = 0.0
        var toGore = from
        while (toGore > 0.0) {
            val speed = 25.0 + 4.0 * (1.0 - toGore / from)
            updates.add(before(t, toGore, speed = speed))
            t += 1.0
            toGore -= speed
        }
        end[0] = t - 1.0
        return updates
    }

    @Test
    fun `the exit missed on the shape of the lane design's drive, one line for the step change and none for the last point`() {
        val watch = DivergeWatch()
        val end = DoubleArray(1)
        val lines = watch.feed(approach(from = 2_100.0, end = end)).toMutableList()
        // The edge point's watch was closed by the next point; the last point of the four was
        // watched for 30 s and the car stayed on the route: taken.
        assertEquals(listOf(DIVERGE_TAKEN), lines.map { it.type })
        assertEquals("edge", lines[0].fields["kind"])
        assertEquals("r/0/2/5", lines[0].fields["key"])
        assertEquals(30.0, lines[0].fields["sinceS"])
        // The last point, 400 m before the gore (about 13 s), is passed: its watch is open at the gore.
        val gore = end[0] + 1.0
        // The step changes at the gore to the exit's ramp; the navigator is less sure, then surer.
        val probabilities = listOf(0.7, 0.75, 0.8, 0.88, 0.9, 0.92, 0.94, 0.95, 0.95, 0.95)
        for (s in 0..9) {
            val line = watch.onProgress(onRamp(gore + s, pastGore = 10.0 + 250.0 * s / 9.0, probability = probabilities[s], side = -4.0 - s))
            if (line != null) lines.add(line)
        }
        assertEquals(1, lines.size, "no line for the point before the gore")
        // 9 s and 250 m on, the navigator finds the car off the route, and a new route comes at once.
        val missed = requireNotNull(watch.onOffRoute(gore + 9.0, probability = 0.95, sideM = -13.0, speedMps = 29.0, teleport = true))
        assertNull(watch.onProgress(onRamp(gore + 9.2, pastGore = 0.0, routeId = "r2", stepType = "continue")))
        assertEquals(MISSED_MANEUVER, missed.type)
        assertEquals(
            mapOf(
                "kind" to "maneuver",
                "maneuverType" to "off ramp",
                "modifier" to "slight right",
                "key" to "r/0/2/end",
                "freeway" to true,
                "lanes" to 6,
                "usable" to 1,
                "card" to "run_up",
                "words" to "R1",
                "runUp" to "r/0/2/end",
                "advanced" to true,
                "sinceS" to 9.0,
                "sinceM" to 250L,
                "probAt" to 0.7,
                "probMin" to 0.7,
                "probMax" to 0.95,
                "probLast" to 0.95,
                "uncertainS" to 0.0,
                "sideAt" to -4.0,
                "sideMin" to -13.0,
                "sideMax" to -4.0,
                "speedAt" to 29.0,
                "speedEnd" to 29.0,
                "teleportAtOff" to true,
            ),
            missed.fields,
        )
        // Nothing is open any more.
        assertNull(watch.onOffRoute(gore + 10.0, 0.5, null, 29.0, false))
    }

    @Test
    fun `a car that leaves the route at an edge point missed it`() {
        val watch = DivergeWatch()
        assertNull(watch.onProgress(before(0.0, toGore = 2_010.0)))
        // Passed the edge point 2,000 m before the gore.
        assertNull(watch.onProgress(before(1.0, toGore = 1_985.0)))
        assertNull(watch.onProgress(before(2.0, toGore = 1_960.0, probability = 0.6, side = 3.5)))
        val missed = requireNotNull(watch.onOffRoute(8.0, probability = 0.4, sideM = 9.0, speedMps = 24.0, teleport = false))
        assertEquals(MISSED_MANEUVER, missed.type)
        assertEquals("edge", missed.fields["kind"])
        assertEquals("r/0/2/1", missed.fields["key"])
        assertEquals(5, missed.fields["lanes"])
        assertEquals(4, missed.fields["usable"])
        assertEquals(false, missed.fields["advanced"])
        assertEquals(7.0, missed.fields["sinceS"])
        assertEquals(25L, missed.fields["sinceM"], "to the last update the watch had")
        assertEquals(0.4, missed.fields["probMin"])
        assertEquals(9.0, missed.fields["sideMax"])
        assertEquals(false, missed.fields["teleportAtOff"])
        assertFalse("maneuverType" in missed.fields, "a point where the route goes straight on is no maneuver")
        assertFalse("modifier" in missed.fields)
    }

    @Test
    fun `an exit taken is written 30 s after the step change`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        val lines = (1..31).mapNotNull { s -> watch.onProgress(onRamp(s.toDouble(), pastGore = 5.0 + 20.0 * (s - 1))) }
        assertEquals(1, lines.size)
        val taken = lines.single()
        assertEquals(DIVERGE_TAKEN, taken.type)
        assertEquals("maneuver", taken.fields["kind"])
        assertEquals(30.0, taken.fields["sinceS"])
        assertEquals(600L, taken.fields["sinceM"])
        assertFalse("teleportAtOff" in taken.fields)
        assertNull(watch.onOffRoute(32.0, 0.5, null, 20.0, false), "nothing is watched after it")
    }

    @Test
    fun `at freeway speed the 1,000 m come first`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        val lines = (1..30).mapNotNull { s -> watch.onProgress(onRamp(s.toDouble(), pastGore = 20.0 + 45.0 * (s - 1))) }
        val taken = lines.single()
        assertEquals(DIVERGE_TAKEN, taken.type)
        assertEquals(1_035L, taken.fields["sinceM"])
        assertEquals(23.0, taken.fields["sinceS"])
        assertEquals(DivergeWatch.WATCH_METERS, 1_000.0)
        assertEquals(DivergeWatch.WATCH_SECONDS, 30.0)
    }

    @Test
    fun `a report off the route after the limits is no miss`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        watch.onProgress(onRamp(1.0, pastGore = 5.0))
        // No update for a while, then the report.
        val late = requireNotNull(watch.onOffRoute(32.0, 0.5, null, 20.0, true))
        assertEquals(DIVERGE_TAKEN, late.type)
        assertEquals(31.0, late.fields["sinceS"])
    }

    @Test
    fun `an update that finds the car off the route ends the watch as the report would`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        watch.onProgress(onRamp(1.0, pastGore = 5.0, probability = 0.7))
        watch.onProgress(onRamp(2.0, pastGore = 30.0, probability = 0.8, uncertain = true))
        watch.onProgress(onRamp(3.0, pastGore = 55.0, probability = 0.6, uncertain = true))
        val missed = requireNotNull(watch.onProgress(onRamp(4.0, pastGore = 80.0, probability = 0.5, offRoute = true)))
        assertEquals(MISSED_MANEUVER, missed.type)
        assertEquals(3.0, missed.fields["sinceS"])
        assertEquals(75L, missed.fields["sinceM"])
        // Unsure from 2 s on, until the update that found the car off the route.
        assertEquals(2.0, missed.fields["uncertainS"])
        assertEquals(0.5, missed.fields["probMin"])
        assertEquals(0.8, missed.fields["probMax"])
        assertEquals(0.5, missed.fields["probLast"])
        assertEquals(false, missed.fields["teleportAtOff"])
        // The report that comes after it finds nothing open.
        assertNull(watch.onOffRoute(4.1, 0.5, null, 20.0, true))
    }

    @Test
    fun `the navigator was unsure from the update that said so to the next`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        watch.onProgress(onRamp(1.0, pastGore = 5.0))
        watch.onProgress(onRamp(2.0, pastGore = 30.0, uncertain = true))
        // The next update comes 3 s later, sure again.
        watch.onProgress(onRamp(5.0, pastGore = 105.0))
        val missed = requireNotNull(watch.onOffRoute(6.0, 0.5, null, 25.0, false))
        assertEquals(3.0, missed.fields["uncertainS"])
    }

    @Test
    fun `a step the car comes back to from off the route is no diverge passed`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 20.0))
        // Off the route, and back on it on the exit's step, as when the navigator finds the car on
        // the ramp after all: it did not pass the exit while the route was followed.
        assertNull(watch.onProgress(before(1.0, toGore = 10.0).copy(offRoute = true, points = emptyList())))
        assertNull(watch.onProgress(onRamp(2.0, pastGore = 30.0)))
        assertNull(watch.onOffRoute(3.0, 0.5, null, 25.0, false), "no watch was started")
    }

    @Test
    fun `a new route, a seek or the end of guidance ends a watch without a line`() {
        val reroute = DivergeWatch()
        reroute.onProgress(before(0.0, toGore = 20.0))
        reroute.onProgress(onRamp(1.0, pastGore = 5.0))
        assertNull(reroute.onProgress(onRamp(2.0, pastGore = 0.0, routeId = "r2", stepType = "continue")))
        assertNull(reroute.onOffRoute(3.0, 0.5, null, 20.0, true))
        assertEquals(emptyList<DivergeWatch.Line>(), reroute.feed((4..40).map { onRamp(it.toDouble(), pastGore = 25.0 * it, routeId = "r2", stepType = "continue") }))

        for (end in listOf("seek", "guidance end")) {
            val watch = DivergeWatch()
            watch.onProgress(before(0.0, toGore = 20.0))
            watch.onProgress(onRamp(1.0, pastGore = 5.0))
            watch.reset()
            assertNull(watch.onOffRoute(2.0, 0.5, null, 20.0, true), end)
            // Where the car is put next is a first update: nothing was passed since.
            assertNull(watch.onProgress(onRamp(3.0, pastGore = 500.0)), end)
            assertEquals(emptyList<DivergeWatch.Line>(), watch.feed((4..40).map { onRamp(it.toDouble(), pastGore = 500.0 + 25.0 * it) }), end)
        }
    }

    @Test
    fun `a newer diverge ends the open watch without a line`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 425.0))
        // The last point is passed; 15 s later the step changes past the exit.
        watch.onProgress(before(1.0, toGore = 390.0))
        for (s in 2..15) assertNull(watch.onProgress(before(s.toDouble(), toGore = 390.0 - 25.0 * (s - 1))))
        assertNull(watch.onProgress(onRamp(16.0, pastGore = 15.0)))
        val missed = requireNotNull(watch.onOffRoute(20.0, 0.5, null, 29.0, false))
        assertEquals("maneuver", missed.fields["kind"])
        assertEquals(4.0, missed.fields["sinceS"])
    }

    @Test
    fun `a road off on the exit's side without lane data is watched when passed`() {
        val watch = DivergeWatch()
        val road = DivergeWatch.Branch("r/0/2/9", 30.0)
        watch.onProgress(before(0.0, toGore = 900.0, branches = listOf(road)))
        // Passed: it is no longer ahead.
        watch.onProgress(before(1.0, toGore = 875.0))
        val missed = requireNotNull(watch.onOffRoute(5.0, 0.5, 6.0, 25.0, false))
        assertEquals("branch", missed.fields["kind"])
        assertEquals("r/0/2/9", missed.fields["key"])
        assertEquals(true, missed.fields["freeway"])
        assertFalse("lanes" in missed.fields)
        assertFalse("usable" in missed.fields)
    }

    @Test
    fun `the step changing on the way says so, and only a diverge's step change starts a watch`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 2_010.0))
        watch.onProgress(before(1.0, toGore = 1_985.0))
        // A new name on the way: the edge point's watch goes on.
        assertNull(watch.onProgress(before(2.0, toGore = 1_960.0).copy(stepKey = "r/0/3", stepType = "new name", points = emptyList())))
        val missed = requireNotNull(watch.onOffRoute(3.0, 0.5, null, 25.0, false))
        assertEquals("edge", missed.fields["kind"])
        assertEquals(true, missed.fields["advanced"])
        // A turn starts nothing.
        val turn = DivergeWatch()
        turn.onProgress(before(0.0, toGore = 20.0))
        turn.onProgress(onRamp(1.0, pastGore = 5.0, stepType = "turn"))
        assertNull(turn.onOffRoute(2.0, 0.5, null, 10.0, false))
    }

    @Test
    fun `a point that is no lane choice and no edge point starts nothing`() {
        val watch = DivergeWatch()
        val quiet = listOf(choice("r/0/2/7", 10.0, usable = 4))
        val update = before(0.0, toGore = 900.0)
        watch.onProgress(update.copy(points = quiet))
        watch.onProgress(update.copy(nowSeconds = 1.0, points = emptyList()))
        assertNull(watch.onOffRoute(2.0, 0.5, null, 25.0, false))
    }

    @Test
    fun `what the card showed is taken from the update before the diverge`() {
        val watch = DivergeWatch()
        watch.onProgress(before(0.0, toGore = 410.0, card = "choice", words = null, runUp = null))
        // The point is passed; this update's card already shows the next row.
        watch.onProgress(before(1.0, toGore = 385.0, card = "run_up", words = "R1", runUp = "r/0/2/end", speed = 27.5, probability = null, side = null))
        val missed = requireNotNull(watch.onOffRoute(3.0, null, null, null, null))
        assertEquals("choice", missed.fields["kind"])
        assertEquals("r/0/2/6", missed.fields["key"])
        assertEquals("choice", missed.fields["card"])
        assertFalse("words" in missed.fields)
        assertFalse("runUp" in missed.fields)
        assertEquals(27.5, missed.fields["speedAt"])
        assertEquals(27.5, missed.fields["speedEnd"])
        assertFalse("probAt" in missed.fields)
        assertFalse("sideAt" in missed.fields)
        assertFalse("teleportAtOff" in missed.fields, "not known")
    }
}
