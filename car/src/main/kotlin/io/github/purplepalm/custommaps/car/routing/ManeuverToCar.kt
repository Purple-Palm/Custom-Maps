package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.model.CarIcon
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.roundToInt
import androidx.car.app.navigation.model.Maneuver as HostManeuver

/**
 * A maneuver as the car's host takes it: one of the library's `Maneuver.TYPE_*`, with a roundabout's
 * exit number and angle where that type takes them and never elsewhere, so that the library's builder
 * never refuses it ([ManeuverToCar]).
 *
 * @property exitNumber the exit to take, from 1; set exactly for the roundabout types that take one
 * @property exitAngle degrees travelled round the roundabout, 1 to 360; set exactly for the types with an angle
 */
data class CarManeuver(val type: Int, val exitNumber: Int? = null, val exitAngle: Int? = null)

/**
 * The app's maneuvers as the car's host names them, for the routing card, the trip the host shows
 * on the car's cluster and head-up display, and the host's own arrow where it draws one. The arrow on
 * the car's screen is the app's own, drawn from the same maneuver.
 *
 * The rules are those of Mapbox's car maneuver mapper for traffic on the right, where the app drives:
 *
 * - A U-turn is a left one ([HostManeuver.TYPE_U_TURN_LEFT]), towards the middle of the road, also
 *   where the route calls it going on along the road, as the phone's card draws it.
 * - A turn, a new name, a notice and a maneuver of no known kind take the kind of their turn; one
 *   with no turn goes straight on. An end of the road with no turn is of no known kind.
 * - Going on along the road is straight on, whatever its bend.
 * - An off-ramp or a fork without a side (no turn, straight on or a U-turn) leaves on the near side,
 *   the right; an on-ramp without a side goes straight on. A merge without a side has no side.
 * - The arrival is the destination, ahead, on the left or on the right as the route says.
 * - A roundabout, a roundabout turn or a rotary is entered and left counter-clockwise, with the exit
 *   number (1 when the route gives none) and, where the route gives an angle of 1 to 360 degrees
 *   once rounded, the angle as well. Leaving one is the roundabout's exit, with no number, which the
 *   host refuses for it.
 */
object ManeuverToCar {

    fun of(maneuver: Maneuver): CarManeuver =
        of(maneuver.type, maneuver.modifier, maneuver.roundaboutExitNumber, maneuver.roundaboutExitDegrees)

    fun of(type: ManeuverType, turn: TurnModifier?, exitNumber: Int?, exitDegrees: Double?): CarManeuver = when (type) {
        ManeuverType.DEPART -> CarManeuver(HostManeuver.TYPE_DEPART)
        ManeuverType.ARRIVE -> CarManeuver(arrival(turn))
        ManeuverType.TURN, ManeuverType.NEW_NAME, ManeuverType.NOTIFICATION, ManeuverType.UNKNOWN -> CarManeuver(turnType(turn))
        ManeuverType.END_OF_ROAD -> CarManeuver(if (turn == null) HostManeuver.TYPE_UNKNOWN else turnType(turn))
        ManeuverType.CONTINUE -> CarManeuver(if (turn == TurnModifier.U_TURN) HostManeuver.TYPE_U_TURN_LEFT else HostManeuver.TYPE_STRAIGHT)
        ManeuverType.MERGE -> CarManeuver(merge(turn))
        ManeuverType.ON_RAMP -> CarManeuver(onRamp(turn))
        ManeuverType.OFF_RAMP -> CarManeuver(offRamp(turn))
        ManeuverType.FORK -> CarManeuver(fork(turn))
        ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY -> roundabout(exitNumber, exitDegrees)
        ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY -> CarManeuver(HostManeuver.TYPE_ROUNDABOUT_EXIT_CCW)
    }

    /** The exit number when the route gives none: the first one. */
    const val UNKNOWN_EXIT = 1

    /** The angles the host takes for a roundabout's exit, in degrees round it from the entry. */
    val EXIT_ANGLES = 1..360

    /** The angle of an exit at [degrees] as the host takes it, or null when it would refuse that. */
    fun exitAngle(degrees: Double?): Int? = degrees?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in EXIT_ANGLES }

    private fun roundabout(exitNumber: Int?, exitDegrees: Double?): CarManeuver {
        val number = exitNumber?.takeIf { it >= UNKNOWN_EXIT } ?: UNKNOWN_EXIT
        val angle = exitAngle(exitDegrees)
        return if (angle != null) {
            CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, exitNumber = number, exitAngle = angle)
        } else {
            CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, exitNumber = number)
        }
    }

    private fun turnType(turn: TurnModifier?): Int = when (turn) {
        TurnModifier.U_TURN -> HostManeuver.TYPE_U_TURN_LEFT
        TurnModifier.SHARP_RIGHT -> HostManeuver.TYPE_TURN_SHARP_RIGHT
        TurnModifier.RIGHT -> HostManeuver.TYPE_TURN_NORMAL_RIGHT
        TurnModifier.SLIGHT_RIGHT -> HostManeuver.TYPE_TURN_SLIGHT_RIGHT
        TurnModifier.STRAIGHT, null -> HostManeuver.TYPE_STRAIGHT
        TurnModifier.SLIGHT_LEFT -> HostManeuver.TYPE_TURN_SLIGHT_LEFT
        TurnModifier.LEFT -> HostManeuver.TYPE_TURN_NORMAL_LEFT
        TurnModifier.SHARP_LEFT -> HostManeuver.TYPE_TURN_SHARP_LEFT
    }

    private fun arrival(turn: TurnModifier?): Int = when {
        turn == TurnModifier.STRAIGHT -> HostManeuver.TYPE_DESTINATION_STRAIGHT
        turn.isRight() -> HostManeuver.TYPE_DESTINATION_RIGHT
        turn.isLeft() -> HostManeuver.TYPE_DESTINATION_LEFT
        else -> HostManeuver.TYPE_DESTINATION
    }

    private fun merge(turn: TurnModifier?): Int = when {
        turn.isRight() -> HostManeuver.TYPE_MERGE_RIGHT
        turn.isLeft() -> HostManeuver.TYPE_MERGE_LEFT
        else -> HostManeuver.TYPE_MERGE_SIDE_UNSPECIFIED
    }

    private fun onRamp(turn: TurnModifier?): Int = when (turn) {
        TurnModifier.SHARP_RIGHT -> HostManeuver.TYPE_ON_RAMP_SHARP_RIGHT
        TurnModifier.RIGHT -> HostManeuver.TYPE_ON_RAMP_NORMAL_RIGHT
        TurnModifier.SLIGHT_RIGHT -> HostManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        TurnModifier.SLIGHT_LEFT -> HostManeuver.TYPE_ON_RAMP_SLIGHT_LEFT
        TurnModifier.LEFT -> HostManeuver.TYPE_ON_RAMP_NORMAL_LEFT
        TurnModifier.SHARP_LEFT -> HostManeuver.TYPE_ON_RAMP_SHARP_LEFT
        TurnModifier.STRAIGHT, TurnModifier.U_TURN, null -> HostManeuver.TYPE_STRAIGHT
    }

    private fun offRamp(turn: TurnModifier?): Int = when (turn) {
        TurnModifier.SLIGHT_RIGHT -> HostManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        TurnModifier.SLIGHT_LEFT -> HostManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        TurnModifier.LEFT, TurnModifier.SHARP_LEFT -> HostManeuver.TYPE_OFF_RAMP_NORMAL_LEFT
        // Without a side, the near side.
        TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT, TurnModifier.STRAIGHT, TurnModifier.U_TURN, null -> HostManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    }

    private fun fork(turn: TurnModifier?): Int = if (turn.isLeft()) HostManeuver.TYPE_FORK_LEFT else HostManeuver.TYPE_FORK_RIGHT

    private fun TurnModifier?.isRight() = this == TurnModifier.SLIGHT_RIGHT || this == TurnModifier.RIGHT || this == TurnModifier.SHARP_RIGHT

    private fun TurnModifier?.isLeft() = this == TurnModifier.SLIGHT_LEFT || this == TurnModifier.LEFT || this == TurnModifier.SHARP_LEFT
}

/** The library's maneuver for [this], with the app's own arrow as its [icon] when there is one. */
internal fun CarManeuver.toHost(icon: CarIcon?): HostManeuver {
    val builder = HostManeuver.Builder(type)
    exitNumber?.let(builder::setRoundaboutExitNumber)
    exitAngle?.let(builder::setRoundaboutExitAngle)
    icon?.let(builder::setIcon)
    return builder.build()
}
