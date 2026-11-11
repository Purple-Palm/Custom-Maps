package io.github.purplepalm.custommaps.nav.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The card's updates as a drive has them, one after the other. */
class CardContinuityTest {

    @Test
    fun `an update with a maneuver is shown, whatever its route`() {
        val card = CardContinuity()
        assertEquals(CardUpdate.FRESH, card.next("route-a", hasManeuver = true))
        assertEquals(CardUpdate.FRESH, card.next("route-a", hasManeuver = true))
        assertEquals(CardUpdate.FRESH, card.next("route-b", hasManeuver = true))
    }

    @Test
    fun `a new route without a maneuver yet never keeps the old route's card`() {
        // A reroute, a switch to another route, a replan at Start.
        val card = CardContinuity()
        card.next("route-a", hasManeuver = true)
        assertEquals(CardUpdate.PENDING, card.next("route-b", hasManeuver = false))
    }

    @Test
    fun `a second without a maneuver on the route of the card keeps it`() {
        val card = CardContinuity()
        card.next("route-a", hasManeuver = true)
        assertEquals(CardUpdate.KEEP, card.next("route-a", hasManeuver = false))
        assertEquals(CardUpdate.KEEP, card.next("route-a", hasManeuver = false))
    }

    @Test
    fun `a pending card is of the new route, and a second without a maneuver keeps it`() {
        val card = CardContinuity()
        card.next("route-a", hasManeuver = true)
        assertEquals(CardUpdate.PENDING, card.next("route-b", hasManeuver = false))
        assertEquals(CardUpdate.KEEP, card.next("route-b", hasManeuver = false))
        assertEquals(CardUpdate.FRESH, card.next("route-b", hasManeuver = true))
    }

    @Test
    fun `the first update of a drive without a maneuver is pending too`() {
        assertEquals(CardUpdate.PENDING, CardContinuity().next("route-a", hasManeuver = false))
    }

    @Test
    fun `after the card is gone the next update finds none to keep`() {
        val card = CardContinuity()
        card.next("route-a", hasManeuver = true)
        card.reset()
        assertEquals(CardUpdate.PENDING, card.next("route-a", hasManeuver = false))
    }

    @Test
    fun `the debug log names each the way the progress line has it`() {
        assertEquals(listOf("fresh", "kept", "pending"), CardUpdate.values().map { it.logName })
    }
}
