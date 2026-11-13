package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The look of the host card's images, and what a "car_look" line says. */
class CarLookTest {

    @Test
    fun `the host's mode names its look`() {
        assertEquals(Look.NIGHT, CarLook.lookOf(dark = true))
        assertEquals(Look.DAY, CarLook.lookOf(dark = false))
    }

    @Test
    fun `a car_look line has the host's mode beside the look the map shows`() {
        // The host says day while the app's look is the night's: the map shows the night.
        assertEquals(
            mapOf("dark" to false, "cause" to "APP", "look" to Look.NIGHT),
            CarLook.fields(dark = false, cause = CarLook.CAUSE_APP, request = LookOnMap(Look.NIGHT)),
        )
        // A theme that waits for a quiet moment says so; a mode the context could not read is left out.
        assertEquals(
            mapOf("cause" to "HOST", "look" to Look.NIGHT, "themeDeferred" to true),
            CarLook.fields(dark = null, cause = CarLook.CAUSE_HOST, request = LookOnMap(Look.NIGHT, theme = Look.DAY)),
        )
        assertEquals(mapOf("dark" to true, "cause" to "START", "look" to Look.DAY), CarLook.fields(true, CarLook.CAUSE_START, LookOnMap(Look.DAY)))
    }
}
