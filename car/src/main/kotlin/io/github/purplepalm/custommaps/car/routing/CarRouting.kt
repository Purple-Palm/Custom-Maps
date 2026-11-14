package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.glyph.GlyphPart
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.time.ZonedDateTime
import kotlin.time.Duration

/** The words of a step: [full] where the host has room, [short] where it has less; null when they are the same. */
data class CarCue(val full: String, val short: String? = null)

/** The words the car's routing content needs from the car's resources. */
interface CarWords {
    /** "Exit 2": a roundabout's exit, as the phone's card names it. */
    fun roundaboutExit(number: Int): String

    /** The side the destination is on, as the phone's Then chip names it: "On the left", "On the right", or "Destination". */
    fun arrivalSide(side: ArrivalSide): String

    /** The destination's name when the place has neither a name nor an address. */
    val destination: String
}

/** Where the destination is at the arrival, as the route says. */
enum class ArrivalSide { LEFT, RIGHT, AHEAD }

/**
 * One step of the car's routing card and trip: the maneuver as the host names it, the app's own arrow
 * for it, its words, the lane row with the host's lanes, how far it is from the car, and when the car
 * gets there.
 *
 * @property lanes the card's lane row, for the lanes image; null without a row, and for the Then step
 * @property hostLanes the row's lanes as the host takes them; empty without a row
 */
data class CarStep(
    val maneuver: CarManeuver,
    val arrow: List<GlyphPart>,
    val cue: CarCue?,
    val lanes: LaneInfo?,
    val hostLanes: List<CarLane>,
    val distance: CarDistance,
    val estimate: CarEstimate,
)

/**
 * What the car shows of a drive at one progress update: the routing card and the trip the host sends
 * to the car's cluster and head-up display are both made from it, so they never disagree, and both
 * say what the phone's card says ([of]).
 *
 * @property loading the card shows that the route is being worked out: the car left the route, or a
 *   new route has no instruction yet; it then has no step
 * @property current the next maneuver, the phone card's; null while [loading]
 * @property then the maneuver after it, only while the phone's Then chip shows it
 * @property destinationName what the trip names the destination: the place's name, else its address,
 *   else the car's word for it; never blank
 * @property key what the card shows other than its numbers: a change of it goes to the host at once,
 *   distances and times at most once a second
 */
data class CarRouting(
    val loading: Boolean,
    val current: CarStep?,
    val then: CarStep?,
    val destination: CarEstimate,
    val destinationName: String,
    val key: Key,
) {
    /** The parts of [key]: the step, whether it is loading, its maneuver and arrow, words, lane row, and the Then step's. */
    data class Key(
        val stepKey: String,
        val loading: Boolean,
        val current: StepKey?,
        val then: StepKey?,
    )

    /** What a step shows other than its distance and time. */
    data class StepKey(val maneuver: CarManeuver, val arrow: List<GlyphPart>, val cue: CarCue?, val lanes: LaneInfo?)

    companion object {
        /**
         * The car's routing content for [progress] at [now], on the way to [place].
         *
         * - The current step is the phone card's: its maneuver, its words as the card has them (the
         *   road, then the roundabout's exit and the second line, "Exit 2 · Office"; the road alone
         *   where the host has less room), its distance and its time, and the card's lane row
         *   ([CardLanes]: the run-up's row first, then a lane choice ahead, then the maneuver's own
         *   within two miles of it).
         * - The Then step is there exactly while the phone's Then chip shows it
         *   ([RouteProgressSnapshot.thenManeuver]), with the chip's words: the road, or the side of
         *   the destination at the arrival. Its distance and time count from the car.
         * - Off the route, and right after a new route until its first instruction, the card loads,
         *   as the phone's card dims: no step, the destination's estimate as it last was.
         */
        fun of(progress: RouteProgressSnapshot, place: Place?, now: ZonedDateTime, words: CarWords): CarRouting {
            val destination = EstimateToCar.destination(progress.distanceRemaining, progress.durationRemaining, now)
            val name = place?.name?.takeIf { it.isNotBlank() } ?: place?.address?.takeIf { it.isNotBlank() } ?: words.destination
            val loading = progress.offRoute || progress.pending
            if (loading) {
                return CarRouting(true, null, null, destination, name, Key(progress.stepKey, true, null, null))
            }
            val row = CardLanes.of(progress).lanes
            val maneuver = progress.nextManeuver
            val current = CarStep(
                maneuver = ManeuverToCar.of(maneuver),
                arrow = arrowOf(maneuver),
                cue = currentCue(maneuver, words),
                lanes = row,
                hostLanes = row?.let(LanesToCar::of).orEmpty(),
                distance = DistanceToCar.of(progress.distanceToNextManeuver),
                estimate = EstimateToCar.step(progress.distanceToNextManeuver, progress.durationToNextManeuver, now),
            )
            val then = progress.thenManeuver?.let { following ->
                val distance = progress.distanceToNextManeuver + following.distanceAfterNext
                CarStep(
                    maneuver = ManeuverToCar.of(following.maneuver),
                    arrow = arrowOf(following.maneuver),
                    cue = thenCue(following.maneuver, words),
                    lanes = null,
                    hostLanes = emptyList(),
                    distance = DistanceToCar.of(distance),
                    estimate = EstimateToCar.step(distance, stepTime(progress, following.durationAfterNext), now),
                )
            }
            return CarRouting(false, current, then, destination, name, Key(progress.stepKey, false, current.key(), then?.key()))
        }

        private fun CarStep.key() = StepKey(maneuver, arrow, cue, lanes)

        private fun arrowOf(maneuver: Maneuver): List<GlyphPart> =
            GlyphShapes.maneuver(maneuver.type, maneuver.modifier, maneuver.roundaboutExitDegrees)

        /** The time to the Then step: the time to the next one and from there to it, unknown when either is. */
        private fun stepTime(progress: RouteProgressSnapshot, after: Duration): Duration =
            if (progress.durationToNextManeuver.isPositive() && after.isPositive()) progress.durationToNextManeuver + after else Duration.ZERO

        /** The phone card's words: the road, then the roundabout's exit and the second line. */
        internal fun currentCue(maneuver: Maneuver, words: CarWords): CarCue? {
            val road = maneuver.primaryText.trim()
            val details = listOfNotNull(
                maneuver.roundaboutExitNumber?.let(words::roundaboutExit),
                maneuver.secondaryText?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            return when {
                road.isNotEmpty() && details.isNotEmpty() -> CarCue("$road · $details", short = road)
                road.isNotEmpty() -> CarCue(road)
                details.isNotEmpty() -> CarCue(details)
                else -> null
            }
        }

        /** The Then chip's words: the road, or the side the destination is on at the arrival. */
        internal fun thenCue(maneuver: Maneuver, words: CarWords): CarCue? =
            if (maneuver.type == ManeuverType.ARRIVE) {
                CarCue(words.arrivalSide(sideOf(maneuver.modifier)))
            } else {
                maneuver.primaryText.trim().takeIf { it.isNotEmpty() }?.let { CarCue(it) }
            }

        internal fun sideOf(turn: TurnModifier?): ArrivalSide = when (turn) {
            TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT -> ArrivalSide.LEFT
            TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT -> ArrivalSide.RIGHT
            else -> ArrivalSide.AHEAD
        }
    }
}
