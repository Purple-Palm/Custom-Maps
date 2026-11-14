package io.github.purplepalm.custommaps.nav.sound

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tone rule on invented drives: a small driver feeds it once a second along straight roads with
 * maneuvers at given distances, at given speeds. Names and distances are invented; no drive of anyone's.
 */
class ToneRuleTest {

    private val turn = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Oak Street")

    /** One tone the drive heard: when, which, why, and how far the maneuver was. */
    private data class Heard(val second: Int, val kind: ToneKind, val reason: ToneReason, val meters: Double, val step: String)

    /** A maneuver [at] metres along the route; a lane row shown from [laneFrom] metres before [laneAt]. */
    private data class Point(
        val at: Double,
        val maneuver: Maneuver,
        val laneAt: Double? = null,
        val laneKey: String? = null,
        val laneFrom: Double = 0.0,
    )

    /** Feeds the rule once a second. */
    private inner class Drive(
        val events: ToneEvents = ToneEvents(),
        val tuning: ToneTuning = ToneTuning(),
        var route: String = "r1",
    ) {
        var state = ToneRule.State.NONE
        val heard = mutableListOf<Heard>()
        val skipped = mutableListOf<Pair<Int, ToneSkipped>>()
        var second = 0

        fun update(moment: ToneMoment): ToneRule.Step {
            val step = ToneRule.reduce(state, moment, second * 1_000L, events, tuning)
            state = step.state
            step.cue?.let { heard += Heard(second, it.kind, it.reason, moment.metersToManeuver, moment.stepKey) }
            step.skipped.forEach { skipped += second to it }
            second += 1
            return step
        }

        /**
         * Drives along [points], starting [from] metres along the route, at the speed [speed] gives for each
         * second, until past the last point or [seconds] are over. [offRoute] marks seconds the navigator has
         * the car off the route.
         */
        fun along(
            points: List<Point>,
            speed: (Int) -> Double,
            from: Double = 0.0,
            seconds: Int = 10_000,
            offRoute: (Int) -> Boolean = { false },
            reportedSpeed: (Int) -> Double? = { speed(it) },
        ) {
            var position = from
            val start = second
            while (second - start < seconds) {
                val index = points.indexOfFirst { it.at > position }
                if (index < 0) return
                val point = points[index]
                val lane = point.laneAt?.let { at -> if (position >= at - point.laneFrom && position < at) at - position else null }
                update(
                    ToneMoment(
                        routeKey = route,
                        stepKey = "$route/0/$index",
                        maneuver = point.maneuver,
                        metersToManeuver = point.at - position,
                        secondsToManeuver = 0.0,
                        speedMps = reportedSpeed(second - start),
                        offRoute = offRoute(second - start),
                        laneChoiceKey = if (lane != null) point.laneKey else null,
                        laneChoiceMeters = lane ?: Double.NaN,
                    ),
                )
                position += speed(second - start)
            }
        }
    }

    private fun steady(mps: Double): (Int) -> Double = { mps }

    @Test
    fun `one tone 25 s and two tones 7 s before a turn, at each speed, about 18 s apart`() {
        // 30, 45, 65 and 75 mph, in metres a second.
        for ((mps, ahead, final) in listOf(Triple(13.41, 335.3, 93.9), Triple(20.12, 503.0, 140.8), Triple(29.06, 726.5, 203.4), Triple(33.53, 838.3, 234.7))) {
            assertEquals(ahead, ToneRule.aheadMeters(mps), 0.1)
            assertEquals(final, ToneRule.finalMeters(mps), 0.1)
            val drive = Drive()
            drive.along(listOf(Point(2_000.0, turn)), steady(mps))
            assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), drive.heard.map { it.kind }, "$mps")
            val (single, pair) = drive.heard
            // At the distance or within the next update.
            assertTrue(single.meters <= ahead && single.meters > ahead - mps, "$mps: single at ${single.meters}")
            assertTrue(pair.meters <= final && pair.meters > final - mps, "$mps: pair at ${pair.meters}")
            assertTrue(pair.second - single.second in 17..19, "$mps: ${pair.second - single.second} s")
        }
    }

    @Test
    fun `a creeping or standing car gets the floors, 300 m and 30 m`() {
        assertEquals(300.0, ToneRule.aheadMeters(0.0))
        assertEquals(30.0, ToneRule.finalMeters(0.0))
        // The pair's floor is 30 m: a 50 m floor sounds it too early in slow traffic and queues.
        assertEquals(30.0, ToneTuning().finalMinMeters)
        // Under about 9.6 mph the floor binds.
        assertEquals(30.0, ToneRule.finalMeters(4.0))
        assertEquals(300.0, ToneRule.aheadMeters(4.0))
        val drive = Drive()
        drive.along(listOf(Point(500.0, turn)), steady(2.0))
        val (single, pair) = drive.heard
        assertTrue(single.meters in 298.0..300.0, "${single.meters}")
        assertTrue(pair.meters in 28.0..30.0, "${pair.meters}")
        // The caps guard against a wrong speed only.
        assertEquals(1_000.0, ToneRule.aheadMeters(60.0))
        assertEquals(300.0, ToneRule.finalMeters(60.0))
    }

    @Test
    fun `a car that stands 20 s, 120 m before its turn, gets no pair while it stands and the pair once within its distance`() {
        val drive = Drive()
        // From 400 m before the turn at 10 m/s to 120 m before it, 20 s standing, then 5 m/s.
        drive.along(listOf(Point(400.0, turn)), speed = { s -> if (s < 28) 10.0 else if (s < 48) 0.0 else 5.0 })
        val pair = drive.heard.single { it.kind == ToneKind.AT_TURN }
        assertTrue(pair.second > 48, "after it moves again, at ${pair.second}")
        assertTrue(pair.meters <= 35.0, "within 7 s at 5 m/s: ${pair.meters}")
    }

    @Test
    fun `two turns 80 m apart get a pair each and no single between`() {
        val drive = Drive()
        drive.along(listOf(Point(1_000.0, turn), Point(1_080.0, turn.copy(modifier = TurnModifier.LEFT))), steady(13.41))
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN, ToneKind.AT_TURN), drive.heard.map { it.kind })
        // The second turn is inside its pair's distance from its step's first update: its pair at once, its single never due.
        assertEquals(emptyList<Pair<Int, ToneSkipped>>(), drive.skipped)
        assertTrue(drive.heard[2].second - drive.heard[1].second <= 7)
    }

    @Test
    fun `a second turn far enough gets its single, one nearer waits for the quiet, one too near gets only its pair`() {
        val v = 13.41
        fun second(after: Double): Drive = Drive().also { it.along(listOf(Point(1_000.0, turn), Point(1_000.0 + after, turn)), steady(v)) }

        // 450 m on: the single at its own distance.
        val far = second(450.0)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN, ToneKind.AHEAD, ToneKind.AT_TURN), far.heard.map { it.kind })
        assertTrue(far.heard[2].meters > ToneRule.aheadMeters(v) - v)
        // 400 m on: it falls due within the quiet, waits, and sounds 15 s after the pair.
        val near = second(400.0)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN, ToneKind.AHEAD, ToneKind.AT_TURN), near.heard.map { it.kind })
        assertEquals(15, near.heard[2].second - near.heard[1].second)
        // 300 m on: it waits, then too little room is left before the pair: only the pair.
        val nearer = second(300.0)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN, ToneKind.AT_TURN), nearer.heard.map { it.kind })
        assertEquals(ToneSkipped(ToneSkip.TOO_CLOSE, ToneReason.AHEAD), nearer.skipped.single().second)
    }

    @Test
    fun `each tone at most once in a step, also when the speed swings and the distance crosses a limit twice`() {
        val drive = Drive()
        fun at(meters: Double, speed: Double) = drive.update(moment(meters = meters, speed = speed))
        at(400.0, 13.41)
        at(330.0, 13.41)
        // The position jumps back past the distance, and the speed swings up so the distance grows.
        at(345.0, 20.0)
        at(330.0, 30.0)
        at(250.0, 13.41)
        at(90.0, 13.41)
        at(110.0, 13.41)
        at(80.0, 13.41)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), drive.heard.map { it.kind })
    }

    @Test
    fun `a roundabout sounds before its way in, nothing inside, and both for the turn after`() {
        val drive = Drive()
        val into = Maneuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT, "Elm Circle", roundaboutExitNumber = 2)
        val out = Maneuver(ManeuverType.EXIT_ROUNDABOUT, TurnModifier.RIGHT, "Elm Circle")
        drive.along(listOf(Point(1_000.0, into), Point(1_060.0, out), Point(2_000.0, turn)), steady(10.0))
        assertEquals(listOf("r1/0/0" to ToneKind.AHEAD, "r1/0/0" to ToneKind.AT_TURN, "r1/0/2" to ToneKind.AHEAD, "r1/0/2" to ToneKind.AT_TURN), drive.heard.map { it.step to it.kind })
    }

    @Test
    fun `which maneuvers sound`() {
        for (type in ManeuverType.entries) {
            for (modifier in TurnModifier.entries.map<TurnModifier, TurnModifier?> { it } + null) {
                val expected = when (type) {
                    ManeuverType.TURN, ManeuverType.END_OF_ROAD, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK,
                    ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ARRIVE,
                    -> true
                    ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> modifier != null && modifier != TurnModifier.STRAIGHT
                    ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY, ManeuverType.MERGE, ManeuverType.DEPART,
                    ManeuverType.NOTIFICATION, ManeuverType.UNKNOWN,
                    -> false
                }
                assertEquals(expected, ToneRule.sounds(Maneuver(type, modifier, "Pine Road")), "$type $modifier")
            }
        }
        // A maneuver that does not sound gets nothing, all the way to it.
        val drive = Drive()
        drive.along(listOf(Point(800.0, Maneuver(ManeuverType.CONTINUE, TurnModifier.STRAIGHT, "Pine Road"))), steady(13.41))
        assertEquals(emptyList<Heard>(), drive.heard)
    }

    @Test
    fun `the destination gets both tones by distance, and the arrival gives the pair once and none after a pair`() {
        val arrive = Maneuver(ManeuverType.ARRIVE, null, "Office")
        val byDistance = Drive()
        byDistance.along(listOf(Point(1_000.0, arrive)), steady(13.41))
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), byDistance.heard.map { it.kind })
        // After the pair the arrival adds nothing.
        assertNull(ToneRule.arrived(byDistance.state, 99_000, ToneEvents()).cue)

        // The navigator declares the arrival before the pair's distance: the pair now, and once.
        val early = Drive()
        early.along(listOf(Point(1_000.0, arrive)), steady(13.41), seconds = 60)
        val arrived = ToneRule.arrived(early.state, 61_000, ToneEvents())
        assertEquals(ToneCue(ToneKind.AT_TURN, ToneReason.ARRIVED, Double.NaN, Double.NaN), arrived.cue)
        assertNull(ToneRule.arrived(arrived.state, 62_000, ToneEvents()).cue)
        // Without a drive, and with the pair switched off, nothing.
        assertNull(ToneRule.arrived(ToneRule.State.NONE, 0, ToneEvents()).cue)
        assertNull(ToneRule.arrived(early.state, 61_000, ToneEvents(atTurn = false)).cue)
    }

    @Test
    fun `off the route nothing sounds, and back on the same step what was done stays done`() {
        val drive = Drive()
        // Off the route from 340 m before the turn until past it: the single and the pair would have come there.
        drive.along(listOf(Point(1_000.0, turn)), steady(13.41), offRoute = { it >= 49 })
        assertEquals(emptyList<Heard>(), drive.heard)
        // Back on the route still before the turn, inside the pair's distance: the pair, which the step has not had.
        val late = Drive()
        late.along(listOf(Point(1_000.0, turn)), steady(13.41), offRoute = { it in 49..68 })
        assertEquals(listOf(ToneKind.AT_TURN), late.heard.map { it.kind })
        // Off route, the state does not move at all.
        val before = drive.state
        assertEquals(before, ToneRule.reduce(before, moment(meters = 50.0, offRoute = true), 0, ToneEvents()).state)

        val back = Drive()
        back.along(listOf(Point(1_000.0, turn)), steady(13.41), seconds = 52)
        assertEquals(listOf(ToneKind.AHEAD), back.heard.map { it.kind })
        back.along(listOf(Point(1_000.0, turn)), steady(13.41), from = 52 * 13.41, seconds = 3, offRoute = { true })
        back.along(listOf(Point(1_000.0, turn)), steady(13.41), from = 55 * 13.41)
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), back.heard.map { it.kind })
    }

    @Test
    fun `a new route sounds nothing at once for what is already inside a tone's distance`() {
        val v = 13.41
        // The drive's own route first, far from its turn.
        fun reroutedTo(meters: Double): Drive = Drive().also { drive ->
            drive.along(listOf(Point(5_000.0, turn)), steady(v), seconds = 5)
            drive.route = "r2"
            drive.along(listOf(Point(meters, turn)), steady(v))
        }
        // 200 m ahead: inside the single's distance, outside the pair's: no single, the pair at its distance.
        val inside = reroutedTo(200.0)
        assertEquals(listOf(ToneKind.AT_TURN), inside.heard.map { it.kind })
        assertTrue(inside.heard.single().meters <= ToneRule.finalMeters(v))
        assertEquals(ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.AHEAD), inside.skipped.single().second)
        // 60 m ahead: inside the pair's distance too, so it sounds no tone at all: it would come at once, with no warning in it.
        val pairInside = reroutedTo(60.0)
        assertEquals(emptyList<Heard>(), pairInside.heard)
        assertEquals(ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.AT_TURN), pairInside.skipped.single().second)
        // 1,500 m ahead: both, as usual.
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), reroutedTo(1_500.0).heard.map { it.kind })
        // The drive's first update is a new route too: a manual Start sounds nothing at once.
        val start = Drive()
        start.along(listOf(Point(80.0, turn)), steady(v))
        assertEquals(emptyList<Heard>(), start.heard)
    }

    @Test
    fun `a reroute whose first update is a pending card 40 m before its turn sounds nothing`() {
        val drive = Drive()
        drive.along(listOf(Point(5_000.0, turn)), steady(10.0), seconds = 5)
        // The pending card: the maneuver as the route's step gives it, before the navigator's instruction.
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 40.0, speed = 10.0, maneuver = Maneuver(ManeuverType.UNKNOWN, null, ""), pending = true))
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 30.0, speed = 10.0))
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 20.0, speed = 10.0))
        assertEquals(emptyList<Heard>(), drive.heard)
        // Its next turn is a turn like any other.
        drive.update(moment(route = "r2", step = "r2/0/1", meters = 900.0, speed = 10.0))
        repeat(85) { drive.update(moment(route = "r2", step = "r2/0/1", meters = 890.0 - it * 10.0, speed = 10.0)) }
        assertEquals(listOf(ToneKind.AHEAD, ToneKind.AT_TURN), drive.heard.map { it.kind })
    }

    @Test
    fun `a lane choice gets one single for its key, none again, and none without room`() {
        val v = 29.06
        val exit = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.RIGHT, "Exit 12")
        // A lane choice 1,500 m before the exit, shown from its window on.
        val drive = Drive()
        drive.along(listOf(Point(5_000.0, exit, laneAt = 3_500.0, laneKey = "r1/0/0/7", laneFrom = 726.0)), steady(v))
        assertEquals(listOf(ToneReason.LANE_CHOICE, ToneReason.AHEAD, ToneReason.AT_TURN), drive.heard.map { it.reason })
        // With an exit 300 m behind it: single, 15 s, single, pair.
        val close = Drive()
        close.along(listOf(Point(5_000.0, exit, laneAt = 4_700.0, laneKey = "r1/0/0/7", laneFrom = 726.0)), steady(v))
        assertEquals(listOf(ToneReason.LANE_CHOICE, ToneReason.AHEAD, ToneReason.AT_TURN), close.heard.map { it.reason })
        assertEquals(15, close.heard[1].second - close.heard[0].second)
        assertTrue(close.heard[2].second - close.heard[1].second in 12..14)
        // A lane choice shown with less than 10 s of room before it: dropped.
        val late = Drive()
        late.along(listOf(Point(5_000.0, exit, laneAt = 3_000.0, laneKey = "r1/0/0/7", laneFrom = 200.0)), steady(v))
        assertEquals(listOf(ToneReason.AHEAD, ToneReason.AT_TURN), late.heard.map { it.reason })
        assertEquals(ToneSkipped(ToneSkip.TOO_CLOSE, ToneReason.LANE_CHOICE), late.skipped.single().second)
        // The same key again, after a moment without the row: nothing.
        val again = Drive()
        again.update(moment(meters = 3_030.0, speed = v))
        again.update(moment(meters = 3_000.0, speed = v, laneKey = "k", laneMeters = 700.0))
        again.update(moment(meters = 2_970.0, speed = v))
        again.update(moment(meters = 2_940.0, speed = v, laneKey = "k", laneMeters = 640.0))
        assertEquals(1, again.heard.size)
    }

    @Test
    fun `a lane row shown when the maneuver's single sounds has its tone in that one`() {
        val v = 29.06
        val drive = Drive()
        drive.update(moment(meters = 760.0, speed = v))
        // The row comes at the update the single is due at: one tone for both.
        drive.update(moment(meters = 720.0, speed = v, laneKey = "r1/0/0/3", laneMeters = 620.0))
        var meters = 720.0
        while (meters > 0) {
            meters -= v
            drive.update(moment(meters = meters, speed = v, laneKey = "r1/0/0/3".takeIf { meters > 100.0 }, laneMeters = meters - 100.0))
        }
        assertEquals(listOf(ToneReason.AHEAD, ToneReason.AT_TURN), drive.heard.map { it.reason })
        assertTrue("r1/0/0/3" in drive.state.laneKeysDone)
        assertEquals(emptyList<Pair<Int, ToneSkipped>>(), drive.skipped)
    }

    @Test
    fun `a run-up sounds as it begins and at each change of its row, but not when it begins at the exit's own window`() {
        val v = 29.06
        val exit = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.RIGHT, "Exit 12")
        val own = "r1/0/0" + ToneMoment.MANEUVER_POINT_KEY
        fun at(drive: Drive, meters: Double, key: String?, keyMeters: Double = Double.NaN, fromWindow: Boolean = false) = drive.update(
            ToneMoment(
                routeKey = "r1", stepKey = "r1/0/0", maneuver = exit, metersToManeuver = meters, secondsToManeuver = 0.0, speedMps = v,
                offRoute = false, laneChoiceKey = key, laneChoiceMeters = if (key == own) meters else keyMeters,
                laneRowIsManeuver = key == own, runUpFromExitWindow = fromWindow,
            ),
        )
        // Begun early by an edge point: a tone; its row changes to the exit's own: another; then the exit's own single and pair.
        val edge = Drive()
        var m = 2_000.0
        at(edge, m, null)
        while (m > 1_300.0) { m -= v; at(edge, m, "r1/0/0/4", keyMeters = m - 800.0) }
        while (m > 0.0) { m -= v; at(edge, m, own) }
        assertEquals(listOf(ToneReason.LANE_CHOICE, ToneReason.LANE_CHOICE, ToneReason.AHEAD, ToneReason.AT_TURN), edge.heard.map { it.reason })

        // Begun at the exit's own window with the exit's own row: the exit's single is that moment, no lane tone.
        val window = Drive()
        m = 1_500.0
        at(window, m, null)
        while (m > 0.0) { m -= v; at(window, m, if (m <= 760.0) own else null, fromWindow = true) }
        assertEquals(listOf(ToneReason.AHEAD, ToneReason.AT_TURN), window.heard.map { it.reason })
        assertEquals(ToneSkip.EXIT_ROW, window.skipped.single().second.skip)

        // Begun far earlier with the exit's own row, at the lane plan's first narrowing: that is a run-up's beginning, a tone.
        val seam = Drive()
        m = 2_000.0
        at(seam, m, null)
        while (m > 0.0) { m -= v; at(seam, m, if (m <= 1_600.0) own else null) }
        assertEquals(listOf(ToneReason.LANE_CHOICE, ToneReason.AHEAD, ToneReason.AT_TURN), seam.heard.map { it.reason })
    }

    @Test
    fun `a new route's lane row, shown as it comes, has no tone, also when its first card is pending`() {
        val drive = Drive()
        drive.along(listOf(Point(9_000.0, turn)), steady(29.06), seconds = 5)
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 2_000.0, speed = 29.06, pending = true))
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 1_970.0, speed = 29.06, laneKey = "r2/0/0/2", laneMeters = 600.0))
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 1_940.0, speed = 29.06, laneKey = "r2/0/0/2", laneMeters = 570.0))
        assertEquals(emptyList<Heard>(), drive.heard)
        assertEquals(ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.LANE_CHOICE), drive.skipped.single().second)
        // A row that comes after the route's start has its tone.
        drive.update(moment(route = "r2", step = "r2/0/0", meters = 1_910.0, speed = 29.06, laneKey = "r2/0/0/3", laneMeters = 900.0))
        assertEquals(listOf(ToneReason.LANE_CHOICE), drive.heard.map { it.reason })
    }

    @Test
    fun `without the car's speed the route's speed counts, and with neither the floors`() {
        val routeSpeed = moment(meters = 600.0, speed = null, seconds = 30.0)
        assertEquals(20.0, ToneRule.speedOf(routeSpeed))
        assertEquals(0.0, ToneRule.speedOf(moment(meters = 600.0, speed = null)))
        assertEquals(0.0, ToneRule.speedOf(moment(meters = 600.0, speed = Double.NaN)))
        assertEquals(7.0, ToneRule.speedOf(moment(meters = 600.0, speed = 7.0, seconds = 30.0)))
        // With neither: the floors decide.
        val drive = Drive()
        drive.along(listOf(Point(500.0, turn)), steady(5.0), reportedSpeed = { null })
        assertTrue(drive.heard[0].meters in 295.0..300.0)
        assertTrue(drive.heard[1].meters in 25.0..30.0)
    }

    @Test
    fun `each switch leaves the other tones where they were, and with both off the rule moves in silence`() {
        val points = listOf(Point(1_000.0, turn), Point(1_400.0, turn), Point(3_000.0, turn), Point(3_100.0, turn), Point(5_000.0, turn))
        fun drive(events: ToneEvents) = Drive(events).also { it.along(points, steady(13.41)) }
        val both = drive(ToneEvents())
        val pairsOnly = drive(ToneEvents(beforeTurn = false))
        val singlesOnly = drive(ToneEvents(atTurn = false))
        // Before a turn off: every pair at the same moment, none later.
        assertEquals(both.heard.filter { it.kind == ToneKind.AT_TURN }, pairsOnly.heard)
        // Two tones off: the singles that no pair's quiet held come at the same moments; one a sounded pair held
        // back comes at its own distance instead, never later.
        val held = both.heard.filter { it.kind == ToneKind.AHEAD }
        assertEquals(held.map { it.step }, singlesOnly.heard.map { it.step })
        for ((on, off) in held.zip(singlesOnly.heard)) assertTrue(off.second <= on.second, "$on, $off")
        assertEquals(held.first(), singlesOnly.heard.first())
        assertTrue(singlesOnly.heard[1].second < held[1].second, "the second turn's single no longer waits for a pair no one heard")
        val none = drive(ToneEvents.NONE)
        assertEquals(emptyList<Heard>(), none.heard)
        assertEquals("r1/0/4", none.state.stepKey)
        assertTrue(none.state.finalDone)
    }

    @Test
    fun `the moment of a snapshot takes the snapshot's speed, its lane choice and the exit's own row`() {
        val snapshot = RouteProgressSnapshot(
            nextManeuver = turn,
            distanceToNextManeuver = Distance.meters(640.0),
            lanes = null,
            distanceRemaining = Distance.meters(5_000.0),
            durationRemaining = 300.seconds,
            durationToNextManeuver = 32.seconds,
            stepKey = "r1/0/4",
            offRoute = false,
            laneChoiceAhead = LanesAhead(Distance.meters(410.0), LaneInfo(listOf(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true)))),
            speedMps = 18.5,
        )
        val plain = ToneMoment.of(snapshot, "r1", laneChoiceKey = null)
        assertEquals(ToneMoment("r1", "r1/0/4", turn, 640.0, 32.0, 18.5, offRoute = false), plain)
        // A straight-on lane choice: its distance from the snapshot when the engine gives none.
        val choice = ToneMoment.of(snapshot, "r1", laneChoiceKey = "r1/0/4/2")
        assertEquals(410.0, choice.laneChoiceMeters)
        assertFalse(choice.laneRowIsManeuver)
        assertEquals(380.0, ToneMoment.of(snapshot, "r1", "r1/0/4/2", laneChoiceMeters = 380.0).laneChoiceMeters)
        // The exit's own row: the maneuver's distance, and whether the run-up began at the exit's window.
        val own = ToneMoment.of(snapshot, "r1", laneChoiceKey = "r1/0/4/end", laneChoiceMeters = 999.0, runUpFromExitWindow = true)
        assertTrue(own.laneRowIsManeuver)
        assertTrue(own.runUpFromExitWindow)
        assertEquals(640.0, own.laneChoiceMeters)
        assertFalse(ToneMoment.of(snapshot, "r1", "r1/0/4/2", runUpFromExitWindow = true).runUpFromExitWindow)
        // A pending card shows no lane row, whatever the engine's choices held.
        val pending = ToneMoment.of(snapshot.copy(pending = true, laneChoiceAhead = null), "r2", laneChoiceKey = "r1/0/4/2")
        assertNull(pending.laneChoiceKey)
        assertTrue(pending.pending)
        // Off the route, and without the car's speed.
        assertTrue(ToneMoment.of(snapshot.copy(offRoute = true), "r1", null).offRoute)
        assertNull(ToneMoment.of(snapshot.copy(speedMps = null), "r1", null).speedMps)
    }

    @Test
    fun `a drive of 500 invented steps keeps the rule's promises`() {
        val random = Random(20_000_101)
        repeat(5) { drive ->
            val v = listOf(4.0, 9.0, 13.41, 22.0, 31.0)[drive]
            var at = 0.0
            val points = List(100) { index ->
                at += 40.0 + random.nextDouble() * 1_500.0
                val type = listOf(ManeuverType.TURN, ManeuverType.TURN, ManeuverType.FORK, ManeuverType.OFF_RAMP, ManeuverType.CONTINUE, ManeuverType.ROUNDABOUT).random(random)
                val lane = if (random.nextInt(4) == 0) at - 50.0 - random.nextDouble() * 900.0 else null
                Point(at, Maneuver(type, TurnModifier.entries.random(random), "Road $index"), laneAt = lane, laneKey = "r1/0/$index/1", laneFrom = 25 * v)
            }
            val run = Drive()
            run.along(points, steady(v))
            val tuning = ToneTuning()
            for ((previous, next) in run.heard.zipWithNext()) {
                assertTrue(next.second > previous.second, "never two tones at one update")
                if (next.kind == ToneKind.AHEAD) {
                    assertTrue((next.second - previous.second) * 1_000L >= tuning.quietMillis, "a single only after the quiet: $previous, $next")
                }
            }
            // At a steady speed a maneuver's single leaves at least 10 s before its pair.
            for (single in run.heard.filter { it.reason == ToneReason.AHEAD }) {
                val pair = run.heard.firstOrNull { it.step == single.step && it.kind == ToneKind.AT_TURN } ?: continue
                assertTrue(pair.second - single.second >= tuning.roomSeconds, "$single, $pair")
            }
            // Each step's tones at most once each.
            for (step in run.heard.groupBy { it.step }.values) {
                assertTrue(step.count { it.reason == ToneReason.AHEAD } <= 1)
                assertTrue(step.count { it.kind == ToneKind.AT_TURN } <= 1)
            }
            assertTrue(run.heard.count { it.kind == ToneKind.AT_TURN } > 30, "the drive heard its pairs: ${run.heard.size}")
        }
    }

    @Test
    fun `the tones' distances keep their order at every speed`() {
        var mps = 0.0
        while (mps < 60.0) {
            assertTrue(ToneRule.finalMeters(mps) <= ToneRule.aheadMeters(mps))
            assertTrue(abs(ToneRule.roomMeters(mps) - maxOf(10 * mps, 100.0)) < 1e-9)
            mps += 0.5
        }
    }

    private fun moment(
        meters: Double,
        speed: Double? = 13.41,
        seconds: Double = 0.0,
        route: String = "r1",
        step: String = "r1/0/0",
        maneuver: Maneuver = turn,
        offRoute: Boolean = false,
        laneKey: String? = null,
        laneMeters: Double = Double.NaN,
        pending: Boolean = false,
    ) = ToneMoment(
        routeKey = route,
        stepKey = step,
        maneuver = maneuver,
        metersToManeuver = meters,
        secondsToManeuver = seconds,
        speedMps = speed,
        offRoute = offRoute,
        laneChoiceKey = laneKey,
        laneChoiceMeters = laneMeters,
        pending = pending,
    )
}
