package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Synthetic routes only: invented road names, distances and times. */
class ThenRuleTest {

    private val stepA = "route-a/0/3"
    private val stepB = "route-a/0/4"
    private val stepC = "route-a/0/5"
    private val stepD = "route-a/0/6"

    private fun following(
        type: ManeuverType = ManeuverType.TURN,
        modifier: TurnModifier? = TurnModifier.RIGHT,
        meters: Double = 150.0,
        seconds: Double = 12.0,
    ) = FollowingManeuver(Maneuver(type, modifier, "Pine Street"), Distance.meters(meters), seconds.seconds)

    /** One progress update; the time to the next maneuver defaults to town speed, 13 m/s. */
    private fun at(
        toNext: Double,
        toNextTime: Duration = (toNext / 13.0).seconds,
        next: ManeuverType = ManeuverType.TURN,
        following: FollowingManeuver? = following(),
        step: String = stepA,
        offRoute: Boolean = false,
    ) = RouteProgressSnapshot(
        nextManeuver = Maneuver(next, TurnModifier.LEFT, "Oak Street"),
        distanceToNextManeuver = Distance.meters(toNext),
        lanes = null,
        distanceRemaining = Distance.meters(5_000.0),
        durationRemaining = 10.minutes,
        durationToNextManeuver = toNextTime,
        stepKey = step,
        following = following,
        offRoute = offRoute,
    )

    /** Runs the updates in order and returns whether the chip shows after each one. */
    private fun shown(vararg updates: RouteProgressSnapshot): List<Boolean> {
        var state = ThenRule.State.NONE
        return updates.map { update ->
            state = ThenRule.reduce(state, update)
            state.shown
        }
    }

    @Test
    fun `a close pair shows once the first maneuver is near, not before`() {
        assertEquals(listOf(false, false, false, true, true), shown(at(900.0), at(600.0), at(420.0), at(390.0), at(200.0)))
    }

    @Test
    fun `a pair far apart never shows`() {
        val far = following(meters = 800.0, seconds = 60.0)
        assertEquals(listOf(false, false, false), shown(at(300.0, following = far), at(100.0, following = far), at(40.0, following = far)))
    }

    @Test
    fun `a short time counts even when the distance is longer`() {
        val quick = following(meters = 450.0, seconds = 18.0)
        assertEquals(listOf(true), shown(at(380.0, following = quick)))
    }

    @Test
    fun `after a ramp the next turn shows earlier and from farther apart`() {
        val turnAtRampEnd = following(meters = 500.0, seconds = 39.0)
        val updates = arrayOf(
            at(1_700.0, toNextTime = 57.seconds, next = ManeuverType.OFF_RAMP, following = turnAtRampEnd),
            at(1_450.0, toNextTime = 48.seconds, next = ManeuverType.OFF_RAMP, following = turnAtRampEnd),
        )
        assertEquals(listOf(false, true), shown(*updates))
        // The same pair after a plain turn is not close.
        assertEquals(listOf(false), shown(at(200.0, following = turnAtRampEnd)))
    }

    @Test
    fun `a ramp after a town turn counts as close from farther apart`() {
        val ramp = following(type = ManeuverType.ON_RAMP, modifier = TurnModifier.SLIGHT_RIGHT, meters = 600.0, seconds = 35.0)
        assertEquals(listOf(false, true), shown(at(600.0, following = ramp), at(350.0, following = ramp)))
    }

    @Test
    fun `once shown it stays for the rest of the step`() {
        val refreshed = following(meters = 150.0, seconds = 45.0)
        assertEquals(
            listOf(true, true, true, true),
            // GPS jitter moves the car back out of the window, then traffic makes the pair slow.
            shown(at(390.0), at(430.0), at(300.0, following = refreshed), at(5.0, following = refreshed)),
        )
    }

    @Test
    fun `every step decides again`() {
        val far = following(meters = 900.0, seconds = 70.0)
        assertEquals(
            listOf(true, false, true, true),
            // The last two steps are a chain of close turns: the chip stays across the step change.
            shown(at(390.0), at(150.0, step = stepB, following = far), at(120.0, step = stepC), at(140.0, step = stepD)),
        )
    }

    @Test
    fun `a new route is a new step even with the same numbers`() {
        assertEquals(listOf(true, false), shown(at(390.0), at(1_000.0, step = "route-b/0/3")))
    }

    @Test
    fun `it does not start right at the maneuver`() {
        assertEquals(listOf(false, false), shown(at(20.0), at(8.0)))
    }

    @Test
    fun `leaving the route hides it until the route is known again`() {
        assertEquals(listOf(true, false, true), shown(at(390.0), at(370.0, offRoute = true), at(350.0)))
    }

    @Test
    fun `inside a roundabout the chip keeps the maneuver it shows`() {
        // On the approach the turn comes 40 m of roundabout and 90 m of road after the way in.
        val approach = at(200.0, next = ManeuverType.ROUNDABOUT, following = following(meters = 130.0, seconds = 14.0))
        // Inside, the next maneuver is the way out, 18 m ahead, and the same turn still follows.
        val inside = at(18.0, next = ManeuverType.EXIT_ROUNDABOUT, following = following(meters = 90.0, seconds = 8.0), step = stepB)
        assertEquals(listOf(true, true), shown(approach, inside))
        // A chip does not start there.
        assertEquals(listOf(false), shown(inside))
        // And a chip that showed another maneuver does not stay for this one.
        val other = following(modifier = TurnModifier.LEFT, meters = 130.0, seconds = 14.0)
        assertEquals(listOf(true, false), shown(at(200.0, next = ManeuverType.ROUNDABOUT, following = other), inside))
        // Leaving the route in between starts over: the chip was gone, so it does not stay.
        assertEquals(listOf(true, false, false), shown(approach, approach.copy(offRoute = true), inside))
    }

    @Test
    fun `nothing follows the arrival`() {
        val arriving = at(100.0, next = ManeuverType.ARRIVE, following = null)
        assertEquals(listOf(false), shown(arriving))
        assertEquals(listOf(true, false), shown(at(390.0), arriving.copy(stepKey = stepB)))
    }

    @Test
    fun `the destination right after the last turn shows`() {
        val destination = following(type = ManeuverType.ARRIVE, modifier = TurnModifier.RIGHT, meters = 30.0, seconds = 15.0)
        assertEquals(listOf(true), shown(at(250.0, following = destination)))
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "CONTINUE, STRAIGHT, false",
        "CONTINUE, , false",
        "NEW_NAME, STRAIGHT, false",
        "MERGE, SLIGHT_LEFT, false",
        "NOTIFICATION, RIGHT, false",
        "UNKNOWN, RIGHT, false",
        "DEPART, , false",
        "EXIT_ROUNDABOUT, RIGHT, false",
        "EXIT_ROTARY, SLIGHT_RIGHT, false",
        "CONTINUE, SLIGHT_LEFT, true",
        "FORK, SLIGHT_RIGHT, true",
        "OFF_RAMP, SLIGHT_RIGHT, true",
        "ROUNDABOUT, RIGHT, true",
        "ROUNDABOUT_TURN, LEFT, true",
        "ROTARY, RIGHT, true",
        "END_OF_ROAD, LEFT, true",
        "TURN, U_TURN, true",
    )
    fun `only maneuvers worth a look get a chip`(type: ManeuverType, modifier: TurnModifier?, expected: Boolean) {
        assertEquals(expected, ThenRule.isWorthShowing(Maneuver(type, modifier, "Pine Street")))
        // And the rule asks: the pair is close and near, so only the kind of maneuver decides.
        assertEquals(listOf(expected), shown(at(200.0, following = following(type, modifier))))
    }

    /**
     * Each number of [ThenTuning] at its bound and just past it, while the other dimension is too
     * long to count: on a slow street only the meters can decide, on a fast road only the seconds.
     */
    @ParameterizedTest(name = "{0} then {1}, {2} m and {3} s apart, {4} m and {5} s before -> {6}")
    @CsvSource(
        // Close within 300 m, shown from 400 m.
        "TURN, TURN, 300, 45, 400, 70, true",
        "TURN, TURN, 301, 45, 400, 70, false",
        "TURN, TURN, 300, 45, 401, 70, false",
        // Close within 20 s, shown from 30 s.
        "TURN, TURN, 900, 20, 880, 30, true",
        "TURN, TURN, 900, 21, 880, 30, false",
        "TURN, TURN, 900, 20, 880, 31, false",
        // Around a ramp or a fork: close within 1,000 m or 40 s.
        "TURN, FORK, 1000, 90, 300, 23, true",
        "TURN, FORK, 1001, 90, 300, 23, false",
        "TURN, OFF_RAMP, 1400, 40, 300, 23, true",
        "TURN, OFF_RAMP, 1400, 41, 300, 23, false",
        // When the first one is a ramp: shown from 1,500 m or 50 s.
        "OFF_RAMP, TURN, 200, 15, 1500, 90, true",
        "OFF_RAMP, TURN, 200, 15, 1501, 90, false",
        "OFF_RAMP, TURN, 200, 15, 1990, 50, true",
        "OFF_RAMP, TURN, 200, 15, 1990, 51, false",
        // Not started within 30 m of the first maneuver.
        "TURN, TURN, 150, 12, 30.5, 2, true",
        "TURN, TURN, 150, 12, 30, 2, false",
    )
    fun `each distance and each time decides alone, up to its bound`(
        next: ManeuverType,
        then: ManeuverType,
        metersApart: Double,
        secondsApart: Double,
        metersBefore: Double,
        secondsBefore: Double,
        expected: Boolean,
    ) {
        val pair = following(type = then, meters = metersApart, seconds = secondsApart)
        assertEquals(listOf(expected), shown(at(metersBefore, toNextTime = secondsBefore.seconds, next = next, following = pair)))
    }

    @Test
    fun `an unknown time counts for nothing and the distance decides`() {
        val unknownTime = following(meters = 500.0, seconds = 0.0)
        assertEquals(listOf(false), shown(at(200.0, following = unknownTime)))
        assertEquals(listOf(false), shown(at(1_000.0, toNextTime = Duration.ZERO)))
        // With no time at all, a pair within both distances still shows.
        val near = following(meters = 300.0, seconds = 0.0)
        assertEquals(listOf(true), shown(at(400.0, toNextTime = Duration.ZERO, following = near)))
    }

    @Test
    fun `reach covers either the distance or the time`() {
        val reach = Reach(Distance.meters(300.0), 20.seconds)
        assertTrue(reach.covers(Distance.meters(300.0), 60.seconds))
        assertTrue(reach.covers(Distance.meters(900.0), 20.seconds))
        assertFalse(reach.covers(Distance.meters(301.0), 21.seconds))
        assertFalse(reach.covers(Distance.meters(301.0), Duration.ZERO))
    }

    @Test
    fun `the snapshot hands out the following maneuver only while the chip shows`() {
        val hidden = at(390.0)
        assertNull(hidden.thenManeuver)
        assertSame(hidden.following, hidden.copy(showThen = true).thenManeuver)
        assertNull(at(390.0, following = null).copy(showThen = true).thenManeuver)
    }
}
