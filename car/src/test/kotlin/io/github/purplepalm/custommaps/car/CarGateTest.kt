package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.MapsAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the car's screen may show while the phone is set up, and what its map does on each change. */
class CarGateTest {

    @Test
    fun `the gate follows the token store and precise location`() {
        for (precise in listOf(true, false)) {
            assertEquals(CarGate.LOADING, CarGate.of(MapsAccess.Checking, precise))
            assertEquals(CarGate.NO_TOKEN, CarGate.of(MapsAccess.NeedsToken, precise))
        }
        assertEquals(CarGate.READY, CarGate.of(MapsAccess.Ready(1), preciseLocation = true))
        assertEquals(CarGate.NO_PRECISE_LOCATION, CarGate.of(MapsAccess.Ready(1), preciseLocation = false))
    }

    @Test
    fun `the map is made once the gate is ready, for the token of the moment`() {
        assertEquals(CarMapStep.Build(1), CarMapStep.next(null, MapsAccess.Ready(1), CarGate.READY, lookDecided = true))
        assertEquals(CarMapStep.Build(3), CarMapStep.next(null, MapsAccess.Ready(3), CarGate.READY, lookDecided = true))
    }

    @Test
    fun `the same token keeps the map`() {
        assertEquals(CarMapStep.Keep, CarMapStep.next(2, MapsAccess.Ready(2), CarGate.READY, lookDecided = true))
    }

    @Test
    fun `another token makes the map again`() {
        assertEquals(CarMapStep.Rebuild(3), CarMapStep.next(2, MapsAccess.Ready(3), CarGate.READY, lookDecided = true))
    }

    @Test
    fun `without a token the map goes, and none is made`() {
        assertEquals(CarMapStep.Release, CarMapStep.next(2, MapsAccess.NeedsToken, CarGate.NO_TOKEN, lookDecided = true))
        assertEquals(CarMapStep.Keep, CarMapStep.next(null, MapsAccess.NeedsToken, CarGate.NO_TOKEN, lookDecided = true))
    }

    @Test
    fun `no map is made before the app's first look is decided`() {
        // The car's host started the process: the gate is ready before the first look.
        assertEquals(CarMapStep.Keep, CarMapStep.next(null, MapsAccess.Ready(1), CarGate.READY, lookDecided = false))
        assertEquals(CarMapStep.Build(1), CarMapStep.next(null, MapsAccess.Ready(1), CarGate.READY, lookDecided = true))
    }

    @Test
    fun `the first look holds back only the first map`() {
        // A map there is, made after the first look, is made again for a new token and let go of as
        // before, whatever the look says.
        assertEquals(CarMapStep.Rebuild(3), CarMapStep.next(2, MapsAccess.Ready(3), CarGate.READY, lookDecided = false))
        assertEquals(CarMapStep.Release, CarMapStep.next(2, MapsAccess.NeedsToken, CarGate.NO_TOKEN, lookDecided = false))
        assertEquals(CarMapStep.Keep, CarMapStep.next(2, MapsAccess.Ready(2), CarGate.READY, lookDecided = false))
        assertEquals(CarMapStep.Keep, CarMapStep.next(null, MapsAccess.Checking, CarGate.LOADING, lookDecided = false))
    }

    @Test
    fun `no map is made while the token store is read or without precise location`() {
        assertEquals(CarMapStep.Keep, CarMapStep.next(null, MapsAccess.Checking, CarGate.LOADING, lookDecided = true))
        assertEquals(CarMapStep.Keep, CarMapStep.next(null, MapsAccess.Ready(1), CarGate.NO_PRECISE_LOCATION, lookDecided = true))
        assertEquals(CarMapStep.Release, CarMapStep.next(1, MapsAccess.Ready(1), CarGate.NO_PRECISE_LOCATION, lookDecided = true))
    }
}
