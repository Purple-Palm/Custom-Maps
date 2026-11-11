package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.abs
import kotlin.math.exp

/*
 * The motion math of the guidance camera that moves on every display frame: plain numbers in and
 * out, in seconds, degrees and meters, so it runs and is tested on the JVM. The camera that uses it
 * lives with the map engine.
 */

/**
 * Every number the per-frame guidance camera uses, in seconds, degrees and meters: one place to
 * tune after a drive.
 */
data class CameraTuning(
    /** The tilt on the road; the same as the navigation SDK's default following pitch. */
    val cruisePitch: Double = 45.0,
    /** The tilt over a maneuver: straight down onto the junction. */
    val maneuverPitch: Double = 0.0,
    /** How far before a maneuver the navigation SDK starts to frame the maneuver instead of the road. */
    val maneuverFramingMeters: Double = 180.0,
    /** The tilt goes down over this long and is down when the car reaches [maneuverFramingMeters]. */
    val descentSeconds: Double = 3.5,
    val ascentSeconds: Double = 3.0,
    /** After the maneuver the view stays down for at least this far and this long. */
    val holdAfterManeuverMeters: Double = 25.0,
    val holdAfterManeuverSeconds: Double = 2.0,
    /**
     * A once-a-second sample is reached over this long. It is longer than the time between two
     * samples and equal to the puck's glide, so no input stops between samples.
     */
    val sampleHoldSeconds: Double = 1.1,
    /**
     * How far ahead of the ramp between two samples the zoom and the bearing aim, in time constants
     * of their springs. With 1 the camera trails the ramp by one time constant and comes up to the
     * sample a ramp stops at, never past it. With 2 it stays on a steady ramp, and swings a few
     * degrees past the bearing a turn ends on.
     */
    val rampLead: Double = 1.0,
    /** Spring time constants. The camera center trails a moving puck by 2 × [centerTau] × speed. */
    val centerTau: Double = 0.20,
    val zoomTau: Double = 0.45,
    val bearingTau: Double = 0.35,
    val pitchTau: Double = 0.12,
    val paddingTau: Double = 0.35,
    /** The fastest change outside a mode transition, in zoom levels and degrees per second. */
    val maxZoomRate: Double = 1.0,
    val maxBearingRate: Double = 60.0,
    /** How long a change of mode takes; the long one is for moves across several zoom levels or screens. */
    val transitionSeconds: Double = 1.5,
    val longTransitionSeconds: Double = 2.2,
    val teleportTransitionSeconds: Double = 0.8,
    /** The most a long move zooms out on its way, in zoom levels. */
    val maxArcZoom: Double = 3.0,
    /** A jump of the matched position that leaves the camera farther than this from the puck is flown, not chased. */
    val teleportMeters: Double = 60.0,
    /**
     * The closest the view goes with the Default lane zoom, and on the open road with Large: the
     * navigation SDK's own limit. What follows is what Large adds; none of it applies with Default.
     */
    val defaultMaxZoom: Double = 16.35,
    /** Large never goes closer than this. */
    val maxZoom: Double = 20.0,
    /** Large at a junction: a lane is this wide on the screen, about 4.4 mm. */
    val junctionLaneDp: Double = 28.0,
    /** Large near a lane choice on a freeway, with the tilt kept at [cruisePitch]: about 2.2 mm. */
    val freewayLaneDp: Double = 14.0,
    /** How much road after a maneuver Large keeps in the frame of that maneuver; the navigation SDK keeps 100 m. */
    val largeMetersAfterManeuver: Double = 30.0,
    /**
     * Where the road bends after a maneuver ([TurnBend]): the first point that heads this many
     * degrees or more off the way in, or else the first place more than [bendSideMeters] to its
     * side and to the side of the road's own first [bendMinMeters], looked for within
     * [bendSearchMeters] of the maneuver point.
     */
    val bendDegrees: Double = 20.0,
    val bendSideMeters: Double = 4.0,
    val bendSearchMeters: Double = 150.0,
    /**
     * With Large, a bend at least this far past the maneuver point, and the road
     * [largeMetersAfterManeuver] past it, join the frame of the junction ([JunctionFrame]). A
     * nearer bend is in that frame already. The road's first this many meters also give the line
     * it leaves the maneuver point on, which a bend found to the side has to leave too.
     */
    val bendMinMeters: Double = 15.0,
    /** A lane choice takes at least this many lanes, not counting lanes kept for buses, car pools and the like. */
    val laneChoiceMinLanes: Int = 3,
    /** Where the route goes straight on, at least this many lanes have to leave it. */
    val laneChoiceMinLeaving: Int = 2,
    /** The close view starts this long before a lane choice, no nearer and no farther than the two distances. */
    val laneLeadSeconds: Double = 25.0,
    val laneMinWindowMeters: Double = 400.0,
    val laneMaxWindowMeters: Double = 1_200.0,
    /** The view goes close and comes back over this long. */
    val laneInSeconds: Double = 6.0,
    val laneOutSeconds: Double = 6.0,
    /** After a lane choice the view stays close for at least this far and this long. */
    val laneHoldMeters: Double = 30.0,
    val laneHoldSeconds: Double = 2.0,
    /** A lane choice no more than this much driving from its own close view keeps the view close until then. */
    val laneCoalesceSeconds: Double = 10.0,
    /**
     * Near a lane choice the view turns at most this far from the car's heading toward the road
     * ahead, so the lanes at the car stay close to upright. Elsewhere the navigation SDK's 45
     * degrees apply.
     */
    val laneMaxBearingLead: Double = 20.0,
    /**
     * With Large, while the view looks down onto a turn, it also turns at most this far toward the
     * road ahead. A bend past the turn is then not swung to the top of the screen while the car is
     * still close in and driving straight. Elsewhere the navigation SDK's 45 degrees apply.
     */
    val junctionMaxBearingLead: Double = 20.0,
    /** Close in, the camera aims ahead of the moving car so that its center trails the car by no more than this. */
    val maxPuckLagDp: Double = 12.0,
) {
    companion object {
        val PHONE = CameraTuning()

        /** A calmer center for the larger screen that is farther from the driver. */
        val CAR = CameraTuning(centerTau = 0.25)
    }
}

/**
 * A critically damped spring with an exact step: the result does not depend on how the time is cut
 * into frames. From rest it comes up to a target that holds still and never passes it. One that is
 * still moving when its target stops can run past, which is why a ramp is led by no more than one
 * time constant (see [SampleRamp.lead]).
 */
class Spring(private val tau: Double) {
    var value = 0.0
        private set
    var velocity = 0.0
        private set

    /** Puts the spring at rest at [to]. */
    fun snap(to: Double) {
        value = to
        velocity = 0.0
    }

    /**
     * Moves [dt] seconds toward [target], held still for that time. With x measured from the target,
     * x(t) = (x0 + (v0 + x0 / tau) t) e^(-t / tau).
     */
    fun step(target: Double, dt: Double) {
        if (dt <= 0.0) return
        val w = 1.0 / tau
        val x = value - target
        val k = velocity + w * x
        val decay = exp(-w * dt)
        value = target + (x + k * dt) * decay
        velocity = (velocity - w * k * dt) * decay
    }

    fun isSettled(target: Double, tolerance: Double) = abs(value - target) < tolerance && abs(velocity) < tolerance * 4
}

/**
 * Makes a value that arrives about once a second continuous: from wherever it is to the newest
 * sample at a constant rate over [holdSeconds]. The hold is longer than the time between samples, so
 * the value never stops between them, and a spring that follows it does not pulse once a second.
 */
class SampleRamp(private val holdSeconds: Double) {
    private var from = 0.0
    private var to = 0.0
    private var start = 0.0

    /** False until the first sample: there is nothing to follow yet. */
    var ready = false
        private set

    /** Drops what it holds; the next sample is taken at once, as the first one is. */
    fun forget() {
        ready = false
    }

    /** Holds [value] from [now] on, without a ramp. */
    fun reset(value: Double, now: Double) {
        from = value
        to = value
        start = now
        ready = true
    }

    /** A new sample: the ramp turns toward it from where it is at [now]. */
    fun push(sample: Double, now: Double) {
        if (!ready) return reset(sample, now)
        from = value(now)
        to = sample
        start = now
    }

    /** Where the value is at [now]: on the newest sample itself once the hold has passed. */
    fun value(now: Double): Double {
        val fraction = (now - start) / holdSeconds
        return if (fraction >= 1.0) to else from + (to - from) * fraction.coerceAtLeast(0.0)
    }

    /** The change per second at [now]; zero once the sample is reached. */
    fun rate(now: Double) = if (now - start < holdSeconds) (to - from) / holdSeconds else 0.0

    /**
     * The value moved ahead by its change over the next [seconds], for a spring to aim at. A spring
     * of time constant tau lags a steady ramp by 2 × tau × rate. Aimed one tau ahead it trails the
     * ramp by tau × rate and moves as the ramp smoothed once does, so it comes up to the sample the
     * ramp stops at and not past it. Aimed two ahead it stays on a steady ramp, but still has the
     * ramp's full speed when the ramp stops, and runs past.
     */
    fun lead(now: Double, seconds: Double) = value(now) + rate(now) * seconds

    /**
     * Whether the value is still on its way, more than [tolerance] from the newest sample. A sample
     * that comes before the last one was reached starts the way over from where the value is. So a
     * value that is given the same sample again and again comes ever closer to it and takes many
     * seconds to arrive; with a tolerance it counts as at rest once it is close.
     */
    fun isMoving(now: Double, tolerance: Double = 0.0) = now - start < holdSeconds && abs(to - value(now)) > tolerance
}

/** Angles in degrees. */
object Angles {
    /** [a] as an angle from -180 up to, but not including, 180. */
    fun wrap180(a: Double) = ((a + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** [a] as an angle from 0 up to, but not including, 360. */
    fun wrap360(a: Double) = (a % 360.0 + 360.0) % 360.0

    /** [target] moved by whole turns to within half a turn of [reference], so the way there is the short one. */
    fun nearest(target: Double, reference: Double) = reference + wrap180(target - reference)
}

/** From 0 to 1 as [t] goes from 0 to 1, with no speed and no acceleration at either end (smootherstep). */
fun easeInOut(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/** The point at fraction [f] of the way from [a] to [b]. */
fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

/**
 * How far the view has moved from the road onto the next maneuver, as a smooth function of time:
 * 0 on the road, 1 over the junction. It is fed once a second from the route progress and read on
 * every frame. The tilt is lerp(cruisePitch, maneuverPitch, focus), and the Large lane zoom goes
 * close to the junction along the same curve ([ZoomPlanner]).
 *
 * It keeps the navigation SDK's rule for which maneuvers count and where the view is down, and
 * replaces the SDK's one-step change of tilt with timed, eased moves:
 * - Down over [CameraTuning.descentSeconds], starting at maneuverFramingMeters + speed ×
 *   descentSeconds, so the view is down as the SDK starts to frame the maneuver. Once started for a
 *   step it stays: braking never tilts the view back up before the turn.
 * - Held through the turn, for at least holdAfterManeuverMeters and holdAfterManeuverSeconds into
 *   the next step.
 * - Up over [CameraTuning.ascentSeconds], unless the next counted maneuver is already inside its own
 *   window: turns close together keep the view down, with no bounce.
 * - A reversal starts from where the focus is and takes the share of the time that is left to go.
 */
class ManeuverFocus(private val tuning: CameraTuning) {
    private var from = 0.0
    private var to = 0.0
    private var start = 0.0
    private var duration = 1.0
    private var stepKey: String? = null
    private var latchedStep: String? = null
    private var holdStep: String? = null
    private var holdUntil = 0.0

    /** The focus being moved to: 0 or 1. */
    var goal = 0.0
        private set

    fun focusAt(now: Double) = lerp(from, to, easeInOut((now - start) / duration))

    fun pitchAt(now: Double) = lerp(tuning.cruisePitch, tuning.maneuverPitch, focusAt(now))

    fun isAnimating(now: Double) = from != to && now - start < duration

    /**
     * True on the step after a maneuver the view went down for, until the next maneuver of that
     * step takes the view: the car is past the junction, and the frame of the navigation SDK shows
     * the road beyond it. The Large lane zoom keeps the junction's zoom meanwhile and lets it go as
     * the focus comes back.
     */
    val afterManeuver: Boolean
        get() = holdStep != null && holdStep == stepKey && latchedStep != stepKey

    /**
     * @param stepKey the route, leg and step. It must include the route's id: a reroute is then a
     *   new step even when its leg and step numbers repeat.
     * @param metersToManeuver what is left of the step
     * @param metersOnStep how far the car is into the step
     * @param maneuverType the type of the maneuver that ends the step, as the route's banner names it
     * @param excludedTypes the maneuver types the view stays up for
     * @param speedMps the car's speed; unknown counts as slow
     */
    fun onProgress(
        now: Double,
        stepKey: String,
        metersToManeuver: Double,
        metersOnStep: Double,
        maneuverType: String?,
        excludedTypes: Collection<String>,
        speedMps: Double?,
    ) {
        if (stepKey != this.stepKey) {
            // The car passed a maneuver with the view down: hold it there into this step.
            if (this.stepKey != null && goal == 1.0) {
                holdStep = stepKey
                holdUntil = now + tuning.holdAfterManeuverSeconds
            }
            this.stepKey = stepKey
        }
        val speed = (speedMps ?: 0.0).coerceAtLeast(MIN_SPEED_MPS)
        val counts = maneuverType != null && maneuverType !in excludedTypes
        if (counts && metersToManeuver <= tuning.maneuverFramingMeters + speed * tuning.descentSeconds) {
            latchedStep = stepKey
        }
        val inTurn = holdStep == stepKey && (metersOnStep < tuning.holdAfterManeuverMeters || now < holdUntil)
        val newGoal = if (latchedStep == stepKey || inTurn) 1.0 else 0.0
        if (newGoal != goal) animateTo(newGoal, now)
    }

    /** Guidance ended or the route was cleared: back to the road view from where the focus is. */
    fun reset(now: Double) {
        stepKey = null
        latchedStep = null
        holdStep = null
        if (goal != 0.0) animateTo(0.0, now)
    }

    private fun animateTo(newGoal: Double, now: Double) {
        val current = focusAt(now)
        val full = if (newGoal > current) tuning.descentSeconds else tuning.ascentSeconds
        from = current
        to = newGoal
        start = now
        duration = (full * abs(newGoal - current)).coerceAtLeast(MIN_MOVE_SECONDS)
        goal = newGoal
    }

    private companion object {
        /** A car that is slower than this, or stopped, still gets a window that opens before the maneuver. */
        const val MIN_SPEED_MPS = 3.0

        /** The shortest move, so a reversal right after a start is not a jerk. */
        const val MIN_MOVE_SECONDS = 0.3
    }
}
