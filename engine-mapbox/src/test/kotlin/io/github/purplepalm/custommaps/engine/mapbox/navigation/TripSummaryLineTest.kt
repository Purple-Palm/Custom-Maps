package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.trip.TripEnding
import io.github.purplepalm.custommaps.nav.trip.TripTally
import io.github.purplepalm.custommaps.nav.trip.TripVitals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The "trip_summary" line as the engine writes it. Invented values. */
class TripSummaryLineTest {

    @Test
    fun `the line starts with the trip, whether it was simulated and how it ended`() {
        val tally = TripTally()
        tally.begin(0L, simulated = true, offline = false)
        tally.onTripId("session-c")
        tally.onReroute()

        val line = summaryLine(
            tally.fields(30_000L, staleUpdates = 0),
            TripEnding.END_IN_NOTIFICATION,
            TripTally.vitalsFields(TripVitals(100L, 90), TripVitals(600L, 89)),
        )

        assertEquals(listOf("trip", "simulated", "ending"), line.keys.take(3))
        assertEquals("session-c", line["trip"])
        assertEquals(true, line["simulated"])
        assertEquals("end_in_notification", line["ending"])
        assertEquals(1, line["reroutes"])
        assertEquals(30.0, line["seconds"])
        assertEquals(500L, line["receivedBytes"])
        assertEquals(1, line["batteryUsedPercent"])
    }

    @Test
    fun `a trip found at the next start says the process ended`() {
        val left = mapOf("trip" to "session-d", "simulated" to false, "seconds" to 120.0, "reroutes" to 2L)

        val line = summaryLine(left, TripEnding.PROCESS_DEATH, emptyMap())

        assertEquals(mapOf("trip" to "session-d", "simulated" to false, "ending" to "process_death", "seconds" to 120.0, "reroutes" to 2L), line)
        assertEquals(listOf("trip", "simulated", "ending", "seconds", "reroutes"), line.keys.toList())
    }
}
