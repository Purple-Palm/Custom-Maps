package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import io.github.purplepalm.custommaps.engine.mapbox.map.StepShapes
import io.github.purplepalm.custommaps.nav.camera.Bend
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.JunctionFrame
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.model.LaneZoom

/**
 * The road past a maneuver point that the frame of its junction holds with Large
 * ([JunctionFrame]), handed to one map's viewport data source as more points to frame.
 *
 * It is worked out once for each step, from the route's shape of the next step, when the SDK first
 * frames that step's maneuver. The data source keeps the points it was given until it is given
 * others, also in frames of the open road. So they are taken back with the first progress that does
 * not frame the maneuver, at a new route, and at the end of guidance. Main thread only.
 *
 * @param frame hands the data source the points to frame besides its own; an empty list for none
 */
internal class JunctionPoints(private val frame: (List<Point>) -> Unit) {

    constructor(dataSource: MapboxNavigationViewportDataSource) :
        this({ points -> dataSource.additionalPointsToFrameForFollowing(points) })

    // The step whose maneuver the plan is for: its route, leg and step.
    private var routeId: String? = null
    private var legIndex = -1
    private var stepIndex = -1
    private var planned = Planned.NONE

    /** Whether the last update was one where the SDK framed the maneuver of [planned] with Large. */
    private var framing = false

    /** What the data source holds now. */
    private var given: List<Point> = emptyList()

    /**
     * Before the frame of [progress] is computed, after the data source was given it: the points
     * of the next maneuver while [framing] says that Large has the SDK frame it, none otherwise.
     *
     * @param coalesceMeters the SDK frames a next step of at most this length whole
     */
    fun update(progress: RouteProgress?, framing: Boolean, coalesceMeters: Double, tuning: CameraTuning) {
        val leg = progress?.currentLegProgress
        val step = leg?.currentStepProgress
        if (!framing || progress == null || leg == null || step == null) {
            this.framing = false
            return give(emptyList())
        }
        this.framing = true
        if (progress.navigationRoute.id != routeId || leg.legIndex != legIndex || step.stepIndex != stepIndex) {
            routeId = progress.navigationRoute.id
            legIndex = leg.legIndex
            stepIndex = step.stepIndex
            val next = leg.upcomingStep
            val road = progress.upcomingStepPoints.orEmpty()
            planned = plan(road, next?.maneuver()?.bearingBefore(), next?.distance(), coalesceMeters, tuning)
        }
        give(planned.points)
    }

    /** No route, a new route, or no guidance: the data source frames nothing of a junction's road. */
    fun clear() {
        routeId = null
        planned = Planned.NONE
        framing = false
        give(emptyList())
    }

    /**
     * For the camera line: how far past the maneuver point the road bends ("bendM") and by how
     * many degrees ("bendDeg", negative to the left), while the SDK frames that maneuver with
     * Large, and how many points the data source frames besides its own ("extraPts").
     */
    fun diagnostics(): Map<String, Any?> {
        val bend = planned.bend?.takeIf { framing }
        return mapOf(
            "bendM" to bend?.let { Motion.round(it.meters, 1) },
            "bendDeg" to bend?.let { Motion.round(it.degrees, 1) },
            "extraPts" to given.size,
        )
    }

    private fun give(points: List<Point>) {
        if (points === given || points.isEmpty() && given.isEmpty()) return
        given = points
        frame(points)
    }

    /** A plan and its points on the map. */
    class Planned(val bend: Bend?, val points: List<Point>) {
        companion object {
            val NONE = Planned(null, emptyList())
        }
    }

    companion object {
        /** How much of the next step is read: as far as a bend is looked for, and the road past it. */
        private fun readMeters(tuning: CameraTuning) = tuning.bendSearchMeters + tuning.largeMetersAfterManeuver

        /**
         * The plan for the maneuver at the start of [road], the next step's shape, with Large.
         * Without the bearing the car comes in on there is no plan.
         *
         * @param bearingBefore the route's bearing of the way in to the maneuver
         * @param stepMeters the next step's length
         */
        fun plan(road: List<Point>, bearingBefore: Double?, stepMeters: Double?, coalesceMeters: Double, tuning: CameraTuning): Planned {
            val origin = road.firstOrNull() ?: return Planned.NONE
            if (bearingBefore == null) return Planned.NONE
            val local = LocalMeters(origin.latitude(), origin.longitude())
            val plan = JunctionFrame.plan(
                mode = LaneZoom.LARGE,
                road = StepShapes.head(road, local, readMeters(tuning)),
                approachBearing = bearingBefore,
                stepMeters = stepMeters ?: Double.NaN,
                coalesceMeters = coalesceMeters,
                tuning = tuning,
            )
            return Planned(plan.bend, plan.points.map { StepShapes.toPoint(it, local) })
        }
    }
}
