package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's list of the maneuvers ahead, on invented roads: distances from the car and dimming. */
class ManeuverListTest {

    /** The leg after a right turn onto Oak Street: a left onto Pine Street, a roundabout, the destination. */
    private val upcoming = listOf(
        FollowingManeuver(Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Pine Street"), Distance.meters(300.0), 25.seconds),
        FollowingManeuver(
            Maneuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT, "Elm Street", roundaboutExitNumber = 2, roundaboutExitDegrees = 180.0),
            Distance.meters(1_500.0),
            110.seconds,
        ),
        FollowingManeuver(Maneuver(ManeuverType.ARRIVE, TurnModifier.RIGHT, "Office"), Distance.meters(2_100.0), 160.seconds),
    )

    private fun at(toNext: Double, pending: Boolean = false, offRoute: Boolean = false) = RouteProgressSnapshot(
        nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Oak Street"),
        distanceToNextManeuver = Distance.meters(toNext),
        lanes = null,
        distanceRemaining = Distance.meters(toNext + 2_100.0),
        durationRemaining = 4.minutes,
        stepKey = "route-a/0/3",
        upcoming = if (pending) emptyList() else upcoming,
        pending = pending,
        offRoute = offRoute,
    )

    private fun shown(progress: RouteProgressSnapshot) =
        progress.upcoming.map { ImperialFormat.distance(ManeuverList.distanceFromCar(progress, it)).toString() }

    @Test
    fun `each maneuver is as far from the car as the next one, and then its own distance after it`() {
        val progress = at(400.0)
        assertEquals(
            listOf(700.0, 1_900.0, 2_500.0),
            progress.upcoming.map { ManeuverList.distanceFromCar(progress, it).meters },
        )
    }

    @Test
    fun `the distances are rounded as the card rounds its own`() {
        // 700 m is 0.43 mi, 1,900 m 1.18 mi and 2,500 m 1.55 mi: tenths of a mile, as the card shows them.
        assertEquals(listOf("0.4 mi", "1.2 mi", "1.6 mi"), shown(at(400.0)))
        // Close to the turn: 300 m on from 30 m away is 1,083 ft, still in tenths; 15 m before a short
        // block, feet in steps of 50.
        assertEquals("0.2 mi", shown(at(30.0)).first())
        val short = at(15.0).copy(upcoming = listOf(upcoming.first().copy(distanceAfterNext = Distance.meters(75.0))))
        // 90 m is 295 ft: 300 ft.
        assertEquals(listOf("300 ft"), shown(short))
        assertEquals(ImperialFormat.distance(Distance.meters(90.0)).toString(), shown(short).single())
    }

    @Test
    fun `as the car goes on, only the distances change, from the same list of the step`() {
        val before = at(400.0)
        val after = at(200.0).copy(upcoming = before.upcoming)
        assertSame(before.upcoming, after.upcoming)
        assertEquals(listOf("0.3 mi", "1.1 mi", "1.4 mi"), shown(after))
        assertEquals(
            before.upcoming.map { ManeuverList.distanceFromCar(before, it).meters - 200.0 },
            after.upcoming.map { ManeuverList.distanceFromCar(after, it).meters },
        )
    }

    @Test
    fun `the list is dimmed while the card waits for a new route and while the car is off the route`() {
        assertFalse(ManeuverList.dimmed(at(400.0)))
        assertTrue(ManeuverList.dimmed(at(400.0, pending = true)))
        assertTrue(ManeuverList.dimmed(at(400.0, offRoute = true)))
        // A pending card has no maneuvers ahead to show.
        assertEquals(emptyList<String>(), shown(at(400.0, pending = true)))
    }
}
