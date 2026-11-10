package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.StartPlan
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.AS_PREVIEWED
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REFRESH_IN_TRIP
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REPLAN_IN_TRIP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The start plan carried out against a navigator that only counts what it is asked. Invented
 * routes in the sea near the point 0, 0, on invented roads.
 */
class StartRefresherTest {

    private data class Route(val id: String, val summary: String?, val end: Point?)

    /** Counts every call, and answers when the test says so. */
    private class Navigator : TripRoutes<Route> {
        var refreshes = 0
        var requests = 0
        val cancelled = mutableListOf<Long>()
        val sets = mutableListOf<List<Route>>()

        /** Answers a refresh inside the call, as the navigator does when it has nothing to refresh. */
        var refreshAtOnce: RefreshOutcome? = null

        /** Answers a request inside the call. */
        var answerAtOnce: ReplanAnswer<Route>? = null

        /** False: the request cannot be sent. */
        var canRequest = true

        /** Answers a cancelled request inside the cancel, as a router may. */
        var cancelAnswers = false

        private val refreshCallbacks = mutableListOf<(RefreshOutcome) -> Unit>()
        private val requestCallbacks = mutableListOf<(ReplanAnswer<Route>) -> Unit>()

        /** How many calls would be billed outside a trip, or could start a second one. */
        val calls: Int get() = refreshes + requests + sets.size

        override fun refresh(done: (RefreshOutcome) -> Unit) {
            refreshes += 1
            refreshCallbacks += done
            refreshAtOnce?.let(done)
        }

        override fun request(done: (ReplanAnswer<Route>) -> Unit): Long? {
            if (!canRequest) return null
            requests += 1
            requestCallbacks += done
            answerAtOnce?.let(done)
            return FIRST_REQUEST_ID + requests
        }

        override fun cancel(requestId: Long) {
            cancelled += requestId
            if (cancelAnswers) requestCallbacks.last()(ReplanAnswer.Failed("Route request cancelled"))
        }

        override fun set(routes: List<Route>) {
            sets += routes
        }

        override fun summary(route: Route): String? = route.summary

        override fun destination(route: Route): Point? = route.end

        /** The navigator answers the refresh numbered [index], the first being 1. */
        fun refreshed(outcome: RefreshOutcome, index: Int = refreshes) = refreshCallbacks[index - 1](outcome)

        /** The router answers the request numbered [index], the first being 1. */
        fun answered(answer: ReplanAnswer<Route>, index: Int = requests) = requestCallbacks[index - 1](answer)
    }

    private class Lines : DebugLog {
        val all = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            all += type to fields
        }

        fun of(type: String): List<Map<String, Any?>> = all.filter { it.first == type }.map { it.second }

        fun results(type: String): List<Any?> = of(type).map { it["result"] }

        fun reasons(type: String): List<Any?> = of(type).map { it["reason"] }
    }

    private val navigator = Navigator()
    private val lines = Lines()
    private var guidanceActive = true
    private var clock = 0L
    private val refresher = StartRefresher(navigator, lines, guidanceActive = { guidanceActive }, elapsedMillis = { clock })

    /** Where the trip's route ends, and three other ends: 5 m, just under and just over 100 m away. */
    private val end = Point.fromLngLat(0.002, 0.001)
    private val endNear = Point.fromLngLat(0.002, 0.001045)
    private val endAtLimit = Point.fromLngLat(0.002, 0.001899)
    private val endTooFar = Point.fromLngLat(0.002, 0.001905)

    private val started = Route("started#0", "Oak Street, Pine Avenue", end)
    private val fastest = Route("fresh#0", "Elm Street", endNear)
    private val sameRoads = Route("fresh#1", "Pine Avenue, Oak Street", endNear)

    private val onRoute = TripMoment(started.id, end)

    private fun begin(plan: StartPlan, trip: Int = 1) = refresher.begin(trip, plan, started.id, started.summary)

    private fun routes(vararg routes: Route, online: Boolean = true) = ReplanAnswer.Routes(routes.toList(), online)

    /** A replan up to the answer: the first progress sends the request and the router answers. */
    private fun replanAnswered(answer: ReplanAnswer<Route> = routes(fastest, sameRoads)) {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.answered(answer)
    }

    // What each plan does.

    @Test
    fun `a start as previewed asks for nothing at all`() {
        begin(AS_PREVIEWED)
        assertFalse(refresher.awaitsProgress)
        repeat(5) { refresher.onProgress(onRoute) }
        refresher.onReroute()
        refresher.end()

        assertEquals(0, navigator.calls)
        assertTrue(lines.all.isEmpty())
    }

    @Test
    fun `nothing is asked before the first progress of the trip`() {
        begin(REFRESH_IN_TRIP)
        assertTrue(refresher.awaitsProgress)
        assertEquals(0, navigator.calls)

        begin(REPLAN_IN_TRIP, trip = 2)
        assertTrue(refresher.awaitsProgress)
        assertEquals(0, navigator.calls)
    }

    @Test
    fun `a refresh is asked once, at the first progress, and the routes stay`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        assertEquals(1, navigator.refreshes)
        assertFalse(refresher.awaitsProgress)

        repeat(3) { refresher.onProgress(onRoute) }
        navigator.refreshed(RefreshOutcome.SUCCESS)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(1, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("success"), lines.results("start_refresh"))
        assertTrue(lines.of("start_replan").isEmpty())
    }

    @Test
    fun `a refresh that fails is followed by one replan`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.FAILURE)
        assertEquals(1, navigator.requests)
        // The answer alone sets nothing: the routes wait for the next progress.
        navigator.answered(routes(fastest))
        assertTrue(navigator.sets.isEmpty())
        assertTrue(refresher.awaitsProgress)

        refresher.onProgress(onRoute)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(listOf(listOf(fastest)), navigator.sets)
        assertEquals(1, navigator.refreshes)
        assertEquals(1, navigator.requests)
        assertEquals(listOf<Any?>("failure"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>("set"), lines.results("start_replan"))
    }

    @Test
    fun `routes that cannot be refreshed are planned again, also when the navigator says so at once`() {
        navigator.refreshAtOnce = RefreshOutcome.NOT_STARTED
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)

        assertEquals(1, navigator.refreshes)
        assertEquals(1, navigator.requests)
        assertEquals(listOf<Any?>("not_started"), lines.results("start_refresh"))

        navigator.answered(routes(fastest))
        refresher.onProgress(onRoute)
        assertEquals(listOf(listOf(fastest)), navigator.sets)
    }

    @Test
    fun `a replan sends one request at the first progress and sets its routes once`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        assertEquals(1, navigator.requests)
        assertEquals(0, navigator.refreshes)
        repeat(3) { refresher.onProgress(onRoute) }
        assertEquals(1, navigator.requests)

        clock = 2_500L
        navigator.answered(routes(fastest, sameRoads))
        assertTrue(navigator.sets.isEmpty())
        refresher.onProgress(onRoute)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(1, navigator.sets.size)
        assertEquals(1, navigator.requests)
        assertTrue(navigator.cancelled.isEmpty())
        val line = lines.of("start_replan").single()
        assertEquals("set", line["result"])
        assertNull(line["reason"])
        assertEquals(2, line["count"])
        assertEquals(true, line["online"])
        assertEquals(2.5, line["seconds"])
        assertEquals(5.0, line["shiftMeters"] as Double, 0.1)
    }

    @Test
    fun `the fresh route with the roads of the chosen route goes first`() {
        replanAnswered(routes(fastest, sameRoads))
        refresher.onProgress(onRoute)

        assertEquals(listOf(listOf(sameRoads, fastest)), navigator.sets)
        val line = lines.of("start_replan").single()
        assertEquals(1, line["listIndex"])
        assertEquals(2, line["chosenRoads"])
        assertEquals(2, line["sharedRoads"])
    }

    @Test
    fun `fresh routes on other roads keep the order of the routing service`() {
        val other = Route("fresh#1", "Birch Road", endNear)
        replanAnswered(routes(fastest, other))
        refresher.onProgress(onRoute)

        assertEquals(listOf(listOf(fastest, other)), navigator.sets)
        val line = lines.of("start_replan").single()
        assertEquals(0, line["listIndex"])
        assertEquals(0, line["sharedRoads"])
    }

    // What is never set.

    @Test
    fun `an empty answer never sets routes`() {
        replanAnswered(routes())
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("no_routes"), lines.reasons("start_replan"))
    }

    @Test
    fun `routes worked out on the phone are never set`() {
        replanAnswered(routes(fastest, sameRoads, online = false))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        val line = lines.of("start_replan").single()
        assertEquals("dropped", line["result"])
        assertEquals("no_routes", line["reason"])
        assertEquals(false, line["online"])
        assertEquals(2, line["count"])
    }

    @Test
    fun `a destination that moved more than a hundred meters is never set`() {
        replanAnswered(routes(Route("fresh#0", "Oak Street, Pine Avenue", endTooFar)))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        val line = lines.of("start_replan").single()
        assertEquals("dropped", line["result"])
        assertEquals("destination_moved", line["reason"])
        assertTrue(line["shiftMeters"] as Double > 100.0)
    }

    @Test
    fun `a destination just within a hundred meters is set`() {
        val atLimit = Route("fresh#0", "Elm Street", endAtLimit)
        replanAnswered(routes(atLimit))
        refresher.onProgress(onRoute)

        assertEquals(listOf(listOf(atLimit)), navigator.sets)
        val shift = lines.of("start_replan").single()["shiftMeters"] as Double
        assertTrue(shift > 99.9 && shift <= 100.0, "$shift")
    }

    @Test
    fun `the destination of the route that goes first is the one that is measured`() {
        // The service's first route ends at the trip's end, the one with the chosen roads elsewhere.
        val elsewhere = Route("fresh#1", "Oak Street, Pine Avenue", endTooFar)
        replanAnswered(routes(fastest, elsewhere))
        refresher.onProgress(onRoute)

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("destination_moved"), lines.reasons("start_replan"))
    }

    @Test
    fun `a destination that cannot be compared is never set`() {
        // The fresh route has no single destination.
        replanAnswered(routes(Route("fresh#0", "Elm Street", end = null)))
        refresher.onProgress(onRoute)
        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("destination_unknown"), lines.reasons("start_replan"))
    }

    @Test
    fun `a progress without one stop ahead never sets routes`() {
        replanAnswered()
        // The navigator's route has no stop left, as after the arrival.
        refresher.onProgress(TripMoment(started.id, destination = null))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("destination_unknown"), lines.reasons("start_replan"))
    }

    @Test
    fun `a failed request sets nothing and is not sent again`() {
        clock = 1_000L
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        clock = 9_000L
        navigator.answered(ReplanAnswer.Failed("No network"))
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(1, navigator.requests)
        assertTrue(navigator.sets.isEmpty())
        val line = lines.of("start_replan").single()
        assertEquals("failed", line["result"])
        assertEquals("request_failed", line["reason"])
        assertEquals("No network", line["message"])
        assertEquals(8.0, line["seconds"])
    }

    @Test
    fun `a request that cannot be sent is skipped`() {
        navigator.canRequest = false
        begin(REPLAN_IN_TRIP)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("skipped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("no_position"), lines.reasons("start_replan"))
    }

    @Test
    fun `an answer that comes while the request is sent still waits for the next progress`() {
        navigator.answerAtOnce = routes(fastest)
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        assertTrue(navigator.sets.isEmpty())
        assertTrue(refresher.awaitsProgress)

        refresher.onProgress(onRoute)
        assertEquals(listOf(listOf(fastest)), navigator.sets)
        assertTrue(navigator.cancelled.isEmpty())
    }

    // A refresh the navigator cancelled.

    @Test
    fun `a refresh cancelled while the trip stays on its route is asked for once more`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        // Only the alternatives changed: the navigator still guides on the route of the start.
        refresher.onRoutesChanged(started.id)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        // Not from inside the navigator's answer: the next progress asks.
        assertEquals(1, navigator.refreshes)
        assertTrue(refresher.awaitsProgress)

        refresher.onProgress(onRoute)
        assertEquals(2, navigator.refreshes)
        assertFalse(refresher.awaitsProgress)
        navigator.refreshed(RefreshOutcome.SUCCESS)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(2, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("cancelled", "success"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(true, null), lines.of("start_refresh").map { it["again"] })
        assertTrue(lines.of("start_replan").isEmpty())
    }

    @Test
    fun `a refresh cancelled a second time is left to the navigator`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        assertFalse(refresher.awaitsProgress)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(2, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("cancelled", "cancelled"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(true, false), lines.of("start_refresh").map { it["again"] })
        assertTrue(lines.of("start_replan").isEmpty())
    }

    @Test
    fun `a refresh cancelled by a reroute is not asked for again`() {
        begin(REFRESH_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        refresher.onReroute()
        navigator.refreshed(RefreshOutcome.CANCELLED)
        assertFalse(refresher.awaitsProgress)
        repeat(3) { refresher.onProgress(onRoute) }

        // The same when the navigator's other routes are the only sign of it.
        begin(REFRESH_IN_TRIP, trip = 2)
        refresher.onProgress(onRoute)
        refresher.onRoutesChanged("rerouted#0")
        navigator.refreshed(RefreshOutcome.CANCELLED)
        assertFalse(refresher.awaitsProgress)
        repeat(3) { refresher.onProgress(TripMoment("rerouted#0", end)) }

        assertEquals(2, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("cancelled", "cancelled"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(false, false), lines.of("start_refresh").map { it["again"] })
        assertTrue(lines.of("start_replan").isEmpty())
    }

    @Test
    fun `a reroute heard only after the cancel stops the refresh that waits`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        assertTrue(refresher.awaitsProgress)
        refresher.onRoutesChanged("rerouted#0")
        assertFalse(refresher.awaitsProgress)
        repeat(3) { refresher.onProgress(TripMoment("rerouted#0", end)) }

        assertEquals(1, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("cancelled", "skipped"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(null, "rerouted"), lines.reasons("start_refresh"))
    }

    @Test
    fun `a progress off the route after the cancel asks for nothing`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        refresher.onProgress(onRoute.copy(offRoute = true))
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(1, navigator.refreshes)
        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("cancelled", "skipped"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(null, "rerouted"), lines.reasons("start_refresh"))
    }

    @Test
    fun `a refresh cancelled once guidance has stopped is not asked for again`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        guidanceActive = false
        navigator.refreshed(RefreshOutcome.CANCELLED)
        assertFalse(refresher.awaitsProgress)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(1, navigator.refreshes)
        assertEquals(listOf<Any?>("cancelled"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(false), lines.of("start_refresh").map { it["again"] })
    }

    @Test
    fun `guidance that ends after the cancel asks for nothing, and the next drive may ask twice again`() {
        begin(REFRESH_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        refresher.end()
        repeat(3) { refresher.onProgress(onRoute) }
        assertEquals(1, navigator.refreshes)
        assertEquals(listOf<Any?>("cancelled", "skipped"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>(null, "guidance_ended"), lines.reasons("start_refresh"))

        begin(REFRESH_IN_TRIP, trip = 2)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        refresher.onProgress(onRoute)
        assertEquals(3, navigator.refreshes)
        // The cancel of the first drive's refresh, should it come again, belongs to no trip.
        navigator.refreshed(RefreshOutcome.CANCELLED, index = 1)
        assertFalse(refresher.awaitsProgress)
        navigator.refreshed(RefreshOutcome.SUCCESS)
        assertEquals(0, navigator.requests)
        assertEquals(
            listOf<Any?>("cancelled", "skipped", "cancelled", "success"),
            lines.results("start_refresh"),
        )
    }

    @Test
    fun `a refresh that fails when it is asked for again is followed by one replan`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.CANCELLED)
        refresher.onProgress(onRoute)
        navigator.refreshed(RefreshOutcome.FAILURE)
        assertEquals(1, navigator.requests)
        navigator.answered(routes(fastest))
        refresher.onProgress(onRoute)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(2, navigator.refreshes)
        assertEquals(1, navigator.requests)
        assertEquals(listOf(listOf(fastest)), navigator.sets)
        assertEquals(listOf<Any?>("cancelled", "failure"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>("set"), lines.results("start_replan"))
    }

    // Guidance that ends.

    @Test
    fun `guidance that ends before the first progress begins nothing`() {
        begin(REPLAN_IN_TRIP)
        refresher.end()
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertFalse(refresher.awaitsProgress)
        assertEquals(listOf<Any?>("skipped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_replan"))
    }

    @Test
    fun `guidance that ends before the first progress asks for no refresh`() {
        begin(REFRESH_IN_TRIP)
        refresher.end()
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("skipped"), lines.results("start_refresh"))
    }

    @Test
    fun `guidance that ends while the request is under way cancels it and drops its answer`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.end()
        assertEquals(listOf(FIRST_REQUEST_ID + 1), navigator.cancelled)

        // The answer was on its way already.
        navigator.answered(routes(fastest))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_replan"))
    }

    @Test
    fun `a router that answers the cancel at once gets one closing line`() {
        navigator.cancelAnswers = true
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.end()

        assertEquals(1, lines.of("start_replan").size)
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
    }

    @Test
    fun `guidance that ends between the answer and the next progress sets nothing`() {
        replanAnswered()
        refresher.end()
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        // The request had been answered: there is nothing to cancel.
        assertTrue(navigator.cancelled.isEmpty())
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_replan"))
    }

    @Test
    fun `an arrival before the answer sets nothing`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        // Arrived: guidance is not active any more, and a progress still comes before the end is handled.
        guidanceActive = false
        navigator.answered(routes(fastest))
        refresher.onProgress(onRoute)
        refresher.end()

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_replan"))
    }

    @Test
    fun `a first progress after guidance stopped begins nothing`() {
        begin(REFRESH_IN_TRIP)
        guidanceActive = false
        refresher.onProgress(onRoute)

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_refresh"))
    }

    @Test
    fun `a refresh that fails after guidance ended is not followed by a replan`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.end()
        navigator.refreshed(RefreshOutcome.FAILURE)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("dropped"), lines.results("start_refresh"))
        assertTrue(lines.of("start_replan").isEmpty())
    }

    @Test
    fun `a refresh that fails while the session has stopped is not followed by a replan`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        guidanceActive = false
        navigator.refreshed(RefreshOutcome.FAILURE)

        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("failure"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>("skipped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("guidance_ended"), lines.reasons("start_replan"))
    }

    // The navigator's own reroute.

    @Test
    fun `a reroute before the first progress leaves the trip to the navigator`() {
        for (plan in listOf(REFRESH_IN_TRIP, REPLAN_IN_TRIP)) {
            begin(plan, trip = plan.ordinal)
            refresher.onReroute()
            repeat(3) { refresher.onProgress(onRoute) }
        }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("skipped"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_refresh"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `a reroute while the request is under way cancels it and drops its answer`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.onReroute()
        assertEquals(listOf(FIRST_REQUEST_ID + 1), navigator.cancelled)

        navigator.answered(routes(fastest))
        repeat(3) { refresher.onProgress(TripMoment("rerouted#0", end)) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `a reroute between the answer and the next progress drops the routes`() {
        replanAnswered()
        refresher.onReroute()
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `a refresh that fails after a reroute is not followed by a replan`() {
        begin(REFRESH_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.onReroute()
        navigator.refreshed(RefreshOutcome.FAILURE)

        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("failure"), lines.results("start_refresh"))
        assertEquals(listOf<Any?>("skipped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `a first progress on another route counts as a reroute`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(TripMoment("rerouted#0", end))
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `routes are not set from a progress on another route than the trip started on`() {
        replanAnswered()
        refresher.onProgress(TripMoment("rerouted#0", end))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `a first progress off the route begins nothing`() {
        for (plan in listOf(REFRESH_IN_TRIP, REPLAN_IN_TRIP)) {
            begin(plan, trip = plan.ordinal)
            refresher.onProgress(onRoute.copy(offRoute = true))
            repeat(3) { refresher.onProgress(onRoute) }
        }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_refresh"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `routes are not set from a progress that is off the route`() {
        replanAnswered()
        refresher.onProgress(onRoute.copy(offRoute = true))
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("dropped"), lines.results("start_replan"))
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `other routes in the navigator than the trip started on count as a reroute`() {
        begin(REPLAN_IN_TRIP)
        refresher.onProgress(onRoute)
        refresher.onRoutesChanged("other#0")
        assertEquals(listOf(FIRST_REQUEST_ID + 1), navigator.cancelled)

        navigator.answered(routes(fastest))
        // A progress update from before the change arrives late.
        repeat(3) { refresher.onProgress(onRoute) }

        assertTrue(navigator.sets.isEmpty())
        assertEquals(listOf<Any?>("rerouted"), lines.reasons("start_replan"))
    }

    @Test
    fun `the trip's own route in the navigator, set or refreshed, changes nothing`() {
        begin(REPLAN_IN_TRIP)
        // The routes of the start reach the navigator, and later a refresh of them.
        refresher.onRoutesChanged(started.id)
        refresher.onProgress(onRoute)
        refresher.onRoutesChanged(started.id)
        navigator.answered(routes(fastest))
        refresher.onRoutesChanged(started.id)
        refresher.onProgress(onRoute)

        assertEquals(listOf(listOf(fastest)), navigator.sets)
        // The fresh routes, now in the navigator, are not taken for a reroute either.
        refresher.onRoutesChanged(fastest.id)
        assertEquals(listOf<Any?>("set"), lines.results("start_replan"))
    }

    @Test
    fun `a navigator left without routes before the first progress begins nothing`() {
        begin(REFRESH_IN_TRIP)
        refresher.onRoutesChanged(null)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertEquals(listOf<Any?>("skipped"), lines.results("start_refresh"))
    }

    // One drive after another.

    @Test
    fun `a second drive starts with nothing left of the first`() {
        begin(REPLAN_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        refresher.onReroute()
        refresher.end()

        // The second drive is planned again too: the reroute of the first does not count for it.
        begin(REPLAN_IN_TRIP, trip = 2)
        refresher.onProgress(onRoute)
        assertEquals(2, navigator.requests)
        navigator.answered(routes(fastest))
        refresher.onProgress(onRoute)

        assertEquals(listOf(listOf(fastest)), navigator.sets)
    }

    @Test
    fun `an answer for the first drive does nothing in the second`() {
        begin(REPLAN_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        refresher.end()

        begin(REPLAN_IN_TRIP, trip = 2)
        refresher.onProgress(onRoute)
        assertEquals(2, navigator.requests)
        // The first drive's answer arrives late, while the second drive's request is under way.
        navigator.answered(routes(sameRoads), index = 1)
        refresher.onProgress(onRoute)
        assertTrue(navigator.sets.isEmpty())

        navigator.answered(routes(fastest), index = 2)
        refresher.onProgress(onRoute)
        assertEquals(listOf(listOf(fastest)), navigator.sets)
    }

    @Test
    fun `a refresh answer for the first drive does nothing in the second`() {
        begin(REFRESH_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        refresher.end()

        begin(REFRESH_IN_TRIP, trip = 2)
        refresher.onProgress(onRoute)
        assertEquals(2, navigator.refreshes)
        // The first drive's refresh fails late: no replan in the second drive.
        navigator.refreshed(RefreshOutcome.FAILURE, index = 1)
        assertEquals(0, navigator.requests)

        navigator.refreshed(RefreshOutcome.SUCCESS, index = 2)
        assertEquals(0, navigator.requests)
        assertEquals(listOf<Any?>("dropped", "success"), lines.results("start_refresh"))
    }

    @Test
    fun `a drive started as previewed after a planned one asks for nothing`() {
        begin(REPLAN_IN_TRIP, trip = 1)
        refresher.end()
        begin(AS_PREVIEWED, trip = 2)
        repeat(3) { refresher.onProgress(onRoute) }

        assertEquals(0, navigator.calls)
        assertFalse(refresher.awaitsProgress)
    }

    @Test
    fun `a new drive that begins without an end gives up the work of the last`() {
        begin(REPLAN_IN_TRIP, trip = 1)
        refresher.onProgress(onRoute)
        begin(AS_PREVIEWED, trip = 2)

        assertEquals(listOf(FIRST_REQUEST_ID + 1), navigator.cancelled)
        navigator.answered(routes(fastest))
        repeat(3) { refresher.onProgress(onRoute) }
        assertTrue(navigator.sets.isEmpty())
    }

    // The measures.

    @Test
    fun `the shift of a destination is counted on the navigator's larger sphere`() {
        val plain = Geo.meters(end.latitude(), end.longitude(), endAtLimit.latitude(), endAtLimit.longitude())
        val shift = requireNotNull(destinationShiftMeters(end, endAtLimit))
        // Scaled to the navigator's radius, and a millimeter on top for the last digits.
        assertEquals(plain * 6_373_000.0 / 6_371_008.8 + 0.001, shift, 1e-9)
        assertTrue(shift > plain)
        // 99.98 m on the Earth of Geo is over the navigator's hundred meters.
        val degrees = Math.toDegrees(99.98 / Geo.EARTH_RADIUS_METERS)
        val over = Point.fromLngLat(0.0, degrees)
        val origin = Point.fromLngLat(0.0, 0.0)
        assertEquals(99.98, requireNotNull(metersBetween(origin, over)), 1e-6)
        assertTrue(requireNotNull(destinationShiftMeters(origin, over)) > 100.0)
    }

    @Test
    fun `the test ends lie where the tests say`() {
        assertEquals(5.0, requireNotNull(destinationShiftMeters(end, endNear)), 0.1)
        assertTrue(requireNotNull(destinationShiftMeters(end, endAtLimit)) in 99.9..100.0)
        assertTrue(requireNotNull(destinationShiftMeters(end, endTooFar)) in 100.0..101.0)
        assertEquals(0.001, requireNotNull(destinationShiftMeters(end, end)), 1e-9)
    }

    @Test
    fun `a missing point gives no distance`() {
        assertNull(destinationShiftMeters(null, end))
        assertNull(destinationShiftMeters(end, null))
        assertNull(metersBetween(null, null))
        assertNull(metersBetween(end, Point.fromLngLat(Double.NaN, 0.0)))
    }

    @Test
    fun `only a route from an origin to one stop still ahead has a destination to compare`() {
        val origin = Point.fromLngLat(0.0, 0.0)
        assertEquals(end, soleDestination(listOf(origin, end)))
        assertEquals(end, soleDestination(listOf(origin, end), remaining = 1))
        // Arrived, or not on its way yet.
        assertNull(soleDestination(listOf(origin, end), remaining = 0))
        assertNull(soleDestination(listOf(origin, end), remaining = 2))
        // A stop in between, no points, a single point.
        assertNull(soleDestination(listOf(origin, endNear, end), remaining = 1))
        assertNull(soleDestination(listOf(origin, endNear, end), remaining = 2))
        assertNull(soleDestination(null))
        assertNull(soleDestination(emptyList()))
        assertNull(soleDestination(listOf(end)))
    }

    private companion object {
        const val FIRST_REQUEST_ID = 40L
    }
}
