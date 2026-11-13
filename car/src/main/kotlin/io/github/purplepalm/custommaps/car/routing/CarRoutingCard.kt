package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import io.github.purplepalm.custommaps.nav.glyph.GlyphPart
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.ManeuverType

/**
 * The library's objects for the car's routing content, with the app's own images: the routing card
 * and the destination's estimate of the home screen's navigation template, the trip the guidance
 * bridge sends, and the arrival. The only code of the car's guidance that builds them, and it builds
 * them only from what the mappers have checked, so no builder throws:
 *
 * - a step's lanes go with their image or not at all, as the host takes a step's lanes only with
 *   their image ([RoutingInfo.Builder.build]) and an image only with its lanes ([Step.Builder.build]):
 *   an image that could not be drawn takes the lanes with it;
 * - a loading card is empty, as the library requires; a loading trip keeps its destination;
 * - an arrow that could not be drawn leaves the maneuver without an icon, which the host allows.
 *
 * @param arrow the arrow of a maneuver's shapes for its role, in the look the car shows now
 * @param lanesImage the lanes image of a lane row, in the look the car shows now
 */
class CarRoutingCard(
    private val arrow: (List<GlyphPart>, ArrowRole) -> CarIcon?,
    private val lanesImage: (LaneInfo) -> CarIcon?,
) {

    /** The routing card for [routing]: its current step and distance, and the Then step; loading when [routing] is, or none has come yet. */
    fun routingInfo(routing: CarRouting?): RoutingInfo {
        val current = routing?.current
        if (routing == null || routing.loading || current == null) return RoutingInfo.Builder().setLoading(true).build()
        val builder = RoutingInfo.Builder().setCurrentStep(step(current, ArrowRole.NOW), current.distance.toHost())
        routing.then?.let { builder.setNextStep(step(it, ArrowRole.THEN)) }
        return builder.build()
    }

    /** The arrival time, time and distance left, as the phone's guidance bar has them; none before the first progress. */
    fun destinationEstimate(routing: CarRouting?): TravelEstimate? = routing?.destination?.toHost()

    /** The trip of [routing], for the host's cluster and head-up display: the card's steps and the destination. */
    fun trip(routing: CarRouting): Trip {
        val builder = Trip.Builder()
        val current = routing.current
        if (routing.loading || current == null) {
            builder.setLoading(true)
        } else {
            builder.addStep(step(current, ArrowRole.NOW), current.estimate.toHost())
            routing.then?.let { builder.addStep(step(it, ArrowRole.THEN), it.estimate.toHost()) }
        }
        builder.addDestination(Destination.Builder().setName(routing.destinationName).build(), routing.destination.toHost())
        return builder.build()
    }

    /** The arrival, as the phone's arrival card shows it: [title], the destination's [name] where it has one, and the destination's mark. */
    fun arrival(title: String, name: String?): MessageInfo {
        val builder = MessageInfo.Builder(title)
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.setText(it) }
        arrow(GlyphShapes.maneuver(ManeuverType.ARRIVE, null), ArrowRole.NOW)?.let { builder.setImage(it) }
        return builder.build()
    }

    /** One step, with its lanes and their image together or neither. */
    internal fun step(step: CarStep, role: ArrowRole): Step {
        val builder = step.cue?.let { cue ->
            val text = CarText.Builder(cue.full)
            cue.short?.let(text::addVariant)
            Step.Builder(text.build())
        } ?: Step.Builder()
        builder.setManeuver(step.maneuver.toHost(arrow(step.arrow, role)))
        val row = step.lanes
        if (row != null && step.hostLanes.isNotEmpty()) {
            val image = lanesImage(row)
            if (image != null) {
                step.hostLanes.toHost().forEach(builder::addLane)
                builder.setLanesImage(image)
            }
        }
        return builder.build()
    }
}
