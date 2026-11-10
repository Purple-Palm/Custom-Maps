package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.guidance.StartPlan.AS_PREVIEWED
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REFRESH_IN_TRIP
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REPLAN_IN_TRIP
import io.github.purplepalm.custommaps.nav.model.Distance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Invented ages and distances, on both sides of each limit. */
class StartFreshnessTest {

    private fun plan(age: Duration, movedMeters: Double?, simulated: Boolean = false): StartPlan =
        StartFreshness.plan(age, movedMeters?.let(Distance::meters), simulated)

    @Test
    fun `the limits are five minutes, a hundred meters and half an hour`() {
        assertEquals(5.minutes, StartFreshness.REFRESH_AFTER)
        assertEquals(Distance.meters(100.0), StartFreshness.MOVED_BEYOND)
        assertEquals(30.minutes, StartFreshness.REPLAN_AFTER)
    }

    @Test
    fun `a fresh preview with the car where it was starts as previewed`() {
        assertEquals(AS_PREVIEWED, plan(10.seconds, 0.0))
        assertEquals(AS_PREVIEWED, plan(Duration.ZERO, 0.0))
        assertEquals(AS_PREVIEWED, plan(2.minutes, 20.0))
    }

    @Test
    fun `exactly five minutes is still as previewed, a moment more is refreshed`() {
        assertEquals(AS_PREVIEWED, plan(5.minutes, 0.0))
        assertEquals(REFRESH_IN_TRIP, plan(5.minutes + 1.milliseconds, 0.0))
        assertEquals(AS_PREVIEWED, plan(5.minutes - 1.milliseconds, 0.0))
    }

    @Test
    fun `an older preview is refreshed inside the trip`() {
        assertEquals(REFRESH_IN_TRIP, plan(6.minutes, 0.0))
        assertEquals(REFRESH_IN_TRIP, plan(8.minutes, 22.0))
        assertEquals(REFRESH_IN_TRIP, plan(29.minutes, 99.0))
    }

    @Test
    fun `exactly a hundred meters is still as previewed, a step more is refreshed`() {
        assertEquals(AS_PREVIEWED, plan(1.minutes, 100.0))
        assertEquals(REFRESH_IN_TRIP, plan(1.minutes, 100.5))
        assertEquals(AS_PREVIEWED, plan(1.minutes, 99.5))
    }

    @Test
    fun `a car that moved is refreshed, however fresh the preview and however far it went`() {
        assertEquals(REFRESH_IN_TRIP, plan(10.seconds, 300.0))
        assertEquals(REFRESH_IN_TRIP, plan(Duration.ZERO, 5_000.0))
        // Never planned again for the distance alone: a car off the route is rerouted by the navigator.
        assertEquals(REFRESH_IN_TRIP, plan(29.minutes, 50_000.0))
    }

    @Test
    fun `exactly half an hour is still refreshed, a moment more is planned again`() {
        assertEquals(REFRESH_IN_TRIP, plan(30.minutes, 0.0))
        assertEquals(REPLAN_IN_TRIP, plan(30.minutes + 1.milliseconds, 0.0))
    }

    @Test
    fun `a very old preview is planned again, moved or not`() {
        assertEquals(REPLAN_IN_TRIP, plan(40.minutes, 0.0))
        assertEquals(REPLAN_IN_TRIP, plan(40.minutes, 300.0))
        assertEquals(REPLAN_IN_TRIP, plan(3.hours, null))
        assertEquals(REPLAN_IN_TRIP, plan(2.days, 0.0))
        // An age that is not known is handed over as an endless one.
        assertEquals(REPLAN_IN_TRIP, plan(Duration.INFINITE, null))
    }

    @Test
    fun `with no position only the age counts`() {
        assertEquals(AS_PREVIEWED, plan(10.seconds, null))
        assertEquals(AS_PREVIEWED, plan(5.minutes, null))
        assertEquals(REFRESH_IN_TRIP, plan(10.minutes, null))
        assertEquals(REPLAN_IN_TRIP, plan(31.minutes, null))
    }

    @Test
    fun `a simulated drive always starts as previewed`() {
        assertEquals(AS_PREVIEWED, plan(10.seconds, 0.0, simulated = true))
        assertEquals(AS_PREVIEWED, plan(6.minutes, 0.0, simulated = true))
        assertEquals(AS_PREVIEWED, plan(1.minutes, 300.0, simulated = true))
        assertEquals(AS_PREVIEWED, plan(2.hours, 5_000.0, simulated = true))
        assertEquals(AS_PREVIEWED, plan(Duration.INFINITE, null, simulated = true))
    }

    @Test
    fun `an age below zero counts as no age at all`() {
        // A clock that was set back must not turn a fresh preview into an old one.
        assertEquals(AS_PREVIEWED, plan((-3).minutes, 0.0))
        assertEquals(REFRESH_IN_TRIP, plan((-3).minutes, 300.0))
    }
}
