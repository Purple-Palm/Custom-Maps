package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.camera.DriveViewPlanner.Reason
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.util.Random
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One mile an hour in meters a second, and one mile in meters. */
private const val MPH = 0.44704
private const val MILE = 1609.344

private val camera = CameraTuning.PHONE
private val tuning = DriveViewTuning.PHONE

/** A point of a made-up straight route: where it lies along the route and what it is. */
private data class P(val at: Double, val kind: ApproachKind = ApproachKind.TURN, val focus: FocusKind = FocusKind.TURN, val freeway: Boolean = false)

/** A street turn, a complex street junction, a freeway exit, a freeway lane choice. */
private fun turn(at: Double) = P(at)
private fun complex(at: Double) = P(at, ApproachKind.TURN, FocusKind.COMPLEX)
private fun exit(at: Double) = P(at, ApproachKind.RAMP, FocusKind.NONE, freeway = true)
private fun lanes(at: Double) = P(at, ApproachKind.LANE_CHOICE, FocusKind.NONE, freeway = true)

/** What the planner asked for, when and where. */
private data class Ask(val t: Double, val x: Double, val mode: CameraMode, val reason: Reason)

/**
 * A made-up drive along a straight route from 0, at a speed it is told, with an update of the
 * navigator once a second ([phase] seconds into the drive first). Point i is the maneuver at the end
 * of step i, keyed "route/0/i"; the navigator lists the next two points ahead.
 */
private class Drive(
    var points: List<P>,
    view: DriveView = DriveView.OVERVIEW,
    turnZoom: Boolean = true,
    fromPreview: Boolean = true,
    phase: Double = 0.0,
    var routeId: String = "r1",
) {
    val planner = DriveViewPlanner(camera, tuning)
    var n = 0
    var x = 0.0
    var nextUpdate = phase
    val asks = ArrayList<Ask>()
    var offRoute = false
    var secondsAhead: (P, Double) -> Double = { _, _ -> 0.0 }
    var runUpKey: String? = null

    /** Called after every update with the speed of that update. */
    var onUpdate: (Double) -> Unit = {}

    val t: Double get() = n * DT

    init {
        planner.setDriveView(view, 0.0)
        planner.setTurnZoom(turnZoom, 0.0)
        planner.onGuidanceStarted(0.0, fromPreview)?.let { asks += Ask(0.0, 0.0, it, planner.reason) }
    }

    fun key(i: Int) = "$routeId/0/$i"

    fun ahead(): List<ViewPoint> = points.withIndex().filter { it.value.at > x }.take(2).map { (i, p) ->
        ViewPoint(key(i), p.kind, p.focus, p.freeway, p.at - x, secondsAhead(p, p.at - x))
    }

    fun update(v: Double?) {
        planner.onProgress(ViewInput(t, routeId, x, offRoute, v, ahead(), runUpKey))?.let { asks += Ask(t, x, it, planner.reason) }
        onUpdate(v ?: 0.0)
    }

    /** Drives at [speed] of the place until [untilX] or [seconds] have passed. */
    fun drive(untilX: Double, seconds: Double = Double.POSITIVE_INFINITY, speed: (Double) -> Double) {
        val end = t + seconds
        while (x < untilX && t < end - 1e-9) {
            if (t >= nextUpdate - 1e-9) {
                nextUpdate += 1.0
                update(speed(x))
            }
            x += speed(x) * DT
            n += 1
            // A drive that cannot reach its end fails instead of running on.
            check(n < MAX_STEPS) { "the drive does not get to $untilX" }
        }
    }

    fun driveAt(v: Double, untilX: Double) = drive(untilX) { v }

    /** Stands still for [seconds], with the updates going on. */
    fun stand(seconds: Double) = drive(Double.POSITIVE_INFINITY, seconds) { 0.0 }

    fun asksAfter(tFrom: Double) = asks.filter { it.t > tFrom }

    companion object {
        const val DT = 0.1
        const val MAX_STEPS = 3_000_000
    }
}

/** When the Overview drive view leaves the overview and when it goes back out. */
class DriveViewPlanTest {
    private val phases = (0 until 10).map { it / 10.0 }

    @Test
    fun `every maneuver type and modifier has its kind of point`() {
        for (type in ManeuverType.values()) {
            for (modifier in TurnModifier.values().toList() + listOf<TurnModifier?>(null)) {
                val turns = modifier != null && modifier != TurnModifier.STRAIGHT
                val expected = when (type) {
                    ManeuverType.DEPART, ManeuverType.NOTIFICATION -> null
                    ManeuverType.NEW_NAME -> if (turns) ApproachKind.TURN else null
                    ManeuverType.CONTINUE -> if (turns) ApproachKind.RAMP else null
                    ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK, ManeuverType.MERGE -> ApproachKind.RAMP
                    else -> ApproachKind.TURN
                }
                assertEquals(expected, DriveViewRule.approachKind(type, modifier), "$type $modifier")
            }
        }
        // The way out of a roundabout is a point of its own, a few metres after the way in.
        assertEquals(ApproachKind.TURN, DriveViewRule.approachKind(ManeuverType.EXIT_ROUNDABOUT, TurnModifier.RIGHT))
        assertEquals(ApproachKind.TURN, DriveViewRule.approachKind(ManeuverType.EXIT_ROTARY, null))
        assertEquals(ApproachKind.TURN, DriveViewRule.approachKind(ManeuverType.ARRIVE, null))
    }

    @Test
    fun `the view leaves the overview as the design's table says, from 20 to 75 mph`() {
        // Metres before the point: a street turn, a complex street junction, a freeway exit or lane choice.
        val table = mapOf(
            20 to Triple(316.0, 356.0, 445.0),
            30 to Triple(354.0, 394.0, 467.0),
            45 to Triple(412.0, 473.0, 604.0),
            55 to Triple(504.0, 578.0, 738.0),
            65 to Triple(567.0, 647.0, 872.0),
            75 to Triple(605.0, 685.0, 1_006.0),
        )
        for ((mph, row) in table) {
            val v = mph * MPH
            fun leaves(p: P) = DriveViewRule.approachMeters(ViewPoint("k", p.kind, p.focus, p.freeway, 5_000.0), v, camera, tuning)
            assertEquals(row.first, leaves(turn(0.0)), 0.6, "turn at $mph mph")
            assertEquals(row.second, leaves(complex(0.0)), 0.6, "complex at $mph mph")
            assertEquals(row.third, leaves(exit(0.0)), 0.6, "exit at $mph mph")
            assertEquals(row.third, leaves(lanes(0.0)), 0.6, "lane choice at $mph mph")
        }
        // 30 mph at a turn: 354 m, with no screen cap (a cap would have given 180 m and 294 m).
        val street = ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 5_000.0)
        assertEquals(240.0 + 30 * MPH * 3.5 + 30 * MPH * 5.0, DriveViewRule.approachMeters(street, 30 * MPH, camera, tuning), 1e-9)
        // A fork or ramp off a street takes the complex window; a street merge, which the camera does not tilt for, the turn's.
        val fork = ViewPoint("k", ApproachKind.RAMP, FocusKind.COMPLEX, false, 5_000.0)
        val merge = ViewPoint("k", ApproachKind.RAMP, FocusKind.NONE, false, 5_000.0)
        assertEquals(394.0, DriveViewRule.approachMeters(fork, 30 * MPH, camera, tuning), 0.6)
        assertEquals(354.0, DriveViewRule.approachMeters(merge, 30 * MPH, camera, tuning), 0.6)
    }

    @Test
    fun `a standing car or one of unknown speed counts as 3 m per s, and the route's speed counts when it is faster`() {
        val point = ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 500.0)
        assertEquals(3.0, DriveViewRule.planSpeed(point, null), 1e-9)
        assertEquals(3.0, DriveViewRule.planSpeed(point, 0.0), 1e-9)
        assertEquals(3.0, DriveViewRule.planSpeed(point, Double.NaN), 1e-9)
        assertEquals(12.0, DriveViewRule.planSpeed(point, 12.0), 1e-9)
        // At a red light the route still says the point is 25 s away: 20 m/s.
        assertEquals(20.0, DriveViewRule.planSpeed(point.copy(secondsAhead = 25.0), 0.0), 1e-9)
        // The larger speed counts: only ever earlier.
        assertEquals(30.0, DriveViewRule.planSpeed(point.copy(secondsAhead = 25.0), 30.0), 1e-9)
        assertEquals(DriveViewRule.approachMeters(point, 3.0, camera, tuning), DriveViewRule.approachMeters(point, null, camera, tuning), 1e-9)
    }

    @Test
    fun `the planner's window is never shorter than a capped camera's`() {
        val random = Random(22)
        repeat(2_000) {
            val focus = if (random.nextBoolean()) FocusKind.TURN else FocusKind.COMPLEX
            val v = random.nextDouble() * 45.0
            val cap = when (random.nextInt(4)) {
                0 -> Double.NaN
                1 -> Double.POSITIVE_INFINITY
                else -> random.nextDouble() * 600.0 - 50.0
            }
            val point = ViewPoint("k", ApproachKind.TURN, focus, false, 9_000.0)
            val schedule = DriveViewRule.scheduleMeters(point, DriveViewRule.planSpeed(point, v), camera)
            val cameraWindow = FocusWindow.windowMeters(focus, v, cap, camera)
            assertTrue(schedule >= cameraWindow - 1e-9, "$focus $v $cap: $schedule < $cameraWindow")
            assertTrue(DriveViewRule.approachMeters(point, v, camera, tuning) >= cameraWindow + 5.0 * maxOf(v, 3.0) - 1e-9)
        }
    }

    @Test
    fun `side by side with the camera the view is down at least 4,9 s before the tilt or the lanes' close view starts`() {
        for (v in (3..40).map { it.toDouble() }) {
            for (ph in phases) {
                // A street turn: the real maneuver focus, uncapped as the earliest camera.
                val focus = ManeuverFocus(camera)
                var latchAt = Double.NaN
                val d = Drive(listOf(turn(6_000.0), turn(30_000.0)), phase = ph)
                d.onUpdate = { speed ->
                    val i = d.points.indexOfFirst { it.at > d.x }
                    val stepStart = if (i <= 0) 0.0 else d.points[i - 1].at
                    focus.onProgress(d.t, d.key(i), d.points[i].at - d.x, d.x - stepStart, ManeuverFacts(d.key(i), FocusKind.TURN), Double.POSITIVE_INFINITY, speed)
                    if (focus.goal == 1.0 && latchAt.isNaN()) latchAt = d.t
                }
                d.driveAt(v, 5_990.0)
                val down = d.asks.first { it.mode == CameraMode.FOLLOWING }
                assertTrue(latchAt - down.t >= 4.9, "turn at $v m/s phase $ph: down ${down.t}, tilt $latchAt")

                // A freeway exit whose lanes count: the real lanes' close view.
                val lanesFocus = LaneFocus(camera)
                var inAt = Double.NaN
                val e = Drive(listOf(exit(9_000.0), turn(40_000.0)), phase = ph)
                e.onUpdate = { speed ->
                    val choices = listOf(LaneChoice(e.key(0), 9_000.0 - e.x, 4, 1, 0, onFreeway = true, atManeuver = true))
                    lanesFocus.onProgress(e.t, e.x, choices, speed)
                    if (lanesFocus.goal == 1.0 && inAt.isNaN()) inAt = e.t
                }
                e.driveAt(v, 8_990.0)
                val exitDown = e.asks.first { it.mode == CameraMode.FOLLOWING }
                assertTrue(inAt - exitDown.t >= 4.9, "exit at $v m/s phase $ph: down ${exitDown.t}, lanes $inAt")
            }
        }
    }

    @Test
    fun `braking to a stop, and a red light, never send the view back out before the point`() {
        // Down at 45 mph, then braking hard to a stop 100 m before the turn and standing a minute.
        val d = Drive(listOf(turn(3_000.0), turn(20_000.0)))
        d.drive(2_900.0) { x -> if (x < 2_700.0) 45 * MPH else maxOf(0.5, 45 * MPH * (2_900.0 - x) / 200.0) }
        d.stand(60.0)
        assertEquals(listOf(CameraMode.OVERVIEW, CameraMode.FOLLOWING), d.asks.map { it.mode })
        assertTrue(d.planner.near)
        // A car waiting at a red light 250 m before the turn: the route says 25 s, so the view is down before the light turns green.
        val light = Drive(listOf(turn(3_000.0), turn(20_000.0)))
        light.secondsAhead = { _, meters -> meters / 10.0 }
        light.driveAt(13.0, 2_750.0)
        light.stand(30.0)
        val down = light.asks.first { it.mode == CameraMode.FOLLOWING }
        assertTrue(down.x < 2_750.0 && 3_000.0 - down.x > DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 500.0), 13.0, camera, tuning) - 13.0)
    }

    @Test
    fun `the overview stands after the way out as the design's table says`() {
        // Seconds of standing overview after the way out, at a steady speed; null stays normal.
        val table = listOf(
            Triple(1.0, null, null), Triple(1.2, null, null), Triple(1.25, 120.0, 76.0), Triple(1.5, 150.0, 96.0), Triple(2.0, 210.0, 136.0),
        )
        val freeway = listOf(
            Triple(1.0, null, null), Triple(1.2, null, null), Triple(1.25, 35.0, null), Triple(1.5, 49.0, 38.0), Triple(2.0, 77.0, 62.0),
        )
        for (ph in phases) {
            for ((gap, at30, at45) in table) {
                assertStand(at30, standSeconds(30 * MPH, gap * MILE, exit = false, ph), "turn 30 mph $gap mi")
                assertStand(at45, standSeconds(45 * MPH, gap * MILE, exit = false, ph), "turn 45 mph $gap mi")
            }
            for ((gap, at65, at75) in freeway) {
                assertStand(at65, standSeconds(65 * MPH, gap * MILE, exit = true, ph), "exit 65 mph $gap mi")
                assertStand(at75, standSeconds(75 * MPH, gap * MILE, exit = true, ph), "exit 75 mph $gap mi")
            }
        }
    }

    private fun assertStand(expected: Double?, got: Double?, what: String) {
        if (expected == null) {
            assertNull(got, what)
        } else {
            assertTrue(got != null && abs(got - expected) <= 1.5, "$what: $got s, the design $expected s")
        }
    }

    /** Seconds from the end of the way out after a turn to the way back in for the next point, [gap] on; null when it stays normal. */
    private fun standSeconds(v: Double, gap: Double, exit: Boolean, phase: Double): Double? {
        val d = Drive(listOf(turn(3_000.0), if (exit) exit(3_000.0 + gap) else turn(3_000.0 + gap), turn(3_000.0 + gap + 20_000.0)), phase = phase)
        d.driveAt(v, 3_000.0 + gap + 10.0)
        val out = d.asks.firstOrNull { it.mode == CameraMode.OVERVIEW && it.x > 3_000.0 } ?: return null
        val back = d.asks.firstOrNull { it.mode == CameraMode.FOLLOWING && it.t > out.t } ?: return null
        return back.t - out.t - camera.longTransitionSeconds
    }

    @Test
    fun `the way out comes on or before the update the tilt starts back up, flat to flat`() {
        for (v in listOf(8.0, 13.4, 20.1, 29.0, 40.0)) {
            for (ph in phases) {
                val focus = ManeuverFocus(camera)
                var upAt = Double.NaN
                var wasDown = false
                val d = Drive(listOf(turn(3_000.0), turn(9_000.0)), phase = ph)
                d.onUpdate = { speed ->
                    val i = d.points.indexOfFirst { it.at > d.x }
                    val stepStart = if (i <= 0) 0.0 else d.points[i - 1].at
                    focus.onProgress(d.t, d.key(i), d.points[i].at - d.x, d.x - stepStart, ManeuverFacts(d.key(i), FocusKind.TURN), Double.POSITIVE_INFINITY, speed)
                    if (focus.goal == 1.0) wasDown = true
                    if (wasDown && focus.goal == 0.0 && upAt.isNaN()) upAt = d.t
                }
                d.driveAt(v, 3_400.0)
                val out = d.asks.first { it.mode == CameraMode.OVERVIEW && it.x > 3_000.0 }
                assertEquals(Reason.OPEN_ROAD, out.reason)
                assertTrue(out.t <= upAt, "$v m/s phase $ph: out ${out.t}, tilt up $upAt")
                assertTrue(out.x >= 3_000.0 + camera.holdAfterManeuverMeters, "out at ${out.x}")
            }
        }
    }

    @Test
    fun `two turns 250 m apart are one way in, and the view goes out only after the second`() {
        for (ph in phases) {
            val d = Drive(listOf(turn(3_000.0), turn(3_250.0), turn(9_000.0)), phase = ph)
            d.driveAt(30 * MPH, 3_400.0)
            val moves = d.asksAfter(0.0)
            assertEquals(listOf(CameraMode.FOLLOWING, CameraMode.OVERVIEW), moves.map { it.mode }, "phase $ph: $moves")
            assertTrue(moves[1].x > 3_250.0)
        }
    }

    @Test
    fun `in a roundabout the way out is the turn, and nothing moves inside the circle`() {
        for (exitAfter in listOf(15.0, 40.0, 120.0)) {
            for (ph in phases) {
                val d = Drive(listOf(complex(3_000.0), turn(3_000.0 + exitAfter), turn(3_000.0 + exitAfter + 2 * MILE)), phase = ph)
                d.driveAt(20 * MPH, 3_000.0 + exitAfter + 200.0)
                val out = d.asks.single { it.mode == CameraMode.OVERVIEW && it.t > 0.0 }
                assertTrue(out.x >= 3_000.0 + exitAfter + camera.holdAfterManeuverMeters, "exit $exitAfter m phase $ph: out at ${out.x}")
            }
        }
    }

    @Test
    fun `an exit with a lane choice 1,2 km on stays normal, and a lane choice is held 30 m and 1,5 s`() {
        val d = Drive(listOf(exit(5_000.0), lanes(6_200.0), turn(30_000.0)))
        d.driveAt(29.0, 5_100.0)
        assertEquals(Reason.GAP_TOO_SHORT, d.planner.reason)
        // The lane choice's own approach then opens with the view down: no move.
        d.driveAt(29.0, 6_100.0)
        assertEquals(Reason.APPROACH, d.planner.reason)
        assertEquals(listOf(CameraMode.FOLLOWING), d.asksAfter(0.0).map { it.mode })
        // Past the lane choice, the next point far: out once the car is 30 m and 1.5 s past it.
        d.driveAt(29.0, 6_400.0)
        val out = d.asks.last()
        assertEquals(CameraMode.OVERVIEW, out.mode)
        assertTrue(out.x >= 6_200.0 + camera.laneHoldMeters && out.x < 6_200.0 + camera.laneHoldMeters + 29.0 * 2.0, "out at ${out.x}")
    }

    @Test
    fun `1,5 miles or more always goes out, and below it the overview must stand 30 s`() {
        // 85 mph, a 1.5-mile gap to an exit: about 29 s of overview, out all the same.
        assertTrue(standSeconds(85 * MPH, 1.5 * MILE, exit = true, 0.0)!! in 27.0..31.0)
        // 75 mph and 1.25 miles to an exit: under 30 s of overview, the view stays normal.
        val d = Drive(listOf(turn(3_000.0), exit(3_000.0 + 1.25 * MILE), turn(30_000.0)))
        d.driveAt(75 * MPH, 3_200.0)
        assertEquals(Reason.STAY_TOO_SHORT, d.planner.reason)
        assertTrue(d.planner.standSeconds < 30.0 && d.planner.gapMeters >= tuning.returnMeters)
        assertEquals(CameraMode.FOLLOWING, d.planner.wanted)
    }

    @Test
    fun `off the route a single update moves nothing, two do, and back on the route it waits for 8 s of normal view`() {
        val d = Drive(listOf(turn(1.4 * MILE + 3_000.0), turn(30_000.0)))
        d.driveAt(13.0, 2_000.0)
        assertEquals(CameraMode.OVERVIEW, d.planner.wanted)
        d.offRoute = true
        d.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, d.planner.wanted, "one update off the route")
        d.offRoute = false
        d.drive(Double.POSITIVE_INFINITY, 5.0) { 13.0 }
        assertTrue(d.asks.none { it.mode == CameraMode.FOLLOWING && it.t > 0.0 }, "a blip moves nothing")
        d.offRoute = true
        d.drive(Double.POSITIVE_INFINITY, 2.0) { 13.0 }
        val down = d.asks.last()
        assertEquals(CameraMode.FOLLOWING, down.mode)
        assertEquals(Reason.OFF_ROUTE, down.reason)
        assertFalse(d.planner.near)
        // Back on the route with the next turn 1.4 miles from the car: out, but not before the normal view stood 8 s.
        d.offRoute = false
        d.drive(Double.POSITIVE_INFINITY, 20.0) { 13.0 }
        val out = d.asks.last()
        assertEquals(CameraMode.OVERVIEW, out.mode)
        assertTrue(out.t - down.t >= tuning.minNormalSeconds, "down ${down.t}, out ${out.t}")
        assertEquals(2, d.asksAfter(0.0).size)
    }

    @Test
    fun `after a new route the plan waits 30 s before it goes out by itself, also through a burst of reroutes`() {
        // The settle is the tuning's own number.
        assertEquals(30.0, tuning.rerouteSettleSeconds)
        // A deviation: off the route, then a new route whose next turn is far.
        val d = Drive(listOf(turn(4_000.0), turn(30_000.0)))
        d.driveAt(13.0, 1_000.0)
        d.offRoute = true
        d.drive(Double.POSITIVE_INFINITY, 3.0) { 13.0 }
        val down = d.asks.last()
        assertEquals(CameraMode.FOLLOWING, down.mode)
        // Three reroutes within 20 s, each a new route with its next turn 3 km away.
        val reroutedAt = ArrayList<Double>()
        for (k in 2..4) {
            d.offRoute = false
            d.routeId = "r$k"
            d.points = listOf(turn(d.x + 3_000.0), turn(d.x + 30_000.0))
            d.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
            reroutedAt += d.t
            assertEquals(Reason.NEW_ROUTE, d.planner.reason)
            d.drive(Double.POSITIVE_INFINITY, 6.0) { 13.0 }
        }
        d.drive(Double.POSITIVE_INFINITY, 40.0) { 13.0 }
        val out = d.asks.last()
        assertEquals(CameraMode.OVERVIEW, out.mode)
        assertEquals(Reason.OPEN_ROAD, out.reason)
        // One move down and one out for the whole episode, the way out 30 s after the last new route.
        assertEquals(listOf(CameraMode.FOLLOWING, CameraMode.OVERVIEW), d.asksAfter(0.0).map { it.mode })
        assertTrue(out.t - (reroutedAt.last() - 1.0) >= tuning.rerouteSettleSeconds, "last route ${reroutedAt.last()}, out ${out.t}")
        assertTrue(out.t - (reroutedAt.last() - 1.0) < tuning.rerouteSettleSeconds + 1.5)
    }

    @Test
    fun `a normal view kept for a point that vanished with a new route is decided again by the new route`() {
        // Kept normal between close points (the gap too short), then a reroute puts the next turn 4 km away.
        val d = Drive(listOf(turn(3_000.0), turn(4_000.0), turn(30_000.0)))
        d.driveAt(13.0, 3_100.0)
        assertEquals(Reason.GAP_TOO_SHORT, d.planner.reason)
        d.routeId = "r2"
        d.points = listOf(turn(d.x + 4_000.0), turn(d.x + 30_000.0))
        d.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        assertEquals(Reason.NEW_ROUTE, d.planner.reason)
        d.drive(Double.POSITIVE_INFINITY, 35.0) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, d.asks.last().mode)
        // A new route while the overview stands moves nothing, unless a point of it is within its approach.
        val o = Drive(listOf(turn(5_000.0), turn(30_000.0)))
        o.driveAt(13.0, 1_000.0)
        o.routeId = "r2"
        o.points = listOf(turn(o.x + 2_000.0))
        o.drive(Double.POSITIVE_INFINITY, 3.0) { 13.0 }
        assertTrue(o.asksAfter(0.0).isEmpty())
        o.routeId = "r3"
        o.points = listOf(turn(o.x + 300.0))
        o.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        assertEquals(CameraMode.FOLLOWING, o.asks.last().mode)
        assertEquals(Reason.APPROACH, o.asks.last().reason)
    }

    @Test
    fun `a start from the preview keeps the overview unless a point is near, a start without one decides at the first update`() {
        val far = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        assertEquals(CameraMode.OVERVIEW, far.asks.single().mode)
        far.driveAt(13.0, 500.0)
        assertTrue(far.asksAfter(0.0).isEmpty())
        // One glide down when the first maneuver is near.
        val near = Drive(listOf(turn(200.0), turn(30_000.0)))
        near.driveAt(13.0, 20.0)
        assertEquals(listOf(CameraMode.OVERVIEW, CameraMode.FOLLOWING), near.asks.map { it.mode })
        // A direct Start, or a map made anew in the drive: the car at once, and the first update decides with no wait.
        val direct = Drive(listOf(turn(3_000.0), turn(30_000.0)), fromPreview = false)
        assertEquals(CameraMode.FOLLOWING, direct.asks.single().mode)
        direct.drive(Double.POSITIVE_INFINITY, 0.5) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, direct.asks.last().mode)
        assertEquals(0.0, direct.asks.last().t)
        // A second start answers nothing, and the next guidance starts afresh.
        assertNull(direct.planner.onGuidanceStarted(1.0, fromPreview = false))
        direct.planner.onGuidanceEnded()
        assertEquals(CameraMode.FOLLOWING, direct.planner.wanted)
        assertEquals(CameraMode.FOLLOWING, direct.planner.onGuidanceStarted(2.0, fromPreview = false))
    }

    @Test
    fun `the Overview turned on far from a point shows at once, near one it waits for the first moment it can stand`() {
        val d = Drive(listOf(turn(3_000.0), turn(4_000.0), turn(5_500.0), turn(30_000.0)), view = DriveView.NORMAL, fromPreview = false)
        d.driveAt(13.0, 1_000.0)
        // The silent plan is out already: the overview at once.
        assertEquals(CameraMode.OVERVIEW, d.planner.setDriveView(DriveView.OVERVIEW, d.t))
        assertEquals(Reason.OPEN_ROAD, d.planner.reason)
        assertEquals(CameraMode.FOLLOWING, d.planner.setDriveView(DriveView.NORMAL, d.t))
        // Inside the approach of the first turn: nothing moves, the tap waits.
        d.driveAt(13.0, 2_800.0)
        assertTrue(d.planner.near)
        assertNull(d.planner.setDriveView(DriveView.OVERVIEW, d.t))
        assertEquals(Reason.BUTTON_TOO_CLOSE, d.planner.reason)
        // Past it, the next turns 1 km and 2 km on: the gap rule alone would keep the view normal, the tap shows the overview.
        d.driveAt(13.0, 3_200.0)
        val out = d.asks.last()
        assertEquals(CameraMode.OVERVIEW, out.mode)
        assertEquals(Reason.BUTTON, out.reason)
        assertTrue(out.x > 3_000.0)
        // Honoured once: after the next turn the gap rule holds again.
        d.driveAt(13.0, 4_100.0)
        assertEquals(Reason.GAP_TOO_SHORT, d.planner.reason)
        assertEquals(CameraMode.FOLLOWING, d.planner.wanted)
        // Where the gap rule keeps the view normal, far from the next approach, a tap shows the overview at once.
        assertEquals(CameraMode.FOLLOWING, d.planner.setDriveView(DriveView.NORMAL, d.t) ?: CameraMode.FOLLOWING)
        assertEquals(CameraMode.OVERVIEW, d.planner.setDriveView(DriveView.OVERVIEW, d.t))
        assertEquals(Reason.BUTTON, d.planner.reason)
    }

    @Test
    fun `the Overview turned on while the silent plan is out waits where the overview could not stand 10 s`() {
        // In Normal the silent plan is out on the open road; the next approach opens 1, 5, 9 or 9.9 s of overview
        // away: the button fills, the car stays followed through that turn, and the overview comes after it.
        for (seconds in listOf(1.0, 5.0, 9.0, 9.9)) {
            val (d, next) = shortOfApproach(seconds, afterTurn = false, view = DriveView.NORMAL, fromPreview = false)
            assertEquals(CameraMode.OVERVIEW, d.planner.plan, "$seconds s: the silent plan is out")
            assertEquals(seconds, DriveViewRule.overviewSeconds(d.planner.point!!, 13.0, camera, tuning), 1e-6)
            assertNull(d.planner.setDriveView(DriveView.OVERVIEW, d.t), "$seconds s")
            assertEquals(Reason.BUTTON_TOO_CLOSE, d.planner.reason)
            assertEquals(CameraMode.FOLLOWING, d.planner.wanted)
            val before = d.asks.size
            d.driveAt(13.0, next + 100.0)
            val after = d.asks.drop(before)
            assertTrue(after.none { it.mode == CameraMode.OVERVIEW && it.x < next }, "$seconds s: $after")
            assertEquals(CameraMode.OVERVIEW, after.single().mode, "$seconds s: $after")
            assertEquals(Reason.BUTTON, after.single().reason)
        }
        // 10.1 or 11 s: the overview at once, with the silent plan's own reason.
        for (seconds in listOf(10.1, 11.0)) {
            val (d, _) = shortOfApproach(seconds, afterTurn = false, view = DriveView.NORMAL, fromPreview = false)
            assertEquals(CameraMode.OVERVIEW, d.planner.setDriveView(DriveView.OVERVIEW, d.t), "$seconds s")
            assertEquals(Reason.OPEN_ROAD, d.planner.reason)
        }
        // Back to Normal before the turn: the car, and the waiting tap is forgotten.
        val (back, next) = shortOfApproach(5.0, afterTurn = false, view = DriveView.NORMAL, fromPreview = false)
        assertNull(back.planner.setDriveView(DriveView.OVERVIEW, back.t))
        assertNull(back.planner.setDriveView(DriveView.NORMAL, back.t))
        back.driveAt(13.0, next + 100.0)
        assertEquals(Reason.NORMAL_VIEW, back.planner.reason)
        assertEquals(Reason.OPEN_ROAD, back.planner.planReason)
    }

    @Test
    fun `a route the driver picked is judged as a tap on the Overview, with no wait for reroutes`() {
        val d = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        d.driveAt(13.0, 1_000.0)
        d.planner.onRouteChosenByDriver()
        d.routeId = "r2"
        d.points = listOf(turn(d.x + 3_000.0), turn(d.x + 30_000.0))
        d.drive(Double.POSITIVE_INFINITY, 2.0) { 13.0 }
        assertTrue(d.asksAfter(0.0).isEmpty())
        assertEquals(Reason.DRIVER_ROUTE, d.planner.reason)
        // Picked while the view was down between close points: out at once, the new route's next turn far.
        val s = Drive(listOf(turn(3_000.0), turn(4_000.0), turn(30_000.0)))
        s.driveAt(13.0, 3_100.0)
        assertEquals(CameraMode.FOLLOWING, s.planner.wanted)
        s.planner.onRouteChosenByDriver()
        s.routeId = "r2"
        s.points = listOf(turn(s.x + 3_000.0))
        s.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, s.asks.last().mode)
        assertEquals(Reason.DRIVER_ROUTE, s.asks.last().reason)
    }

    @Test
    fun `in the Normal drive view the plan runs silently and equals the Overview's`() {
        val random = Random(7)
        repeat(40) {
            val points = randomRoute(random)
            val normal = Drive(points, view = DriveView.NORMAL)
            val overview = Drive(points)
            val v = 5.0 + random.nextDouble() * 25.0
            var x = 0.0
            while (x < points.last().at - 50.0) {
                x += 400.0
                normal.driveAt(v, x)
                overview.driveAt(v, x)
                assertEquals(overview.planner.wanted, normal.planner.plan)
                assertEquals(overview.planner.planReason, normal.planner.planReason)
                assertEquals(overview.planner.near, normal.planner.near)
                assertEquals(CameraMode.FOLLOWING, normal.planner.wanted)
                assertEquals(Reason.NORMAL_VIEW, normal.planner.reason)
                assertTrue(normal.planner.silent)
            }
            assertTrue(normal.asksAfter(0.0).isEmpty(), "the Normal view never asks the camera after the start")
        }
    }

    @Test
    fun `near opens once at the approach's edge, whatever the speed does there`() {
        val d = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        var flips = 0
        var last = false
        d.onUpdate = { if (d.planner.near != last) { flips += 1; last = d.planner.near } }
        // The matched speed swings between 0 and 8 m/s at the approach's edge, which moves it by up to 34 m.
        var k = 0
        d.drive(2_960.0) { x -> if (x < 2_500.0) 10.0 else if ((k++ / 20) % 2 == 0) 8.0 else 0.5 }
        assertEquals(1, flips)
        assertTrue(d.planner.near)
        d.driveAt(10.0, 3_200.0)
        assertEquals(2, flips)
        assertFalse(d.planner.near)
    }

    @Test
    fun `the plain Overview is out from every start to the end, and its silent plan is the smart one`() {
        val random = Random(31)
        repeat(60) { round ->
            val points = randomRoute(random)
            val plain = Drive(points, turnZoom = false, fromPreview = round % 2 == 0)
            val smart = Drive(points, fromPreview = round % 2 == 0)
            assertEquals(CameraMode.OVERVIEW, plain.asks.single().mode, "the start's answer")
            val v = 4.0 + random.nextDouble() * 30.0
            var x = 0.0
            while (x < points.last().at - 50.0) {
                x += 250.0
                // Now and then off the route, or a tap into the Overview, the same for both.
                if (random.nextInt(8) == 0) {
                    plain.offRoute = true; smart.offRoute = true
                    plain.drive(Double.POSITIVE_INFINITY, 3.0) { v }; smart.drive(Double.POSITIVE_INFINITY, 3.0) { v }
                    plain.offRoute = false; smart.offRoute = false
                }
                plain.driveAt(v, x)
                smart.driveAt(v, x)
                assertEquals(CameraMode.OVERVIEW, plain.planner.wanted)
                assertEquals(Reason.PLAIN, plain.planner.reason)
                assertEquals(smart.planner.wanted, plain.planner.plan)
                assertEquals(smart.planner.near, plain.planner.near)
            }
            assertTrue(plain.asks.none { it.mode == CameraMode.FOLLOWING })
        }
        // A tap into the plain Overview near a turn: the overview at once, no wait.
        val d = Drive(listOf(turn(3_000.0), turn(30_000.0)), view = DriveView.NORMAL, turnZoom = false, fromPreview = false)
        d.driveAt(13.0, 2_800.0)
        assertEquals(CameraMode.OVERVIEW, d.planner.setDriveView(DriveView.OVERVIEW, d.t))
        assertEquals(Reason.PLAIN, d.planner.reason)
    }

    @Test
    fun `Zoom in before turns off while down shows the overview at once, on inside an approach the car at once`() {
        val d = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        d.driveAt(13.0, 2_800.0)
        assertEquals(CameraMode.FOLLOWING, d.planner.wanted)
        assertEquals(CameraMode.OVERVIEW, d.planner.setTurnZoom(false, d.t))
        assertEquals(CameraMode.FOLLOWING, d.planner.setTurnZoom(true, d.t))
        // On between close turns, where the plain Overview had the view out: out until the next approach.
        val s = Drive(listOf(turn(3_000.0), turn(4_000.0), turn(30_000.0)), turnZoom = false)
        s.driveAt(13.0, 3_100.0)
        assertEquals(Reason.GAP_TOO_SHORT, s.planner.planReason)
        assertNull(s.planner.setTurnZoom(true, s.t))
        assertEquals(CameraMode.OVERVIEW, s.planner.wanted)
        assertEquals(Reason.PLAIN, s.planner.reason)
        s.driveAt(13.0, 3_700.0)
        assertEquals(CameraMode.FOLLOWING, s.asks.last().mode)
        assertEquals(Reason.APPROACH, s.asks.last().reason)
        // In the Normal drive view the switch changes nothing.
        val n = Drive(listOf(turn(3_000.0)), view = DriveView.NORMAL)
        assertNull(n.planner.setTurnZoom(false, 0.0))
        assertNull(n.planner.setTurnZoom(true, 0.0))
    }

    @Test
    fun `a hold's end brings the overview in the smart Overview, the car only when a turn is near`() {
        // Kept normal between turns 1 km apart, no approach within 10 s: the overview, and down again when the next approach opens.
        val d = Drive(listOf(turn(3_000.0), turn(4_000.0), turn(30_000.0)))
        d.driveAt(13.0, 3_100.0)
        assertEquals(Reason.GAP_TOO_SHORT, d.planner.reason)
        assertEquals(CameraMode.OVERVIEW, d.planner.onRecenter(d.t))
        assertEquals(Reason.RECENTERED, d.planner.reason)
        d.driveAt(13.0, 3_800.0)
        assertEquals(CameraMode.FOLLOWING, d.asks.last().mode)
        assertTrue(4_000.0 - d.asks.last().x <= d.planner.approachMeters + 13.0)
        // The overview could stand 1, 5 or 9 s before the next approach: the car, and no overview before the point.
        for (seconds in listOf(1.0, 5.0, 9.0, 11.0)) {
            val v = 13.0
            val approach = DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 9_000.0), v, camera, tuning)
            val gapToApproach = (seconds + camera.longTransitionSeconds) * v
            val r = Drive(listOf(turn(3_000.0), turn(3_000.0 + 400.0 + approach + gapToApproach), turn(40_000.0)))
            r.driveAt(v, 3_400.0)
            // Exactly at the update where the overview could stand that long.
            val stand = DriveViewRule.overviewSeconds(r.planner.point!!, v, camera, tuning)
            assertTrue(abs(stand - seconds) < 0.7, "$seconds: $stand")
            val back = r.planner.onRecenter(r.t)
            if (seconds < tuning.buttonOverviewSeconds) {
                assertEquals(CameraMode.FOLLOWING, back, "$seconds s")
                assertEquals(Reason.RECENTER_TOO_CLOSE, r.planner.reason)
                val before = r.asks.size
                r.driveAt(v, 3_000.0 + 400.0 + approach + gapToApproach + 100.0)
                assertTrue(r.asks.drop(before).none { it.mode == CameraMode.OVERVIEW && it.x < 3_000.0 + 400.0 + approach + gapToApproach })
            } else {
                assertEquals(CameraMode.OVERVIEW, back, "$seconds s")
            }
        }
        // Inside an approach, and off the route: the car.
        val near = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        near.driveAt(13.0, 2_800.0)
        assertEquals(CameraMode.FOLLOWING, near.planner.onRecenter(near.t))
        val off = Drive(listOf(turn(3_000.0), turn(30_000.0)))
        off.driveAt(13.0, 1_000.0)
        off.offRoute = true
        off.drive(Double.POSITIVE_INFINITY, 3.0) { 13.0 }
        assertEquals(CameraMode.FOLLOWING, off.planner.onRecenter(off.t))
        // The Normal drive view: the car; the plain Overview: the overview.
        assertEquals(CameraMode.FOLLOWING, Drive(listOf(turn(3_000.0)), view = DriveView.NORMAL).planner.onRecenter(0.0))
        assertEquals(CameraMode.OVERVIEW, Drive(listOf(turn(3_000.0)), turnZoom = false).planner.onRecenter(0.0))
    }

    @Test
    fun `a hold's end where the plan is out brings the car when the overview could not stand 10 s`() {
        // Out from the preview with no point passed; the overview could stand 1, 5, 9 or 9.9 s more before the next
        // approach: the car until that turn, as between close turns, and no flight out and straight back down.
        for (seconds in listOf(1.0, 5.0, 9.0, 9.9)) {
            val (d, next) = shortOfApproach(seconds, afterTurn = false)
            assertEquals(CameraMode.OVERVIEW, d.planner.wanted, "$seconds s: out before the hold's end")
            assertEquals(seconds, DriveViewRule.overviewSeconds(d.planner.point!!, 13.0, camera, tuning), 1e-6)
            assertEquals(CameraMode.FOLLOWING, d.planner.onRecenter(d.t), "$seconds s")
            assertEquals(Reason.RECENTER_TOO_CLOSE, d.planner.reason)
            val before = d.asks.size
            d.driveAt(13.0, next + 100.0)
            assertTrue(d.asks.drop(before).none { it.mode == CameraMode.OVERVIEW && it.x < next }, "$seconds s: ${d.asks.drop(before)}")
            // After that turn the mode's own rules: the next turn is far, out.
            assertEquals(CameraMode.OVERVIEW, d.planner.wanted, "$seconds s: out after the turn")
            assertEquals(Reason.OPEN_ROAD, d.planner.reason)
        }
        // 10.1 or 11 s: the overview stays, with the plan's own reason.
        for (seconds in listOf(10.1, 11.0)) {
            val (d, _) = shortOfApproach(seconds, afterTurn = false)
            assertEquals(CameraMode.OVERVIEW, d.planner.onRecenter(d.t), "$seconds s")
            assertEquals(Reason.START, d.planner.reason)
        }
        // The same out on the open road, past a turn far behind: a short overview brings the car, a long one stays.
        val open = Drive(listOf(turn(1_000.0), turn(5_000.0), turn(40_000.0)))
        open.driveAt(13.0, 4_550.0)
        assertEquals(Reason.OPEN_ROAD, open.planner.reason)
        assertTrue(DriveViewRule.overviewSeconds(open.planner.point!!, 13.0, camera, tuning) in 0.0..10.0)
        assertEquals(CameraMode.FOLLOWING, open.planner.onRecenter(open.t))
        assertEquals(Reason.RECENTER_TOO_CLOSE, open.planner.reason)
        // In the Normal drive view the car either way, the silent plan as the Overview's would be.
        val (normal, _) = shortOfApproach(5.0, afterTurn = false, view = DriveView.NORMAL)
        assertEquals(CameraMode.FOLLOWING, normal.planner.onRecenter(normal.t))
        assertEquals(Reason.RECENTER_TOO_CLOSE, normal.planner.planReason)
        assertTrue(normal.planner.silent)
    }

    /**
     * A drive at 13 m/s from 0 whose last update, at 2,990 m, leaves the overview [seconds] to stand before the next
     * turn's approach opens: with no point passed ([afterTurn] false), or kept normal 400 m past a turn with the gap too
     * short. Returns the drive and where that next turn lies.
     */
    private fun shortOfApproach(seconds: Double, afterTurn: Boolean, view: DriveView = DriveView.OVERVIEW, fromPreview: Boolean = true): Pair<Drive, Double> {
        val v = 13.0
        val approach = DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 9_000.0), v, camera, tuning)
        // The update at 230 s, the phase of the updates 0.
        val last = 230 * v
        val next = last + approach + (seconds + camera.longTransitionSeconds) * v
        val points = if (afterTurn) listOf(turn(last - 400.0), turn(next), turn(40_000.0)) else listOf(turn(next), turn(40_000.0))
        val d = Drive(points, view = view, fromPreview = fromPreview)
        d.driveAt(v, last + 1.0)
        return d to next
    }

    @Test
    fun `a run-up is one approach, down from its start and never out before its end`() {
        // A freeway lane choice 2 km before an exit. Alone, the view goes out between them and back in for the exit.
        val points = listOf(lanes(7_000.0), exit(9_000.0), turn(40_000.0))
        val alone = Drive(points)
        alone.driveAt(29.0, 9_400.0)
        assertEquals(listOf(CameraMode.FOLLOWING, CameraMode.OVERVIEW, CameraMode.FOLLOWING, CameraMode.OVERVIEW), alone.asksAfter(0.0).map { it.mode })
        // The run-up to the exit begins 3 km before it and ends past its gore: one way in at its start, one way out after its end.
        val d = Drive(points)
        d.driveAt(29.0, 6_000.0)
        assertEquals(CameraMode.OVERVIEW, d.planner.wanted)
        d.runUpKey = "r1/0/1/exit"
        d.driveAt(29.0, 6_040.0)
        assertEquals(CameraMode.FOLLOWING, d.asks.last().mode, "down at the run-up's start")
        assertEquals(Reason.APPROACH, d.asks.last().reason)
        assertTrue(d.asks.last().x < 6_040.0)
        d.driveAt(29.0, 9_050.0)
        assertEquals(1, d.asksAfter(0.0).size, "never out between its start and its end")
        assertTrue(d.planner.near)
        d.runUpKey = null
        d.driveAt(29.0, 9_300.0)
        val out = d.asks.last()
        assertEquals(CameraMode.OVERVIEW, out.mode)
        assertTrue(out.x >= 9_050.0 + camera.laneHoldMeters - 29.0, "out at ${out.x}")
        assertEquals(listOf(CameraMode.FOLLOWING, CameraMode.OVERVIEW), d.asksAfter(0.0).map { it.mode })
    }

    @Test
    fun `a seek back keeps no passed point ahead of the car, a seek forward no view for a point jumped over`() {
        // Held after a turn; a seek back 2 km: the hold does not wait for the car to reach the old point again.
        val back = Drive(listOf(turn(5_000.0), turn(30_000.0)))
        back.driveAt(13.0, 5_005.0)
        assertTrue(back.planner.near)
        back.x = 2_000.0
        back.planner.onJump(back.t)
        back.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        // Decided from the new place, as at a new route: the turn 3 km ahead again, far enough for the overview.
        back.drive(Double.POSITIVE_INFINITY, 10.0) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, back.asks.last().mode)
        assertFalse(back.planner.near)
        // And down again at its approach, held through it as the first time.
        back.driveAt(13.0, 4_900.0)
        assertEquals(CameraMode.FOLLOWING, back.asks.last().mode)
        assertTrue(back.planner.near)
        // Down for a turn; a seek forward past it and the next: no view held for what was jumped over.
        val forward = Drive(listOf(turn(3_000.0), turn(3_500.0), turn(12_000.0)))
        forward.driveAt(13.0, 2_900.0)
        assertTrue(forward.planner.near)
        forward.x = 6_000.0
        forward.planner.onJump(forward.t)
        forward.drive(Double.POSITIVE_INFINITY, 12.0) { 13.0 }
        assertEquals(CameraMode.OVERVIEW, forward.asks.last().mode)
        assertFalse(forward.planner.near)
        // A seek while the overview stands moves nothing unless a point is near the new place.
        val out = Drive(listOf(turn(8_000.0), turn(30_000.0)))
        out.driveAt(13.0, 1_000.0)
        out.x = 4_000.0
        out.planner.onJump(out.t)
        out.drive(Double.POSITIVE_INFINITY, 3.0) { 13.0 }
        assertTrue(out.asksAfter(0.0).isEmpty())
        out.x = 7_800.0
        out.planner.onJump(out.t)
        out.drive(Double.POSITIVE_INFINITY, 1.0) { 13.0 }
        assertEquals(CameraMode.FOLLOWING, out.asks.last().mode)
    }

    @Test
    fun `random drives never ask the same view twice in a row, never the overview where it must not be, one way out and in between two points`() {
        val random = Random(2_026)
        repeat(400) { round ->
            val points = randomRoute(random)
            val smart = round % 4 != 0
            val drive = Drive(points, view = if (round % 4 == 1) DriveView.NORMAL else DriveView.OVERVIEW, fromPreview = random.nextBoolean())
            val viewChosen = if (round % 4 == 1) DriveView.NORMAL else DriveView.OVERVIEW
            var lastAsk = drive.asks.single().mode
            var seen = drive.asks.size
            var offRun = 0
            var outs = 0
            var ins = 0
            var lastNext: String? = null
            var deviations = 0
            drive.onUpdate = {
                if (drive.asks.size > seen) {
                    val ask = drive.asks.last()
                    seen = drive.asks.size
                    assertTrue(ask.mode != lastAsk, "round $round: ${ask.mode} twice")
                    lastAsk = ask.mode
                    if (ask.mode == CameraMode.OVERVIEW) outs += 1 else ins += 1
                }
                offRun = if (drive.offRoute) offRun + 1 else 0
                // A deviation counts where it takes the view: at its second update off the route.
                if (offRun == tuning.offRouteUpdates) deviations += 1
                if (viewChosen == DriveView.NORMAL) assertEquals(CameraMode.FOLLOWING, drive.planner.wanted)
                if (smart && offRun >= tuning.offRouteUpdates) assertEquals(CameraMode.FOLLOWING, drive.planner.wanted, "round $round off the route")
                val next = drive.ahead().firstOrNull()?.key
                if (next != lastNext) {
                    // Between two points: one way out and one way in, and one more of each for every deviation.
                    assertTrue(outs <= 1 + deviations && ins <= 1 + deviations, "round $round: $outs out, $ins in, $deviations deviations before $next")
                    outs = 0
                    ins = 0
                    deviations = 0
                    lastNext = next
                }
            }
            var x = 0.0
            while (x < points.last().at - 30.0) {
                x = minOf(points.last().at - 30.0, x + 50.0 + random.nextDouble() * 600.0)
                // Crawling now and then; standing is a stop of its own below.
                val v = if (random.nextInt(10) == 0) 0.5 + random.nextDouble() else 3.0 + random.nextDouble() * 35.0
                when (random.nextInt(25)) {
                    0 -> {
                        // A blip off the route, or a deviation of a few updates.
                        drive.offRoute = true
                        val updates = 1 + random.nextInt(4)
                        drive.drive(Double.POSITIVE_INFINITY, updates.toDouble()) { v }
                        drive.offRoute = false
                    }
                    1 -> drive.stand(5.0 + random.nextDouble() * 60.0)
                }
                drive.drive(x) { v }
            }
            drive.planner.onGuidanceEnded()
            assertEquals(CameraMode.FOLLOWING, drive.planner.wanted, "after guidance")
        }
    }

    /** A made-up route: turns, complex junctions, exits and lane choices, 150 m to 4 km apart. */
    private fun randomRoute(random: Random): List<P> {
        val points = ArrayList<P>()
        var at = 300.0 + random.nextDouble() * 3_000.0
        repeat(8 + random.nextInt(8)) {
            points += when (random.nextInt(4)) {
                0 -> complex(at)
                1 -> exit(at)
                2 -> lanes(at)
                else -> turn(at)
            }
            at += 150.0 + random.nextDouble() * 4_000.0
        }
        return points
    }
}
