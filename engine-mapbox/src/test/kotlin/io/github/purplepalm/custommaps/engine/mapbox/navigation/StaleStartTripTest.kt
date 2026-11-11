package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.bindgen.Value
import com.mapbox.common.location.Location
import com.mapbox.common.location.LocationExtraKeys
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.FixTime
import io.github.purplepalm.custommaps.nav.guidance.StaleStart
import io.github.purplepalm.custommaps.nav.guidance.StartPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The first updates of a trip as the engine hands them on: an update that still carries where the
 * trip before left the car reaches neither the screen nor the start plan. The engine itself cannot
 * be made on the JVM; this follows its order of calls (a position from the trip session's source,
 * then the navigator's matched position and its progress). Invented routes in the sea near 0, 0.
 */
class StaleStartTripTest {

    private data class Route(val id: String, val end: Point)

    /** The route of the trip: along the equator, 2.2 km east of 0, 0. */
    private val route = Route("trip#0", Point.fromLngLat(0.02, 0.0))
    private val onRoute = Point.fromLngLat(0.001, 0.0)

    /** Where the trip before ended: 0.018 degrees north of the route, which is 2 km. */
    private val whereTheLastTripEnded = Point.fromLngLat(0.001, 0.018)

    /** The navigator as the start plan sees it; a route request starts where the car is matched now. */
    private inner class Navigator : TripRoutes<Route> {
        val requestedFrom = mutableListOf<Point>()

        override fun refresh(done: (RefreshOutcome) -> Unit) = Unit

        override fun request(done: (ReplanAnswer<Route>) -> Unit): Long {
            requestedFrom += matched
            return requestedFrom.size.toLong()
        }

        override fun cancel(requestId: Long) = Unit

        override fun set(routes: List<Route>) = Unit

        override fun summary(route: Route): String? = null

        override fun destination(route: Route): Point = route.end
    }

    private val navigator = Navigator()
    private val refresher = StartRefresher(navigator, DebugLog.NONE, guidanceActive = { true }, elapsedMillis = { 0L })
    private val rule = StaleStart()

    /** What the card, the Then chip and the lane choices were given, in order. */
    private val shown = mutableListOf<Point>()
    private var matched: Point = whereTheLastTripEnded

    /** Seconds since the phone started, on both clocks; the wall clock from 2000-01-01 UTC. */
    private fun at(seconds: Double) = FixTime((seconds * 1e9).toLong(), 946_684_800_000L + (seconds * 1e3).toLong())

    /**
     * A position as the SDK hands it over, measured [seconds] after the phone started: [mock] is the
     * mark the replayer of a simulated drive sets and the navigator keeps on the positions it
     * matches; null for a position of the phone's location service that comes without it.
     */
    private fun location(position: Point, seconds: Double, mock: Boolean?): Location = Location.Builder()
        .latitude(position.latitude())
        .longitude(position.longitude())
        .timestamp(946_684_800_000L + (seconds * 1e3).toLong())
        .monotonicTimestamp((seconds * 1e9).toLong())
        .apply { if (mock != null) extra(Value.valueOf(hashMapOf(LocationExtraKeys.IS_MOCK to Value(mock)))) }
        .build()

    /** One update of the navigator: its matched position, when that was measured, and its progress. */
    private fun update(position: Point, measured: FixTime) {
        matched = position
        if (rule.isStale(measured)) {
            rule.countStaleUpdate()
            return
        }
        shown += position
        if (refresher.awaitsProgress) refresher.onProgress(TripMoment(route.id, route.end, live = true))
    }

    @Test
    fun `two updates with the last trip's position, then one on the route`() {
        assertEquals(2_001.5, Geo.meters(whereTheLastTripEnded.latitude(), whereTheLastTripEnded.longitude(), onRoute.latitude(), onRoute.longitude()), 0.5)
        // Planned again at Start, so the first update of the trip would request routes from where
        // the car is matched. The trip before ended 20 s before this one started, so its position
        // is young enough to count as recent.
        rule.begin()
        refresher.begin(trip = 1, plan = StartPlan.REPLAN_IN_TRIP, routeId = route.id, chosenSummary = null)
        update(whereTheLastTripEnded, at(980.0))
        update(whereTheLastTripEnded, at(980.0))
        assertEquals(emptyList<Point>(), navigator.requestedFrom)
        assertEquals(emptyList<Point>(), shown)

        // The trip session's first position, then the navigator's update with it.
        rule.onSourceFix(at(1_000.2))
        update(onRoute, at(1_000.2))

        assertEquals(listOf(onRoute), shown)
        assertEquals(listOf(onRoute), navigator.requestedFrom)
        assertEquals(2, rule.staleUpdates)
    }

    @Test
    fun `a real trip right after a simulated drive shows nothing of the drive's last position`() {
        // The simulated drive ended at 1,000 s where the last trip ended, and the replayer had
        // stamped its last position 1,100 s. The real trip's preview was made after that drive, so
        // the trip starts as previewed, at 1,060 s, on the phone's newest position, from 1,058 s.
        val driveLast = location(whereTheLastTripEnded, 1_100.0, mock = true)
        rule.begin()
        refresher.begin(trip = 1, plan = StartPlan.AS_PREVIEWED, routeId = route.id, chosenSummary = null)
        update(whereTheLastTripEnded, driveLast.fixTime())
        rule.onSourceFix(location(onRoute, 1_058.0, mock = null).fixTime())
        update(whereTheLastTripEnded, driveLast.fixTime())
        assertEquals(emptyList<Point>(), shown)

        update(onRoute, location(onRoute, 1_058.0, mock = false).fixTime())
        update(onRoute, location(onRoute, 1_061.0, mock = false).fixTime())

        assertEquals(listOf(onRoute, onRoute), shown)
        assertEquals(2, rule.staleUpdates)
    }

    @Test
    fun `a trip that starts without an old position loses no update`() {
        rule.begin()
        refresher.begin(trip = 1, plan = StartPlan.REPLAN_IN_TRIP, routeId = route.id, chosenSummary = null)
        rule.onSourceFix(at(50.0))
        update(onRoute, at(50.0))
        update(onRoute, at(51.0))

        assertEquals(listOf(onRoute, onRoute), shown)
        assertEquals(listOf(onRoute), navigator.requestedFrom)
        assertEquals(0, rule.staleUpdates)
    }
}
