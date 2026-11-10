package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/** Maps the Directions API maneuver vocabulary to domain types. Pure, so it is unit tested. */
internal object ManeuverMapping {

    fun type(value: String?): ManeuverType = when (value) {
        "depart" -> ManeuverType.DEPART
        "arrive" -> ManeuverType.ARRIVE
        "turn" -> ManeuverType.TURN
        "continue" -> ManeuverType.CONTINUE
        "new name" -> ManeuverType.NEW_NAME
        "merge" -> ManeuverType.MERGE
        "on ramp" -> ManeuverType.ON_RAMP
        "off ramp" -> ManeuverType.OFF_RAMP
        "fork" -> ManeuverType.FORK
        "end of road" -> ManeuverType.END_OF_ROAD
        "roundabout" -> ManeuverType.ROUNDABOUT
        "roundabout turn" -> ManeuverType.ROUNDABOUT_TURN
        "exit roundabout" -> ManeuverType.EXIT_ROUNDABOUT
        "rotary" -> ManeuverType.ROTARY
        "exit rotary" -> ManeuverType.EXIT_ROTARY
        "notification" -> ManeuverType.NOTIFICATION
        else -> ManeuverType.UNKNOWN
    }

    fun modifier(value: String?): TurnModifier? = when (value) {
        "uturn" -> TurnModifier.U_TURN
        "sharp right" -> TurnModifier.SHARP_RIGHT
        "right" -> TurnModifier.RIGHT
        "slight right" -> TurnModifier.SLIGHT_RIGHT
        "straight" -> TurnModifier.STRAIGHT
        "slight left" -> TurnModifier.SLIGHT_LEFT
        "left" -> TurnModifier.LEFT
        "sharp left" -> TurnModifier.SHARP_LEFT
        else -> null
    }

    /** One lane; for a lane the route uses, the active arrow defaults to its only arrow. */
    fun lane(directions: List<String>, isActive: Boolean, activeDirection: String?): Lane {
        val indications = directions.mapNotNull(::modifier).toSet()
        val active = if (isActive) modifier(activeDirection) ?: indications.singleOrNull() else null
        return Lane(indications, isRecommended = isActive, activeIndication = active)
    }

    /**
     * One lane at a point the route goes straight through, for the maneuver card. A lane the route
     * can be followed from is lit with the arrow the route takes there: the one the route names,
     * else the lane's arrow that turns least, which is its straight arrow where it has one. A lane
     * without arrows is drawn with a straight one, and that one is lit. A lane that leaves the
     * route is not recommended and has no lit arrow.
     */
    fun laneAhead(directions: List<String?>, usable: Boolean, usedDirection: String?): Lane {
        val indications = directions.mapNotNull(::modifier).toSet()
        val active = if (usable) modifier(usedDirection) ?: indications.minByOrNull(::sharpness) ?: TurnModifier.STRAIGHT else null
        return Lane(indications, isRecommended = usable, activeIndication = active)
    }

    /** How far an arrow turns away from straight on, left or right alike. */
    private fun sharpness(turn: TurnModifier): Int = when (turn) {
        TurnModifier.STRAIGHT -> 0
        TurnModifier.SLIGHT_LEFT, TurnModifier.SLIGHT_RIGHT -> 1
        TurnModifier.LEFT, TurnModifier.RIGHT -> 2
        TurnModifier.SHARP_LEFT, TurnModifier.SHARP_RIGHT -> 3
        TurnModifier.U_TURN -> 4
    }

    fun isRoundabout(type: ManeuverType): Boolean = type in ROUNDABOUT_TYPES

    /** The way out of a roundabout, which routes carry as a maneuver of its own. */
    fun isRoundaboutExit(type: ManeuverType): Boolean = type == ManeuverType.EXIT_ROUNDABOUT || type == ManeuverType.EXIT_ROTARY

    private val ROUNDABOUT_TYPES = setOf(
        ManeuverType.ROUNDABOUT,
        ManeuverType.ROUNDABOUT_TURN,
        ManeuverType.EXIT_ROUNDABOUT,
        ManeuverType.ROTARY,
        ManeuverType.EXIT_ROTARY,
    )
}
