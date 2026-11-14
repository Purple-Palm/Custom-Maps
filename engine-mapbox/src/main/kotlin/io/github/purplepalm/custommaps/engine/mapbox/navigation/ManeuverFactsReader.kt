package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.navigation.base.trip.model.RouteProgress
import io.github.purplepalm.custommaps.nav.camera.Angles
import io.github.purplepalm.custommaps.nav.camera.FocusKind
import io.github.purplepalm.custommaps.nav.camera.FocusRule
import io.github.purplepalm.custommaps.nav.camera.FocusWindow
import io.github.purplepalm.custommaps.nav.camera.ManeuverFacts
import kotlin.math.abs

/**
 * The facts of the maneuver at the end of the step the car is on, for the camera's view down onto
 * its junction ([ManeuverFacts]): read from the route once per step, the same object for every
 * caller of that step, so the phone's map and the car's map get one answer whichever asks first. No
 * request and no other service is involved.
 *
 * The maneuver is the next step's own, as the route names it (its type and modifier), never the
 * banner's: the banner calls on ramps, name changes, continues and the end of a road a turn, and the
 * way out of a roundabout a roundabout. Whether the road to it is a freeway comes from the step's
 * last intersection, as the lane choices read it; how many roads meet and the angle of the turn from
 * the junction's own bearings. Past a step the view stays up for, the facts also name the junction
 * at the end of the step after it. A route whose steps cannot be read gives no facts, and the camera
 * then frames as the navigation SDK does; it never ends the drive. Main thread only.
 */
internal class ManeuverFactsReader(private val onError: (RuntimeException) -> Unit = {}) {

    private var lastKey: String? = null
    private var last: ManeuverFacts? = null

    /** The facts of [progress]'s step, the same object while the step is; null where they cannot be read. */
    fun facts(progress: RouteProgress): ManeuverFacts? {
        val leg = progress.currentLegProgress ?: return null
        val step = leg.currentStepProgress ?: return null
        return facts(progress.navigationRoute.id, leg.legIndex, step.stepIndex) { leg.routeLeg?.steps().orEmpty() }
    }

    /**
     * The rule of [facts] on plain values: the leg's [steps] are read once for each step key, and the
     * facts of that step handed out again while the key stays. A fault while reading them is reported
     * once and gives no facts for that step.
     */
    fun facts(routeId: String, legIndex: Int, stepIndex: Int, steps: () -> List<LegStep>): ManeuverFacts? {
        val key = stepKey(routeId, legIndex, stepIndex)
        if (key == lastKey) return last
        lastKey = key
        last = try {
            factsOf(routeId, legIndex, steps(), stepIndex)
        } catch (e: RuntimeException) {
            onError(e)
            null
        }
        return last
    }

    companion object {
        /** The camera's key of a step: the route, leg and step. */
        fun stepKey(routeId: String, legIndex: Int, stepIndex: Int): String = "$routeId/$legIndex/$stepIndex"

        /**
         * The facts of the maneuver at the end of step [stepIndex] of a leg's [steps]: the next
         * step's own maneuver. For a step the view stays up for, also the junction at the end of the
         * step after it, where the view goes down for that one. Null past the leg's last maneuver.
         */
        fun factsOf(routeId: String, legIndex: Int, steps: List<LegStep>, stepIndex: Int): ManeuverFacts? {
            val own = junctionFacts(routeId, legIndex, steps, stepIndex) ?: return null
            if (own.kind != FocusKind.NONE) return own
            val next = junctionFacts(routeId, legIndex, steps, stepIndex + 1)?.takeIf { it.kind != FocusKind.NONE } ?: return own
            return own.copy(next = next, nextMeters = steps[stepIndex + 1].distance())
        }

        /** The facts of the maneuver that ends step [index], the one that begins the step after it. */
        private fun junctionFacts(routeId: String, legIndex: Int, steps: List<LegStep>, index: Int): ManeuverFacts? {
            val step = steps.getOrNull(index) ?: return null
            val upcoming = steps.getOrNull(index + 1) ?: return null
            val maneuver = upcoming.maneuver()
            val type = maneuver.type()?.let(ManeuverMapping::type)
            val modifier = ManeuverMapping.modifier(maneuver.modifier())
            val onFreeway = step.intersections()?.lastOrNull()?.isFreeway() == true
            val junction = upcoming.intersections()?.firstOrNull()
            val roads = junction?.bearings()?.size ?: 0
            return ManeuverFacts(
                stepKey = stepKey(routeId, legIndex, index),
                kind = FocusRule.kind(type, modifier, onFreeway, roads),
                why = FocusRule.why(type, modifier, onFreeway, roads),
                chainedMeters = chainedMeters(steps, index + 1),
                turnDegrees = junction?.let(::turnDegrees) ?: turnDegrees(maneuver.bearingBefore(), maneuver.bearingAfter()),
            )
        }

        /**
         * How long the steps from [from] on are that the navigation SDK frames with the maneuver
         * before them: each no longer than [FocusWindow.COALESCE_METERS], up to the first longer one.
         */
        fun chainedMeters(steps: List<LegStep>, from: Int): Double {
            var meters = 0.0
            for (i in from until steps.size) {
                val length = steps[i].distance()
                if (length > FocusWindow.COALESCE_METERS) break
                meters += length
            }
            return meters
        }

        /**
         * The angle between the road arriving at [junction] and the road leaving it, in degrees from 0
         * (straight on) to 180 (back the way the car came): from the junction's bearings, the one it
         * is entered by ([StepIntersection.in], which points back along the road arrived on) and the
         * one it is left by ([StepIntersection.out]). Null where either is missing.
         */
        fun turnDegrees(junction: StepIntersection): Double? {
            val bearings = junction.bearings() ?: return null
            val arrived = junction.`in`()?.let { bearings.getOrNull(it) } ?: return null
            val leaves = junction.out()?.let { bearings.getOrNull(it) } ?: return null
            return turnDegrees(arrived + 180.0, leaves.toDouble())
        }

        /** The angle between a heading [before] a maneuver and one [after] it, from 0 to 180 degrees; null without both. */
        fun turnDegrees(before: Double?, after: Double?): Double? {
            if (before == null || after == null) return null
            return abs(Angles.wrap180(after - before))
        }
    }
}
