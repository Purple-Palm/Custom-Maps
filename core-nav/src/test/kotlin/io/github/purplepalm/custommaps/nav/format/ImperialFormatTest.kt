package io.github.purplepalm.custommaps.nav.format

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ImperialFormatTest {

    @ParameterizedTest(name = "{0} ft -> {1}")
    @CsvSource(
        "0, 0 ft",
        "37, 40 ft",
        "99, 100 ft",
        "330, 350 ft",
        "520, 500 ft",
        "526, 500 ft",
    )
    fun `short distances are shown in feet`(feet: Double, expected: String) {
        assertEquals(expected, ImperialFormat.distance(Distance.feet(feet)).toString())
    }

    @ParameterizedTest(name = "{0} mi -> {1}")
    @CsvSource(
        "0.1, 0.1 mi",
        "0.25, 0.3 mi",
        "1.0, 1.0 mi",
        "4.46, 4.5 mi",
        "9.96, 10 mi",
        "12.4, 12 mi",
        "120.6, 121 mi",
    )
    fun `longer distances are shown in miles`(miles: Double, expected: String) {
        assertEquals(expected, ImperialFormat.distance(Distance.miles(miles)).toString())
    }

    @Test
    fun `value and unit are separate for large digits`() {
        val shown = ImperialFormat.distance(Distance.feet(330.0))
        assertEquals("350", shown.value)
        assertEquals("ft", shown.unit)
    }

    @Test
    fun `durations round to whole minutes and never show zero`() {
        assertEquals("1 min", ImperialFormat.duration(20.seconds))
        assertEquals("1 min", ImperialFormat.duration(89.seconds))
        assertEquals("2 min", ImperialFormat.duration(90.seconds))
        assertEquals("59 min", ImperialFormat.duration(59.minutes))
        assertEquals("1 hr", ImperialFormat.duration(60.minutes))
        assertEquals("1 hr 5 min", ImperialFormat.duration(65.minutes))
    }

    @Test
    fun `arrival time is a US clock time`() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals("3:45 PM", ImperialFormat.arrivalTime(ZonedDateTime.of(2026, 11, 3, 15, 30, 0, 0, zone), 15.minutes))
        assertEquals("12:10 AM", ImperialFormat.arrivalTime(ZonedDateTime.of(2026, 11, 3, 23, 50, 0, 0, zone), 20.minutes))
    }
}
