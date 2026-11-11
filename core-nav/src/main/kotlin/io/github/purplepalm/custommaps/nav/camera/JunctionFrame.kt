package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.LaneZoom

/**
 * The road a junction's frame must hold besides what the navigation SDK puts in it, with Large.
 *
 * Toward a maneuver the SDK frames the car, the rest of the step and some road past the maneuver
 * point: with Large 30 m of the next step, or the whole next step and 30 m more when that step is
 * no longer than its coalescing distance (150 m). Where a long next step begins well before the
 * road bends, as at a turn bay, the bend then lies beyond the frame, under the maneuver card or
 * above the screen. So the road from the maneuver point to [CameraTuning.largeMetersAfterManeuver]
 * past the bend ([TurnBend]) is handed to the SDK as more points to frame, and the frame comes out
 * as far as the bend needs.
 *
 * The SDK takes the frame's bearing from the first of its points, the car, to the last, and these
 * points come last. So they are given in reverse order and end with the maneuver point: the
 * bearing stays the road's on the way in, instead of turning toward the bend.
 */
object JunctionFrame {

    /** What one maneuver adds to its frame: the [bend] found, if any, and the [points] to frame, last the maneuver point. */
    class Plan(val bend: Bend?, val points: List<LocalPoint>)

    /** Nothing to add. */
    val NONE = Plan(null, emptyList())

    /**
     * What the frame of the maneuver at the start of [road] needs besides the SDK's own points.
     * With Default, nothing: Default frames 100 m past the maneuver, and nothing of this runs.
     *
     * @param road the next step from its maneuver point on, in local meters
     * @param approachBearing the compass bearing the car comes in on
     * @param stepMeters how long the next step is
     * @param coalesceMeters the SDK frames a next step of at most this length whole
     */
    fun plan(
        mode: LaneZoom,
        road: List<LocalPoint>,
        approachBearing: Double,
        stepMeters: Double,
        coalesceMeters: Double,
        tuning: CameraTuning,
    ): Plan {
        if (mode != LaneZoom.LARGE) return NONE
        val bend = TurnBend.bend(road, approachBearing, tuning.bendDegrees, tuning.bendSideMeters, tuning.bendSearchMeters)
        // A bend near the maneuver point is in the SDK's frame already, and so is all of a short step.
        if (bend == null || bend.meters < tuning.bendMinMeters || stepMeters <= coalesceMeters) return Plan(bend, emptyList())
        return Plan(bend, LocalLine.head(road, bend.meters + tuning.largeMetersAfterManeuver).asReversed().toList())
    }
}
