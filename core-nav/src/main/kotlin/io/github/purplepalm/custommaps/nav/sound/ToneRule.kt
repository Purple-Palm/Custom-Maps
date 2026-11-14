package io.github.purplepalm.custommaps.nav.sound

import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.max

/**
 * A maneuver of the leg after the next one, for the tones' look past a maneuver that makes no sound ([ToneMoment.of]):
 * the maneuver with its distance and time after the next one, summed along the way as [RouteProgressSnapshot.upcoming]
 * has them, and the key of the step that ends with it, as the snapshot keys steps: the key the snapshot has once the car
 * is on that step.
 */
data class ManeuverAhead(val stepKey: String, val following: FollowingManeuver)

/**
 * One navigator update, as the tone rule needs it: where the drive is, how fast the car goes, and the
 * lane row the card shows ahead of the maneuver. Numbers and ids only.
 */
data class ToneMoment(
    /** The route's id: a new one is a new route. */
    val routeKey: String,
    /** Route, leg and step, as the progress snapshot keys them: the step that ends with [maneuver]. */
    val stepKey: String,
    /** The maneuver the tones are for: the next one, or the first after it that sounds where the next one is passed over. */
    val maneuver: Maneuver,
    val metersToManeuver: Double,
    /** At the route's speeds; 0 when unknown. */
    val secondsToManeuver: Double,
    /** The car's matched speed; null when unknown. */
    val speedMps: Double?,
    val offRoute: Boolean,
    /**
     * The key of the point whose lanes the card shows ahead of the maneuver: a lane choice the route goes
     * straight through, or on a run-up the point whose row the run-up shows; null while the card shows the
     * maneuver's own row, and on a pending card.
     */
    val laneChoiceKey: String? = null,
    /** How far ahead that point is, in metres; not a number without one. */
    val laneChoiceMeters: Double = Double.NaN,
    /** The row shown is the maneuver's own, an exit's or a fork's, on its run-up. */
    val laneRowIsManeuver: Boolean = false,
    /** The run-up on now began at the exit's own window, the close view's start for the exit itself. */
    val runUpFromExitWindow: Boolean = false,
    /** The card waits for the new route's first instruction: the route's step gave its maneuver. */
    val pending: Boolean = false,
    /**
     * The kind of the next maneuver where the tones passed over it ([ToneRule.passes]) and this moment is about the first
     * one after it that sounds; null where it is about the next maneuver itself. For the log.
     */
    val lookedPast: ManeuverType? = null,
) {
    companion object {
        /** The lane choices' key of a step's own maneuver point: the step's key with this after it. */
        const val MANEUVER_POINT_KEY = "/end"

        /**
         * The moment of [progress], the update the card shows, on the route [routeKey], with the car's speed
         * the snapshot carries ([RouteProgressSnapshot.speedMps], the navigator's latest matched one).
         * [laneChoiceKey] is the point whose lanes the card shows ahead of the maneuver and [laneChoiceMeters]
         * how far ahead it is; [runUpFromExitWindow] whether the run-up on now began at the exit's own window.
         *
         * Where the next maneuver is one the tones pass over ([ToneRule.passes]: straight on under a new name, a
         * merge, a roundabout's way out), the moment is about the first maneuver of [ahead], the leg's maneuvers
         * after the next one, that sounds: at its own distance and time, the next one's and its own after it, and
         * with the key of its own step, so its tones come at their moments across the silent one, and nothing
         * changes for the rule when the car reaches that step. The look stops at a maneuver of an unknown kind,
         * which may be one to act at. The lane row stays the card's: a row's key holds its own step's key, so the
         * row of the passed maneuver itself is a row ahead of the one that sounds.
         */
        fun of(
            progress: RouteProgressSnapshot,
            routeKey: String,
            laneChoiceKey: String?,
            laneChoiceMeters: Double? = null,
            runUpFromExitWindow: Boolean = false,
            ahead: List<ManeuverAhead> = emptyList(),
        ): ToneMoment {
            val meters = progress.distanceToNextManeuver.meters
            val seconds = progress.durationToNextManeuver.inWholeMilliseconds / MILLIS_PER_SECOND
            val past = soundingAfter(progress.nextManeuver, ahead)
            val stepKey = past?.stepKey ?: progress.stepKey
            val toManeuver = if (past == null) meters else meters + past.following.distanceAfterNext.meters
            val after = past?.following?.durationAfterNext
            val toManeuverSeconds = when {
                after == null -> seconds
                // A time not known on the way makes the whole time unknown.
                seconds > 0.0 && after.isPositive() -> seconds + after.inWholeMilliseconds / MILLIS_PER_SECOND
                else -> 0.0
            }
            // A pending card shows no lane row: a key from before is no row of the new route's.
            val key = laneChoiceKey.takeIf { !progress.pending }
            // The row of the maneuver the tones are for; the row of one passed over is a row ahead of it.
            val ownRow = key != null && key == stepKey + MANEUVER_POINT_KEY
            return ToneMoment(
                routeKey = routeKey,
                stepKey = stepKey,
                maneuver = past?.following?.maneuver ?: progress.nextManeuver,
                metersToManeuver = toManeuver,
                secondsToManeuver = toManeuverSeconds,
                speedMps = progress.speedMps,
                offRoute = progress.offRoute,
                laneChoiceKey = key,
                laneChoiceMeters = when {
                    key == null -> Double.NaN
                    ownRow -> toManeuver
                    laneChoiceMeters != null -> laneChoiceMeters
                    else -> progress.laneChoiceAhead?.distance?.meters ?: meters
                },
                laneRowIsManeuver = ownRow,
                runUpFromExitWindow = ownRow && runUpFromExitWindow,
                pending = progress.pending,
                lookedPast = if (past == null) null else progress.nextManeuver.type,
            )
        }

        /**
         * The first maneuver of [ahead] that sounds, where [next] and every one before it are passed over; null where [next]
         * is not passed over, and where the look stops before one that sounds.
         */
        private fun soundingAfter(next: Maneuver, ahead: List<ManeuverAhead>): ManeuverAhead? {
            if (!ToneRule.passes(next)) return null
            for (i in ahead.indices) {
                val maneuver = ahead[i].following.maneuver
                if (ToneRule.sounds(maneuver)) return ahead[i]
                if (!ToneRule.passes(maneuver)) return null
            }
            return null
        }

        private const val MILLIS_PER_SECOND = 1_000.0
    }
}

/** Which tones may sound: the switches Before a turn and Two tones at the turn. [NONE] runs the rule in silence. */
data class ToneEvents(val beforeTurn: Boolean = true, val atTurn: Boolean = true) {
    companion object {
        val NONE = ToneEvents(beforeTurn = false, atTurn = false)
    }
}

/** Every number of the rule. Each is a first setting: untuned on a road but for the pair's floor. */
data class ToneTuning(
    /** The one tone, this long before the maneuver at the car's speed. */
    val aheadSeconds: Double = 25.0,
    /** But never nearer: in slow traffic a lane change needs road, not seconds. */
    val aheadMinMeters: Double = 300.0,
    /** A guard against a wrong speed, not a driving rule. */
    val aheadMaxMeters: Double = 1_000.0,
    /** The two tones, this long before the maneuver. */
    val finalSeconds: Double = 7.0,
    /**
     * But never nearer, for a car that creeps or stands in a queue. 30 m, not the design's 50 m: in slow
     * traffic and queues a 50 m floor sounds the pair too early.
     */
    val finalMinMeters: Double = 30.0,
    /** A guard against a wrong speed, not a driving rule. */
    val finalMaxMeters: Double = 300.0,
    /** A single tone needs this much driving before the pair, a glance and a lane change, or it is left out. */
    val roomSeconds: Double = 10.0,
    /** And this much road, for a car that crawls. */
    val roomMinMeters: Double = 100.0,
    /** After any tone, single tones wait this long; the pair never waits. */
    val quietMillis: Long = 15_000,
)

/** Why a tone sounds. */
enum class ToneReason { AHEAD, LANE_CHOICE, AT_TURN, ARRIVED }

/** Why a tone that was due is left out. */
enum class ToneSkip {
    /** Too little room before the pair, or before the lane choice. */
    TOO_CLOSE,

    /** The route arrived with its maneuver, or a lane row, already inside the tone's distance: no warning left in it. */
    NEW_ROUTE,

    /** A run-up that began at the exit's own window with the exit's own row: the exit's one tone is that moment. */
    EXIT_ROW,
}

/** A tone that was due and is left out, for the log: why, and which tone. */
data class ToneSkipped(val skip: ToneSkip, val reason: ToneReason)

/** A tone to sound now, with the two distances it was decided by, for the log. */
data class ToneCue(
    val kind: ToneKind,
    val reason: ToneReason,
    val aheadMeters: Double,
    val finalMeters: Double,
    /** To the lane choice, when that is the reason; not a number otherwise. */
    val laneMeters: Double = Double.NaN,
)

/**
 * Decides which tone sounds at a navigator update, the one rule for the phone and the car. One call per
 * update; pure, so the same updates give the same tones. Within a step each tone sounds at most once.
 *
 * - One tone [ToneTuning.aheadSeconds] before a maneuver that [sounds], at the car's speed, within its
 *   floor and cap; two tones [ToneTuning.finalSeconds] before it. Faster means earlier on the road.
 * - The one tone needs [ToneTuning.roomSeconds] and [ToneTuning.roomMinMeters] before the pair, or it is
 *   left out: a turn close after another gets only its pair. Single tones wait [ToneTuning.quietMillis]
 *   after any tone, while the room lasts; the pair never waits.
 * - A lane row the card starts to show ahead of the maneuver gets one tone, by the same room and quiet:
 *   a straight-on lane choice, a run-up as it begins and each change of its row. A run-up that began at the
 *   exit's own window with the exit's own row gets none: the exit's one tone is that moment.
 * - A maneuver that asks nothing of the driver ([passes]) is passed over: the moment is about the first one after
 *   it that sounds ([ToneMoment.of]), whose tones keep their distances across it.
 * - Off the route nothing sounds, and nothing changes.
 * - A new route (a reroute, a route switch, the drive's start or a new start after a seek) sounds nothing at
 *   its first update for what is already inside a tone's distance: neither the one tone nor the pair, nor a
 *   lane row it shows as it comes. Such a tone would sound at once, with no warning in it. Until the route's
 *   first card with an instruction a step change is part of that start: the pending card warned of nothing.
 */
object ToneRule {

    /** What the rule remembers from one update to the next. */
    data class State(
        val routeKey: String?,
        val stepKey: String?,
        val aheadDone: Boolean,
        val finalDone: Boolean,
        /** The lane rows of this step that had their tone, or were left out. */
        val laneKeysDone: Set<String>,
        val lastToneMillis: Long?,
        /** Whether the step's maneuver sounds, for the arrival. */
        val stepSounds: Boolean,
        /** The route's start is not over: its first card with an instruction has not come yet. */
        val routeStarting: Boolean,
    ) {
        companion object {
            /** Before a drive's first update, and after a drive ended or a seek put the car somewhere else. */
            val NONE = State(
                routeKey = null,
                stepKey = null,
                aheadDone = false,
                finalDone = false,
                laneKeysDone = emptySet(),
                lastToneMillis = null,
                stepSounds = false,
                routeStarting = false,
            )
        }
    }

    /** The state after one update, the tone to sound now if any, and the tones left out at it. */
    data class Step(val state: State, val cue: ToneCue? = null, val skipped: List<ToneSkipped> = emptyList())

    private val DEFAULT = ToneTuning()

    fun reduce(state: State, moment: ToneMoment, nowMillis: Long, events: ToneEvents, tuning: ToneTuning = DEFAULT): Step {
        // Off the route the maneuvers may be wrong: nothing sounds, and what was done stays done.
        if (moment.offRoute) return Step(state)
        val speed = speedOf(moment)
        val ahead = aheadMeters(speed, tuning)
        val final = finalMeters(speed, tuning)
        val sounds = sounds(moment.maneuver)
        val skipped = ArrayList<ToneSkipped>(0)
        var s = state
        if (moment.stepKey != s.stepKey) {
            // A step reached before the route's first card with an instruction is part of the route's start.
            val newRoute = moment.routeKey != s.routeKey || s.routeStarting
            // A new route's maneuver already inside a tone's distance gets that tone no more: it would sound at once.
            val insideAhead = newRoute && moment.metersToManeuver <= ahead
            val insideFinal = newRoute && moment.metersToManeuver <= final
            if (sounds && insideFinal) {
                skipped += ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.AT_TURN)
            } else if (sounds && insideAhead) {
                skipped += ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.AHEAD)
            }
            s = s.copy(
                routeKey = moment.routeKey,
                stepKey = moment.stepKey,
                aheadDone = insideAhead || insideFinal,
                finalDone = insideFinal,
                laneKeysDone = emptySet(),
                routeStarting = if (newRoute) true else s.routeStarting,
            )
        }
        s = s.copy(stepSounds = sounds)
        val laneKey = moment.laneChoiceKey
        if (s.routeStarting) {
            // A lane row the new route shows as it comes is part of what changed under the driver's eyes: no tone.
            if (laneKey != null && laneKey !in s.laneKeysDone) {
                s = s.copy(laneKeysDone = s.laneKeysDone + laneKey)
                skipped += ToneSkipped(ToneSkip.NEW_ROUTE, ToneReason.LANE_CHOICE)
            }
            // The pending card shows no lane row: the route's start ends with its first card with an instruction.
            if (!moment.pending) s = s.copy(routeStarting = false)
        }

        if (sounds && !s.finalDone && moment.metersToManeuver <= final) {
            s = s.copy(finalDone = true, aheadDone = true, laneKeysDone = s.laneKeysDone.withKey(laneKey))
            if (!events.atTurn) return Step(s, skipped = skipped)
            return Step(s.copy(lastToneMillis = nowMillis), ToneCue(ToneKind.AT_TURN, ToneReason.AT_TURN, ahead, final), skipped)
        }
        val quiet = inQuiet(s, nowMillis, tuning)
        val room = roomMeters(speed, tuning)
        if (sounds && !s.aheadDone && moment.metersToManeuver <= ahead) {
            when {
                moment.metersToManeuver - final < room -> {
                    s = s.copy(aheadDone = true)
                    skipped += ToneSkipped(ToneSkip.TOO_CLOSE, ToneReason.AHEAD)
                }
                !events.beforeTurn -> s = s.copy(aheadDone = true)
                // A lane row shown at this moment has its tone in this one.
                !quiet -> return Step(
                    s.copy(aheadDone = true, laneKeysDone = s.laneKeysDone.withKey(laneKey), lastToneMillis = nowMillis),
                    ToneCue(ToneKind.AHEAD, ToneReason.AHEAD, ahead, final),
                    skipped,
                )
                // Else it waits for the quiet to end, and is asked again at the next update.
            }
        }
        if (laneKey != null && laneKey !in s.laneKeysDone) {
            // The exit's own row: the room it leaves is the room before the exit's pair.
            val laneRoom = if (moment.laneRowIsManeuver) moment.metersToManeuver - final else moment.laneChoiceMeters
            when {
                moment.laneRowIsManeuver && moment.runUpFromExitWindow -> {
                    s = s.copy(laneKeysDone = s.laneKeysDone + laneKey)
                    skipped += ToneSkipped(ToneSkip.EXIT_ROW, ToneReason.LANE_CHOICE)
                }
                // A distance that is not a number is never too close: the card shows the row.
                laneRoom < room -> {
                    s = s.copy(laneKeysDone = s.laneKeysDone + laneKey)
                    skipped += ToneSkipped(ToneSkip.TOO_CLOSE, ToneReason.LANE_CHOICE)
                }
                !events.beforeTurn -> s = s.copy(laneKeysDone = s.laneKeysDone + laneKey)
                !quiet -> return Step(
                    s.copy(laneKeysDone = s.laneKeysDone + laneKey, lastToneMillis = nowMillis),
                    ToneCue(ToneKind.AHEAD, ToneReason.LANE_CHOICE, ahead, final, laneMeters = moment.laneChoiceMeters),
                    skipped,
                )
            }
        }
        return Step(s, skipped = skipped)
    }

    /**
     * The trip reached its end: the pair, if the last step's maneuver sounds and has not had it. The
     * navigator declares the arrival by a rule of its own, so the pair sounds by its distance or here,
     * whichever comes first, and once.
     */
    fun arrived(state: State, nowMillis: Long, events: ToneEvents): Step {
        if (state.stepKey == null || state.finalDone || !state.stepSounds) return Step(state)
        val done = state.copy(finalDone = true, aheadDone = true)
        if (!events.atTurn) return Step(done)
        return Step(done.copy(lastToneMillis = nowMillis), ToneCue(ToneKind.AT_TURN, ToneReason.ARRIVED, Double.NaN, Double.NaN))
    }

    /**
     * Whether [maneuver] gets its tones: where the driver has to act. A turn, the end of a road, a ramp
     * on or off, a fork, the way into a roundabout or a rotary, the arrival, and a continue or a new name
     * round a corner. Not straight on, not the way out of a roundabout (its way in had them), not a merge
     * (the ramp ends in it), not the departure, a notification or an unknown maneuver.
     */
    fun sounds(maneuver: Maneuver): Boolean = when (maneuver.type) {
        ManeuverType.TURN, ManeuverType.END_OF_ROAD, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK -> true
        ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN -> true
        ManeuverType.ARRIVE -> true
        ManeuverType.CONTINUE, ManeuverType.NEW_NAME -> maneuver.modifier != null && maneuver.modifier != TurnModifier.STRAIGHT
        ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY -> false
        ManeuverType.MERGE -> false
        ManeuverType.DEPART, ManeuverType.NOTIFICATION, ManeuverType.UNKNOWN -> false
    }

    /**
     * Whether the tones look past [maneuver] to the maneuvers after it ([ToneMoment.of]): one that makes no sound and asks
     * nothing of the driver, as straight on under a new name, a merge or the way out of a roundabout. Not one of an
     * unknown kind, which may be a turn the route could not name.
     */
    fun passes(maneuver: Maneuver): Boolean = !sounds(maneuver) && maneuver.type != ManeuverType.UNKNOWN

    /** The car's speed; when unknown, the metres left over the seconds left of the step at the route's speeds; else standing. */
    fun speedOf(moment: ToneMoment): Double {
        val speed = moment.speedMps
        if (speed != null && speed.isFinite() && speed >= 0.0) return speed
        val meters = moment.metersToManeuver
        val seconds = moment.secondsToManeuver
        return if (meters > 0.0 && seconds > 0.0 && meters.isFinite() && seconds.isFinite()) meters / seconds else 0.0
    }

    /** Where the one tone sounds, before the maneuver. */
    fun aheadMeters(speedMps: Double, tuning: ToneTuning = DEFAULT): Double =
        (tuning.aheadSeconds * speedMps).coerceIn(tuning.aheadMinMeters, tuning.aheadMaxMeters)

    /** Where the two tones sound, before the maneuver. */
    fun finalMeters(speedMps: Double, tuning: ToneTuning = DEFAULT): Double =
        (tuning.finalSeconds * speedMps).coerceIn(tuning.finalMinMeters, tuning.finalMaxMeters)

    /** The least room a single tone needs before what it warns of. */
    fun roomMeters(speedMps: Double, tuning: ToneTuning = DEFAULT): Double = max(tuning.roomSeconds * speedMps, tuning.roomMinMeters)

    private fun inQuiet(state: State, nowMillis: Long, tuning: ToneTuning): Boolean {
        val last = state.lastToneMillis ?: return false
        return nowMillis - last < tuning.quietMillis
    }

    private fun Set<String>.withKey(key: String?): Set<String> = if (key == null || key in this) this else this + key
}
