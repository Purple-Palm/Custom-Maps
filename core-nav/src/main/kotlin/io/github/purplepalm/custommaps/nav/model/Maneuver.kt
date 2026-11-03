package io.github.purplepalm.custommaps.nav.model

/** Direction of a maneuver or of a lane arrow, as seen by the driver. */
enum class TurnModifier {
    U_TURN,
    SHARP_RIGHT,
    RIGHT,
    SLIGHT_RIGHT,
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
}

/** Kind of maneuver, independent of the routing engine. */
enum class ManeuverType {
    DEPART,
    ARRIVE,
    TURN,
    CONTINUE,
    NEW_NAME,
    MERGE,
    ON_RAMP,
    OFF_RAMP,
    FORK,
    END_OF_ROAD,
    ROUNDABOUT,
    ROUNDABOUT_TURN,
    EXIT_ROUNDABOUT,
    ROTARY,
    EXIT_ROTARY,
    NOTIFICATION,
    UNKNOWN,
}

/** The next action the driver has to take. */
data class Maneuver(
    val type: ManeuverType,
    val modifier: TurnModifier?,
    /** Main instruction, usually the road or exit name. */
    val primaryText: String,
    val secondaryText: String? = null,
    /** Exit to take at a roundabout or rotary, counted from 1. */
    val roundaboutExitNumber: Int? = null,
) {
    init {
        require(roundaboutExitNumber == null || roundaboutExitNumber > 0) {
            "Roundabout exit number must be positive: $roundaboutExitNumber"
        }
    }
}
