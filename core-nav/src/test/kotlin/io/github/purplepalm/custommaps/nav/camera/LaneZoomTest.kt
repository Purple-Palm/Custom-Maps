package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Made-up roads only: round latitudes for the scale, and points near 0, 0 for distances. */
class LaneZoomTest {
    private val tuning = CameraTuning.PHONE

    @Test
    fun `zoom and lane width are inverse and follow 512 dp tiles`() {
        assertEquals(78_271.517, MapScale.metersPerDp(0.0, 0.0), 0.01)
        for (latitude in listOf(0.0, 25.0, 35.0, 45.0, 60.0)) {
            for (dp in listOf(5.0, 14.0, 28.0)) {
                assertEquals(dp, MapScale.laneDp(MapScale.zoomForLaneDp(dp, latitude), latitude), 1e-9)
            }
        }
        assertEquals(18.89, MapScale.zoomForLaneDp(28.0, 35.0), 0.01)
        assertEquals(17.89, MapScale.zoomForLaneDp(14.0, 35.0), 0.01)
        assertEquals(4.8, MapScale.laneDp(16.35, 35.0), 0.05)
    }

    private fun choice(
        lanes: Int,
        usable: Int,
        freeway: Boolean = true,
        atManeuver: Boolean = false,
        ahead: Double = 500.0,
        key: String = "r/0/3/2",
        designated: Int = 0,
    ) = LaneChoice(
        key = key,
        metersAhead = ahead,
        laneCount = lanes,
        usableLanes = usable,
        designatedLanes = designated,
        onFreeway = freeway,
        atManeuver = atManeuver,
    )

    @Test
    fun `only freeway points with enough lanes leaving count`() {
        assertFalse(choice(lanes = 2, usable = 1).counts(tuning), "two lanes")
        assertFalse(choice(lanes = 4, usable = 3).counts(tuning), "one lane leaves while the route goes straight on")
        assertTrue(choice(lanes = 4, usable = 2).counts(tuning), "two of four leave")
        assertTrue(choice(lanes = 3, usable = 1).counts(tuning), "two of three leave")
        assertTrue(choice(lanes = 3, usable = 1, atManeuver = true).counts(tuning), "an exit")
        assertTrue(choice(lanes = 4, usable = 3, atManeuver = true).counts(tuning), "a fork where one lane leaves")
        assertFalse(choice(lanes = 5, usable = 5).counts(tuning), "every lane goes on")
        assertFalse(choice(lanes = 5, usable = 0).counts(tuning), "no usable lane: a gap in the data")
        assertFalse(choice(lanes = 5, usable = 2, freeway = false).counts(tuning), "not a freeway")
    }

    @Test
    fun `a point that does not count says why`() {
        assertEquals(LaneChoiceSkip.NOT_A_FREEWAY, choice(lanes = 5, usable = 2, freeway = false).skipReason(tuning))
        assertEquals(LaneChoiceSkip.TOO_FEW_LANES, choice(lanes = 2, usable = 1).skipReason(tuning))
        assertEquals(LaneChoiceSkip.TOO_FEW_LANES, choice(lanes = 2, usable = 1, designated = 1).skipReason(tuning), "a car pool lane is no third lane")
        assertEquals(LaneChoiceSkip.NO_USABLE_LANE, choice(lanes = 5, usable = 0).skipReason(tuning))
        assertEquals(LaneChoiceSkip.NO_LANE_LEAVES, choice(lanes = 5, usable = 5).skipReason(tuning))
        assertEquals(LaneChoiceSkip.NO_LANE_LEAVES, choice(lanes = 5, usable = 5, atManeuver = true).skipReason(tuning))
        assertEquals(LaneChoiceSkip.TOO_FEW_LEAVING, choice(lanes = 4, usable = 3).skipReason(tuning))
        assertNull(choice(lanes = 4, usable = 3, atManeuver = true).skipReason(tuning))
        assertNull(choice(lanes = 4, usable = 2).skipReason(tuning))
        assertEquals(1, choice(lanes = 4, usable = 3).leavingLanes)
    }

    @Test
    fun `both thresholds of a lane choice are tuning numbers`() {
        val oneLeaving = tuning.copy(laneChoiceMinLeaving = 1)
        assertTrue(choice(lanes = 4, usable = 3).counts(oneLeaving), "a single lane that becomes an exit")
        assertFalse(choice(lanes = 4, usable = 4).counts(oneLeaving), "still nothing to choose")
        val twoLanes = tuning.copy(laneChoiceMinLanes = 2)
        assertTrue(choice(lanes = 2, usable = 1, atManeuver = true).counts(twoLanes))
        assertFalse(choice(lanes = 2, usable = 1, atManeuver = true).counts(tuning))
    }

    @Test
    fun `the window grows with speed between its limits`() {
        assertEquals(400.0, LaneFocus.windowMeters(tuning, null))
        assertEquals(400.0, LaneFocus.windowMeters(tuning, 5.0))
        assertEquals(725.0, LaneFocus.windowMeters(tuning, 29.0), 1e-9)
        assertEquals(1_200.0, LaneFocus.windowMeters(tuning, 60.0))
    }

    @Test
    fun `a speed that is no speed opens the shortest window`() {
        assertEquals(400.0, LaneFocus.windowMeters(tuning, -3.0))
        assertEquals(400.0, LaneFocus.windowMeters(tuning, Double.NaN))
    }

    /**
     * Drives at [speed] past lane choices at the given meters of the route, with one update a
     * second, and returns the focus at each second.
     */
    private fun drive(speed: Double, choicesAt: List<Double>, seconds: Int, focus: LaneFocus = LaneFocus(tuning)): List<Double> {
        val out = ArrayList<Double>()
        for (t in 0..seconds) {
            val now = t.toDouble()
            val routeMeters = speed * t
            val ahead = choicesAt.mapIndexedNotNull { i, at ->
                val meters = at - routeMeters
                if (meters >= 0.0) choice(lanes = 5, usable = 3, ahead = meters, key = "r/0/1/$i") else null
            }
            focus.onProgress(now, routeMeters, ahead, speed)
            out.add(focus.focusAt(now))
        }
        return out
    }

    @Test
    fun `zooms in before a lane choice, holds past it and zooms out`() {
        val speed = 29.0 // about 105 km/h
        val focus = drive(speed, listOf(1_500.0), 90)
        // The window is 725 m: the choice is inside it from the update at 27 s, 717 m ahead.
        assertEquals(0.0, focus[26], 1e-9)
        assertEquals(0.0, focus[27], 1e-9)
        assertTrue(focus[28] > 0.0 && focus[28] < 0.5, "on its way in: ${focus[28]}")
        assertEquals(1.0, focus[33], 1e-9) // 6 s later
        // Passed at 51.7 s, seen at 52 s; held for 2 s and 30 m, then out over 6 s.
        assertEquals(1.0, focus[53], 1e-9)
        assertEquals(1.0, focus[54], 1e-9)
        assertTrue(focus[57] > 0.0 && focus[57] < 1.0, "on its way out: ${focus[57]}")
        assertTrue(focus[59] > 0.0, "the way out takes its 6 s: ${focus[59]}")
        assertEquals(0.0, focus[60], 1e-9)
    }

    @Test
    fun `the window ends exactly at its distance`() {
        // At 29 m/s the window is 725 m.
        val inside = LaneFocus(tuning)
        inside.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 725.0)), 29.0)
        assertEquals(1.0, inside.goal)
        val outside = LaneFocus(tuning)
        outside.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 725.5)), 29.0)
        assertEquals(0.0, outside.goal)
    }

    @Test
    fun `the way in and the way out take their own times`() {
        val focus = LaneFocus(tuning.copy(laneInSeconds = 4.0, laneOutSeconds = 8.0))
        focus.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
        assertEquals(0.5, focus.focusAt(2.0), 1e-9)
        assertEquals(1.0, focus.focusAt(4.0), 1e-9)
        focus.reset(10.0)
        assertEquals(0.5, focus.focusAt(14.0), 1e-9)
        assertEquals(0.0, focus.focusAt(18.0), 1e-9)
    }

    @Test
    fun `the hold after a lane choice lasts for its distance as well as its time`() {
        // At walking pace in a jam the 30 m take longer than the 2 s.
        val speed = 2.0
        val focus = LaneFocus(tuning)
        var releasedAt = -1
        for (t in 0..60) {
            val routeMeters = speed * t
            val ahead = 20.0 - routeMeters
            focus.onProgress(t.toDouble(), routeMeters, if (ahead >= 0.0) listOf(choice(5, 3, ahead = ahead)) else emptyList(), speed)
            if (releasedAt < 0 && t > 0 && focus.goal == 0.0) releasedAt = t
        }
        // The choice lies at 20 m; the car is 30 m past it at 50 m, after 25 s.
        assertEquals(25, releasedAt)
    }

    @Test
    fun `close lane choices keep the view in`() {
        val speed = 29.0
        // The second choice is 900 m after the first: outside the 725 m window, inside it plus 10 s.
        val focus = drive(speed, listOf(1_500.0, 2_400.0), 120)
        val between = focus.subList(52, 80)
        assertTrue(between.all { it == 1.0 }, "no dip between: $between")
        // The second choice has a hold of its own: passed at 82.8 s and seen at 83 s, the car is
        // 30 m past it at 84 s and 2 s past it at 85 s. Then out over 6 s.
        assertEquals(1.0, focus[85], 1e-9)
        assertTrue(focus[86] < 1.0, "on its way out: ${focus[86]}")
        assertTrue(focus[90] > 0.0, "still on its way out: ${focus[90]}")
        assertEquals(0.0, focus[91], 1e-9)
    }

    @Test
    fun `the hold after a second lane choice lasts for its own distance`() {
        // In a jam at walking pace: the window is 400 m, and a next choice within 20 m more keeps
        // the view in. The second choice is 415 m ahead when the hold after the first ends.
        val speed = 2.0
        val focus = LaneFocus(tuning)
        var releasedAt = -1
        for (t in 0..300) {
            val routeMeters = speed * t
            val ahead = listOf(20.0, 465.0).mapIndexedNotNull { i, at ->
                val meters = at - routeMeters
                if (meters >= 0.0) choice(5, 3, ahead = meters, key = "r/0/1/$i") else null
            }
            focus.onProgress(t.toDouble(), routeMeters, ahead, speed)
            if (releasedAt < 0 && focus.goal == 0.0) releasedAt = t
        }
        // In from the first update to 30 m past the second choice: 495 m, after 248 s. The 2 s
        // past it ended at 235 s, and 30 m past the first choice was long before.
        assertEquals(248, releasedAt)
    }

    @Test
    fun `of two lane choices inside the window the nearer one holds the view`() {
        val near = choice(5, 3, ahead = 200.0, key = "r/0/1/0")
        val far = choice(4, 2, ahead = 600.0, key = "r/0/1/1")
        for (choices in listOf(listOf(near, far), listOf(far, near))) {
            val focus = LaneFocus(tuning)
            focus.onProgress(0.0, 0.0, choices, 29.0)
            assertEquals("r/0/1/0", focus.current?.key)
            // And it stays the one on the next update, when both have come nearer.
            focus.onProgress(1.0, 29.0, choices.map { it.copy(metersAhead = it.metersAhead - 29.0) }, 29.0)
            assertEquals(171.0, focus.current?.metersAhead)
        }
    }

    @Test
    fun `a next lane choice keeps the view in up to 10 s of driving from its own window`() {
        // At 29 m/s the window is 725 m and 10 s of driving are 290 m more.
        for ((metersAhead, goal) in listOf(1_015.0 to 1.0, 1_015.5 to 0.0)) {
            val focus = LaneFocus(tuning)
            focus.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 100.0, key = "r/0/1/0")), 29.0)
            // The first choice is passed; its hold of 30 m and 2 s is over by the third update.
            focus.onProgress(10.0, 290.0, listOf(choice(5, 3, ahead = metersAhead + 87.0, key = "r/0/1/1")), 29.0)
            assertEquals(1.0, focus.goal)
            focus.onProgress(13.0, 377.0, listOf(choice(5, 3, ahead = metersAhead, key = "r/0/1/1")), 29.0)
            assertEquals(goal, focus.goal, "the next choice $metersAhead m ahead")
        }
    }

    @Test
    fun `far apart lane choices let the view out in between`() {
        val focus = drive(29.0, listOf(1_500.0, 4_000.0), 160)
        assertTrue(focus.subList(62, 100).any { it == 0.0 })
    }

    @Test
    fun `a reroute during the hold does not keep the view in`() {
        val focus = LaneFocus(tuning)
        val speed = 29.0
        for (t in 0..10) {
            focus.onProgress(t.toDouble(), 600.0 + speed * t, listOf(choice(5, 3, ahead = 400.0 - speed * t)), speed)
        }
        assertEquals(1.0, focus.goal)
        // The choice would be passed at about 13.8 s. A reroute at 14 s: the route's meters start again.
        for (t in 14..30) focus.onProgress(t.toDouble(), speed * (t - 14), emptyList(), speed)
        assertEquals(0.0, focus.goal)
        assertNull(focus.current)
    }

    @Test
    fun `off the route a lane choice the car never reaches is held by its time only`() {
        // The choice lies at 1,300 m of the route. Off the route there are no choices and the
        // route's meters stand still: short of the choice, a little further on, or just past it.
        for (frozenAt in listOf(1_000.0, 1_200.0, 1_299.0, 1_310.0)) {
            val focus = LaneFocus(tuning)
            focus.onProgress(0.0, 1_000.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
            assertEquals(1.0, focus.goal)
            focus.onProgress(1.0, frozenAt, emptyList(), 29.0, onRoute = false)
            focus.onProgress(2.0, frozenAt, emptyList(), 29.0, onRoute = false)
            assertEquals(1.0, focus.goal, "held for its 2 s at $frozenAt m")
            for (t in 3..600) {
                focus.onProgress(t.toDouble(), frozenAt, emptyList(), 29.0, onRoute = false)
                assertEquals(0.0, focus.goal, "let go at $frozenAt m after $t s")
            }
            assertNull(focus.current)
            assertEquals(0.0, focus.focusAt(9.0), 1e-9)
        }
    }

    @Test
    fun `a new route after a moment off the route does not keep the view in`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 1_000.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
        focus.onProgress(1.0, 1_029.0, emptyList(), 29.0, onRoute = false)
        // The new route counts from its own start, far below where the old one's choice lay.
        focus.onProgress(2.0, 0.0, emptyList(), 29.0)
        assertEquals(1.0, focus.goal, "still within the 2 s")
        focus.onProgress(3.0, 29.0, emptyList(), 29.0)
        assertEquals(0.0, focus.goal)
        assertNull(focus.current)
    }

    @Test
    fun `back on the route the lane choice ahead takes the view again`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 1_000.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
        for (t in 1..5) focus.onProgress(t.toDouble(), 1_000.0, emptyList(), 29.0, onRoute = false)
        assertEquals(0.0, focus.goal)
        focus.onProgress(6.0, 1_050.0, listOf(choice(5, 3, ahead = 250.0)), 29.0)
        assertEquals(1.0, focus.goal)
        // And from there the hold past it lasts for its distance again, as on any drive.
        focus.onProgress(20.0, 1_310.0, emptyList(), 2.0)
        focus.onProgress(23.0, 1_316.0, emptyList(), 2.0)
        assertEquals(1.0, focus.goal, "2 s have passed, the 30 m have not")
        focus.onProgress(30.0, 1_330.0, emptyList(), 2.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `a car that brakes inside the window keeps the close view`() {
        val focus = LaneFocus(tuning)
        // At 29 m/s the window is 725 m, and the choice 700 m ahead is inside it.
        focus.onProgress(0.0, 1_000.0, listOf(choice(5, 3, ahead = 700.0)), 29.0)
        assertEquals(1.0, focus.goal)
        // Braking to 10 m/s shortens the window to 400 m; the choice is still 650 m ahead.
        focus.onProgress(5.0, 1_050.0, listOf(choice(5, 3, ahead = 650.0)), 10.0)
        focus.onProgress(10.0, 1_100.0, listOf(choice(5, 3, ahead = 600.0)), 10.0)
        assertEquals(1.0, focus.goal)
        assertEquals(600.0, requireNotNull(focus.current).metersAhead)
        // A choice that was never inside its window does not get the view that way.
        val other = LaneFocus(tuning)
        other.onProgress(10.0, 1_100.0, listOf(choice(5, 3, ahead = 600.0)), 10.0)
        assertEquals(0.0, other.goal)
    }

    @Test
    fun `points that do not count never bring the view in`() {
        val focus = LaneFocus(tuning)
        val skipped = listOf(
            choice(lanes = 4, usable = 3, ahead = 300.0, key = "r/0/1/1"),
            choice(lanes = 5, usable = 5, ahead = 200.0, key = "r/0/1/2"),
            choice(lanes = 5, usable = 2, freeway = false, ahead = 100.0, key = "r/0/1/3"),
        )
        focus.onProgress(0.0, 0.0, skipped, 29.0)
        assertEquals(0.0, focus.goal)
        assertNull(focus.current)
        assertFalse(focus.isAnimating(0.5))
    }

    @Test
    fun `a point behind the car or at no known distance never brings the view in`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 500.0, listOf(choice(5, 3, ahead = -5.0), choice(5, 3, ahead = Double.NaN, key = "r/0/3/3")), 29.0)
        assertEquals(0.0, focus.goal)
        assertNull(focus.current)
        assertFalse(choice(5, 3, ahead = -5.0).isAhead)
        assertFalse(choice(5, 3, ahead = Double.NaN).isAhead)
        assertFalse(choice(5, 3, ahead = Double.POSITIVE_INFINITY).isAhead)
        assertTrue(choice(5, 3, ahead = 0.0).isAhead)
    }

    @Test
    fun `a choice whose distance stops being a number does not keep the view close for good`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 1_000.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
        assertEquals(1.0, focus.goal)
        // The same choice at an endless distance counts as gone: the hold runs out as after any
        // other, 30 m past where the choice last was, at 1,300 m of the route.
        for (t in 1..11) focus.onProgress(t.toDouble(), 1_000.0 + 29.0 * t, listOf(choice(5, 3, ahead = Double.POSITIVE_INFINITY)), 29.0)
        assertEquals(1.0, focus.goal)
        focus.onProgress(12.0, 1_348.0, listOf(choice(5, 3, ahead = Double.POSITIVE_INFINITY)), 29.0)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `the end of guidance lets the view out from where it is`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 300.0)), 29.0)
        assertTrue(focus.isAnimating(3.0))
        val halfway = focus.focusAt(3.0)
        assertEquals(0.5, halfway, 1e-9)
        focus.reset(3.0)
        assertEquals(0.0, focus.goal)
        assertNull(focus.current)
        assertEquals(halfway, focus.focusAt(3.0), 1e-9)
        // Half the way back takes half the time.
        assertEquals(0.0, focus.focusAt(6.0), 1e-9)
        assertFalse(focus.isAnimating(6.1))
    }

    /**
     * The lane design's approach at its rounded, synthetic distances, on made-up lanes: points
     * 2,000 m (P0), 1,500, 1,470, 1,460 and 1,440 m (P1a to P1d) and 400 m (P2) before the gore,
     * five lanes at each. The right lane leaves at P0, no lane choice of its own; the right and the
     * left lane leave at P1 and the two left lanes at P2, lane choices both; the exit takes the
     * two right lanes of six.
     */
    private val approachPoints = listOf(
        ApproachPoint("P0", 2_000.0, usable = 4, edgeLeaves = true),
        ApproachPoint("P1a", 1_500.0, usable = 3, edgeLeaves = true),
        ApproachPoint("P1b", 1_470.0, usable = 3, edgeLeaves = true),
        ApproachPoint("P1c", 1_460.0, usable = 3, edgeLeaves = true),
        ApproachPoint("P1d", 1_440.0, usable = 3, edgeLeaves = true),
        ApproachPoint("P2", 400.0, usable = 3, edgeLeaves = false),
    )

    private class ApproachPoint(val key: String, val toGore: Double, val usable: Int, val edgeLeaves: Boolean)

    private val approachExit = "r1/0/3/end"

    /** The points ahead with the car [toGore] meters before the gore, and the exit while it is ahead. */
    private fun approachChoices(toGore: Double): List<LaneChoice> {
        val ahead = ArrayList<LaneChoice>()
        for (p in approachPoints) {
            if (p.toGore > toGore) continue
            ahead.add(LaneChoice("r1/0/3/${p.key}", toGore - p.toGore, 5, p.usable, 0, onFreeway = true, atManeuver = false, edgeLeaves = p.edgeLeaves))
        }
        if (toGore >= 0.0) ahead.add(LaneChoice(approachExit, toGore, 6, 2, 0, onFreeway = true, atManeuver = true))
        return ahead
    }

    /** 25 m/s, rising to 29 m/s over the last 1,500 m before the gore. */
    private fun approachSpeed(toGore: Double) = if (toGore > 1_500.0) 25.0 else lerp(25.0, 29.0, 1.0 - toGore.coerceAtLeast(0.0) / 1_500.0)

    private class ApproachUpdate(val t: Int, val toGore: Double, val goal: Double, val key: String?)

    /**
     * Drives the design's approach at one update a second, from 2,800 m before the gore to
     * 150 m past it, with the run-up latch when one is given, and returns each update.
     */
    private fun approachDrive(
        latch: RunUpLatch?,
        focus: LaneFocus = LaneFocus(tuning),
        each: (ApproachUpdate, List<LaneChoice>, Double, Double) -> Unit = { _, _, _, _ -> },
    ): List<ApproachUpdate> {
        var toGore = 2_800.0
        var routeMeters = 0.0
        val out = ArrayList<ApproachUpdate>()
        var t = 0
        while (toGore > -150.0) {
            val speed = approachSpeed(toGore)
            val choices = approachChoices(toGore)
            latch?.onProgress(t.toDouble(), choices, speed)
            val key = latch?.exitKey
            focus.onProgress(t.toDouble(), routeMeters, choices, speed, runUpKey = key)
            val update = ApproachUpdate(t, toGore, focus.goal, key)
            each(update, choices, speed, routeMeters)
            out.add(update)
            repeat(10) {
                val v = approachSpeed(toGore)
                toGore -= v * 0.1
                routeMeters += v * 0.1
            }
            t++
        }
        return out
    }

    @Test
    fun `on a run-up the view comes in once and stays to the gore`() {
        val focus = LaneFocus(tuning)
        val updates = approachDrive(RunUpLatch(tuning), focus) { update, _, _, _ ->
            if (update.goal == 1.0 && update.toGore >= 0.0) assertEquals(approachExit, focus.runUpKey, "at ${update.toGore} m")
        }
        // In at the run-up's reach, 2,125 m before the gore at 25 m/s.
        val first = updates.first { it.key != null }
        assertEquals(2_125.0, first.toGore, 1e-6)
        assertEquals(1.0, first.goal)
        assertTrue(updates.filter { it.t < first.t }.all { it.goal == 0.0 })
        // Held to the gore with no gap, and 2 s past it: the first update past it and one more.
        val pastGore = updates.first { it.toGore < 0.0 }
        assertTrue(updates.filter { it.t in first.t..pastGore.t + 1 }.all { it.goal == 1.0 }, "no gap")
        assertEquals(0.0, updates.first { it.t == pastGore.t + 2 }.goal)
        assertTrue(updates.filter { it.t >= pastGore.t + 2 }.all { it.goal == 0.0 })
        assertNull(focus.runUpKey, "out of the run-up's hold")
        // The latch gave the key up to the gore.
        assertTrue(updates.filter { it.t in first.t until pastGore.t }.all { it.key == approachExit })
        assertNull(pastGore.key)
    }

    @Test
    fun `without a run-up the same approach lets the view out between its lane choices`() {
        val today = approachDrive(latch = null)
        // Past P1d the next choice, P2, is more than 10 s of driving from its window.
        assertTrue(today.any { it.toGore in 400.0..1_440.0 && it.goal == 0.0 })
        // No key, or a key that is null, is the same view.
        val explicit = ArrayList<Double>()
        val other = LaneFocus(tuning)
        approachDrive(latch = null) { update, choices, speed, routeMeters ->
            other.onProgress(update.t.toDouble(), routeMeters, choices, speed, onRoute = true, runUpKey = null)
            explicit.add(other.goal)
        }
        assertEquals(today.map { it.goal }, explicit)
        assertNull(other.runUpKey)
    }

    @Test
    fun `a camera made anew in the middle of a run-up stays in at once`() {
        var second: LaneFocus? = null
        val goals = ArrayList<Pair<Double, Double>>()
        approachDrive(RunUpLatch(tuning)) { update, choices, speed, routeMeters ->
            if (second == null && update.toGore <= 1_300.0) second = LaneFocus(tuning)
            second?.let {
                it.onProgress(update.t.toDouble(), routeMeters, choices, speed, runUpKey = update.key)
                goals.add(update.toGore to it.goal)
            }
        }
        assertEquals(1.0, goals.first().second, "its first update, at ${goals.first().first} m")
        assertTrue(goals.filter { it.first >= 0.0 }.all { it.second == 1.0 })
    }

    @Test
    fun `the run-up's exit brings the view in before any window`() {
        // A lane drop on the exit's side 500 m before the exit begins the run-up at its reach.
        val focus = LaneFocus(tuning)
        val drop = LaneChoice("r1/0/3/2", 1_952.0, 4, 3, 0, onFreeway = true, atManeuver = false, edgeLeaves = true)
        val exit = LaneChoice("r1/0/3/end", 2_452.0, 4, 1, 0, onFreeway = true, atManeuver = true)
        focus.onProgress(0.0, 0.0, listOf(drop, exit), 29.0, runUpKey = "r1/0/3/end")
        assertEquals(1.0, focus.goal)
        assertEquals(exit, focus.current)
        assertEquals("r1/0/3/end", focus.runUpKey)
        // The same choices without the key: no window is open yet.
        val today = LaneFocus(tuning)
        today.onProgress(0.0, 0.0, listOf(drop, exit), 29.0)
        assertEquals(0.0, today.goal)
    }

    @Test
    fun `a run-up key holds only an exit that counts and lies ahead`() {
        val keys = listOf("r1/0/3/end", "r1/0/3/2")
        for (choices in listOf(
            emptyList(),
            listOf(LaneChoice("r1/0/3/end", 1_500.0, 2, 1, 0, onFreeway = true, atManeuver = true)),
            listOf(LaneChoice("r1/0/3/end", -2.0, 5, 1, 0, onFreeway = true, atManeuver = true)),
            listOf(LaneChoice("r1/0/3/2", 1_500.0, 4, 3, 0, onFreeway = true, atManeuver = false, edgeLeaves = true)),
        )) {
            for (key in keys) {
                val focus = LaneFocus(tuning)
                focus.onProgress(0.0, 0.0, choices, 25.0, runUpKey = key)
                assertEquals(0.0, focus.goal, "$key over $choices")
                assertNull(focus.runUpKey)
            }
        }
    }

    /**
     * A lane choice 50 m ahead at 25 m/s, passed between the updates at 2 s and 3 s; its hold of
     * 2 s ends at 5 s. The step after it ends in a turn whose framing begins [framingAfterHold]
     * meters after the car's place at 5 s. Returns the goal at each second, from 0 s to [seconds].
     */
    private fun turnAfterHold(
        framingAfterHold: Double,
        seconds: Int = 30,
        turnAhead: (t: Int, metersToFraming: Double) -> TurnAhead? = { _, meters -> TurnAhead("r1/0/4", meters) },
        onRoute: (t: Int) -> Boolean = { true },
    ): List<Double> {
        val focus = LaneFocus(tuning)
        val goals = ArrayList<Double>()
        for (t in 0..seconds) {
            val routeMeters = 25.0 * t
            val ahead = 50.0 - routeMeters
            val choices = if (ahead >= 0.0) listOf(choice(5, 3, ahead = ahead, atManeuver = true, key = "r1/0/3/end")) else emptyList()
            val turn = if (ahead >= 0.0) null else turnAhead(t, framingAfterHold + 25.0 * (5 - t))
            focus.onProgress(t.toDouble(), routeMeters, choices, 25.0, onRoute = onRoute(t), turnAhead = turn)
            goals.add(focus.goal)
        }
        return goals
    }

    @Test
    fun `a turn framed within 13,5 s of driving after the hold keeps the view in`() {
        // At 25 m/s the descent and the coalesce, 13.5 s of driving, are 337.5 m.
        for ((seconds, bridged) in listOf(5.0 to true, 13.0 to true, 13.5 to true, 14.0 to false)) {
            val goals = turnAfterHold(framingAfterHold = 25.0 * seconds)
            assertEquals(1.0, goals[4], "held for 2 s past the choice")
            assertEquals(if (bridged) 1.0 else 0.0, goals[5], "framing $seconds s after the hold")
        }
        // Without a turn ahead the view is let go at the end of the hold, as it always was.
        assertEquals(0.0, turnAfterHold(125.0, turnAhead = { _, _ -> null })[5])
    }

    @Test
    fun `the turn is judged at the speed the car has when the hold ends`() {
        // Within 13.5 s at 25 m/s while the hold lasts; the car has slowed to 10 m/s when it ends,
        // and the framing is 30 s away then: no reason to stay close.
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 20.0, atManeuver = true, key = "r1/0/3/end")), 25.0)
        focus.onProgress(1.0, 25.0, emptyList(), 25.0, turnAhead = TurnAhead("r1/0/4", 300.0))
        focus.onProgress(2.0, 45.0, emptyList(), 20.0, turnAhead = TurnAhead("r1/0/4", 280.0))
        assertEquals(1.0, focus.goal, "still in the hold")
        focus.onProgress(3.0, 55.0, emptyList(), 10.0, turnAhead = TurnAhead("r1/0/4", 270.0))
        assertEquals(0.0, focus.goal)
        // The same turn at the same place, the car still at 25 m/s: kept in.
        val fast = LaneFocus(tuning)
        fast.onProgress(0.0, 0.0, listOf(choice(5, 3, ahead = 20.0, atManeuver = true, key = "r1/0/3/end")), 25.0)
        fast.onProgress(1.0, 25.0, emptyList(), 25.0, turnAhead = TurnAhead("r1/0/4", 300.0))
        fast.onProgress(3.0, 75.0, emptyList(), 25.0, turnAhead = TurnAhead("r1/0/4", 250.0))
        assertEquals(1.0, fast.goal)
    }

    @Test
    fun `the view kept for a turn is let go where the turn's framing begins`() {
        // Framing 5 s after the hold, 125 m: it begins at 10 s, where the junction view takes over.
        val goals = turnAfterHold(framingAfterHold = 125.0)
        assertTrue(goals.subList(0, 10).all { it == 1.0 }, "$goals")
        assertTrue(goals.subList(10, goals.size).all { it == 0.0 }, "never held past it: $goals")
        // A framing that has begun already keeps nothing.
        assertEquals(0.0, turnAfterHold(framingAfterHold = 0.0)[5])
        assertEquals(0.0, turnAfterHold(framingAfterHold = -40.0)[5])
    }

    @Test
    fun `the view kept for a turn is let go on another step or off the route`() {
        // The turn was on the step after the choice; a new route or the next step names another.
        val newStep = turnAfterHold(250.0, turnAhead = { t, meters -> TurnAhead(if (t < 8) "r1/0/4" else "r2/0/1", meters) })
        assertEquals(1.0, newStep[7])
        assertEquals(0.0, newStep[8])
        // A step whose maneuver the camera does not frame is no turn to wait for.
        val notFramed = turnAfterHold(250.0, turnAhead = { t, meters -> if (t < 8) TurnAhead("r1/0/4", meters) else null })
        assertEquals(1.0, notFramed[7])
        assertEquals(0.0, notFramed[8])
        // Off the route.
        val off = turnAfterHold(250.0, onRoute = { t -> t < 8 })
        assertEquals(1.0, off[7])
        assertEquals(0.0, off[8])
        // And once let go it is not taken up again for the same turn.
        val back = turnAfterHold(250.0, onRoute = { t -> t != 8 })
        assertEquals(0.0, back[8])
        assertTrue(back.subList(8, back.size).all { it == 0.0 }, "$back")
    }

    @Test
    fun `a run-up whose ramp ends in a turn stays close until the turn is framed`() {
        // The design's S11, synthetic: no points on the way, the exit at the gore, a turn 400 m
        // past the gore, framed from 180 m before it. 27 m/s, one update a second from 2,800 m.
        val latch = RunUpLatch(tuning)
        val focus = LaneFocus(tuning)
        val goals = ArrayList<Pair<Double, Double>>()
        for (t in 0..120) {
            val toGore = 2_800.0 - 27.0 * t
            val choices = if (toGore >= 0.0) listOf(LaneChoice("r1/0/3/end", toGore, 5, 1, 1, onFreeway = true, atManeuver = true)) else emptyList()
            latch.onProgress(t.toDouble(), choices, 27.0)
            val turn = if (toGore < 0.0) TurnAhead("r1/0/4", 400.0 + toGore - tuning.maneuverFramingMeters) else null
            focus.onProgress(t.toDouble(), 27.0 * t, choices, 27.0, turnAhead = turn, runUpKey = latch.exitKey)
            goals.add(toGore to focus.goal)
        }
        // In at the exit's own window, 675 m at 27 m/s: the update at 667 m.
        assertEquals(667.0, goals.first { it.second == 1.0 }.first, 1e-9)
        // Held past the gore until the turn's framing begins, 220 m past it: let go at 224 m.
        assertEquals(-224.0, goals.first { it.first < 0.0 && it.second == 0.0 }.first, 1e-9)
        assertTrue(goals.filter { it.first in -197.0..667.0 }.all { it.second == 1.0 })
    }

    @Test
    fun `the end of guidance forgets the run-up`() {
        val focus = LaneFocus(tuning)
        focus.onProgress(0.0, 0.0, listOf(LaneChoice("r1/0/3/end", 1_500.0, 5, 1, 0, onFreeway = true, atManeuver = true)), 25.0, runUpKey = "r1/0/3/end")
        assertEquals("r1/0/3/end", focus.runUpKey)
        focus.reset(1.0)
        assertNull(focus.runUpKey)
        assertNull(focus.current)
        assertEquals(0.0, focus.goal)
    }

    @Test
    fun `default keeps the frame zoom`() {
        val plan = ZoomPlanner(tuning)
        assertEquals(17.2, plan.target(LaneZoom.DEFAULT, 17.2, 35.0, 1.0, false, 1.0, false))
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
    }

    @Test
    fun `large on the road is default`() {
        val plan = ZoomPlanner(tuning)
        assertEquals(16.35, plan.target(LaneZoom.LARGE, 18.0, 35.0, 0.0, false, 0.0, false), 1e-9)
        assertEquals(14.2, plan.target(LaneZoom.LARGE, 14.2, 35.0, 0.0, false, 0.0, false), 1e-9)
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
    }

    @Test
    fun `large follows the junction frame up to the lane cap along the tilt curve`() {
        val plan = ZoomPlanner(tuning)
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        assertEquals(lerp(16.35, 17.4, 0.5), plan.target(LaneZoom.LARGE, 17.4, 35.0, 0.5, false, 0.0, false), 1e-9)
        assertEquals(18.5, plan.target(LaneZoom.LARGE, 18.5, 35.0, 1.0, false, 0.0, false), 1e-9)
        assertEquals(cap, plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, false), 1e-9)
        assertEquals(ZoomPlanner.Kind.JUNCTION, plan.kind)
        // On the next step the frame shows the road ahead of the car again: the junction's zoom
        // is kept and let go as the tilt comes back up.
        assertEquals(cap, plan.target(LaneZoom.LARGE, 16.1, 35.0, 1.0, true, 0.0, false), 1e-9)
        assertEquals(ZoomPlanner.Kind.AFTER_JUNCTION, plan.kind)
        assertEquals(lerp(16.1, cap, 0.25), plan.target(LaneZoom.LARGE, 16.1, 35.0, 0.25, true, 0.0, false), 1e-9)
        assertEquals(16.1, plan.target(LaneZoom.LARGE, 16.1, 35.0, 0.0, true, 0.0, false), 1e-9)
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
    }

    @Test
    fun `a frame of the open road counts no closer than the road, also while the tilt goes down`() {
        val plan = ZoomPlanner(tuning)
        // Fitted to a short stretch of road ahead, such a frame says nothing of the junction.
        assertEquals(16.35, plan.target(LaneZoom.LARGE, 18.4, 35.0, 0.5, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(16.35, plan.target(LaneZoom.LARGE, 18.4, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
        assertEquals(15.0, plan.target(LaneZoom.LARGE, 15.0, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        // The frame around the car and the junction is what goes closer.
        assertEquals(17.3, plan.target(LaneZoom.LARGE, 17.3, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        assertEquals(ZoomPlanner.Kind.JUNCTION, plan.kind)
    }

    @Test
    fun `between two junctions the view stays down for, the first one's zoom is kept until the second is framed`() {
        val plan = ZoomPlanner(tuning)
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        assertEquals(cap, plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        // Past the first turn, with the next one's window open already: the tilt stays down, the
        // frame is of the road again, and the view does not go out and back in.
        assertEquals(cap, plan.target(LaneZoom.LARGE, 16.2, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(ZoomPlanner.Kind.AFTER_JUNCTION, plan.kind)
        assertEquals(cap, plan.target(LaneZoom.LARGE, 16.2, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        // The second junction is framed: its frame decides from here on, so it stays in view.
        assertEquals(17.4, plan.target(LaneZoom.LARGE, 17.4, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        assertEquals(ZoomPlanner.Kind.JUNCTION, plan.kind)
        // And past the second one it is that junction's zoom that is kept and let go.
        assertEquals(lerp(16.0, 17.4, 0.5), plan.target(LaneZoom.LARGE, 16.0, 35.0, 0.5, true, 0.0, roadFrame = true), 1e-9)
    }

    @Test
    fun `a junction's zoom that was partly let go is not taken up again before the next one is framed`() {
        val plan = ZoomPlanner(tuning)
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        assertEquals(cap, plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        // Past the turn the tilt comes back up and the zoom lets go with it.
        assertEquals(lerp(16.2, cap, 0.6), plan.target(LaneZoom.LARGE, 16.2, 35.0, 0.6, true, 0.0, roadFrame = true), 1e-9)
        val lowest = lerp(16.2, cap, 0.3)
        assertEquals(lowest, plan.target(LaneZoom.LARGE, 16.2, 35.0, 0.3, true, 0.0, roadFrame = true), 1e-9)
        // The next turn takes the view before the tilt is all the way up: the tilt turns back
        // down, and the frame is still of the open road. The zoom waits where it is.
        assertEquals(lowest, plan.target(LaneZoom.LARGE, 16.2, 35.0, 0.5, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(lowest, plan.target(LaneZoom.LARGE, 16.2, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(ZoomPlanner.Kind.AFTER_JUNCTION, plan.kind)
        // It follows the road's zoom meanwhile, at the share that is left.
        assertEquals(lerp(15.8, cap, 0.3), plan.target(LaneZoom.LARGE, 15.8, 35.0, 1.0, false, 0.0, roadFrame = true), 1e-9)
        // The next junction is framed: its frame decides, and past it all of its zoom is kept again.
        assertEquals(17.4, plan.target(LaneZoom.LARGE, 17.4, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        assertEquals(ZoomPlanner.Kind.JUNCTION, plan.kind)
        assertEquals(17.4, plan.target(LaneZoom.LARGE, 16.0, 35.0, 1.0, true, 0.0, roadFrame = true), 1e-9)
        assertEquals(lerp(16.0, 17.4, 0.5), plan.target(LaneZoom.LARGE, 16.0, 35.0, 0.5, true, 0.0, roadFrame = true), 1e-9)
    }

    @Test
    fun `a frame of a wide junction is taken at its word, also when it is farther out than the last junction`() {
        val plan = ZoomPlanner(tuning)
        plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false)
        // Two maneuvers framed together need a wider view than the road's: both must stay in view.
        assertEquals(15.9, plan.target(LaneZoom.LARGE, 15.9, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
    }

    @Test
    fun `once the view is back on the road the last junction is forgotten`() {
        val plan = ZoomPlanner(tuning)
        plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, false)
        plan.target(LaneZoom.LARGE, 16.1, 35.0, 0.0, true, 0.0, false)
        // Whatever comes next on that step starts from the frame again, not from the old junction.
        assertEquals(lerp(16.35, 17.0, 0.5), plan.target(LaneZoom.LARGE, 17.0, 35.0, 0.5, true, 0.0, false), 1e-9)
    }

    @Test
    fun `a junction forgotten while the camera was away does not come back before a later turn`() {
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        // The camera leaves the car over a junction, and comes back on the approach to a later
        // turn: the tilt is on its way down and the frame is still of the open road.
        val away = ZoomPlanner(tuning)
        assertEquals(cap, away.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false), 1e-9)
        away.forgetJunction()
        assertEquals(16.2, away.target(LaneZoom.LARGE, 16.2, 35.0, 0.6, false, 0.0, roadFrame = true), 1e-9)
        assertEquals(ZoomPlanner.Kind.ROAD, away.kind)
        // Not told that the tilt came back up meanwhile, it would go to the old junction's zoom.
        val untold = ZoomPlanner(tuning)
        untold.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false)
        assertEquals(lerp(16.2, cap, 0.6), untold.target(LaneZoom.LARGE, 16.2, 35.0, 0.6, false, 0.0, roadFrame = true), 1e-9)
        // Forgetting leaves what the log was told; the end of guidance takes that back as well.
        val ended = ZoomPlanner(tuning)
        ended.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false)
        ended.forgetJunction()
        assertEquals(ZoomPlanner.Kind.JUNCTION, ended.kind)
        ended.reset()
        assertEquals(ZoomPlanner.Kind.ROAD, ended.kind)
        assertEquals(16.2, ended.target(LaneZoom.LARGE, 16.2, 35.0, 0.6, false, 0.0, roadFrame = true), 1e-9)
    }

    @Test
    fun `a change to default and back starts from the frame`() {
        val plan = ZoomPlanner(tuning)
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        assertEquals(cap, plan.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, false), 1e-9)
        assertEquals(16.1, plan.target(LaneZoom.DEFAULT, 16.1, 35.0, 1.0, true, 0.0, false))
        assertEquals(ZoomPlanner.Kind.ROAD, plan.kind)
        // Back to Large after the turn: the junction's zoom was dropped, the frame's is taken.
        assertEquals(16.1, plan.target(LaneZoom.LARGE, 16.1, 35.0, 1.0, true, 0.0, false), 1e-9)
    }

    @Test
    fun `large zooms in to the lane width before a lane choice`() {
        val plan = ZoomPlanner(tuning)
        val lanes = MapScale.zoomForLaneDp(14.0, 35.0)
        assertEquals(lanes, plan.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 1.0, roadFrame = true), 1e-9)
        assertEquals(lerp(14.0, lanes, 0.5), plan.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 0.5, roadFrame = true), 1e-9)
        assertEquals(ZoomPlanner.Kind.LANES, plan.kind)
        // The same lane width takes a closer zoom nearer the equator.
        assertTrue(plan.target(LaneZoom.LARGE, 14.0, 25.0, 0.0, false, 1.0, roadFrame = true) > lanes)
    }

    @Test
    fun `in the frame of a maneuver a lane choice goes no closer than the frame`() {
        val lanes = MapScale.zoomForLaneDp(14.0, 35.0)
        val cap = MapScale.zoomForLaneDp(28.0, 35.0)
        // An exit that was a lane choice, and its ramp ends in a turn: the lane choice still holds
        // the view when the turn is framed. The frame is fitted around the car and the junction.
        for (frameZoom in listOf(15.0, 16.35, 17.26, 17.62, lanes - 0.01)) {
            val plan = ZoomPlanner(tuning)
            assertEquals(frameZoom, plan.target(LaneZoom.LARGE, frameZoom, 35.0, 1.0, false, 1.0, roadFrame = false), 1e-9, "frame $frameZoom")
            // Also while the tilt has only begun to go down and the lane choice is on its way out.
            for (junctionFocus in listOf(0.0, 0.3, 1.0)) {
                for (laneFocus in listOf(0.2, 0.7, 1.0)) {
                    val zoom = ZoomPlanner(tuning).target(LaneZoom.LARGE, frameZoom, 35.0, junctionFocus, false, laneFocus, roadFrame = false)
                    assertTrue(zoom <= frameZoom + 1e-9, "frame $frameZoom, focus $junctionFocus and $laneFocus: $zoom")
                }
            }
        }
        // With both at the frame's zoom, the log names the junction.
        val plan = ZoomPlanner(tuning)
        plan.target(LaneZoom.LARGE, 17.26, 35.0, 1.0, false, 1.0, roadFrame = false)
        assertEquals(ZoomPlanner.Kind.JUNCTION, plan.kind)
        // Before the tilt has begun, the lane choice alone goes up to the frame.
        assertEquals(17.26, plan.target(LaneZoom.LARGE, 17.26, 35.0, 0.0, false, 1.0, roadFrame = false), 1e-9)
        assertEquals(ZoomPlanner.Kind.LANES, plan.kind)
        // A frame fitted closer than the lane choice asks for: the lane choice keeps its own zoom
        // and the junction goes on to the frame's, up to its cap.
        assertEquals(lanes, ZoomPlanner(tuning).target(LaneZoom.LARGE, 18.5, 35.0, 0.0, false, 1.0, roadFrame = false), 1e-9)
        assertEquals(18.5, ZoomPlanner(tuning).target(LaneZoom.LARGE, 18.5, 35.0, 1.0, false, 1.0, roadFrame = false), 1e-9)
        assertEquals(cap, ZoomPlanner(tuning).target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 1.0, roadFrame = false), 1e-9)
        // A frame of the open road has the car on its own place: there the lane choice is not held back.
        assertEquals(lanes, ZoomPlanner(tuning).target(LaneZoom.LARGE, 14.2, 35.0, 0.0, false, 1.0, roadFrame = true), 1e-9)
    }

    @Test
    fun `a look-ahead cap holds the lanes back, never below the road and never at a junction`() {
        val lanes = MapScale.zoomForLaneDp(14.0, 35.0)
        val cap28 = MapScale.zoomForLaneDp(28.0, 35.0)
        val plan = ZoomPlanner(tuning)
        // Under the 14 dp zoom it is the lanes' goal, along the lane curve.
        assertEquals(17.5, plan.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = 17.5), 1e-9)
        assertEquals(ZoomPlanner.Kind.LANES, plan.kind)
        assertEquals(lerp(14.0, 17.5, 0.5), plan.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 0.5, roadFrame = true, lanesCap = 17.5), 1e-9)
        // Above it, it changes nothing.
        assertEquals(lanes, plan.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = lanes + 0.3), 1e-9)
        // Never under the road's zoom.
        assertEquals(16.35, plan.target(LaneZoom.LARGE, 18.0, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = 15.0), 1e-9)
        assertEquals(15.2, plan.target(LaneZoom.LARGE, 15.2, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = 15.0), 1e-9)
        // The junction goes to its own frame, also while a lane choice holds the view.
        assertEquals(18.5, ZoomPlanner(tuning).target(LaneZoom.LARGE, 18.5, 35.0, 1.0, false, 1.0, roadFrame = false, lanesCap = 17.0), 1e-9)
        assertEquals(cap28, ZoomPlanner(tuning).target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, roadFrame = false, lanesCap = 15.0), 1e-9)
        // In a maneuver's frame the lanes stay within it, and within the cap.
        assertEquals(16.9, ZoomPlanner(tuning).target(LaneZoom.LARGE, 17.4, 35.0, 0.0, false, 1.0, roadFrame = false, lanesCap = 16.9), 1e-9)
        // Default has no lane zoom to cap.
        assertEquals(17.2, ZoomPlanner(tuning).target(LaneZoom.DEFAULT, 17.2, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = 15.0))
        // A cap that is not a number is none.
        assertEquals(lanes, ZoomPlanner(tuning).target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 1.0, roadFrame = true, lanesCap = Double.NaN), 1e-9)
    }

    @Test
    fun `without a cap the zoom is what it always was`() {
        // The same inputs to a planner without the cap and to ones with a cap that never binds.
        val random = java.util.Random(7)
        val plain = ZoomPlanner(tuning)
        val infinite = ZoomPlanner(tuning)
        val high = ZoomPlanner(tuning)
        repeat(2_000) {
            val mode = if (random.nextInt(8) == 0) LaneZoom.DEFAULT else LaneZoom.LARGE
            val frameZoom = 13.0 + random.nextDouble() * 7.0
            val latitude = random.nextDouble() * 120.0 - 60.0
            val junction = listOf(0.0, 1.0, random.nextDouble())[random.nextInt(3)]
            val after = random.nextBoolean()
            val lane = listOf(0.0, 1.0, random.nextDouble())[random.nextInt(3)]
            val roadFrame = random.nextBoolean()
            val expected = plain.target(mode, frameZoom, latitude, junction, after, lane, roadFrame)
            assertEquals(expected, infinite.target(mode, frameZoom, latitude, junction, after, lane, roadFrame, lanesCap = Double.POSITIVE_INFINITY), 0.0)
            assertEquals(expected, high.target(mode, frameZoom, latitude, junction, after, lane, roadFrame, lanesCap = tuning.maxZoom + 1.0), 0.0)
            assertEquals(plain.kind, infinite.kind)
        }
    }

    @Test
    fun `large never goes closer than its limit`() {
        val close = ZoomPlanner(tuning.copy(maxZoom = 18.0))
        assertEquals(18.0, close.target(LaneZoom.LARGE, 19.6, 35.0, 1.0, false, 0.0, false), 1e-9)
        assertEquals(17.89, close.target(LaneZoom.LARGE, 14.0, 35.0, 0.0, false, 1.0, roadFrame = true), 0.01)
    }

    @Test
    fun `a latitude that is not a number zooms in nowhere`() {
        val plan = ZoomPlanner(tuning)
        assertEquals(16.35, plan.target(LaneZoom.LARGE, 19.6, Double.NaN, 1.0, false, 1.0, false), 1e-9)
        assertEquals(14.0, plan.target(LaneZoom.LARGE, 14.0, Double.NaN, 1.0, false, 1.0, false), 1e-9)
    }

    @Test
    fun `the center leads only where the lag would pass the limit`() {
        val tau = tuning.centerTau
        assertEquals(0.0, centerLeadSeconds(tau, 80 / 3.6, MapScale.metersPerDp(16.35, 35.0), 12.0))
        val metersPerDp = MapScale.metersPerDp(17.89, 35.0)
        val speed = 105 / 3.6
        val lead = centerLeadSeconds(tau, speed, metersPerDp, 12.0)
        assertTrue(lead > 0.0 && lead < 2 * tau, "lead $lead")
        assertEquals(12.0, (2 * tau - lead) * speed / metersPerDp, 1e-9)
        assertEquals(0.0, centerLeadSeconds(tau, 0.0, metersPerDp, 12.0))
        assertEquals(0.0, centerLeadSeconds(tau, Double.NaN, metersPerDp, 12.0))
    }

    @Test
    fun `the maneuver focus knows when the car is past the junction`() {
        val focus = ManeuverFocus(tuning)
        val excluded = listOf("continue", "merge", "on ramp", "off ramp", "fork")
        focus.onProgress(0.0, "r/0/1", 150.0, 50.0, "turn", excluded, 10.0)
        assertFalse(focus.afterManeuver)
        focus.onProgress(10.0, "r/0/2", 400.0, 5.0, "turn", excluded, 10.0)
        assertTrue(focus.afterManeuver, "on the step after the turn")
        focus.onProgress(20.0, "r/0/2", 200.0, 205.0, "turn", excluded, 10.0)
        assertFalse(focus.afterManeuver, "the next turn has taken the view")
    }

    @Test
    fun `a step after a maneuver the view stayed up for is no step after a junction`() {
        val focus = ManeuverFocus(tuning)
        val excluded = listOf("continue", "merge", "on ramp", "off ramp", "fork")
        focus.onProgress(0.0, "r/0/1", 150.0, 50.0, "fork", excluded, 10.0)
        focus.onProgress(10.0, "r/0/2", 2_000.0, 5.0, "turn", excluded, 10.0)
        assertFalse(focus.afterManeuver)
        // And the end of guidance forgets the junction.
        focus.onProgress(20.0, "r/0/2", 150.0, 1_855.0, "turn", excluded, 10.0)
        focus.onProgress(30.0, "r/0/3", 900.0, 5.0, "fork", excluded, 10.0)
        assertTrue(focus.afterManeuver)
        // The view comes back up, and the step after that one starts at a fork it stayed up for:
        // the junction is far behind.
        focus.onProgress(40.0, "r/0/3", 800.0, 105.0, "fork", excluded, 10.0)
        focus.onProgress(90.0, "r/0/4", 3_000.0, 5.0, "turn", excluded, 10.0)
        assertFalse(focus.afterManeuver)
        focus.onProgress(100.0, "r/0/4", 150.0, 2_855.0, "turn", excluded, 10.0)
        focus.onProgress(110.0, "r/0/5", 900.0, 5.0, "turn", excluded, 10.0)
        assertTrue(focus.afterManeuver)
        focus.reset(111.0)
        assertFalse(focus.afterManeuver)
    }

    /** A line north from 0, 0 with a point every 0.0009 degrees, about 100 m. */
    private val line = (0..10).map { GeoPoint(it * 0.0009, 0.0) }
    private val spacing = Geo.meters(0.0, 0.0, 0.0009, 0.0)

    @Test
    fun `distances along a step to its intersections`() {
        assertEquals(100.08, spacing, 0.01)
        // The second target lies beside the line, a meter east of its seventh point.
        val targets = listOf(line[3], GeoPoint(0.0063, 0.00001), line[10])
        val meters = Polyline.distancesTo(line, targets)
        assertEquals(3 * spacing, meters[0], 1e-6)
        assertEquals(7 * spacing, meters[1], 1e-6)
        assertEquals(10 * spacing, meters[2], 1e-6)
    }

    @Test
    fun `a target between two points counts from the nearer one`() {
        val meters = Polyline.distancesTo(line, listOf(GeoPoint(0.00055, 0.0), GeoPoint(0.0013, 0.0)))
        assertEquals(1 * spacing, meters[0], 1e-6)
        assertEquals(1 * spacing, meters[1], 1e-6)
    }

    @Test
    fun `a target on the way back of a hairpin is measured on the way back`() {
        // North for 500 m, then back south two meters to the east.
        val out = (0..5).map { GeoPoint(it * 0.0009, 0.0) }
        val back = (5 downTo 0).map { GeoPoint(it * 0.0009, 0.00002) }
        val hairpin = out + back
        val across = Geo.meters(out[5], back[0])
        val meters = Polyline.distancesTo(hairpin, listOf(out[1], back[3]))
        assertEquals(1 * spacing, meters[0], 1e-6)
        // Up 5, across, and down 3: the point of the way out that lies beside it is not taken.
        assertEquals(5 * spacing + across + 3 * spacing, meters[1], 1e-3)
    }

    @Test
    fun `a line through the same point twice measures each target at its own pass`() {
        // Around a block: north, east, south, west, and north again through the second point.
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0009, 0.0)
        val c = GeoPoint(0.0018, 0.0)
        val d = GeoPoint(0.0018, 0.0009)
        val e = GeoPoint(0.0009, 0.0009)
        val loop = listOf(a, b, c, d, e, b, c)
        val meters = Polyline.distancesTo(loop, listOf(b, d, b))
        assertEquals(1 * spacing, meters[0], 1e-6)
        assertEquals(3 * spacing, meters[1], 1e-3)
        assertEquals(5 * spacing, meters[2], 1e-3)
    }

    @Test
    fun `a line of no points or one point has no length`() {
        assertEquals(0, Polyline.distancesTo(line, emptyList()).size)
        assertEquals(listOf(0.0, 0.0), Polyline.distancesTo(emptyList(), listOf(line[1], line[2])).toList())
        assertEquals(listOf(0.0), Polyline.distancesTo(listOf(line[4]), listOf(line[9])).toList())
    }
}
