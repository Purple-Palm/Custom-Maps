package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * When the view looks down onto the junction before a maneuver, and how far before it: by the kind
 * of junction, the car's speed and what the screen can hold. Plain values in and out, like the rest
 * of the camera math; the map engine reads the maneuver's facts from the route once per step.
 */

/** What the view does for the maneuver at the end of a step. */
enum class FocusKind {
    /** It stays up: a merge, a step that goes straight on, a split on a freeway (the long view ahead stays). */
    NONE,

    /** A plain junction: a turn, the end of a road, the way out of a roundabout, the arrival. */
    TURN,

    /** A junction that takes longer to read: a split off a street, a roundabout's way in, five roads or more, a sharp turn. */
    COMPLEX,
}

/** Why a junction is [FocusKind.COMPLEX], by its name in the log. */
enum class FocusWhy(val logName: String) {
    /** A fork, an on or off ramp, or a continue with a turn, off a street. */
    SPLIT("split"),

    /** The way into a roundabout or a rotary. */
    ROUNDABOUT("roundabout"),

    /** A junction of [FocusRule.MANY_ROADS] roads or more. */
    MANY_ROADS("roads"),

    /** A sharp turn or a U-turn. */
    SHARP("sharp"),
}

/**
 * The maneuver at the end of a step, as the map engine reads it once per step from that step's own
 * maneuver in the route. Not from the banner: the banner calls on ramps, name changes, continues and
 * the end of a road a turn, and the way out of a roundabout a roundabout.
 */
data class ManeuverFacts(
    /** The route, leg and step whose maneuver this is, keyed as the camera keys steps. */
    val stepKey: String,
    val kind: FocusKind,
    /** Why the junction is [FocusKind.COMPLEX]; null for the other kinds. */
    val why: FocusWhy? = null,
    /**
     * How long the steps right after the maneuver are that the navigation SDK frames with it, each no
     * longer than [FocusWindow.COALESCE_METERS], in meters; 0 for none.
     */
    val chainedMeters: Double = 0.0,
    /**
     * The angle between the road arriving at the junction and the road leaving it, in degrees: 0
     * straight on, 90 a right angle, 180 back the way the car came. Null where the route does not tell.
     */
    val turnDegrees: Double? = null,
    /**
     * For a step the view stays up for ([FocusKind.NONE]) whose next step ends in a maneuver the view
     * goes down for: the facts of that maneuver, [nextMeters] past the end of this step. Null otherwise.
     * The view goes down for it while the car is still on this step, so it is down by the time the
     * junction's own step begins.
     */
    val next: ManeuverFacts? = null,
    /** How long the next step is, from this step's maneuver to [next]'s; not a number without [next]. */
    val nextMeters: Double = Double.NaN,
) {
    /** The step of [next]; null without it. */
    val nextKey: String? get() = next?.stepKey

    /** What the view does for [next]; [FocusKind.NONE] without it. */
    val nextKind: FocusKind get() = next?.kind ?: FocusKind.NONE
}

/** Which kind of junction a maneuver is, for the view before it ([FocusKind]). */
object FocusRule {

    /** A junction of this many roads or more takes longer to read. */
    const val MANY_ROADS = 5

    /**
     * @param type the step's own maneuver, not the banner's word for it; null where there is none
     * @param modifier the direction of that maneuver
     * @param onFreeway the road that leads to the maneuver is a freeway
     * @param roads how many roads meet at the junction, from its bearings; 0 where unknown
     */
    fun kind(type: ManeuverType?, modifier: TurnModifier?, onFreeway: Boolean, roads: Int): FocusKind = classify(type, modifier, onFreeway, roads).first

    /** Why [kind] is [FocusKind.COMPLEX]; null for the other kinds. */
    fun why(type: ManeuverType?, modifier: TurnModifier?, onFreeway: Boolean, roads: Int): FocusWhy? = classify(type, modifier, onFreeway, roads).second

    /**
     * Without the facts of a step: the navigation SDK's own rule on the banner's type, which the view
     * followed until the facts came. It stays up for no type and for the five types the SDK excludes
     * (continue, merge, on ramp, off ramp, fork), and goes down at a plain junction's distance for any
     * other.
     */
    fun fallback(type: ManeuverType?): FocusKind = if (type == null || type in SDK_STAYS_UP) FocusKind.NONE else FocusKind.TURN

    private fun classify(type: ManeuverType?, modifier: TurnModifier?, onFreeway: Boolean, roads: Int): Pair<FocusKind, FocusWhy?> {
        val turns = modifier != null && modifier != TurnModifier.STRAIGHT
        return when (type) {
            null, ManeuverType.DEPART, ManeuverType.MERGE, ManeuverType.NOTIFICATION -> NONE
            ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN -> FocusKind.COMPLEX to FocusWhy.ROUNDABOUT
            // Off a freeway the long view ahead and the lanes' close view stay.
            ManeuverType.FORK, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP -> if (onFreeway) NONE else FocusKind.COMPLEX to FocusWhy.SPLIT
            ManeuverType.CONTINUE -> if (!turns || onFreeway) NONE else FocusKind.COMPLEX to FocusWhy.SPLIT
            ManeuverType.NEW_NAME -> if (!turns) NONE else junction(modifier, roads)
            // The way out of a roundabout, and the arrival, are plain: the roundabout's way in was the one to read.
            ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY, ManeuverType.ARRIVE -> FocusKind.TURN to null
            ManeuverType.TURN, ManeuverType.END_OF_ROAD, ManeuverType.UNKNOWN -> junction(modifier, roads)
        }
    }

    /** A junction the view goes down for: complex where the turn is sharp or many roads meet. */
    private fun junction(modifier: TurnModifier?, roads: Int): Pair<FocusKind, FocusWhy?> = when {
        modifier == TurnModifier.SHARP_LEFT || modifier == TurnModifier.SHARP_RIGHT || modifier == TurnModifier.U_TURN ->
            FocusKind.COMPLEX to FocusWhy.SHARP
        roads >= MANY_ROADS -> FocusKind.COMPLEX to FocusWhy.MANY_ROADS
        else -> FocusKind.TURN to null
    }

    private val NONE: Pair<FocusKind, FocusWhy?> = FocusKind.NONE to null

    /** The maneuver types the navigation SDK keeps the view up for: its own excluded list, which the app leaves as it is. */
    private val SDK_STAYS_UP = setOf(ManeuverType.CONTINUE, ManeuverType.MERGE, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK)
}

/**
 * Where the view is flat over a junction and its frame begins, and where the tilt starts down for it.
 * One source for the camera ([ManeuverFocus]) and for the drive view's planner, so the two cannot
 * drift apart.
 *
 * The frame of a junction holds the car, the rest of the step, the junction and some road past it,
 * fitted from straight above. It begins by time at the car's speed, within the kind's two distances,
 * and no farther out than the frame still fits at the road's own zoom ([capMeters]): it then never
 * widens the view, and the Large lane zoom never has to push the car under the bar. It never begins
 * later than the navigation SDK's own [CameraTuning.maneuverFramingMeters], which the two Mapbox
 * styles keep.
 */
object FocusWindow {

    /**
     * The navigation SDK frames a step after a maneuver whole, with that maneuver, when the step is no
     * longer than this (its distanceToCoalesceCompoundManeuvers, which the app leaves at its default).
     */
    const val COALESCE_METERS = 150.0

    /** A car slower than this, or one with no speed yet, counts as this fast: its window still opens before the junction. */
    const val MIN_SPEED_MPS = 3.0

    /**
     * How far before the junction the view is flat and the junction framed, in meters.
     *
     * @param speedMps the car's speed; slower than [MIN_SPEED_MPS] counts as that
     * @param capMeters the longest approach the frame fits at the road's zoom ([capMeters]);
     *   [Double.POSITIVE_INFINITY] for no cap, as the planner asks; not a number, zero or less for a map
     *   not laid out yet, which frames at the navigation SDK's own distance
     */
    fun framingMeters(kind: FocusKind, speedMps: Double, capMeters: Double, tuning: CameraTuning): Double {
        val v = speedMps.coerceAtLeast(MIN_SPEED_MPS)
        val wanted = when (kind) {
            FocusKind.NONE -> return tuning.maneuverFramingMeters
            FocusKind.TURN -> (v * tuning.turnFramingSeconds).coerceIn(tuning.turnFramingMinMeters, tuning.turnFramingMaxMeters)
            FocusKind.COMPLEX -> (v * tuning.complexFramingSeconds).coerceIn(tuning.complexFramingMinMeters, tuning.complexFramingMaxMeters)
        }
        if (!(capMeters > 0.0)) return tuning.maneuverFramingMeters
        return max(tuning.maneuverFramingMeters, min(wanted, capMeters))
    }

    /** How far before the junction the tilt starts down: the framing distance plus what the car covers in [CameraTuning.descentSeconds]. */
    fun windowMeters(kind: FocusKind, speedMps: Double, capMeters: Double, tuning: CameraTuning): Double =
        framingMeters(kind, speedMps, capMeters, tuning) + speedMps.coerceAtLeast(MIN_SPEED_MPS) * tuning.descentSeconds

    /**
     * How much farther than its window a next maneuver still keeps the view down as the hold after the
     * last one ends: [CameraTuning.bridgeSeconds] of driving.
     */
    fun bridgeMeters(speedMps: Double, tuning: CameraTuning): Double = speedMps.coerceAtLeast(MIN_SPEED_MPS) * tuning.bridgeSeconds

    /**
     * The longest approach whose frame fits above the car at the road's zoom
     * ([CameraTuning.defaultMaxZoom]): what [roomDp] of map height holds at [latitude], less the road
     * the frame keeps past the junction, [afterMeters], as far as it lies along the approach
     * (`max(0, cos turnDegrees)` of it: a right-angle turn's road lies across the frame, whose width
     * holds it), and less the steps the frame takes in with the junction, [chainedMeters].
     *
     * @param roomDp the map's height less the following padding at its top and bottom, in dp
     * @param turnDegrees the angle between the road in and the road out; null counts as straight on
     */
    fun capMeters(roomDp: Double, latitude: Double, afterMeters: Double, turnDegrees: Double?, chainedMeters: Double, tuning: CameraTuning): Double {
        val along = if (turnDegrees == null) 1.0 else max(0.0, cos(Math.toRadians(turnDegrees)))
        return MapScale.metersPerDp(tuning.defaultMaxZoom, latitude) * roomDp - afterMeters * along - chainedMeters
    }
}
