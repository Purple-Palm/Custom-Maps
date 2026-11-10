package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Within [distance] or within [time]. A time of zero means unknown; then only the distance counts. */
data class Reach(val distance: Distance, val time: Duration) {
    fun covers(distance: Distance, time: Duration): Boolean =
        distance <= this.distance || (time.isPositive() && time <= this.time)
}

/** The numbers of the Then rule; tuned from the drive logs. */
data class ThenTuning(
    /** Two maneuvers are close when the second one follows the first within this reach. */
    val close: Reach = Reach(Distance.meters(300.0), 20.seconds),
    /** A close pair shows from this far before the first maneuver. */
    val window: Reach = Reach(Distance.meters(400.0), 30.seconds),
    /** Ramps, forks and merges: lanes are picked early, so pairs count as close from farther apart. */
    val rampClose: Reach = Reach(Distance.meters(1_000.0), 40.seconds),
    /** And when the first maneuver is one of them, the chip shows earlier. */
    val rampWindow: Reach = Reach(Distance.meters(1_500.0), 50.seconds),
    /** Not started this close to the first maneuver: it would only flash before the step changes. */
    val startCutoff: Distance = Distance.meters(30.0),
)

/**
 * Decides when the Then chip shows the maneuver after the next one. One call per progress update;
 * pure, so the phone and the car screen give the same answer for the same updates.
 *
 * Within a step the answer changes at most once, from hidden to shown: once shown, the chip stays
 * until the step changes, the navigator leaves the route or no maneuver follows. So it never
 * flickers on GPS jitter, braking or a traffic refresh.
 */
object ThenRule {

    /** What the rule remembers between updates: the step it last decided for, and its answer. */
    data class State(val stepKey: String?, val shown: Boolean) {
        companion object {
            /** Before the first update of a drive. */
            val NONE = State(stepKey = null, shown = false)
        }
    }

    fun reduce(state: State, progress: RouteProgressSnapshot, tuning: ThenTuning = DEFAULT_TUNING): State {
        val following = progress.following
        if (progress.offRoute || following == null) return State(progress.stepKey, shown = false)
        if (state.shown && state.stepKey == progress.stepKey) return state
        return State(progress.stepKey, shown = wanted(progress, following, tuning))
    }

    /** Whether the chip should start showing now. */
    internal fun wanted(progress: RouteProgressSnapshot, following: FollowingManeuver, tuning: ThenTuning): Boolean {
        if (!isWorthShowing(following.maneuver)) return false
        val rampNext = progress.nextManeuver.type in RAMP_NEXT
        val close = if (rampNext || following.maneuver.type in RAMP_FOLLOWING) tuning.rampClose else tuning.close
        val window = if (rampNext) tuning.rampWindow else tuning.window
        return close.covers(following.distanceAfterNext, following.durationAfterNext) &&
            window.covers(progress.distanceToNextManeuver, progress.durationToNextManeuver) &&
            progress.distanceToNextManeuver > tuning.startCutoff
    }

    /** Going on along the same road, joining a road or a note is no reason to look at the chip. */
    fun isWorthShowing(maneuver: Maneuver): Boolean = when (maneuver.type) {
        ManeuverType.DEPART, ManeuverType.MERGE, ManeuverType.NOTIFICATION, ManeuverType.UNKNOWN -> false
        ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> maneuver.modifier != null && maneuver.modifier != TurnModifier.STRAIGHT
        else -> true
    }

    /** One set of numbers for every update, so an update allocates none. */
    private val DEFAULT_TUNING = ThenTuning()

    private val RAMP_NEXT = setOf(ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK, ManeuverType.MERGE)
    private val RAMP_FOLLOWING = setOf(ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK)
}
