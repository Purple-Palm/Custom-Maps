package io.github.purplepalm.custommaps.nav.trip

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.lanes.LaneMeasureGate
import io.github.purplepalm.custommaps.nav.look.Look
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Invented trips in the sea near the point 0, 0, on an invented monotonic clock. */
class TripSummaryTest {

    @TempDir
    lateinit var temp: File

    /** 0.001 degrees of longitude on the equator. */
    private val step = Geo.meters(0.0, 0.0, 0.0, 0.001)

    @Test
    fun `a trip adds up its time, way, reroutes and gaps`() {
        val tally = TripTally()
        tally.begin(nowMillis = 10_000L, simulated = false, offline = false)
        tally.onTripId("session-a")
        tally.onProgress(11_500L)
        tally.onPosition(0.0, 0.0, jump = false)
        tally.onProgress(12_500L)
        tally.onPosition(0.0, 0.001, jump = false)
        // A gap of 4.2 s, as when updates stop for a while.
        tally.onProgress(16_700L)
        tally.onPosition(0.0, 0.002, jump = false)
        tally.onReroute()
        tally.onProgress(17_700L)

        val fields = tally.fields(nowMillis = 70_000L, staleUpdates = 0)

        assertEquals("session-a", fields["trip"])
        assertEquals(false, fields["simulated"])
        assertEquals(60.0, fields["seconds"])
        assertEquals(Math.round(2 * step).toDouble(), fields["meters"])
        assertEquals(222.0, fields["meters"])
        assertEquals(1, fields["reroutes"])
        assertEquals(1.5, fields["firstProgressS"])
        assertEquals(4.2, fields["longestGapS"])
        assertEquals(false, fields["staleStart"])
        assertEquals(0, fields["staleUpdates"])
    }

    @Test
    fun `a trip the measuring switch measured close views of carries the frame gate, and the next trip starts without`() {
        val tally = TripTally()
        tally.begin(0L, simulated = true, offline = false)
        assertFalse("laneGate" in tally.fields(1_000L, staleUpdates = 0), "no view measured, no gate")
        // Made-up views: six of each kind at night, the drawing's within the budget.
        repeat(LaneMeasureGate.MIN_VIEWS) {
            tally.onLaneView(LaneMeasureGate.View(Look.NIGHT, drawn = true, renderP95Ms = 9.0, missedPerSecond = 2.0, uploadMaxMs = 3.0, counts = true))
            tally.onLaneView(LaneMeasureGate.View(Look.NIGHT, drawn = false, renderP95Ms = 8.5, missedPerSecond = 2.0, uploadMaxMs = 3.0, counts = true))
        }
        val gate = tally.fields(2_000L, staleUpdates = 0)["laneGate"] as Map<*, *>
        assertEquals("pass", gate["gate"])
        assertEquals(12, gate["views"])
        tally.end()
        // A view after the trip ended counts into none.
        tally.onLaneView(LaneMeasureGate.View(Look.DAY, drawn = true, renderP95Ms = 9.0, missedPerSecond = 2.0, uploadMaxMs = 3.0, counts = true))
        tally.begin(5_000L, simulated = true, offline = false)
        assertFalse("laneGate" in tally.fields(6_000L, staleUpdates = 0))
    }

    @Test
    fun `a jump of the position is not driven`() {
        val tally = TripTally()
        tally.begin(0L, simulated = true, offline = false)
        tally.onPosition(0.0, 0.0, jump = false)
        tally.onPosition(0.0, 0.001, jump = false)
        // The navigator puts the car 2 km north at once.
        tally.onPosition(0.018, 0.001, jump = true)
        tally.onPosition(0.018, 0.002, jump = false)

        assertEquals(Math.round(2 * step).toDouble(), tally.fields(1_000L, 0)["meters"])
        assertEquals(true, tally.fields(1_000L, 0)["simulated"])
    }

    @Test
    fun `each time the car leaves the route is one episode`() {
        val tally = TripTally()
        tally.begin(0L, simulated = false, offline = false)
        tally.onOffRoute(false)
        tally.onOffRoute(true)
        tally.onOffRoute(true)
        tally.onOffRoute(false)
        tally.onOffRoute(true)

        assertEquals(2, tally.fields(0L, 0)["offRouteEpisodes"])
    }

    @Test
    fun `map errors are counted by kind`() {
        val tally = TripTally()
        tally.begin(0L, simulated = false, offline = false)
        listOf("TILE", "STYLE", "TILE", "TILE").forEach(tally::onMapError)

        assertEquals(mapOf("STYLE" to 1, "TILE" to 3), tally.fields(0L, 0)["mapErrors"])
    }

    @Test
    fun `offline time is added up, also while it lasts at the end`() {
        val tally = TripTally()
        tally.begin(0L, simulated = false, offline = true)
        tally.onOffline(false, 3_000L)
        tally.onOffline(false, 4_000L)
        tally.onOffline(true, 10_000L)

        assertEquals(15.5, tally.fields(22_500L, 0)["offlineSeconds"])
    }

    @Test
    fun `a stale start is flagged with its updates`() {
        val tally = TripTally()
        tally.begin(0L, simulated = false, offline = false)

        val fields = tally.fields(1_000L, staleUpdates = 2)

        assertEquals(true, fields["staleStart"])
        assertEquals(2, fields["staleUpdates"])
    }

    @Test
    fun `a trip without progress has no first progress and no gap`() {
        val tally = TripTally()
        tally.begin(0L, simulated = false, offline = false)

        val fields = tally.fields(5_000L, 0)

        assertNull(fields["firstProgressS"])
        assertNull(fields["longestGapS"])
        assertNull(fields["trip"])
    }

    @Test
    fun `nothing is counted outside a trip, and each trip starts over`() {
        val tally = TripTally()
        tally.onReroute()
        tally.onMapError("TILE")
        assertFalse(tally.running)
        tally.begin(0L, simulated = false, offline = false)
        tally.onReroute()
        tally.end()
        tally.onReroute()
        tally.begin(0L, simulated = false, offline = false)

        assertEquals(0, tally.fields(0L, 0)["reroutes"])
        assertEquals(emptyMap<String, Int>(), tally.fields(0L, 0)["mapErrors"])
    }

    @Test
    fun `the phone's counters give what the trip cost`() {
        val fields = TripTally.vitalsFields(TripVitals(1_000_000L, 80), TripVitals(4_500_000L, 74))
        assertEquals(3_500_000L, fields["receivedBytes"])
        assertEquals(6, fields["batteryUsedPercent"])
        assertEquals(setOf("receivedBytes", "batteryUsedPercent"), fields.keys)
        // Charging during the trip: below zero.
        assertEquals(-3, TripTally.vitalsFields(TripVitals(null, 50), TripVitals(null, 53))["batteryUsedPercent"])
        // A counter that went back, or one without a number, gives nothing.
        assertEquals(emptyMap<String, Any?>(), TripTally.vitalsFields(TripVitals(9_000L, null), TripVitals(1_000L, 40)))
        assertEquals(emptyMap<String, Any?>(), TripTally.vitalsFields(null, TripVitals(1_000L, 40)))
    }

    @Test
    fun `every way of stopping has its ending`() {
        assertEquals(TripEnding.END_IN_APP, TripEnding.of(StopReason.USER_CANCELLED))
        assertEquals(TripEnding.END_IN_NOTIFICATION, TripEnding.of(StopReason.STOPPED_OUTSIDE_APP))
        assertEquals(
            listOf("arrived", "end_in_app", "end_in_notification", "process_death"),
            TripEnding.entries.map { it.logName },
        )
    }

    @Test
    fun `the marker keeps what the trip added up`() {
        val marker = TripMarker(File(temp, "trip-running"))
        val tally = TripTally()
        tally.begin(0L, simulated = true, offline = false)
        tally.onTripId("session-b")
        tally.onMapError("TILE")
        tally.onProgress(500L)
        val fields = tally.fields(90_000L, staleUpdates = 1)

        assertNull(marker.read())
        assertTrue(marker.write(fields))
        val read = marker.read()

        assertEquals(
            mapOf(
                "trip" to "session-b",
                "simulated" to true,
                "seconds" to 90.0,
                "meters" to 0.0,
                "reroutes" to 0L,
                "offRouteEpisodes" to 0L,
                "firstProgressS" to 0.5,
                "mapErrors" to mapOf("TILE" to 1L),
                "offlineSeconds" to 0.0,
                "longestGapS" to 0.0,
                "staleStart" to true,
                "staleUpdates" to 1L,
            ),
            read,
        )
        marker.clear()
        assertNull(marker.read())
    }

    @Test
    fun `a marker line that cannot be read is skipped`() {
        assertEquals(mapOf("a" to 1L, "b" to "x=y"), TripMarker.decode("a=1\nno separator\n=2\nb=x=y\n"))
        assertEquals("trip=a b\n", TripMarker.encode(mapOf("trip" to "a\nb", "none" to null)))
    }
}
