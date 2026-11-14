package io.github.purplepalm.custommaps.nav.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The app's own sounds: one tone before a maneuver, two tones at it, and the chime of a start by itself. */
enum class ToneKind {
    /** One tone, about 25 seconds before: "something is coming". */
    AHEAD,

    /** Two tones, a higher note then the one tone's, about 7 seconds before: "this is it". */
    AT_TURN,

    /** Two notes going up: guidance started by itself (step 7d). */
    START,
}

/**
 * One note of a chime: when it starts, its pitch, how long its clickless rise takes, how fast it rings
 * out (the decay constant of its first harmonic), and how loud it is.
 */
data class ChimeNote(val startMillis: Int, val hertz: Double, val attackMillis: Int, val decayMillis: Int, val gain: Double)

/** A harmonic of every note: its number, its level against the first, and how many times faster it dies away. */
data class ChimePartial(val harmonic: Int, val level: Double, val decaySpeed: Double)

/** A chime: its notes and the length of its buffer. */
data class ChimeSpec(val notes: List<ChimeNote>, val lengthMillis: Int)

/**
 * The sounds, made in code from one soft struck note: a sine with two weak harmonics that die away
 * faster, a raised-cosine rise with no click, and a ring that fades by itself. No sound file exists.
 *
 * ```
 * note(t)   = gain · attack(t) · Σ level · sin(2π · hertz · harmonic · t) · exp(−t · decaySpeed / decay)
 * attack(t) = ½ · (1 − cos(π · t / attackTime)) while t < attackTime, else 1
 * ```
 * The last [CLOSE_MILLIS] of the buffer fall to zero along a raised cosine. One channel, float samples.
 * Pure and fixed: the same input gives the same samples, on the phone and in the tests.
 */
object Chime {
    /** The usual output rate of phones and cars. */
    const val SAMPLE_RATE = 48_000

    /** The buffer's last part, which falls to exactly zero. */
    const val CLOSE_MILLIS = 30

    /** E5, the one tone's note, which the pair ends on and the start chime begins with. */
    const val E5 = 659.26

    /** G5, the pair's first note: a minor third above. */
    const val G5 = 783.99

    /** A5, the start chime's second note: a fourth above. */
    const val A5 = 880.00

    /** Every note's gain: the highest peak of the three sounds stays under full scale at the loudest level. */
    private const val NOTE_GAIN = 0.8

    /** The rise of every note: no click, 88 dB and more under the tone. */
    private const val ATTACK_MILLIS = 12

    /** The second harmonic 18 dB down and twice as fast, the third 26 dB down and three times: bright at the strike, mellow in the ring. */
    val PARTIALS: List<ChimePartial> = listOf(
        ChimePartial(1, 1.0, 1.0),
        ChimePartial(2, 0.126, 2.0),
        ChimePartial(3, 0.05, 3.0),
    )

    /** One tone: E5 that rings about 0.4 s. */
    val AHEAD = ChimeSpec(listOf(ChimeNote(0, E5, ATTACK_MILLIS, 160, NOTE_GAIN)), lengthMillis = 700)

    /** Two tones: G5, then 220 ms later E5 with a slightly longer ring, so the pair settles on the one tone's note. */
    val AT_TURN = ChimeSpec(
        listOf(ChimeNote(0, G5, ATTACK_MILLIS, 130, NOTE_GAIN), ChimeNote(220, E5, ATTACK_MILLIS, 170, NOTE_GAIN)),
        lengthMillis = 900,
    )

    /** The start chime: E5, then 160 ms later A5, going up where the pair comes down. */
    val START = ChimeSpec(
        listOf(ChimeNote(0, E5, ATTACK_MILLIS, 110, NOTE_GAIN), ChimeNote(160, A5, ATTACK_MILLIS, 150, NOTE_GAIN)),
        lengthMillis = 750,
    )

    fun spec(kind: ToneKind): ChimeSpec = when (kind) {
        ToneKind.AHEAD -> AHEAD
        ToneKind.AT_TURN -> AT_TURN
        ToneKind.START -> START
    }

    /**
     * The samples of [spec], from −1 to 1, with [leadInMillis] of silence first (a Bluetooth output that
     * slept wakes up during it). The first and the last sample of the sound are exactly zero.
     */
    fun render(spec: ChimeSpec, leadInMillis: Int = 0, sampleRate: Int = SAMPLE_RATE): FloatArray {
        require(leadInMillis >= 0) { "A lead-in is never negative: $leadInMillis" }
        require(sampleRate > 0 && spec.lengthMillis > CLOSE_MILLIS) { "No room for the sound: $spec at $sampleRate" }
        val lead = samplesOf(leadInMillis, sampleRate)
        val count = samplesOf(spec.lengthMillis, sampleRate)
        val sound = DoubleArray(count)
        for (note in spec.notes) addNote(sound, note, sampleRate)
        val close = samplesOf(CLOSE_MILLIS, sampleRate)
        for (k in 0 until close) sound[count - close + k] *= 0.5 * (1.0 + cos(PI * (k + 1) / close))
        val samples = FloatArray(lead + count)
        // Adding zero turns a negative zero into zero: a silent sample is plain zero.
        for (i in 0 until count) samples[lead + i] = (sound[i] + 0.0).toFloat()
        return samples
    }

    private fun addNote(sound: DoubleArray, note: ChimeNote, sampleRate: Int) {
        val first = samplesOf(note.startMillis, sampleRate)
        val attack = note.attackMillis / MILLIS_PER_SECOND
        val decay = note.decayMillis / MILLIS_PER_SECOND
        for (i in first until sound.size) {
            val t = (i - first).toDouble() / sampleRate
            val rise = if (t < attack) 0.5 * (1.0 - cos(PI * t / attack)) else 1.0
            var body = 0.0
            for (partial in PARTIALS) {
                body += partial.level * sin(2.0 * PI * note.hertz * partial.harmonic * t) * exp(-t * partial.decaySpeed / decay)
            }
            sound[i] += note.gain * rise * body
        }
    }

    private fun samplesOf(millis: Int, sampleRate: Int): Int = (millis.toLong() * sampleRate / 1_000L).toInt()

    private const val MILLIS_PER_SECOND = 1_000.0
}
