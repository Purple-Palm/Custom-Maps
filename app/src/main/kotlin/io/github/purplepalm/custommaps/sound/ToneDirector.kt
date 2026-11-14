package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.nav.GuidanceAudio
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.sound.Chime
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.DuckPlan
import io.github.purplepalm.custommaps.nav.sound.ToneCue
import io.github.purplepalm.custommaps.nav.sound.ToneEvents
import io.github.purplepalm.custommaps.nav.sound.ToneKind
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.sound.ToneMoment
import io.github.purplepalm.custommaps.nav.sound.TonePacing
import io.github.purplepalm.custommaps.nav.sound.ToneReason
import io.github.purplepalm.custommaps.nav.sound.ToneRule
import io.github.purplepalm.custommaps.nav.sound.ToneSkip
import io.github.purplepalm.custommaps.nav.sound.ToneSkipped
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.settings.SoundSettings
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What plays a sound: the app's player ([TonePlayer]), a fake in tests. Returns at once; [onResult] comes later, on any thread. */
fun interface ToneSink {
    fun play(kind: ToneKind, level: ToneLevel, duck: DuckMode, wakeLeadInMillis: Int, car: Boolean, onResult: (ToneResult) -> Unit)
}

/** What became of a sound: heard, or why not. */
enum class ToneResult(val logName: String) {
    PLAYED("played"),

    /** Android refused the sound for the moment: another app holds it, or a call comes in. */
    NO_FOCUS("no_focus"),

    /** A call is on, or the phone rings: no tone, which would sound in the call. */
    IN_CALL("in_call"),

    /** The media volume is at zero. */
    VOLUME_ZERO("volume_zero"),

    FAILED("failed"),
}

/**
 * The one place that decides to make a sound, for the phone and the car alike: one per process. It hears of
 * the drive from the engine ([GuidanceAudio]), decides with [ToneRule] and the Sound settings which tone
 * sounds, keeps one sound at a time ([TonePacing]), and hands each to the [sink]. It writes the `tone` line
 * of each tone it decides and a `tone_skipped` line of each one it leaves out, numbers, ids and words only;
 * the sink writes `tone_played`. Nothing here reads the look, and nothing runs per frame: one call per
 * navigator update. Main thread.
 *
 * With Tones off, and while another app guides on the car's screen ([carYielded]), the rule runs with no
 * tone allowed: it keeps up with the drive and nothing is logged.
 */
class ToneDirector(
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val sink: ToneSink,
    private val debugLog: DebugLog,
    private val elapsedMillis: () -> Long,
) : GuidanceAudio {

    /** The stored settings, from [start] on; the defaults before. */
    private var sound = SoundSettings()

    private var rule = ToneRule.State.NONE
    private var pacing = TonePacing.State.IDLE

    /** The one wait for a sound that waits for the one playing; null while none waits. */
    private var waiting: Job? = null

    /** What is left of the sounds of Play the tones; null when none plays. */
    private var demo: Job? = null

    /** Whether a call to the sink failed: one error line for the process, and the calls go on. */
    private var sinkFailed = false

    private val heardState = MutableStateFlow<ToneResult?>(null)

    /**
     * What became of the last sound of Play the tones or of a level chosen: null before either, and while
     * the next one is on its way. Settings says why it was not heard.
     */
    val heard: StateFlow<ToneResult?> = heardState.asStateFlow()

    /**
     * A car session is connected: the car's screen shows the app. Then every tone asks for the sound and
     * holds it for the whole tone, whatever Lower the music says ([DuckPlan]). Set by the car's sessions.
     */
    var carSession: Boolean = false

    /**
     * The car's host asked the app to stop navigating, as when another navigation app starts, and the car is
     * still connected: no tone sounds, so that two apps never sound over each other. Ends on a disconnect,
     * and when the driver takes the car's screen back. Set by the car's sessions.
     */
    var carYielded: Boolean = false

    /** Follows the stored Sound settings from now on: a change applies from the next tone. */
    fun start() {
        scope.launch { settings.sound.collect { sound = it } }
    }

    override fun onProgress(moment: ToneMoment) {
        val now = elapsedMillis()
        val before = rule.lastToneMillis
        val step = ToneRule.reduce(rule, moment, now, events())
        rule = step.state
        if (silent()) return
        for (skipped in step.skipped) debugLog.log(SKIPPED_LINE, skippedFields(skipped, moment))
        val cue = step.cue ?: return
        sound(cue.kind) { verdict -> debugLog.log(TONE_LINE, toneFields(cue, moment, now, before, verdict)) }
    }

    override fun onArrived() {
        val now = elapsedMillis()
        val before = rule.lastToneMillis
        val step = ToneRule.arrived(rule, now, events())
        rule = step.state
        if (silent()) return
        val cue = step.cue ?: return
        sound(cue.kind) { verdict -> debugLog.log(TONE_LINE, toneFields(cue, null, now, before, verdict)) }
    }

    override fun onGuidanceEnded() {
        rule = ToneRule.State.NONE
        // Nothing waits any more; a sound playing now plays to its end.
        pacing = TonePacing.ended(pacing)
        waiting?.cancel()
        waiting = null
    }

    /**
     * The chime of a guidance that started by itself (step 7d), once; the caller has checked its own switch.
     * Tones off silences it too.
     */
    fun playStart() {
        if (silent()) return
        sound(ToneKind.START) { verdict -> debugLog.log(TONE_LINE, mapOf("tone" to "start", "why" to "start", "pacing" to verdict.logName())) }
    }

    /**
     * Settings' Play the tones: the one tone, then 1.5 s later the two tones, at the stored level and with the
     * stored dip, also with Tones off. A tone of the drive cancels what is left of it. [heard] says what
     * became of each.
     */
    fun playDemo() {
        demo?.cancel()
        heardState.value = null
        debugLog.log(TONE_LINE, mapOf("tone" to "demo", "why" to "demo"))
        demo = scope.launch {
            for ((index, kind) in DEMO.withIndex()) {
                if (index > 0) delay(DEMO_GAP_MILLIS)
                offer(kind, demoLevel = null) { heardState.value = it }
            }
            demo = null
        }
    }

    /** The one tone at [level], just chosen in Settings, so the driver hears it at once; also with Tones off. */
    fun playSample(level: ToneLevel) {
        demo?.cancel()
        demo = null
        heardState.value = null
        debugLog.log(TONE_LINE, mapOf("tone" to "ahead", "why" to "sample", "level" to level.logName()))
        offer(ToneKind.AHEAD, demoLevel = level) { heardState.value = it }
    }

    /** Whether no tone of the drive may sound: Tones off, or another app guides on the car's screen. */
    private fun silent(): Boolean = !sound.tones || carYielded

    private fun events(): ToneEvents = if (silent()) ToneEvents.NONE else ToneEvents(beforeTurn = sound.beforeTurn, atTurn = sound.atTurn)

    /** A tone of the drive: what is left of Play the tones goes, then [kind] is offered; [logged] gets what pacing did. */
    private inline fun sound(kind: ToneKind, logged: (TonePacing.Verdict) -> Unit) {
        demo?.cancel()
        demo = null
        logged(offer(kind, demoLevel = null) {})
    }

    /** Offers [kind] to the pacing and plays it now, waits for the one playing, or drops it. */
    private fun offer(kind: ToneKind, demoLevel: ToneLevel?, onResult: (ToneResult) -> Unit): TonePacing.Verdict {
        val (next, verdict) = TonePacing.offer(pacing, kind, busyMillis(kind), elapsedMillis())
        pacing = next
        when (verdict) {
            TonePacing.Verdict.PLAY -> play(kind, demoLevel ?: sound.level, onResult)
            TonePacing.Verdict.HOLD -> waitForTheOnePlaying(demoLevel, onResult)
            TonePacing.Verdict.DROP -> Unit
        }
        return verdict
    }

    /** One wait, until the sound playing has ended; then the sound that waits plays. A newer one waits in its place. */
    private fun waitForTheOnePlaying(demoLevel: ToneLevel?, onResult: (ToneResult) -> Unit) {
        waiting?.cancel()
        waiting = scope.launch {
            delay((pacing.busyUntilMillis - elapsedMillis()).coerceAtLeast(0))
            waiting = null
            val (next, due) = TonePacing.due(pacing, elapsedMillis())
            pacing = next
            if (due != null) play(due, demoLevel ?: sound.level, onResult)
        }
    }

    private fun play(kind: ToneKind, level: ToneLevel, onResult: (ToneResult) -> Unit) {
        try {
            sink.play(kind, level, sound.duck, sound.leadInMillis, carSession, onResult)
        } catch (e: RuntimeException) {
            // A sound never costs the drive.
            if (!sinkFailed) {
                sinkFailed = true
                debugLog.error("tones", e)
            }
        }
    }

    /** How long a sound keeps the player busy, at most: its buffer and the longest silence in front it can get. */
    private fun busyMillis(kind: ToneKind): Int = Chime.spec(kind).lengthMillis + max(sound.leadInMillis, DuckPlan.MORE_LEAD_MILLIS)

    /** The `tone` line: the tone, why, the maneuver and the distances it was decided by; numbers, ids and words only. */
    private fun toneFields(cue: ToneCue, moment: ToneMoment?, now: Long, before: Long?, verdict: TonePacing.Verdict): Map<String, Any?> = mapOf(
        "tone" to cue.kind.logName(),
        "why" to cue.reason.logName(),
        "maneuver" to moment?.maneuver?.type?.name,
        "modifier" to moment?.maneuver?.modifier?.name,
        "metersLeft" to moment?.metersToManeuver?.let { Math.round(it) },
        "secondsLeft" to moment?.secondsToManeuver?.takeIf { it > 0.0 }?.let { Motion.round(it, 1) },
        "speed" to moment?.speedMps?.let { Motion.round(it, 1) },
        "aheadMeters" to cue.aheadMeters.takeIf(Double::isFinite)?.let { Math.round(it) },
        "finalMeters" to cue.finalMeters.takeIf(Double::isFinite)?.let { Math.round(it) },
        "laneMeters" to cue.laneMeters.takeIf(Double::isFinite)?.let { Math.round(it) },
        "laneKey" to if (cue.reason == ToneReason.LANE_CHOICE) moment?.laneChoiceKey else null,
        "sinceLast" to before?.let { Motion.round((now - it) / MILLIS_PER_SECOND, 1) },
        "pacing" to verdict.logName(),
    ).filterValues { it != null }

    /** The `tone_skipped` line: why, which tone, and where. */
    private fun skippedFields(skipped: ToneSkipped, moment: ToneMoment): Map<String, Any?> = mapOf(
        "why" to skipped.skip.logName(),
        "tone" to skipped.reason.logName(),
        "maneuver" to moment.maneuver.type.name,
        "metersLeft" to Math.round(moment.metersToManeuver),
        "speed" to moment.speedMps?.let { Motion.round(it, 1) },
        "laneKey" to if (skipped.reason == ToneReason.LANE_CHOICE) moment.laneChoiceKey else null,
    ).filterValues { it != null }

    companion object {
        const val TONE_LINE = "tone"
        const val SKIPPED_LINE = "tone_skipped"

        /** Play the tones: the one tone, then the two. The start chime joins once its row exists (step 7d). */
        val DEMO: List<ToneKind> = listOf(ToneKind.AHEAD, ToneKind.AT_TURN)

        /** Between two sounds of Play the tones: the first has rung out and the music is back. */
        const val DEMO_GAP_MILLIS = 1_500L

        private const val MILLIS_PER_SECOND = 1_000.0
    }
}

/** A word of the log, as the lines write it. */
internal fun ToneKind.logName(): String = name.lowercase(Locale.ROOT)

internal fun ToneLevel.logName(): String = name.lowercase(Locale.ROOT)

private fun ToneReason.logName(): String = name.lowercase(Locale.ROOT)

private fun ToneSkip.logName(): String = name.lowercase(Locale.ROOT)

private fun TonePacing.Verdict.logName(): String = name.lowercase(Locale.ROOT)
