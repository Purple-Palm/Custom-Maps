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
    /**
     * How far before a maneuver the navigation SDK starts to frame the maneuver instead of the road,
     * as it does in the two Mapbox styles. Smooth follow frames a junction no later than this.
     */
    val maneuverFramingMeters: Double = 180.0,
    /**
     * Smooth follow: a plain junction ([FocusKind.TURN]) is flat and framed this long before it, at the
     * car's speed, within the two distances ([FocusWindow]): 0.15 mi in town, 0.2 mi at speed.
     */
    val turnFramingSeconds: Double = 12.0,
    val turnFramingMinMeters: Double = 240.0,
    val turnFramingMaxMeters: Double = 320.0,
    /** The same for a junction that takes longer to read ([FocusKind.COMPLEX]): about 3 s more. */
    val complexFramingSeconds: Double = 15.0,
    val complexFramingMinMeters: Double = 280.0,
    val complexFramingMaxMeters: Double = 400.0,
    /** The tilt goes down over this long and is down when the car reaches the framing distance ([FocusWindow]). */
    val descentSeconds: Double = 3.5,
    val ascentSeconds: Double = 3.0,
    /** After the maneuver the view stays down for at least this far and this long. */
    val holdAfterManeuverMeters: Double = 25.0,
    val holdAfterManeuverSeconds: Double = 2.0,
    /**
     * The bridge between close maneuvers: as the hold after one ends, a next maneuver no farther than
     * its own window plus this much driving keeps the view down. The time the view would take to go
     * up and come back down ([ascentSeconds] + [descentSeconds]).
     */
    val bridgeSeconds: Double = 6.5,
    /**
     * While the view stays down past the hold for a next maneuver that is not framed yet, the Large
     * lane zoom lets the last junction's close zoom go over this long, on the curve the tilt would
     * have risen on ([ascentSeconds]), so the next junction closes in from its own frame.
     */
    val keptZoomReleaseSeconds: Double = 3.0,
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
    /** Large near a lane choice on a freeway: a lane at the car is this wide on the screen, about 2.2 mm. */
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
     * The run-up to a freeway exit or fork, over which the close view stays in once ([RunUpLatch]),
     * begins no farther from the exit than the close view's window plus this much driving, and
     * [runUpMinMeters] at the least: the road does not get shorter when the car is slower.
     */
    val runUpSeconds: Double = 60.0,
    val runUpMinMeters: Double = 1_500.0,
    /**
     * A run-up that ended less than this long ago begins again on its reach alone: after a new
     * route, a moment off the route, a seek, or at the gore of a run-up right before. The close
     * view's own hold of [laneHoldSeconds] plus one update.
     */
    val runUpResumeSeconds: Double = 3.0,
    /**
     * After the gore of a run-up's exit the close view holds through the ramp to the next point the
     * lane plan constrains there, a fork or a split with lane data or a ramp step's maneuver the plan
     * names a lane for, when that point lies within this much driving at the ramp's planning speed
     * ([RunUpLatch]). Elsewhere the view is let go past the gore after its hold of [laneHoldSeconds]
     * and [laneHoldMeters], as before.
     */
    val rampBridgeSeconds: Double = 15.0,
    /**
     * With Large, Smooth follow tilts the close view for a lane choice to this, along the same
     * curve as the zoom ([guidancePitch]): the lanes at the car keep [freewayLaneDp], and more of
     * the road ahead shows above them.
     */
    val lanePitch: Double = 55.0,
    /**
     * The close view for a lane choice goes no closer than the zoom that still shows this much
     * driving of road above the car ([LookAhead]), at the highest speed of the last
     * [laneCapSpeedSeconds] ([PeakSpeed]).
     */
    val laneLookAheadSeconds: Double = 12.0,
    val laneCapSpeedSeconds: Double = 10.0,
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
    /**
     * Whether Smooth follow works out its zoom itself, anchored on the car: the navigation SDK never frames a maneuver,
     * so the car stays on its row at the bottom of the following box, and the zoom is the road view by speed
     * ([RoadView]) with either lane zoom; Large adds a junction's own fit and the lanes' close view on top. False on the
     * phone, whose road and junctions the SDK frames, as they always were.
     */
    val anchored: Boolean = false,
    /** Anchored: the road view shows this much driving between the car and the top of the following box ([RoadView]). */
    val roadAheadSeconds: Double = 12.0,
    /**
     * Anchored: the road view is worked out for the highest speed of the last this long ([PeakSpeed]): it goes out at
     * once when the car speeds up, and back in only after the car has stayed slower this long.
     */
    val roadPeakSeconds: Double = 10.0,
    /** Anchored: the road view shows no less road than this, at a stop or a crawl, and no more than [roadMaxAheadMeters]. */
    val roadMinAheadMeters: Double = 150.0,
    val roadMaxAheadMeters: Double = 600.0,
) {
    /**
     * The numbers the two Mapbox camera styles take on this screen. They are the navigation SDK's camera as it comes,
     * the car's too: what the car's own camera changes ([anchored], its tilts and its lanes' look-ahead) stays the
     * phone's for them, so they move on the car as they did before it had a camera of its own.
     */
    fun forMapboxStyles(): CameraTuning =
        if (!anchored) {
            this
        } else {
            copy(
                anchored = false,
                cruisePitch = PHONE.cruisePitch,
                maneuverPitch = PHONE.maneuverPitch,
                lanePitch = PHONE.lanePitch,
                laneLookAheadSeconds = PHONE.laneLookAheadSeconds,
            )
        }

    /**
     * The tilt over a junction shown as [view]: on a camera anchored on the car, from above, straight down as on the
     * phone ([PHONE]'s [maneuverPitch]); otherwise this tuning's own [maneuverPitch], the phone's and the car's tilted one.
     */
    fun junctionPitch(view: JunctionView): Double = if (anchored && view == JunctionView.ABOVE) PHONE.maneuverPitch else maneuverPitch

    companion object {
        val PHONE = CameraTuning()

        /** The car's own camera, for its wide, low screen seen from farther away than a phone's. */
        val CAR = CameraTuning(
            // A calmer center for the larger screen that is farther from the driver.
            centerTau = 0.25,
            // The car's view is the camera's own: the SDK's fit of the road frames about a kilometre on this screen.
            anchored = true,
            // Closer and tilted, as the owner asked: on the car's wide, low screen a flat view shows about 70 m ahead.
            cruisePitch = 55.0,
            // The same over junctions, so the view only comes closer there and never tilts back and forth.
            maneuverPitch = 55.0,
            // And at lane choices: one tilt for every view.
            lanePitch = 55.0,
            // The car's short box shows 8 s at a lane's zoom; 12 s would leave the lanes' view at the road's zoom.
            laneLookAheadSeconds = 8.0,
            // The road view: 12 s ahead, as a plain junction's frame begins 12 s before it.
            roadAheadSeconds = 12.0,
            // Out at once with speed, in only after 10 s slower, as the lanes' look-ahead floor.
            roadPeakSeconds = 10.0,
            // At a stop or a crawl still the block ahead.
            roadMinAheadMeters = 150.0,
            // Never farther out than about the car's view before it had a camera of its own (some 650 m).
            roadMaxAheadMeters = 600.0,
        )
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
 * The tilt of the camera that follows the car in Smooth follow: [CameraTuning.cruisePitch] on the
 * road, up to [CameraTuning.lanePitch] along the lane focus, and down to [junctionPitch] over a
 * junction, which wins over the lanes. With a lane share of 0 and the tuning's own
 * [CameraTuning.maneuverPitch] it is [ManeuverFocus.pitchAt].
 *
 * @param laneShare [LaneFocus.focusAt] with the Large lane zoom, 0 otherwise
 * @param junctionFocus [ManeuverFocus.focusAt]
 * @param junctionPitch the tilt over the junction, [CameraTuning.junctionPitch] of the camera's junction view
 */
fun guidancePitch(tuning: CameraTuning, laneShare: Double, junctionFocus: Double, junctionPitch: Double = tuning.maneuverPitch): Double =
    lerp(lerp(tuning.cruisePitch, tuning.lanePitch, laneShare), junctionPitch, junctionFocus)

/**
 * How far the view has moved from the road onto the next maneuver, as a smooth function of time:
 * 0 on the road, 1 over the junction. It is fed once a second from the route progress and read on
 * every frame. The tilt is lerp(cruisePitch, maneuverPitch, focus), and the Large lane zoom goes
 * close to the junction along the same curve ([ZoomPlanner]).
 *
 * It decides, once for each step, whether and from where the view looks down onto the junction at
 * its end, and when the navigation SDK frames that junction ([framesManeuver]); the map hands the
 * SDK that decision, so the tilt and the frame come from one latched rule. In timed, eased moves:
 * - Which junctions count, and how far before them, comes from the step's own maneuver
 *   ([ManeuverFacts], [FocusRule]): none for a step that goes straight on, a merge or a split on a
 *   freeway; a plain junction's distance for a turn; a few seconds more for one that takes longer to
 *   read ([FocusWindow]). Past a step the view stays up for, it goes down for the junction after it
 *   once that comes within its window ([Latch.AHEAD]), so the view is down by the junction's step.
 * - Down over [CameraTuning.descentSeconds], starting at the window ([FocusWindow.windowMeters]), so
 *   the view is flat at the framing distance ([framingMeters]), which is fixed when the view latches.
 *   Once started for a step it stays: braking never tilts the view back up before the turn.
 * - Framed from the first update at the framing distance until the step changes: a car standing at
 *   that distance, its position wobbling by a meter, keeps its frame.
 * - Held through the turn, for at least holdAfterManeuverMeters and holdAfterManeuverSeconds into
 *   the next step.
 * - Up over [CameraTuning.ascentSeconds], unless the next counted maneuver is already inside its own
 *   window, or no farther than its window plus [CameraTuning.bridgeSeconds] of driving as the hold
 *   ends (the bridge): turns close together keep the view down, with no rise and fall in between.
 *   Where the view stays down so for a maneuver that is not framed yet, [keepAt] lets the last
 *   junction's close zoom go over [CameraTuning.keptZoomReleaseSeconds].
 * - A reversal starts from where the focus is and takes the share of the time that is left to go.
 */
class ManeuverFocus(private val tuning: CameraTuning) {
    private var from = 0.0
    private var to = 0.0
    private var start = 0.0
    private var duration = 1.0
    private var stepKey: String? = null

    /** The step whose junction the view is latched down for: the one the car is on, or the next one, latched ahead. */
    private var latchedStep: String? = null

    /** The step a latch ahead was made on, past whose end its junction lies; null for a latch of the step itself. */
    private var aheadFrom: String? = null
    private var holdStep: String? = null
    private var holdUntil = 0.0

    /** Whether the hold after the last maneuver has ended on this step, so the bridge was decided. */
    private var holdOver = false

    /** When the view stayed down past the hold for a next maneuver not framed yet; not a number otherwise. */
    private var keptDownSince = Double.NaN

    /** Whether the car was in the hold after the last maneuver at the last update. */
    private var inHold = false

    /** The step whose junction is framed, from its first update at the framing distance to the step's end. */
    private var framedStep: String? = null

    /** The focus being moved to: 0 or 1. */
    var goal = 0.0
        private set

    /** How the view latched down for the junction of this step, for the log; null while it has not. */
    var latchedBy: Latch? = null
        private set

    /** How many times the view has latched down for a junction, so the camera can tell each latch once. */
    var latches = 0
        private set

    /**
     * How far before the junction of this step its frame begins, in meters: fixed when the view
     * latched down for it; until then as the window puts it at the last update. For a junction past a
     * step the view stays up for, it is measured from that junction. Not a number on a step that has
     * no junction the view goes down for.
     */
    var framingMeters = Double.NaN
        private set

    /** Whether the navigation SDK is to frame the junction now: from the framing distance on, to the end of the step. */
    val framesManeuver: Boolean
        get() = framedStep != null && framedStep == stepKey

    /**
     * The step at whose end lies the junction the view is latched down for from this step: this step itself, or past a
     * step the view stays up for, the next one; null while the view is not latched down for a junction from this step. A
     * camera that frames its junctions itself ([framesItself]) reads the junction's road by it.
     */
    val latchedJunction: String?
        get() = latchedStep.takeIf { latchedHere() }

    /**
     * Whether a camera anchored on the car ([CameraTuning.anchored]), which the navigation SDK never frames a maneuver
     * for, frames the junction the view is latched down for itself now: from the latch on, so its zoom comes in along the
     * focus with the view. Not while the last junction still holds the view, in the hold after it and while its close
     * zoom is let go past the hold for this one ([keepAt]), until this one's frame begins ([framesManeuver]): one
     * junction's zoom at a time, as the navigation SDK frames one junction at a time on the phone.
     */
    fun framesItself(now: Double): Boolean =
        latchedHere() && (framesManeuver || (!inHold && !(now - keptDownSince < tuning.keptZoomReleaseSeconds)))

    fun focusAt(now: Double) = lerp(from, to, easeInOut((now - start) / duration))

    /**
     * How much of the last junction's close zoom the Large lane zoom keeps ([ZoomPlanner.target]'s
     * junction keep): the focus, so the zoom is let go as the tilt comes back up; but where the view
     * stays down past the hold for a next maneuver that is not framed yet, from 1 at the hold's end
     * down to 0 over [CameraTuning.keptZoomReleaseSeconds], on the same eased curve. The view then
     * drives on at the road's zoom, and the next junction's frame closes in from where it begins.
     */
    fun keepAt(now: Double): Double =
        if (keptDownSince.isNaN()) focusAt(now) else 1.0 - easeInOut((now - keptDownSince) / tuning.keptZoomReleaseSeconds)

    fun pitchAt(now: Double) = lerp(tuning.cruisePitch, tuning.maneuverPitch, focusAt(now))

    fun isAnimating(now: Double) = from != to && now - start < duration

    /**
     * Whether a junction view is on at [now]: latched or held through the turn (goal 1), or on its way back up. A change of
     * how the view shows junctions ([JunctionView]) moves the camera at once only then; otherwise at the next junction.
     */
    fun inView(now: Double): Boolean = goal > 0.0 || focusAt(now) > 0.0

    /**
     * True on the step after a maneuver the view went down for, until the next maneuver of that
     * step, or the junction past it, takes the view: the car is past the junction, and the frame of
     * the navigation SDK shows the road beyond it. The Large lane zoom keeps the junction's zoom
     * meanwhile and lets it go as the focus comes back.
     */
    val afterManeuver: Boolean
        get() = holdStep != null && holdStep == stepKey && !latchedHere()

    /**
     * @param stepKey the route, leg and step. It must include the route's id: a reroute is then a
     *   new step even when its leg and step numbers repeat.
     * @param metersToManeuver what is left of the step
     * @param metersOnStep how far the car is into the step
     * @param facts the maneuver at the end of the step, from the step's own maneuver
     * @param capMeters the longest approach the junction's frame fits ([FocusWindow.capMeters]); not a
     *   number for a map not laid out yet, which frames at the navigation SDK's own distance
     * @param speedMps the car's speed; unknown counts as slow
     */
    fun onProgress(
        now: Double,
        stepKey: String,
        metersToManeuver: Double,
        metersOnStep: Double,
        facts: ManeuverFacts,
        capMeters: Double,
        speedMps: Double?,
    ) {
        if (stepKey != this.stepKey) {
            // The car passed a maneuver with the view down: hold it there into this step.
            if (this.stepKey != null && goal == 1.0) {
                holdStep = stepKey
                holdUntil = now + tuning.holdAfterManeuverSeconds
            }
            this.stepKey = stepKey
            holdOver = false
            keptDownSince = Double.NaN
            // A junction latched ahead from the step before keeps its latch and its framing distance.
            if (latchedStep != stepKey) {
                latchedBy = null
                framingMeters = Double.NaN
            }
        }
        val speed = speedMps ?: 0.0
        val inTurn = holdStep == stepKey && (metersOnStep < tuning.holdAfterManeuverMeters || now < holdUntil)
        inHold = inTurn
        // The first update past the hold after the last maneuver.
        val holdEnds = holdStep == stepKey && !inTurn && !holdOver
        if (holdEnds) holdOver = true
        // The junction the view goes down for from this step: its own maneuver, or past a step the
        // view stays up for, the maneuver after it.
        val own = facts.kind != FocusKind.NONE
        val junction = if (own) facts else facts.next?.takeIf { it.kind != FocusKind.NONE }
        val junctionKey = if (own) stepKey else facts.nextKey
        val metersToJunction = if (own) metersToManeuver else metersToManeuver + facts.nextMeters
        if (junction != null && junctionKey != null && latchedStep != junctionKey) {
            val framing = FocusWindow.framingMeters(junction.kind, speed, capMeters, tuning)
            val window = FocusWindow.windowMeters(junction.kind, speed, capMeters, tuning)
            framingMeters = framing
            val by = when {
                metersToJunction <= window -> if (own) Latch.WINDOW else Latch.AHEAD
                // The bridge: going up now, the view would have to come straight back down.
                holdEnds && goal == 1.0 && metersToJunction <= window + FocusWindow.bridgeMeters(speed, tuning) -> Latch.BRIDGE
                else -> null
            }
            if (by != null) {
                latchedStep = junctionKey
                aheadFrom = if (own) null else stepKey
                latchedBy = by
                latches += 1
            }
        } else if (junction == null && !latchedHere()) {
            framingMeters = Double.NaN
        }
        // Once the junction is framed, it stays framed to the end of the step. Past a step the view
        // stays up for, the SDK frames the junction with this step's end where the step between is short.
        if (framedStep != stepKey && latchedHere()) {
            val framed = if (latchedStep == stepKey) {
                metersToManeuver <= framingMeters
            } else {
                facts.nextMeters <= FocusWindow.COALESCE_METERS && metersToJunction <= framingMeters
            }
            if (framed) framedStep = stepKey
        }
        // The view stays down past the hold for a junction the SDK does not frame yet: the last
        // junction's close zoom is let go from here.
        if (holdEnds && goal == 1.0 && latchedHere() && !framesManeuver) keptDownSince = now
        val newGoal = if (latchedHere() || inTurn) 1.0 else 0.0
        if (newGoal != goal) animateTo(newGoal, now)
    }

    /**
     * Puts the focus at its goal at once, with no move of its own: a move of the camera that runs now
     * takes the tilt with it, so the tilt has one motion only.
     */
    fun settle(now: Double) {
        from = goal
        to = goal
        start = now
    }

    /** Guidance ended or the route was cleared: back to the road view from where the focus is. */
    fun reset(now: Double) {
        stepKey = null
        latchedStep = null
        aheadFrom = null
        holdStep = null
        holdOver = false
        keptDownSince = Double.NaN
        inHold = false
        framedStep = null
        latchedBy = null
        framingMeters = Double.NaN
        if (goal != 0.0) animateTo(0.0, now)
    }

    /** Whether the view is latched down for this step: for its own junction, or for the one after it. */
    private fun latchedHere(): Boolean = latchedStep != null && (latchedStep == stepKey || aheadFrom == stepKey)

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
        /** The shortest move, so a reversal right after a start is not a jerk. */
        const val MIN_MOVE_SECONDS = 0.3
    }

    /** How a step latched the view down, by its name in the log. */
    enum class Latch(val logName: String) {
        /** The maneuver came within its window. */
        WINDOW("window"),

        /** As the hold after the last maneuver ended, the maneuver lay within its window plus the bridge. */
        BRIDGE("bridge"),

        /** Past a step the view stays up for, the junction after it came within its window. */
        AHEAD("ahead"),
    }
}

