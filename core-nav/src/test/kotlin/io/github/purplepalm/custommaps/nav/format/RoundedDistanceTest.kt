package io.github.purplepalm.custommaps.nav.format

import io.github.purplepalm.custommaps.nav.model.Distance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The rounding behind every distance the app shows, as numbers, and the phone's text made from it. */
class RoundedDistanceTest {

    @ParameterizedTest(name = "{0} ft -> {1} {2}")
    @CsvSource(
        "0, 0, FEET",
        "37, 40, FEET",
        "99, 100, FEET",
        "330, 350, FEET",
        // The last feet before a tenth of a mile stay at 500.
        "526, 500, FEET",
        // A tenth of a mile is 528 ft.
        "528, 1, TENTHS_OF_MILE",
    )
    fun `short distances in feet`(feet: Double, amount: Long, unit: ImperialUnit) {
        assertEquals(RoundedDistance(amount, unit), ImperialFormat.rounded(Distance.feet(feet)))
    }

    @ParameterizedTest(name = "{0} mi -> {1} {2}")
    @CsvSource(
        "0.25, 3, TENTHS_OF_MILE",
        "4.46, 45, TENTHS_OF_MILE",
        "9.94, 99, TENTHS_OF_MILE",
        // Rounded up to ten miles, it is whole miles.
        "9.96, 10, MILES",
        "120.6, 121, MILES",
    )
    fun `longer distances in tenths of a mile, then whole miles`(miles: Double, amount: Long, unit: ImperialUnit) {
        assertEquals(RoundedDistance(amount, unit), ImperialFormat.rounded(Distance.miles(miles)))
    }

    @Test
    fun `the phone's text says exactly the rounded numbers, every meter up to 30 km`() {
        for (meters in 0..30_000) {
            val distance = Distance.meters(meters.toDouble())
            val rounded = ImperialFormat.rounded(distance)
            val expected = when (rounded.unit) {
                ImperialUnit.FEET -> "${rounded.amount} ft"
                ImperialUnit.TENTHS_OF_MILE -> "${rounded.amount / 10}.${rounded.amount % 10} mi"
                ImperialUnit.MILES -> "${rounded.amount} mi"
            }
            assertEquals(expected, ImperialFormat.distance(distance).toString(), "$meters m")
        }
    }
}
