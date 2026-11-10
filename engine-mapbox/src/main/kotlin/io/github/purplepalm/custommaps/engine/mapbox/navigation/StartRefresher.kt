package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.InTripAction
import io.github.purplepalm.custommaps.nav.guidance.InTripRefresh
import io.github.purplepalm.custommaps.nav.guidance.MainRoads
import io.github.purplepalm.custommaps.nav.guidance.StartPlan

/** How a refresh that the navigator was asked for ended; the navigator's own four answers. */
internal enum class RefreshOutcome {
    /** At least one route has fresh traffic and times. */
    SUCCESS,

    /** The refresh ran and no route was refreshed: no network, or the service refused. */
    FAILURE,

    /**
     * The navigator has no route it can refresh: a route worked out on the phone without network,
     * or one without the id of the request it came from. Its routes are never refreshed, neither
     * now nor by the navigator's own refresh every few minutes.
     */
    NOT_STARTED,

    /**
     * The navigator dropped the refresh because its routes changed while it ran: it rerouted, or
     * guidance ended. There is nothing left to refresh.
     */
    CANCELLED,
}

/** The answer to the one route request of a replan. */
internal sealed interface ReplanAnswer<out R> {

    /**
     * @param routes in the order of the routing service
     * @param online from the routing service; false for routes worked out on the phone, which
     *   know no traffic
     */
    data class Routes<R>(val routes: List<R>, val online: Boolean) : ReplanAnswer<R>

    data class Failed(val message: String) : ReplanAnswer<Nothing>
}

/**
 * One progress update of a trip, as far as the start plan needs it.
 *
 * @param routeId the route the navigator guides on
 * @param destination the one stop this route still leads to, as the navigator placed it on a
 *   road; null when the route is not one from an origin to a single destination still ahead
 */
internal data class TripMoment(val routeId: String, val destination: Point?)

/** What [StartRefresher] asks of the navigator. Called on the main thread, and answers there. */
internal interface TripRoutes<R> {

    /** Asks for fresh traffic and times on the routes of the trip. [done] is called once, maybe at once. */
    fun refresh(done: (RefreshOutcome) -> Unit)

    /**
     * Sends one route request from where the car is now to the place the trip goes to, and returns
     * its id; null when it could not be sent, and then [done] is never called.
     */
    fun request(done: (ReplanAnswer<R>) -> Unit): Long?

    /** Cancels the request with this id, if it is still under way. */
    fun cancel(requestId: Long)

    /** Gives the navigator these routes in place of the ones it has. Never called with an empty list. */
    fun set(routes: List<R>)

    /** The main roads of [route], as the routing service names them; see [MainRoads]. */
    fun summary(route: R): String?

    /** The single destination of [route], as the service placed it on a road; null for any other route. */
    fun destination(route: R): Point?
}

/**
 * Carries out, inside a running trip, what the [StartPlan] of its start asks for: a refresh of the
 * routes, or routes requested again that take their place. What may be done, and when, is decided
 * by [InTripRefresh]; this class asks it and does what it answers.
 *
 * Mapbox bills a trip once. Routes that reach the navigator before the first progress of a trip,
 * or at a moment when it has no progress, or that end elsewhere, start a second billed trip. So
 * everything here begins with the first progress of the trip, and fresh routes are set only from
 * inside a progress update for the route the trip started on: at that moment the navigator has a
 * progress, this one. The navigator's own reroute always comes first: from the moment it finds
 * the car off the route, or puts other routes in place for any reason, nothing here is begun or
 * set, and a request under way is cancelled.
 *
 * One line in the debug log closes each plan: "start_refresh" for a refresh, with the navigator's
 * result; "start_replan" for routes requested again, with "set" or the reason they were not. A
 * failed refresh is followed by a replan, so it has both.
 *
 * Main thread only.
 *
 * @param guidanceActive whether guidance runs and its trip session with it, read at each decision
 * @param elapsedMillis a monotonic clock, for the time a request took
 */
internal class StartRefresher<R>(
    private val routes: TripRoutes<R>,
    private val debugLog: DebugLog,
    private val guidanceActive: () -> Boolean,
    private val elapsedMillis: () -> Long,
) {

    private enum class Step {
        /** No trip, a plan that asks for nothing, or everything done. */
        IDLE,

        /** Waiting for the first progress of the trip. */
        BEFORE_PROGRESS,

        /** The navigator was asked to refresh and has not answered. */
        REFRESHING,

        /** The route request is under way. */
        REQUESTING,

        /** The answer is here and waits for the next progress. */
        ANSWERED,
    }

    private var step = Step.IDLE

    /** The trip the work belongs to; an answer that carries another number came too late. */
    private var trip = NO_TRIP
    private var plan = StartPlan.AS_PREVIEWED

    /** The route the trip started on. */
    private var routeId: String? = null
    private var chosenSummary: String? = null
    private var rerouteSeen = false
    private var requestId: Long? = null
    private var requestedAt = 0L
    private var answer: ReplanAnswer.Routes<R>? = null

    /** True while a progress update is wanted; otherwise [onProgress] need not be called. */
    val awaitsProgress: Boolean get() = step == Step.BEFORE_PROGRESS || step == Step.ANSWERED

    /**
     * A trip begins: its routes are on their way to the navigator.
     *
     * @param trip a number no other trip has, above zero
     * @param routeId the route the trip starts on, the first of the routes handed over
     * @param chosenSummary the main roads of that route
     */
    fun begin(trip: Int, plan: StartPlan, routeId: String?, chosenSummary: String?) {
        // Nothing of an earlier trip lives on.
        end()
        this.trip = trip
        this.plan = plan
        this.routeId = routeId
        this.chosenSummary = chosenSummary
        step = if (plan == StartPlan.AS_PREVIEWED) Step.IDLE else Step.BEFORE_PROGRESS
    }

    /** A progress update of the trip. Does something only while [awaitsProgress]. */
    fun onProgress(moment: TripMoment) {
        when (step) {
            Step.BEFORE_PROGRESS -> atFirstProgress(moment)
            Step.ANSWERED -> atAnswer(moment)
            Step.IDLE, Step.REFRESHING, Step.REQUESTING -> Unit
        }
    }

    /**
     * The navigator found the car off the route, is fetching a new route, or has set one. From here
     * on the trip's routes are the navigator's business.
     */
    fun onReroute() {
        if (step == Step.IDLE) return
        rerouteSeen = true
        when (step) {
            Step.BEFORE_PROGRESS -> close(lineOf(plan), SKIPPED, REROUTED)
            Step.REQUESTING, Step.ANSWERED -> close(REPLAN, DROPPED, REROUTED)
            // The refresh's own answer closes it; a failure then finds the reroute and stops.
            Step.REFRESHING, Step.IDLE -> Unit
        }
    }

    /**
     * The navigator's routes changed; [primaryId] is the route it guides on now, null when it has
     * none. Another route than the one the trip started on was not put there by this class, which
     * sets routes last of all: the navigator replaced them, as with a reroute. The navigator tells
     * its observers of new routes in the same turn of the main thread in which it drops its
     * progress, so no progress update from before can slip in between and set routes.
     */
    fun onRoutesChanged(primaryId: String?) {
        if (step != Step.IDLE && primaryId != routeId) onReroute()
    }

    /** Guidance ended, or the trip never began: whatever is under way is given up. */
    fun end() {
        when (step) {
            Step.BEFORE_PROGRESS -> close(lineOf(plan), SKIPPED, GUIDANCE_ENDED)
            Step.REFRESHING -> close(REFRESH, DROPPED, GUIDANCE_ENDED)
            Step.REQUESTING, Step.ANSWERED -> close(REPLAN, DROPPED, GUIDANCE_ENDED)
            Step.IDLE -> Unit
        }
        trip = NO_TRIP
        plan = StartPlan.AS_PREVIEWED
        routeId = null
        chosenSummary = null
        rerouteSeen = false
    }

    private fun atFirstProgress(moment: TripMoment) {
        // A progress on another route: the navigator has replaced the routes already.
        if (moment.routeId != routeId) rerouteSeen = true
        // The navigator guides on the route of this progress, and nothing has been requested yet.
        when (decide(routeCount = 1, destinationShiftMeters = 0.0)) {
            InTripAction.REFRESH -> refresh()
            InTripAction.SET_ROUTES -> request()
            InTripAction.NONE -> close(lineOf(plan), SKIPPED, reason(routeCount = 1, destinationShiftMeters = 0.0))
        }
    }

    private fun refresh() {
        step = Step.REFRESHING
        val asked = trip
        routes.refresh { outcome -> onRefreshed(asked, outcome) }
    }

    private fun onRefreshed(asked: Int, outcome: RefreshOutcome) {
        // An answer for an earlier trip, or for one that has ended.
        if (asked != trip || step != Step.REFRESHING) return
        debugLog.log(REFRESH, mapOf("result" to outcome.name.lowercase()))
        step = Step.IDLE
        when (outcome) {
            RefreshOutcome.SUCCESS, RefreshOutcome.CANCELLED -> Unit
            RefreshOutcome.FAILURE, RefreshOutcome.NOT_STARTED -> {
                // The routes are as old as they were: plan them again, if that is still allowed.
                plan = StartPlan.REPLAN_IN_TRIP
                when (decide(routeCount = 1, destinationShiftMeters = 0.0)) {
                    InTripAction.SET_ROUTES -> request()
                    InTripAction.NONE, InTripAction.REFRESH ->
                        close(REPLAN, SKIPPED, reason(routeCount = 1, destinationShiftMeters = 0.0))
                }
            }
        }
    }

    private fun request() {
        // Set before the call: the answer may come while the request is being sent.
        step = Step.REQUESTING
        requestId = null
        requestedAt = elapsedMillis()
        val asked = trip
        val id = routes.request { answer -> onAnswer(asked, answer) }
        if (asked != trip || step != Step.REQUESTING) return
        if (id == null) close(REPLAN, SKIPPED, NO_POSITION) else requestId = id
    }

    private fun onAnswer(asked: Int, answer: ReplanAnswer<R>) {
        // An answer for an earlier trip, for one that has ended, or for a request given up.
        if (asked != trip || step != Step.REQUESTING) return
        requestId = null
        when (answer) {
            is ReplanAnswer.Failed -> close(REPLAN, FAILED, REQUEST_FAILED, mapOf("message" to answer.message) + took())
            is ReplanAnswer.Routes -> {
                // Set at the next progress update, when the navigator is sure to have a progress.
                this.answer = answer
                step = Step.ANSWERED
            }
        }
    }

    private fun atAnswer(moment: TripMoment) {
        val answer = answer ?: return close(REPLAN, DROPPED, NO_ROUTES)
        // Routes worked out on the phone know less than the routes in use: they count as none.
        val fresh = if (answer.online) answer.routes else emptyList()
        val ordered = MainRoads.closestFirst(chosenSummary, fresh, routes::summary)
        val first = ordered.firstOrNull()
        val shift = first?.let { destinationShiftMeters(moment.destination, routes.destination(it)) }
        if (moment.routeId != routeId) rerouteSeen = true
        val details = mapOf(
            "count" to answer.routes.size,
            "online" to answer.online,
            "shiftMeters" to shift?.let { Motion.round(it, 1) },
            "listIndex" to first?.let { route -> fresh.indexOfFirst { it === route } },
            "chosenRoads" to MainRoads.of(chosenSummary).size,
            "sharedRoads" to first?.let { MainRoads.shared(chosenSummary, routes.summary(it)) },
        ) + took()
        if (decide(ordered.size, shift) == InTripAction.SET_ROUTES) {
            // Same trip for Mapbox: a progress exists, the list is not empty, the destination stayed.
            routes.set(ordered)
            close(REPLAN, SET, reason = null, details)
        } else {
            close(REPLAN, DROPPED, reason(ordered.size, shift), details)
        }
    }

    private fun decide(routeCount: Int, destinationShiftMeters: Double?): InTripAction =
        InTripRefresh.decide(plan, progressSeen = true, guidanceActive(), rerouteSeen, routeCount, destinationShiftMeters)

    private fun reason(routeCount: Int, destinationShiftMeters: Double?): String? =
        InTripRefresh.obstacle(plan, progressSeen = true, guidanceActive(), rerouteSeen, routeCount, destinationShiftMeters)
            ?.name?.lowercase()

    private fun took(): Map<String, Any?> = mapOf("seconds" to Motion.round((elapsedMillis() - requestedAt) / MILLIS_PER_SECOND, 1))

    /** Rests, writes the closing line, and gives up a request that is still under way. */
    private fun close(line: String, result: String, reason: String?, details: Map<String, Any?> = emptyMap()) {
        val underWay = requestId
        requestId = null
        answer = null
        // At rest before the request is cancelled: its answer to that must find nothing to do.
        step = Step.IDLE
        debugLog.log(line, mapOf("result" to result, "reason" to reason) + details)
        underWay?.let(routes::cancel)
    }

    private fun lineOf(plan: StartPlan): String = if (plan == StartPlan.REPLAN_IN_TRIP) REPLAN else REFRESH

    private companion object {
        const val NO_TRIP = 0

        const val REFRESH = "start_refresh"
        const val REPLAN = "start_replan"

        const val SET = "set"
        const val SKIPPED = "skipped"
        const val DROPPED = "dropped"
        const val FAILED = "failed"

        const val REROUTED = "rerouted"
        const val GUIDANCE_ENDED = "guidance_ended"
        const val NO_POSITION = "no_position"
        const val NO_ROUTES = "no_routes"
        const val REQUEST_FAILED = "request_failed"

        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * How far the destination of fresh routes lies from the one the trip is on, in meters as the
 * navigator counts them when it tells the same trip from a new one; null when either is not known.
 *
 * The navigator measures on a sphere a little larger than the Earth of [Geo], so its hundred
 * meters are three centimeters shorter. The distance is brought to its scale, and a millimeter is
 * added, far more than the two sums can differ by in their last digits: what is within the limit
 * here is within it there.
 */
internal fun destinationShiftMeters(current: Point?, fresh: Point?): Double? =
    metersBetween(current, fresh)?.let { it * NAVIGATOR_SCALE + SHIFT_MARGIN_METERS }

/** The distance between two points in meters ([Geo.meters]); null when one is missing or no coordinate. */
internal fun metersBetween(a: Point?, b: Point?): Double? {
    if (a == null || b == null) return null
    return Geo.meters(a.latitude(), a.longitude(), b.latitude(), b.longitude()).takeIf { it.isFinite() }
}

/** The radius the navigator's distance check uses for meters, over the radius of [Geo]. */
private const val NAVIGATOR_SCALE = 6_373_000.0 / Geo.EARTH_RADIUS_METERS

private const val SHIFT_MARGIN_METERS = 0.001

/**
 * The destination of a route from an origin to one destination: the second of its two points, as
 * the routing service placed it on a road. Null for a route of any other shape, and with
 * [remaining] given, null unless exactly that one stop is still ahead. Fresh routes are only ever
 * compared, and set, between two routes of this shape, which is every route this app requests.
 */
internal fun soleDestination(waypoints: List<Point?>?, remaining: Int = 1): Point? =
    if (waypoints != null && waypoints.size == 2 && remaining == 1) waypoints[1] else null
