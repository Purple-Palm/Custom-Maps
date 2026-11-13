package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.navigation.model.TravelEstimate
import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The time and distance to a step or to the destination, as the phone's guidance bar has them. */
class EstimateToCarTest {

    private val now = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `the arrival is now plus the whole seconds left, as on the phone`() {
        val estimate = EstimateToCar.destination(Distance.miles(12.3), 18.minutes + 20.seconds + 900.milliseconds, now)
        assertEquals(CarDistance(12.0, 4), estimate.distance)
        assertEquals(now.plusSeconds(1_100), estimate.arrival)
        assertEquals(1_100L, estimate.remainingSeconds)
    }

    @Test
    fun `no time left at the destination is the arrival`() {
        val estimate = EstimateToCar.destination(Distance.ZERO, Duration.ZERO, now)
        assertEquals(0L, estimate.remainingSeconds)
        assertEquals(now, estimate.arrival)
    }

    @Test
    fun `a step's time of zero is one the route does not know`() {
        val unknown = EstimateToCar.step(Distance.feet(350.0), Duration.ZERO, now)
        assertEquals(CarEstimate.UNKNOWN_SECONDS, unknown.remainingSeconds)
        assertEquals(now, unknown.arrival)
        val known = EstimateToCar.step(Distance.feet(350.0), 12.seconds, now)
        assertEquals(12L, known.remainingSeconds)
        assertEquals(now.plusSeconds(12), known.arrival)
    }

    @Test
    fun `a time below zero, without end or a week ahead is not known`() {
        for (remaining in listOf((-5).seconds, Duration.INFINITE, 7.days + 1.seconds)) {
            for (estimate in listOf(EstimateToCar.destination(Distance.miles(1.0), remaining, now), EstimateToCar.step(Distance.miles(1.0), remaining, now))) {
                assertEquals(CarEstimate.UNKNOWN_SECONDS, estimate.remainingSeconds, "$remaining")
                assertEquals(now, estimate.arrival, "$remaining")
            }
        }
        assertEquals(7 * 86_400L, EstimateToCar.destination(Distance.miles(1.0), 7.days, now).remainingSeconds)
    }

    @Test
    fun `the library takes every estimate the app sends, and refuses a time below zero`() {
        val estimates = listOf(
            EstimateToCar.destination(Distance.miles(12.3), 18.minutes, now),
            EstimateToCar.destination(Distance.ZERO, Duration.ZERO, now),
            EstimateToCar.step(Distance.feet(40.0), Duration.ZERO, now),
            EstimateToCar.step(Distance.feet(40.0), Duration.INFINITE, now),
        )
        for (estimate in estimates) {
            val host = assertDoesNotThrow<TravelEstimate> { estimate.toHost() }
            assertEquals(estimate.remainingSeconds, host.remainingTimeSeconds)
            assertEquals(estimate.distance.value, host.remainingDistance?.displayDistance)
            assertEquals(estimate.arrival.toInstant().toEpochMilli(), host.arrivalTimeAtDestination?.timeSinceEpochMillis)
        }
        assertEquals(-1L, CarEstimate.UNKNOWN_SECONDS)
        assertThrows(IllegalArgumentException::class.java) {
            TravelEstimate.Builder(CarDistance(1.0, 4).toHost(), now).setRemainingTimeSeconds(-2)
        }
    }
}
