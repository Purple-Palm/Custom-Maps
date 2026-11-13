package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.navigation.model.LaneDirection
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import androidx.car.app.navigation.model.Lane as HostLane

/** One arrow of a lane as the car's host takes it: a `LaneDirection.SHAPE_*` and whether the route takes it. */
data class CarLaneDirection(val shape: Int, val recommended: Boolean)

/** One lane as the car's host takes it, with every arrow the card draws on it; never without an arrow. */
data class CarLane(val directions: List<CarLaneDirection>)

/**
 * The lanes of the card's lane row as the car's host takes them, for the car's cluster and head-up
 * display; the routing card itself shows the app's own picture of the same row. Lanes go from the
 * leftmost, as on the card.
 *
 * Each lane has the arrows the card draws on it: those painted on it, a straight one for a lane with
 * none, and the arrow the route takes from it should the data not list that one. An arrow is
 * recommended where the card lights it: the route's arrow in a lane the route can be followed from,
 * every arrow of such a lane when the route names none of them, and the arrow of a lane kept for car
 * pools or buses that the card lights because no other lane can be used; never an arrow of a lane the
 * card draws as an outline. Traffic is on the right, so a U-turn arrow is a left one.
 */
object LanesToCar {

    fun of(lanes: LaneInfo): List<CarLane> = lanes.lanes.map(::lane)

    fun lane(lane: Lane): CarLane {
        val arrows = lane.indications.ifEmpty { setOf(TurnModifier.STRAIGHT) }
        // A lane the card draws as an outline is never lit.
        val lit = lane.isRecommended && !lane.outline
        val active = lane.activeIndication.takeIf { lit }
        val drawn = if (active != null && active !in arrows) arrows + active else arrows
        return CarLane(drawn.map { arrow -> CarLaneDirection(shape(arrow), lit && (active == null || arrow == active)) })
    }

    fun shape(turn: TurnModifier): Int = when (turn) {
        TurnModifier.U_TURN -> LaneDirection.SHAPE_U_TURN_LEFT
        TurnModifier.SHARP_RIGHT -> LaneDirection.SHAPE_SHARP_RIGHT
        TurnModifier.RIGHT -> LaneDirection.SHAPE_NORMAL_RIGHT
        TurnModifier.SLIGHT_RIGHT -> LaneDirection.SHAPE_SLIGHT_RIGHT
        TurnModifier.STRAIGHT -> LaneDirection.SHAPE_STRAIGHT
        TurnModifier.SLIGHT_LEFT -> LaneDirection.SHAPE_SLIGHT_LEFT
        TurnModifier.LEFT -> LaneDirection.SHAPE_NORMAL_LEFT
        TurnModifier.SHARP_LEFT -> LaneDirection.SHAPE_SHARP_LEFT
    }
}

/** The library's lanes for [this], in the same order. */
internal fun List<CarLane>.toHost(): List<HostLane> = map { lane ->
    val builder = HostLane.Builder()
    lane.directions.forEach { builder.addDirection(LaneDirection.create(it.shape, it.recommended)) }
    builder.build()
}
