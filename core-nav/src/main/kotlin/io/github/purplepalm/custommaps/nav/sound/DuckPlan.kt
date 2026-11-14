package io.github.purplepalm.custommaps.nav.sound

import kotlin.math.max

/** Lower the music: not at all, a little and briefly, or more and a little longer. */
enum class DuckMode { OFF, LITTLE, MORE }

/** How loud a tone plays against the media volume: one factor on the whole sound, 6 dB apart. */
enum class ToneLevel(val gain: Float) {
    SOFT(0.25f),
    NORMAL(0.5f),

    /** Just under full scale at the highest peak of the three sounds. */
    LOUD(1f),
}

/** What is known of the output when a tone is about to play. */
data class OutputState(
    /** Something plays now, as music: a Bluetooth output is then awake. */
    val musicActive: Boolean,
    /** A Bluetooth output is connected. */
    val wireless: Boolean,
    /** A car session is connected: the car's screen shows the app. */
    val car: Boolean,
)

/**
 * How one tone is played: whether the app asks for the sound for a moment (Android then lowers music by
 * itself, by a depth and a speed it fixes), how much silence goes in front of the tone in its buffer, and
 * how long the sound is held from the request on: giving it back early ends the dip early.
 */
data class PlayPlan(val askFocus: Boolean, val leadInMillis: Int, val holdMillis: Int)

/**
 * Who lowers the music and for how long. Android lowers music to a fifth over 500 ms while the app holds
 * the sound, and brings it back the same way: the depth and the speed are Android's, only the time is the
 * app's. A podcast or an audiobook is not lowered: its player pauses for the moment instead.
 *
 * | Lower the music | asks | silence in front | holds (one tone / two tones / start chime) |
 * |---|---|---|---|
 * | Off | no | none | |
 * | A little | yes | none | 250 / 300 / 300 ms: music to about 0.6, back in about 0.5 s |
 * | More | yes | 300 ms | 700 / 850 / 800 ms: music to a fifth |
 *
 * While nothing plays and a Bluetooth output is connected, or in the car, the output may have gone to
 * sleep: the tone gets [wakeLeadInMillis] of silence in front, in the same buffer, and no deeper hold,
 * since nothing plays to be lowered. While a car session is connected the app always asks for the sound,
 * whatever Lower the music says, and holds it for the whole tone: how far the car lowers its own music is
 * the car's.
 */
object DuckPlan {

    /** More asks this long before the note, so the music is already going down when the note starts. */
    const val MORE_LEAD_MILLIS = 300

    /** The silence in front of a tone on an output that may sleep, unless the Debug row says otherwise. */
    const val WAKE_LEAD_IN_MILLIS = 300

    /** The Debug row's choices of that silence. */
    val WAKE_LEAD_INS: List<Int> = listOf(0, 150, 300, 600)

    fun plan(kind: ToneKind, lengthMillis: Int, duck: DuckMode, output: OutputState, wakeLeadInMillis: Int = WAKE_LEAD_IN_MILLIS): PlayPlan {
        val wake = if (!output.musicActive && (output.wireless || output.car)) wakeLeadInMillis.coerceAtLeast(0) else 0
        val lead = if (duck == DuckMode.MORE) max(wake, MORE_LEAD_MILLIS) else wake
        if (output.car) return PlayPlan(askFocus = true, leadInMillis = lead, holdMillis = lead + lengthMillis)
        return when (duck) {
            DuckMode.OFF -> PlayPlan(askFocus = false, leadInMillis = lead, holdMillis = 0)
            DuckMode.LITTLE -> PlayPlan(askFocus = true, leadInMillis = lead, holdMillis = littleHold(kind))
            DuckMode.MORE -> PlayPlan(askFocus = true, leadInMillis = lead, holdMillis = lead + moreBody(kind))
        }
    }

    /** A little: given back while the dip is still going down, so the music is back within about half a second. */
    private fun littleHold(kind: ToneKind): Int = when (kind) {
        ToneKind.AHEAD -> 250
        ToneKind.AT_TURN -> 300
        ToneKind.START -> 300
    }

    /** More: held through the sound's body, after the silence in front. */
    private fun moreBody(kind: ToneKind): Int = when (kind) {
        ToneKind.AHEAD -> 400
        ToneKind.AT_TURN -> 550
        ToneKind.START -> 500
    }
}
