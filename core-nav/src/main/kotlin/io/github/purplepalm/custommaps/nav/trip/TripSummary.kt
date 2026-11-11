package io.github.purplepalm.custommaps.nav.trip

import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.guidance.StopReason

/** How a trip ended, as its "trip_summary" line names it in [logName]. */
enum class TripEnding(val logName: String) {
    ARRIVED("arrived"),
    END_IN_APP("end_in_app"),
    END_IN_NOTIFICATION("end_in_notification"),

    /** Ended by the car screen (stage 2). */
    CAR("car"),

    /** The app's process ended during the trip; found at its next start. */
    PROCESS_DEATH("process_death"),
    ;

    companion object {
        /** The ending of a trip that guidance stopped for [reason]. */
        fun of(reason: StopReason): TripEnding = when (reason) {
            StopReason.USER_CANCELLED -> END_IN_APP
            StopReason.STOPPED_OUTSIDE_APP -> END_IN_NOTIFICATION
            StopReason.CAR_DISCONNECTED, StopReason.CAR_HOST_REQUEST -> CAR
        }
    }
}

/**
 * The phone's own counters at one moment of a trip: [receivedBytes], everything the app has
 * received since the phone started, and [batteryPercent]; each null when Android gave none.
 */
data class TripVitals(val receivedBytes: Long?, val batteryPercent: Int?)

/**
 * Adds up one trip for its "trip_summary" line, from what happens during it: numbers only, no
 * position and no place. Times are milliseconds on a monotonic clock. Main thread only, like the
 * navigator's callbacks that feed it.
 */
class TripTally {

    /** True from [begin] to [end]. */
    var running = false
        private set

    private var trip: String? = null
    private var simulated = false
    private var startedAt = 0L
    private var meters = 0.0
    private var lastLatitude = Double.NaN
    private var lastLongitude = Double.NaN
    private var reroutes = 0
    private var offRoute = false
    private var offRouteEpisodes = 0
    private var firstProgressAt = NEVER
    private var lastProgressAt = NEVER
    private var longestGap = 0L
    private val mapErrors = sortedMapOf<String, Int>()
    private var offline = false
    private var offlineSince = 0L
    private var offlineMillis = 0L

    /** A trip session starts at [nowMillis]; [offline] says whether the app has no internet now. */
    fun begin(nowMillis: Long, simulated: Boolean, offline: Boolean) {
        running = true
        trip = null
        this.simulated = simulated
        startedAt = nowMillis
        meters = 0.0
        lastLatitude = Double.NaN
        lastLongitude = Double.NaN
        reroutes = 0
        offRoute = false
        offRouteEpisodes = 0
        firstProgressAt = NEVER
        lastProgressAt = NEVER
        longestGap = 0L
        mapErrors.clear()
        this.offline = offline
        offlineSince = nowMillis
        offlineMillis = 0L
    }

    /** The navigator's id of the trip session, which its "session_state" line also has. */
    fun onTripId(id: String) {
        if (running) trip = id
    }

    /** A progress update of the trip came at [nowMillis]. */
    fun onProgress(nowMillis: Long) {
        if (!running) return
        if (firstProgressAt == NEVER) firstProgressAt = nowMillis
        if (lastProgressAt != NEVER) longestGap = maxOf(longestGap, nowMillis - lastProgressAt)
        lastProgressAt = nowMillis
    }

    /**
     * A matched position of the trip. The way from the one before counts as driven, unless
     * [jump] says that the navigator moved the car to another place at once.
     */
    fun onPosition(latitude: Double, longitude: Double, jump: Boolean) {
        if (!running) return
        if (!jump && !lastLatitude.isNaN()) {
            val step = Geo.meters(lastLatitude, lastLongitude, latitude, longitude)
            if (step.isFinite()) meters += step
        }
        lastLatitude = latitude
        lastLongitude = longitude
    }

    /** The navigator put a new route in place after the car left the route. */
    fun onReroute() {
        if (running) reroutes += 1
    }

    /** The navigator's off-route state; each change to true starts an episode. */
    fun onOffRoute(offRoute: Boolean) {
        if (!running) return
        if (offRoute && !this.offRoute) offRouteEpisodes += 1
        this.offRoute = offRoute
    }

    /** A map error of the kind [kind], such as the name of the SDK's error type. */
    fun onMapError(kind: String) {
        if (running) mapErrors[kind] = (mapErrors[kind] ?: 0) + 1
    }

    /** Whether the app has no internet, from [nowMillis] on. */
    fun onOffline(offline: Boolean, nowMillis: Long) {
        if (!running || offline == this.offline) return
        if (this.offline) offlineMillis += nowMillis - offlineSince
        this.offline = offline
        offlineSince = nowMillis
    }

    /**
     * The fields of the trip so far, at [nowMillis], as the "trip_summary" line has them, without
     * the ending and the phone's counters: "trip" (the session id), "simulated", "seconds",
     * "meters", "reroutes", "offRouteEpisodes", "firstProgressS" (from the start to the trip's
     * first progress), "longestGapS" (the longest time between two progress updates),
     * "mapErrors" (a count for each kind), "offlineSeconds" and "staleStart" with
     * "staleUpdates", the updates that still carried where the trip before left the car.
     */
    fun fields(nowMillis: Long, staleUpdates: Int): Map<String, Any?> {
        val offlineNow = offlineMillis + if (offline) nowMillis - offlineSince else 0L
        return mapOf(
            "trip" to trip,
            "simulated" to simulated,
            "seconds" to seconds(nowMillis - startedAt),
            "meters" to Motion.round(meters, 0),
            "reroutes" to reroutes,
            "offRouteEpisodes" to offRouteEpisodes,
            "firstProgressS" to if (firstProgressAt == NEVER) null else seconds(firstProgressAt - startedAt),
            "longestGapS" to if (lastProgressAt == NEVER) null else seconds(longestGap),
            "mapErrors" to mapErrors.toMap(),
            "offlineSeconds" to seconds(offlineNow),
            "staleStart" to (staleUpdates > 0),
            "staleUpdates" to staleUpdates,
        )
    }

    /** The trip session stopped: nothing more is counted. */
    fun end() {
        running = false
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val MILLIS_PER_SECOND = 1_000.0

        private fun seconds(millis: Long): Double = Motion.round(millis / MILLIS_PER_SECOND, 1)

        /**
         * What the trip cost the phone, from its counters at the [start] and at the [end]:
         * "receivedBytes", what the app received meanwhile over any network, and
         * "batteryUsedPercent", the battery level it lost, below zero while it charged. Each is
         * left out when either side has no number, and the bytes also when the counter went back,
         * as it does when the phone restarts.
         */
        fun vitalsFields(start: TripVitals?, end: TripVitals?): Map<String, Any?> {
            val startBytes = start?.receivedBytes
            val endBytes = end?.receivedBytes
            val startBattery = start?.batteryPercent
            val endBattery = end?.batteryPercent
            return mapOf(
                "receivedBytes" to if (startBytes != null && endBytes != null && endBytes >= startBytes) endBytes - startBytes else null,
                "batteryUsedPercent" to if (startBattery != null && endBattery != null) startBattery - endBattery else null,
            ).filterValues { it != null }
        }
    }
}
