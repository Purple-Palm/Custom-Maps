package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Synthetic progress only: invented road names, distances and times. */
class ThenTrackerTest {

    /**
     * One progress update on the way to a left turn, with a right turn onto [road] 150 m after it.
     * Every call builds all of its objects anew, as the engine does for every update.
     */
    private fun at(toNext: Double, road: String = "Pine Street") = RouteProgressSnapshot(
        nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Oak Street"),
        distanceToNextManeuver = Distance.meters(toNext),
        lanes = null,
        distanceRemaining = Distance.meters(5_000.0),
        durationRemaining = 10.minutes,
        durationToNextManeuver = (toNext / 13.0).seconds,
        stepKey = "route-a/0/3",
        following = FollowingManeuver(Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, road), Distance.meters(150.0), 12.seconds),
    )

    @Test
    fun `each update gets the rule's answer, remembered from the updates before`() {
        val tracker = ThenTracker()
        // Outside the window, inside it, and back outside: once shown it stays for the step.
        assertEquals(listOf(false, true, true), listOf(at(420.0), at(390.0), at(430.0)).map { tracker.decide(it).showThen })
    }

    @Test
    fun `nothing but the answer changes in the update`() {
        val decided = ThenTracker().decide(at(390.0))
        assertEquals(at(390.0).copy(showThen = true), decided)
        assertEquals(at(390.0).following, decided.thenManeuver)
        // What the engine adds after the rule is not the tracker's: it comes back as it went in.
        val lanesAhead = LanesAhead(Distance.meters(600.0), LaneInfo(listOf(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true))))
        assertSame(lanesAhead, ThenTracker().decide(at(390.0).copy(laneChoiceAhead = lanesAhead)).laneChoiceAhead)
        assertNull(decided.laneChoiceAhead)
    }

    @Test
    fun `the following maneuver stays the same object while it is the same maneuver`() {
        val tracker = ThenTracker()
        val first = tracker.decide(at(420.0))
        val second = at(390.0)
        assertNotSame(first.following, second.following)
        assertSame(first.following, tracker.decide(second).following)
        // Another maneuver follows: its own object is handed out, and kept from then on.
        val changed = at(380.0, road = "Elm Street")
        assertSame(changed.following, tracker.decide(changed).following)
        assertSame(changed.following, tracker.decide(at(370.0, road = "Elm Street")).following)
    }

    @Test
    fun `nothing following is handed on as nothing`() {
        val tracker = ThenTracker()
        tracker.decide(at(390.0))
        val arriving = tracker.decide(at(390.0).copy(following = null))
        assertNull(arriving.following)
        assertFalse(arriving.showThen)
    }

    @Test
    fun `a reset forgets the drive before`() {
        val tracker = ThenTracker()
        assertTrue(tracker.decide(at(390.0)).showThen)
        // The same step again, outside the window: the chip stays.
        assertTrue(tracker.decide(at(430.0)).showThen)
        tracker.reset()
        // After a reset it is decided as on a first update.
        val again = at(430.0)
        val decided = tracker.decide(again)
        assertFalse(decided.showThen)
        assertSame(again.following, decided.following)
    }
}
