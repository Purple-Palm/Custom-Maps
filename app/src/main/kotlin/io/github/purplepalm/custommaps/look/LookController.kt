package io.github.purplepalm.custommaps.look

import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.look.DriveMoment
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookCause
import io.github.purplepalm.custommaps.nav.look.LookChange
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.look.LookInput
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.look.LookState
import io.github.purplepalm.custommaps.nav.look.LookStep
import io.github.purplepalm.custommaps.nav.look.LookSwitch
import io.github.purplepalm.custommaps.nav.look.LookTrigger
import io.github.purplepalm.custommaps.nav.look.LookTuning
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunElevation
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.look.ThemeRelease
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which look the app and its map show, the bright day look or the dark night look, and when it
 * changes. One for the process, in AppGraph: an activity created again, after a token switch or
 * when Android brings the app back while its process lived on, shows this look at once and
 * decides no first look again.
 *
 * The first look is decided before the activity's first frame ([start]). While the app is on
 * screen ([runWhileStarted]) the look is decided again as the app comes back, then by the sun once
 * a minute, and during a drive at each progress update for a switch or a theme that waits for a
 * quiet moment; at once when the driver picks Auto, Day or Night ([onChoice]) and when Settings
 * covers the map ([onCovered]). [LookSwitch] makes every decision, and every input carries the
 * drive as it is at that moment ([currentDrive]): a tick without it would let a switch go at a
 * turn.
 *
 * A change goes out in one main-thread message: [look] for the window and the screen's colours,
 * then [lookOnMap] for the map, which [showOn] hands on. The debug log gets a `look` line for each
 * change except one the sun brought outside guidance, whose time alone would tell the local dusk or
 * dawn; a `look_theme` line when a theme left for a quiet moment goes; and a `look_settled` line
 * once the map has drawn all its tiles after one of those lines wrote a theme. No line holds a
 * position, a sun angle, a time of the sun or a time zone.
 *
 * Main thread only.
 */
class LookController(
    /** The navigator's guidance state. */
    private val guidance: StateFlow<GuidanceState>,
    /** The drive's latest progress; null outside guidance. */
    private val progress: StateFlow<RouteProgressSnapshot?>,
    /** Where the sun is computed for now, rounded ([SunPlaces.current]). Kept in memory only. */
    private val places: () -> SunPlace,
    /** The driver's choice as stored: the first value decides the start, every later one comes as [onChoice]. */
    private val choices: Flow<LookChoice>,
    private val debugLog: DebugLog,
    /** Lives as long as the process. */
    private val scope: CoroutineScope,
    /** The wall clock the sun is computed for. */
    private val now: () -> Instant,
    /** A clock that never jumps, in milliseconds, for how long the first frame waited. */
    private val elapsedMillis: () -> Long,
    /**
     * Where progress updates are taken. On the phone a dispatcher that queues behind the update, so
     * an update is taken once the navigator has told every observer about it, the map's camera too,
     * and the close view read with it ([onCloseView]) is the one of that same update.
     */
    private val driveContext: CoroutineContext,
    private val sun: SunElevation = SunElevation.NOAA,
    private val tuning: LookTuning = LookTuning(),
) {

    private val _look = MutableStateFlow(Look.NIGHT)

    /** The look the screen shows: the night look until the first look is decided, as the splash is. */
    val look: StateFlow<Look> = _look.asStateFlow()

    private val _lookOnMap = MutableStateFlow(LookOnMap(Look.NIGHT))

    /**
     * What the map is to show: [look], and while a theme waits for a quiet moment, the basemap's
     * theme of the look before. A map made now takes this value as it is made.
     */
    val lookOnMap: StateFlow<LookOnMap> = _lookOnMap.asStateFlow()

    private val _decided = MutableStateFlow(false)

    /** True once the first look of this process is decided; then true for as long as the process lives. */
    val decided: StateFlow<Boolean> = _decided.asStateFlow()

    /** What the switch remembers between inputs: no position in it, and never written anywhere. */
    internal var switchState: LookState = LookState.NONE
        private set

    /** When [start] was called, by [elapsedMillis]; null before. */
    private var startedAt: Long? = null

    /** The driver's choice the inputs carry. */
    private var choice: LookChoice = LookChoice.DEFAULT

    /** The place of the last input that read one; a progress update reads none and takes this one. */
    private var place: SunPlace? = null

    /** Settings is open over the map. */
    private var covered = false

    /** The guidance camera shows a close view, as the map last said. */
    private var closeView = false

    /** How many maps take [lookOnMap] now ([showOn]). */
    private var mapsShowing = 0

    /** Counts what maps handed back, so a late settle time is matched to its own write. */
    private var handBacks = 0L

    /** Lines that wait for what the map did with their request, oldest first. */
    private val waitingLines = ArrayDeque<Line>()

    /** The written line whose theme write the map is still drawing; null when none. */
    private var settling: Settling? = null

    /**
     * Decides the first look of this process, before the activity's first frame. The choice is
     * taken if the store gives it within [START_WAIT_MS]; a store slower than that, or one that
     * fails, leaves Auto for the first look, and its value comes as a choice later. The place is
     * read and the look decided at once. Later calls do nothing: an activity created again keeps
     * the look of the process.
     */
    fun start() {
        if (startedAt != null) return
        val began = elapsedMillis()
        startedAt = began
        scope.launch {
            val stored = try {
                withTimeoutOrNull(START_WAIT_MS) { choices.first() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog.error("look.choice", e)
                null
            }
            choice = stored ?: LookChoice.DEFAULT
            decide(LookTrigger.START, startMs = elapsedMillis() - began)
            _decided.value = true
            try {
                choices.collect { onChoice(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog.error("look.choice", e)
            }
        }
    }

    /**
     * While the app is on screen: the look is decided again at once (it may have changed while the
     * app was away), then once a minute by the sun, and during a drive at each progress update for
     * a switch or a theme that waits; never the sun on an update. Call it while the activity is
     * started; it returns only when cancelled. Waits for the first look if that is not decided yet.
     */
    suspend fun runWhileStarted() {
        decided.first { it }
        decide(LookTrigger.RESUME)
        coroutineScope {
            launch(driveContext) {
                var guided = false
                combine(guidance, progress) { state, _ -> state == GuidanceState.ACTIVE }.collect { active ->
                    // Each update of a drive, and once more as the drive ends: what waited goes then.
                    if (active || guided) decide(LookTrigger.DRIVE)
                    guided = active
                }
            }
            while (true) {
                delay(TICK_MS)
                decide(LookTrigger.TICK)
            }
        }
    }

    /** The driver picked [choice] in Settings, or the store gave its first value late: it goes at once. */
    fun onChoice(choice: LookChoice) {
        if (choice == this.choice) return
        this.choice = choice
        if (decided.value) decide(LookTrigger.CHOICE)
    }

    /**
     * Settings is open over the map ([covered] true) or closed. While it is open a change shows
     * nobody the map, so whatever waits goes at once, and later ones go as they come.
     */
    fun onCovered(covered: Boolean) {
        if (covered == this.covered) return
        this.covered = covered
        if (covered && decided.value) decide(LookTrigger.DRIVE)
    }

    /** Whether the guidance camera shows a close view now, as the map works it out at each progress update. */
    fun onCloseView(closeView: Boolean) {
        this.closeView = closeView
    }

    /**
     * Shows [lookOnMap] on a map until the caller is cancelled: each request goes to [setLook] as it
     * comes, the map's first one included, and what the map did goes into the line of the change
     * that asked for it ([onMapApplied]). While no map shows, a line is written at once.
     */
    suspend fun showOn(setLook: (request: LookOnMap, onSettled: (idleMs: Long?) -> Unit) -> LookApplied) {
        mapsShowing += 1
        try {
            lookOnMap.collect { request ->
                val handBack = ++handBacks
                val applied = setLook(request) { idleMs -> onMapSettled(handBack, idleMs) }
                onMapApplied(request, applied, handBack)
            }
        } finally {
            mapsShowing -= 1
            if (mapsShowing == 0) writeWaitingLines()
        }
    }

    /**
     * A map did [applied] with [request], the latest request: the line of the change that asked
     * for it gets the map's fields, and lines of requests that a newer one replaced before the map
     * took them go as they are. [handBack] matches the settle time of a theme written now.
     */
    fun onMapApplied(request: LookOnMap, applied: LookApplied, handBack: Long) {
        val last = waitingLines.removeLastOrNull() ?: return
        writeWaitingLines()
        if (last.request != request) {
            debugLog.log(last.type, last.fields)
            return
        }
        debugLog.log(last.type, last.fields + mapFields(last.type, applied))
        if (applied.theme) settling = Settling(handBack, request.look)
    }

    /** The map has drawn every tile after the theme write [handBack] made, [idleMs] later, or not within its wait (null). */
    fun onMapSettled(handBack: Long, idleMs: Long?) {
        val waiting = settling?.takeIf { it.handBack == handBack } ?: return
        settling = null
        debugLog.log("look_settled", mapOf("idleMs" to idleMs, "look" to waiting.look))
    }

    /**
     * One input for the switch, built with the drive as it is now. A progress update reads no place
     * and computes no sun: it looks only at a switch or a theme that waits.
     */
    private fun decide(trigger: LookTrigger, startMs: Long? = null) {
        val last = place
        val here = if (trigger == LookTrigger.DRIVE && last != null) last else places()
        place = here
        // A minute's tick that finds a better position than the last one brings it as such.
        val input = if (trigger == LookTrigger.TICK && last != null && here.source.isBetterThan(last.source)) LookTrigger.PLACE else trigger
        val step = LookSwitch.reduce(switchState, LookInput(now(), choice, here, input, currentDrive(), onScreen = !covered), tuning, sun)
        switchState = step.state
        show(step, startMs)
    }

    /**
     * The drive as the look cares about it, from the latest progress update: how far and how long to
     * the next maneuver, its step, the speed, a tunnel, and the close view. A pending card after a
     * reroute brings the new route's step and its distance, so the new route counts as a step
     * change; off the route the update's numbers are taken as the navigator gives them.
     */
    private fun currentDrive(): DriveMoment {
        if (guidance.value != GuidanceState.ACTIVE) return DriveMoment.NONE
        val update = progress.value ?: return DriveMoment(guidance = true, closeView = closeView)
        return DriveMoment(
            guidance = true,
            toManeuver = update.distanceToNextManeuver,
            timeToManeuver = update.durationToNextManeuver,
            stepKey = update.stepKey,
            speedMps = update.speedMps,
            inTunnel = update.inTunnel,
            closeView = closeView,
        )
    }

    /** Puts [step] on screen and on the map, in that order, and writes its line or keeps it for the map's fields. */
    private fun show(step: LookStep, startMs: Long?) {
        val change = step.change
        val release = step.themeRelease
        if (change == null && release == null) return
        val request = _lookOnMap.value.after(step)
        val line = lineOf(change, release, startMs, request)
        if (change != null) _look.value = change.look
        if (line != null) {
            // The first look's map is made with it, so no map has anything to add to its line; nor
            // does one that is asked for nothing new.
            val mapTakesIt = mapsShowing > 0 && change?.cause != LookCause.START && request != _lookOnMap.value
            if (mapTakesIt) waitingLines.addLast(line) else debugLog.log(line.type, line.fields)
        }
        _lookOnMap.value = request
    }

    private fun lineOf(change: LookChange?, release: ThemeRelease?, startMs: Long?, request: LookOnMap): Line? = when {
        // The time of a sun switch is the local dusk or dawn; outside guidance nothing else in the
        // log places the phone, so the line is not written.
        change != null && change.cause == LookCause.SUN && !change.guidance -> null
        change != null -> Line(LOOK, change.logFields() + listOfNotNull(startMs?.let { "startMs" to it }), request)
        release != null -> Line(LOOK_THEME, release.logFields(), request)
        else -> null
    }

    /** The map's part of a line: what it wrote and how long that took; for a theme let go, the time only. */
    private fun mapFields(type: String, applied: LookApplied): Map<String, Any?> {
        val errors = if (applied.errors > 0) mapOf("errors" to applied.errors, "failedKeys" to applied.failedKeys) else emptyMap()
        if (type == LOOK_THEME) return mapOf("writeMs" to applied.writeMs) + errors
        return mapOf(
            "keys" to applied.keys,
            "errors" to applied.errors,
            "failedKeys" to applied.failedKeys.takeIf { applied.errors > 0 },
            "writeMs" to applied.writeMs,
            "reset" to applied.reset,
            "transitionMs" to applied.transitionMs,
            "routeLine" to applied.routeLine,
        ).filterValues { it != null }
    }

    /** Writes every line that still waits for a map, as it is. */
    private fun writeWaitingLines() {
        while (waitingLines.isNotEmpty()) {
            val line = waitingLines.removeFirst()
            debugLog.log(line.type, line.fields)
        }
    }

    private fun PlaceSource.isBetterThan(other: PlaceSource): Boolean = ordinal < other.ordinal

    /** A line of [type] for the change that asked the map for [request]. */
    private class Line(val type: String, val fields: Map<String, Any?>, val request: LookOnMap)

    private class Settling(val handBack: Long, val look: Look)

    companion object {
        /** How long the first look waits for the stored choice before it takes Auto. */
        const val START_WAIT_MS = 300L

        /** How often the sun is looked at while the app is on screen. */
        const val TICK_MS = 60_000L

        private const val LOOK = "look"
        private const val LOOK_THEME = "look_theme"
    }
}
