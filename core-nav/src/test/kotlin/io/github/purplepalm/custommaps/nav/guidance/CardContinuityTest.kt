package io.github.purplepalm.custommaps.nav.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CardContinuityTest {

    @Test
    fun `an update with a maneuver is shown, whatever its route`() {
        assertEquals(CardUpdate.FRESH, CardContinuity.next("route-a", "route-a", hasManeuver = true))
        assertEquals(CardUpdate.FRESH, CardContinuity.next("route-a", "route-b", hasManeuver = true))
        assertEquals(CardUpdate.FRESH, CardContinuity.next(null, "route-a", hasManeuver = true))
    }

    @Test
    fun `a second without a maneuver on the same route keeps the card`() {
        assertEquals(CardUpdate.KEEP, CardContinuity.next("route-a", "route-a", hasManeuver = false))
    }

    @Test
    fun `a new route without a maneuver yet never keeps the old route's card`() {
        // A reroute, a switch to another route, a replan at Start.
        assertEquals(CardUpdate.PENDING, CardContinuity.next("route-a", "route-b", hasManeuver = false))
    }

    @Test
    fun `the first update of a drive without a maneuver is pending too`() {
        assertEquals(CardUpdate.PENDING, CardContinuity.next(null, "route-a", hasManeuver = false))
    }

    @Test
    fun `the debug log names each the way the progress line has it`() {
        assertEquals(listOf("fresh", "kept", "pending"), CardUpdate.values().map { it.logName })
    }
}
