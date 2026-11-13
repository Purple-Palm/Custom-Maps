package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.car.routing.Drives.CHOICE_LANES
import io.github.purplepalm.custommaps.car.routing.Drives.EXIT
import io.github.purplepalm.custommaps.car.routing.Drives.EXIT_LANES
import io.github.purplepalm.custommaps.car.routing.Drives.NOW
import io.github.purplepalm.custommaps.car.routing.Drives.OFFICE
import io.github.purplepalm.custommaps.car.routing.Drives.PINE
import io.github.purplepalm.custommaps.car.routing.Drives.RUN_UP
import io.github.purplepalm.custommaps.car.routing.Drives.WORDS
import io.github.purplepalm.custommaps.car.routing.Drives.towardsExit
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.car.app.navigation.model.Maneuver as HostManeuver

/** What the car shows of one progress update: the phone card's maneuver, words, lane row and Then step. */
class CarRoutingTest {

    private fun of(progress: io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot, place: Place? = OFFICE) =
        CarRouting.of(progress, place, NOW, WORDS)

    @Test
    fun `the current step is the phone card's maneuver, arrow, words, distance and time`() {
        val routing = of(towardsExit(0.4))
        val current = checkNotNull(routing.current)
        assertEquals(false, routing.loading)
        assertEquals(CarManeuver(HostManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT), current.maneuver)
        assertEquals(GlyphShapes.maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT), current.arrow)
        // The road, then the card's second line; the road alone where the host has less room.
        assertEquals(CarCue("Exit 24: Oak Street · Office", short = "Exit 24: Oak Street"), current.cue)
        assertEquals(CarDistance(0.4, 5), current.distance)
        assertEquals(24L, current.estimate.remainingSeconds)
        assertEquals(NOW.plusSeconds(24), current.estimate.arrival)
        assertEquals(CarDistance(3.4, 5), routing.destination.distance)
        assertEquals(12 * 60L, routing.destination.remainingSeconds)
        assertEquals("Office", routing.destinationName)
    }

    @Test
    fun `a roundabout's exit is in the words as the card has it`() {
        val roundabout = Maneuver(ManeuverType.ROUNDABOUT, TurnModifier.LEFT, "Elm Street", roundaboutExitNumber = 3, roundaboutExitDegrees = 270.0)
        assertEquals(CarCue("Elm Street · Exit 3", short = "Elm Street"), CarRouting.currentCue(roundabout, WORDS))
        assertEquals(CarCue("Exit 3"), CarRouting.currentCue(roundabout.copy(primaryText = " "), WORDS))
        assertNull(CarRouting.currentCue(Maneuver(ManeuverType.TURN, TurnModifier.LEFT, ""), WORDS))
    }

    @Test
    fun `the lane row is the phone card's, the run-up's first, then a choice ahead, then the maneuver's own`() {
        val plain = towardsExit(1.2)
        val choice = plain.copy(laneChoiceAhead = LanesAhead(Distance.miles(0.4), CHOICE_LANES))
        val runUp = choice.copy(runUpLanes = RUN_UP)
        for (progress in listOf(plain, choice, runUp)) {
            val current = checkNotNull(of(progress).current)
            assertEquals(CardLanes.of(progress).lanes, current.lanes)
            assertEquals(LanesToCar.of(checkNotNull(current.lanes)), current.hostLanes)
        }
        assertEquals(EXIT_LANES, of(plain).current?.lanes)
        assertEquals(CHOICE_LANES, of(choice).current?.lanes)
        assertEquals(RUN_UP.lanes, of(runUp).current?.lanes)
        val none = of(plain.copy(lanes = null)).current
        assertNull(none?.lanes)
        assertEquals(emptyList<CarLane>(), none?.hostLanes)
    }

    @Test
    fun `the Then step shows exactly while the phone's Then chip does, with its words`() {
        assertNull(of(towardsExit(0.2)).then)
        val shown = of(towardsExit(0.2).copy(showThen = true))
        val then = checkNotNull(shown.then)
        assertEquals(CarManeuver(HostManeuver.TYPE_TURN_NORMAL_RIGHT), then.maneuver)
        assertEquals(CarCue("Pine Street"), then.cue)
        assertNull(then.lanes)
        // From the car: to the exit, then 250 m on.
        assertEquals(DistanceToCar.of(Distance.miles(0.2) + Distance.meters(250.0)), then.distance)
        assertEquals(12L + 15L, then.estimate.remainingSeconds)
    }

    @Test
    fun `at the arrival the Then step names the side, as the chip does`() {
        for ((turn, words) in listOf(TurnModifier.LEFT to "On the left", TurnModifier.SLIGHT_RIGHT to "On the right", null to "Destination")) {
            val arrival = FollowingManeuver(Maneuver(ManeuverType.ARRIVE, turn, "Oak Street"), Distance.meters(90.0), 12.seconds)
            val routing = of(towardsExit(0.1).copy(following = arrival, showThen = true))
            assertEquals(CarCue(words), routing.then?.cue, "$turn")
        }
    }

    @Test
    fun `a step time the route does not know stays unknown`() {
        val unknown = of(towardsExit(0.2).copy(durationToNextManeuver = Duration.ZERO, showThen = true))
        assertEquals(CarEstimate.UNKNOWN_SECONDS, unknown.current?.estimate?.remainingSeconds)
        assertEquals(CarEstimate.UNKNOWN_SECONDS, unknown.then?.estimate?.remainingSeconds)
    }

    @Test
    fun `off the route and before a new route's first instruction the card loads, keeping the destination`() {
        for (progress in listOf(towardsExit(0.4).copy(offRoute = true), towardsExit(0.4).copy(pending = true, showThen = true))) {
            val routing = of(progress)
            assertTrue(routing.loading)
            assertNull(routing.current)
            assertNull(routing.then)
            assertEquals(12 * 60L, routing.destination.remainingSeconds)
        }
    }

    @Test
    fun `the destination is named by its name, else its address, else the word`() {
        assertEquals("Office", of(towardsExit(1.0)).destinationName)
        assertEquals("12 Oak Street", of(towardsExit(1.0), OFFICE.copy(name = " ")).destinationName)
        assertEquals("Destination", of(towardsExit(1.0), Place("", null, GeoPoint(0.0, 0.0))).destinationName)
        assertEquals("Destination", of(towardsExit(1.0), null).destinationName)
    }

    @Test
    fun `the key changes with what the card shows, not with its numbers`() {
        val near = of(towardsExit(0.4))
        val nearer = of(towardsExit(0.3))
        assertEquals(near.key, nearer.key)
        assertNotEquals(near.key, of(towardsExit(0.3, step = "route/0/4")).key)
        assertNotEquals(near.key, of(towardsExit(0.3).copy(showThen = true)).key)
        assertNotEquals(near.key, of(towardsExit(0.3).copy(runUpLanes = RUN_UP)).key)
        assertNotEquals(near.key, of(towardsExit(0.3).copy(offRoute = true)).key)
        assertNotEquals(near.key, of(towardsExit(0.3).copy(nextManeuver = PINE)).key)
        assertNotEquals(near.key, of(towardsExit(0.3).copy(nextManeuver = EXIT.copy(secondaryText = "Gym"))).key)
    }
}
