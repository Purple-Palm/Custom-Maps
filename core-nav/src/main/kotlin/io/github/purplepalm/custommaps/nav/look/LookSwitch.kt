package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the drive is doing at one moment, as far as a look switch cares. */
data class DriveMoment(
    /** A drive is being guided. Outside guidance nothing holds a switch. */
    val guidance: Boolean = false,
    /** Along the route to the next maneuver; null when unknown. */
    val toManeuver: Distance? = null,
    /** Expected time to the next maneuver at the route's speeds; zero when unknown. */
    val timeToManeuver: Duration = Duration.ZERO,
    /** Changes at every step change and with every new route (`RouteProgressSnapshot.stepKey`). */
    val stepKey: String = "",
    /** The car's speed in meters a second; null when unknown. */
    val speedMps: Double? = null,
    /** The navigator says the car is in a tunnel (`RouteProgress.inTunnel`). */
    val inTunnel: Boolean = false,
    /**
     * The guidance camera shows a close view: the view going down onto a junction or a lane
     * choice, held there, or coming back up (`MapRenderer.closeView`).
     */
    val closeView: Boolean = false,
) {
    companion object {
        val NONE = DriveMoment()
    }
}

/** Why the look changed. */
enum class LookCause {
    /** The first look of a screen, decided before its first frame. */
    START,

    /** The app came back on screen and the look had to change while it was away. */
    RESUME,

    /** The driver changed the choice in Settings. */
    CHOICE,

    /** The sun rose or set. */
    SUN,

    /** A better position than the one the look was chosen for (a first fix after a guess). */
    PLACE,
}

/** What a waiting switch waited for. */
enum class LookHold { MANEUVER, CLOSE_VIEW, TUNNEL }

/** What brought an input. */
enum class LookTrigger {
    /** The first decision of a screen, before its first frame. */
    START,

    /** The screen is shown again after the app was stopped. */
    RESUME,

    /** The minute tick while the app is on screen. */
    TICK,

    /** The driver's choice changed. */
    CHOICE,

    /** A position from a better source arrived. */
    PLACE,

    /** A progress update of the drive: only a waiting switch is looked at, the sun is not computed. */
    DRIVE,
}

/**
 * Everything the switch reads for one decision; the place is never kept by the switch. [drive] has
 * no default on purpose: every input of a guided drive must carry the drive as it is now, or a
 * switch would go at a turn.
 */
data class LookInput(
    val at: Instant,
    val choice: LookChoice,
    val place: SunPlace,
    val trigger: LookTrigger,
    val drive: DriveMoment,
    /** False while the map is not on screen: a change then shows nobody anything. */
    val onScreen: Boolean = true,
)

/** What the switch remembers between inputs. No position in it. */
data class LookState(
    val shown: Look? = null,
    val shownAt: Instant? = null,
    val shownBy: LookCause? = null,
    val shownChoice: LookChoice? = null,
    /** The source of the position the shown look was chosen for; null when chosen by hand. */
    val shownSource: PlaceSource? = null,
    /** A look that waits for a quiet moment of the drive, and since when, and for what. */
    val waiting: Look? = null,
    val waitingCause: LookCause? = null,
    val waitingSince: Instant? = null,
    val waitingFor: LookHold? = null,
    /** The drive's current step, and when it last changed; null outside guidance. */
    val stepKey: String? = null,
    val stepChangedAt: Instant? = null,
    /**
     * Since when the map's theme still belongs to the look before [shown]: a change on coming back
     * on screen during a drive left the theme, which rebuilds the basemap's tiles, for a quiet
     * moment. Null when the map has the whole look.
     */
    val themeBehindSince: Instant? = null,
    /** What the theme waited for last; null when it has not waited. */
    val themeHeldBy: LookHold? = null,
) {
    companion object {
        /** Before the first decision. */
        val NONE = LookState()
    }
}

/** A look to apply now, and why. */
data class LookChange(
    val look: Look,
    val was: Look?,
    val cause: LookCause,
    val choice: LookChoice,
    /** The source of the position the sun was computed for; null when the choice is Day or Night. */
    val source: PlaceSource?,
    /** Whole seconds the switch waited for a quiet moment; null when it went at once. */
    val heldSeconds: Long?,
    /** What it waited for last; null when it went at once. */
    val heldBy: LookHold?,
    val guidance: Boolean,
    /**
     * True when the map is to take everything of the look but its theme now: the theme follows
     * with a [ThemeRelease] at the next quiet moment. Only on coming back on screen during a drive
     * near a maneuver, in a tunnel or in a close view.
     */
    val deferTheme: Boolean = false,
) {
    /**
     * The fields of the `look` line of the debug log: enums, whole seconds and booleans. No
     * coordinates, no sun elevation, no sunrise or sunset time, no time zone: the line's own time
     * stamp is the only time in it (see the notes on what that time says).
     */
    fun logFields(): Map<String, Any?> = mapOf(
        "look" to look,
        "was" to was,
        "cause" to cause,
        "choice" to choice,
        "place" to source,
        "heldS" to heldSeconds,
        "heldBy" to heldBy,
        "guidance" to guidance,
        "themeDeferred" to deferTheme.takeIf { it },
    ).filterValues { it != null }
}

/** The map may now take the theme of the look on screen, which it was left without. */
data class ThemeRelease(
    /** Whole seconds the theme waited. */
    val heldSeconds: Long,
    /** What it waited for last; null when it went at the first input. */
    val heldBy: LookHold?,
) {
    /** The fields of the `look_theme` line: whole seconds and an enum. */
    fun logFields(): Map<String, Any?> = mapOf("themeHeldS" to heldSeconds, "heldBy" to heldBy).filterValues { it != null }
}

/** The new state, the change to apply now if there is one, and a theme to release if there is one. */
data class LookStep(val state: LookState, val change: LookChange?, val themeRelease: ThemeRelease? = null)

/**
 * Turns the inputs of the look into changes: at once at a screen's start, when it comes back and
 * when the driver changes the choice; for the sun and a better position, during guidance only at a
 * quiet moment (no tunnel, no close view, no maneuver close ahead or just passed, or the car
 * standing). Pure, one call per input: the minute tick, a choice, a position, a progress update.
 */
object LookSwitch {

    fun reduce(
        state: LookState,
        input: LookInput,
        tuning: LookTuning = LookTuning(),
        sun: SunElevation = SunElevation.NOAA,
    ): LookStep {
        val step = decide(state.tracking(input), input, tuning, sun)
        return if (step.change == null) releaseTheme(step, input, tuning) else step
    }

    /**
     * What holds a switch at this moment of the drive; null when it may go. Once a switch has
     * waited [LookTuning.maxHold], [capped] narrows the window around maneuvers to
     * [LookTuning.cappedBefore] and [LookTuning.cappedAfter]; a tunnel and a close view hold it for
     * as long as they last.
     */
    fun holdFor(
        drive: DriveMoment,
        stepChangedAt: Instant?,
        at: Instant,
        tuning: LookTuning = LookTuning(),
        capped: Boolean = false,
    ): LookHold? {
        if (!drive.guidance) return null
        if (drive.inTunnel) return LookHold.TUNNEL
        if (drive.closeView) return LookHold.CLOSE_VIEW
        val speed = drive.speedMps
        if (speed != null && speed < tuning.standingBelowMps) return null
        val before = if (capped) tuning.cappedBefore else tuning.quietBefore
        val after = if (capped) tuning.cappedAfter else tuning.quietAfter
        val toManeuver = drive.toManeuver
        if (toManeuver != null && before.covers(toManeuver, drive.timeToManeuver)) return LookHold.MANEUVER
        if (stepChangedAt != null && elapsed(stepChangedAt, at) < after) return LookHold.MANEUVER
        return null
    }

    private fun decide(tracked: LookState, input: LookInput, tuning: LookTuning, sun: SunElevation): LookStep {
        val shown = tracked.shown
        if (shown == null || input.trigger == LookTrigger.START) {
            // Nothing is on screen yet: the look goes as it is, and the line says what it is. A new
            // map draws its first tiles with the whole look, so nothing is left behind.
            val look = LookRule.wanted(input.choice, input.place, input.at, shown, tuning, sun)
            return apply(tracked, input, look, LookCause.START)
        }
        if (input.trigger == LookTrigger.DRIVE) {
            // Once a second during a drive: only a waiting switch is looked at again.
            val waiting = tracked.waiting ?: return LookStep(tracked, null)
            return holdOrApply(tracked, input, waiting, tracked.waitingCause ?: LookCause.SUN, tuning)
        }
        val look = LookRule.wanted(input.choice, input.place, input.at, shown, tuning, sun)
        if (input.trigger == LookTrigger.RESUME || (input.trigger == LookTrigger.CHOICE && input.choice != tracked.shownChoice)) {
            // Back on screen, nothing has been drawn yet; a choice is the driver's own: both go at
            // once. Back on screen during a drive, the theme, which rebuilds the basemap's tiles,
            // waits for a quiet moment; the rest of the look goes now.
            if (look == shown) return LookStep(tracked.settled(input), null)
            if (input.trigger == LookTrigger.CHOICE) return apply(tracked, input, look, LookCause.CHOICE)
            val busy = input.onScreen && holdFor(input.drive, tracked.stepChangedAt, input.at, tuning) != null
            return apply(tracked, input, look, LookCause.RESUME, deferTheme = busy)
        }
        if (look == shown) return LookStep(tracked.settled(input), null)

        val cause = if (input.place.source.isBetterThan(tracked.shownSource)) LookCause.PLACE else LookCause.SUN
        val shownAt = tracked.shownAt
        val automatic = tracked.shownBy == LookCause.SUN || tracked.shownBy == LookCause.PLACE
        if (cause == LookCause.SUN && automatic && shownAt != null && elapsed(shownAt, input.at) < tuning.dwell) {
            // Just switched by the sun: it does not switch back yet.
            return LookStep(tracked.copy(waiting = null, waitingCause = null, waitingSince = null, waitingFor = null), null)
        }
        return holdOrApply(tracked, input, look, cause, tuning)
    }

    private fun holdOrApply(state: LookState, input: LookInput, look: Look, cause: LookCause, tuning: LookTuning): LookStep {
        val sameWait = state.waiting == look
        val since = if (sameWait) state.waitingSince ?: input.at else input.at
        val waited = elapsed(since, input.at)
        val capped = waited >= tuning.maxHold
        val hold = if (input.onScreen) holdFor(input.drive, state.stepChangedAt, input.at, tuning, capped) else null
        if (hold == null) {
            val heldBy = if (sameWait) state.waitingFor else null
            return apply(state, input, look, cause, waited.takeIf { sameWait && heldBy != null }, heldBy)
        }
        return LookStep(state.copy(waiting = look, waitingCause = cause, waitingSince = since, waitingFor = hold), null)
    }

    /** A theme left behind goes at the first quiet moment, with the same window as a waiting switch. */
    private fun releaseTheme(step: LookStep, input: LookInput, tuning: LookTuning): LookStep {
        val state = step.state
        val since = state.themeBehindSince ?: return step
        val waited = elapsed(since, input.at)
        val hold = if (input.onScreen) holdFor(input.drive, state.stepChangedAt, input.at, tuning, waited >= tuning.maxHold) else null
        if (hold != null) return step.copy(state = state.copy(themeHeldBy = hold))
        val release = ThemeRelease(heldSeconds = waited.inWholeSeconds, heldBy = state.themeHeldBy)
        return step.copy(state = state.copy(themeBehindSince = null, themeHeldBy = null), themeRelease = release)
    }

    private fun apply(
        state: LookState,
        input: LookInput,
        look: Look,
        cause: LookCause,
        waited: Duration? = null,
        heldBy: LookHold? = null,
        deferTheme: Boolean = false,
    ): LookStep {
        val source = input.place.source.takeIf { input.choice == LookChoice.AUTO }
        val change = LookChange(
            look = look,
            was = state.shown,
            cause = cause,
            choice = input.choice,
            source = source,
            heldSeconds = waited?.inWholeSeconds,
            heldBy = heldBy,
            guidance = input.drive.guidance,
            deferTheme = deferTheme,
        )
        val next = state.copy(
            shown = look,
            shownAt = input.at,
            shownBy = cause,
            shownChoice = input.choice,
            shownSource = source,
            waiting = null,
            waitingCause = null,
            waitingSince = null,
            waitingFor = null,
            // A whole look brings its theme with it; a look without its theme keeps the earliest wait.
            themeBehindSince = if (deferTheme) state.themeBehindSince ?: input.at else null,
            themeHeldBy = if (deferTheme) state.themeHeldBy else null,
        )
        return LookStep(next, change)
    }

    /** The look stays: a waiting switch is dropped, and the choice and source are taken as they are now. */
    private fun LookState.settled(input: LookInput): LookState = copy(
        shownChoice = input.choice,
        shownSource = input.place.source.takeIf { input.choice == LookChoice.AUTO },
        waiting = null,
        waitingCause = null,
        waitingSince = null,
        waitingFor = null,
    )

    /** Notes a step change of the drive: the moment a maneuver was passed. The first step of a drive is none. */
    private fun LookState.tracking(input: LookInput): LookState {
        val key = input.drive.stepKey.takeIf { input.drive.guidance && it.isNotEmpty() }
        if (key == stepKey) return this
        val changedAt = if (key != null && stepKey != null) input.at else if (key == null) null else stepChangedAt
        return copy(stepKey = key, stepChangedAt = changedAt)
    }

    private fun PlaceSource.isBetterThan(other: PlaceSource?): Boolean = other != null && ordinal < other.ordinal

    private fun elapsed(from: Instant, to: Instant): Duration = (to.toEpochMilli() - from.toEpochMilli()).milliseconds
}
