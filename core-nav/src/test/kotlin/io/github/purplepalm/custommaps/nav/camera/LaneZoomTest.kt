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
