package io.github.purplepalm.custommaps.car

import androidx.car.app.connection.CarConnection
import io.github.purplepalm.custommaps.car.routing.CarRouting
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot

/**
 * The drive as the car's host hears of it (docs/STAGE-2.md, The guidance bridge to NavigationManager):
 * the engine's state and progress turned into NavigationManager calls, for the host's own screens, the
 * car's cluster and its head-up display, and the routing card the home screen shows ([shown]). One for
 * each car session, from its ON_CREATE to its ON_DESTROY, so that the cluster goes on while another
 * app is in front.
 *
 * - [start], first thing at ON_CREATE: the callback, in the form with one argument.
 * - A drive, also one already running when the session is made: `navigationStarted()`.
 * - Progress: the routing content ([CarRouting]) goes to the card and, while navigating, as a trip
 *   to the host, at once when what the card shows changes, otherwise at most once a second
 *   ([CarThrottle]). Off the route and while a new route has no instruction yet, the content loads;
 *   the bridge never ends navigation for a reroute.
 * - The end of the drive (End on either screen, End Navigation in the notification, the arrival, or a
 *   start the navigator refused): `navigationEnded()`.
 * - The host asks the app to stop navigating, as when another navigation app starts: the bridge sends
 *   no more trips and the drive goes on (DECISIONS 32). The card stays up to date. Opening the app on
 *   the car's screen again during the drive calls `navigationStarted()` again ([takeBack]).
 * - [stop] at ON_DESTROY: the bridge is gone, clears the callback, and does nothing more; a callback
 *   the library posted before that only writes a line.
 *
 * Every call to the host is caught: the car, the phone and the drive share one process, and an
 * exception on the main thread would end the phone's guidance too. The bridge keeps its own flag of
 * whether it navigates and sends no trip without it. Each start, end and stop of navigation, the
 * host's request to stop and every caught exception with its call write a "car_nav" line: states,
 * numbers and class names only. Starts nothing billed and stops nothing: the drive is the engine's.
 * Main thread only.
 *
 * @param content the routing content of a progress update, worked out when the update comes
 * @param clock milliseconds of a monotonic clock
 * @param schedule runs an action after a delay on the main thread; the returned function cancels it
 * @param lifecycle the session's lifecycle state, for the log
 * @param connection the car connection's type as CarConnection last reported it, null before it has
 * @param onShown the card's content changed: the home screen asks the host for its template again
 */
class CarGuidanceBridge(
    private val port: NavigationManagerPort,
    private val content: (RouteProgressSnapshot) -> CarRouting,
    private val log: DebugLog,
    private val clock: () -> Long,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> () -> Unit,
    private val lifecycle: () -> String,
    private val connection: () -> Int?,
    private val onShown: () -> Unit,
) {
    /** Whether the bridge tells the host about the drive: from navigationStarted to its end or the host's stop. */
    var navigating: Boolean = false
        private set

    /** The session ended: nothing more is sent. */
    var gone: Boolean = false
        private set

    /** The routing content the card shows: the newest one sent, null outside a drive and before its first progress. */
    var shown: CarRouting? = null
        private set

    private var guidance: GuidanceState? = null
    private var latest: CarRouting? = null
    private val throttle = CarThrottle()
    private var pending: (() -> Unit)? = null

    /** Whether this drive asked the host to show it, so that its end tells the host it is over. */
    private var startAsked = false

    /** Trips sent in this drive, for the log line at its end. */
    private var trips = 0

    /** At the session's ON_CREATE, before anything else: the callback the host's requests come through. */
    fun start() {
        if (gone) return
        call("setCallback") { port.setCallback(::onHostStop) }
    }

    /** The drive's state, the current one first. */
    fun onGuidance(state: GuidanceState) {
        if (gone) return
        val previous = guidance
        guidance = state
        if (state == previous) return
        if (state == GuidanceState.ACTIVE) {
            startNavigation(CAUSE_DRIVE)
        } else if (previous == GuidanceState.ACTIVE) {
            endNavigation(state)
        }
    }

    /** A progress update of the drive; null when there is none. Outside a drive it shows nothing. */
    fun onProgress(progress: RouteProgressSnapshot?) {
        if (gone || guidance != GuidanceState.ACTIVE || progress == null) return
        val routing = content(progress)
        latest = routing
        val now = clock()
        when (val decision = throttle.offer(now, routing.key)) {
            CarThrottle.Decision.Now -> send()
            is CarThrottle.Decision.At -> if (pending == null) {
                pending = schedule(decision.atMillis - now) {
                    pending = null
                    if (!gone && guidance == GuidanceState.ACTIVE) send()
                }
            }
        }
    }

    /**
     * The driver opened the app on the car's screen again ([cause]: the session started again, or the
     * host brought it to the front with an intent). During a drive that the host stopped showing, the
     * app takes the car back: `navigationStarted()` again. Whether the host then asks the other app to
     * stop is the host's.
     */
    fun takeBack(cause: String) {
        if (gone || guidance != GuidanceState.ACTIVE || navigating) return
        startNavigation(cause)
    }

    /** At the session's ON_DESTROY: gone, and the callback cleared. The drive goes on without the car. */
    fun stop() {
        if (gone) return
        gone = true
        cancelPending()
        call("clearCallback") { port.clearCallback() }
        log.log(NAV_LINE, mapOf("event" to "gone", "navigating" to navigating, "trips" to trips, "guidance" to guidance?.name).filterValues { it != null })
        navigating = false
    }

    private fun startNavigation(cause: String) {
        // Asked before in this drive: the car is taken back after the host's stop.
        val again = startAsked
        startAsked = true
        if (call("navigationStarted") { port.navigationStarted() }) {
            navigating = true
            log.log(NAV_LINE, mapOf("event" to "navigationStarted", "cause" to cause, "again" to again))
            // What the card shows now goes to the host at once.
            if (latest != null) send()
        }
    }

    private fun endNavigation(state: GuidanceState) {
        cancelPending()
        val was = navigating
        navigating = false
        latest = null
        shown = null
        throttle.reset()
        if (startAsked) {
            call("navigationEnded") { port.navigationEnded() }
            log.log(NAV_LINE, mapOf("event" to "navigationEnded", "cause" to state.name, "navigating" to was, "trips" to trips))
        }
        startAsked = false
        trips = 0
        show()
    }

    private fun send() {
        val routing = latest ?: return
        cancelPending()
        throttle.sent(clock(), routing.key)
        shown = routing
        show()
        if (navigating && call("updateTrip") { port.updateTrip(routing) }) trips += 1
    }

    private fun show() {
        try {
            onShown()
        } catch (e: RuntimeException) {
            log.log(NAV_LINE, CarFailure.fields("show", e))
        }
    }

    /** The host's request to stop navigating, posted by the library; after [stop] it only writes its line. */
    private fun onHostStop() {
        try {
            val fields = mapOf(
                "event" to "onStopNavigation",
                "lifecycle" to lifecycle(),
                "connection" to connectionName(connection()),
                "guidance" to guidance?.name,
                "navigating" to navigating,
                "gone" to gone,
            ).filterValues { it != null }
            navigating = false
            log.log(NAV_LINE, fields)
        } catch (e: RuntimeException) {
            log.log(NAV_LINE, CarFailure.fields("onStopNavigation", e))
        }
    }

    private fun cancelPending() {
        val cancel = pending ?: return
        pending = null
        try {
            cancel()
        } catch (e: RuntimeException) {
            log.log(NAV_LINE, CarFailure.fields("cancelTrip", e))
        }
    }

    /** Runs a call to the host; an exception is written with the call's name and dropped. True when it went through. */
    private inline fun call(name: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: RuntimeException) {
        log.log(NAV_LINE, CarFailure.fields(name, e))
        false
    }

    companion object {
        const val NAV_LINE = "car_nav"

        /** The drive started, or the session was made during one. */
        const val CAUSE_DRIVE = "drive"

        /** The session started again: the app came back to the car's screen. */
        const val CAUSE_START = "start"

        /** The host brought the app to the front with an intent, as its launcher does. */
        const val CAUSE_INTENT = "intent"

        /**
         * CarConnection's type in words: no car, a car running Android itself, or Android Auto on the
         * phone; "unknown" before it reported.
         */
        fun connectionName(type: Int?): String = when (type) {
            null -> "unknown"
            CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "none"
            CarConnection.CONNECTION_TYPE_NATIVE -> "native"
            CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
            else -> type.toString()
        }
    }
}
