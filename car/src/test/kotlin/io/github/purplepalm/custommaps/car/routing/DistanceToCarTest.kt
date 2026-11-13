package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.model.Distance
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import androidx.car.app.model.Distance as HostDistance

/** Distances as the phone shows them, in the units the car's host takes. */
class DistanceToCarTest {

    @ParameterizedTest(name = "{0} m -> {1} unit {2}")
    @CsvSource(
        "0, 0.0, 6",
        // 30 m is 98.4 ft: tens of feet below 100.
        "30, 100.0, 6",
        // 100 m is 328 ft: fifties above.
        "100, 350.0, 6",
        // 160 m is 525 ft, the last feet before a tenth of a mile.
        "160, 500.0, 6",
        // 161 m is 528 ft, a tenth of a mile.
        "161, 0.1, 5",
        "1609.344, 1.0, 5",
        // 9.94 mi keeps its tenth; 9.96 mi rounds to ten whole miles.
        "15996.88, 9.9, 5",
        "16029.07, 10.0, 4",
        "19312.13, 12.0, 4",
    )
    fun `feet, then miles with a tenth, then whole miles`(meters: Double, value: Double, unit: Int) {
        assertEquals(CarDistance(value, unit), DistanceToCar.of(Distance.meters(meters)))
    }

    @Test
    fun `the units are the library's feet, miles with one decimal and miles`() {
        assertEquals(6, HostDistance.UNIT_FEET)
        assertEquals(5, HostDistance.UNIT_MILES_P1)
        assertEquals(4, HostDistance.UNIT_MILES)
    }

    @Test
    fun `the car says what the phone says, every meter up to 30 km`() {
        for (meters in 0..30_000) {
            val distance = Distance.meters(meters.toDouble())
            val car = DistanceToCar.of(distance)
            assertEquals(ImperialFormat.distance(distance).toString(), hostText(car), "$meters m")
            assertTrue(car.value >= 0.0, "$meters m")
        }
    }

    @Test
    fun `the library takes every distance the app sends, and refuses one below zero`() {
        for (meters in listOf(0.0, 1.0, 161.0, 16_029.07, 1.0e6)) {
            val car = DistanceToCar.of(Distance.meters(meters))
            val host = assertDoesNotThrow<HostDistance> { car.toHost() }
            assertEquals(car.value, host.displayDistance)
            assertEquals(car.unit, host.displayUnit)
        }
        assertThrows(IllegalArgumentException::class.java) { HostDistance.create(-0.1, HostDistance.UNIT_FEET) }
    }

    /**
     * What the host shows for [distance] by the library's own description: miles of [HostDistance.UNIT_MILES_P1]
     * always with one decimal, other units with a decimal only where the number has one, and every
     * digit beyond cut, not rounded (1.46 km shows "1.4 km").
     */
    private fun hostText(distance: CarDistance): String {
        val number = BigDecimal(distance.value.toString())
        val shown = when (distance.unit) {
            HostDistance.UNIT_MILES_P1 -> number.setScale(1, RoundingMode.DOWN).toPlainString()
            else -> number.setScale(1, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
        }
        val unit = if (distance.unit == HostDistance.UNIT_FEET) "ft" else "mi"
        return "$shown $unit"
    }
}
