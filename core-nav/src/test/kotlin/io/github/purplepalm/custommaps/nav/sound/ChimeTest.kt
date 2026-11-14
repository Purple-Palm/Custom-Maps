package io.github.purplepalm.custommaps.nav.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The three sounds as samples: their length, their peaks, their pitches, their ring, no click, and the silence in front. */
class ChimeTest {

    private val rate = Chime.SAMPLE_RATE
    private val all = ToneKind.entries.associateWith { Chime.render(Chime.spec(it)) }

    private fun at(millis: Int) = millis * rate / 1_000

    @Test
    fun `each sound is its buffer's length long and begins and ends at exactly zero`() {
        for ((kind, samples) in all) {
            assertEquals(Chime.spec(kind).lengthMillis * 48, samples.size, "$kind")
            assertEquals(0f, samples.first(), "$kind")
            assertEquals(0f, samples.last(), "$kind")
        }
        assertEquals(listOf(700, 900, 750), listOf(Chime.AHEAD, Chime.AT_TURN, Chime.START).map { it.lengthMillis })
    }

    @Test
    fun `no sample reaches full scale, and the peaks are the design's`() {
        val peaks = mapOf(ToneKind.AHEAD to 0.74, ToneKind.AT_TURN to 0.88, ToneKind.START to 0.89)
        for ((kind, samples) in all) {
            val peak = samples.maxOf { abs(it) }
            assertTrue(peak < 1f, "$kind $peak")
            assertEquals(peaks.getValue(kind), peak.toDouble(), 0.02, "$kind")
        }
    }

    @Test
    fun `the strongest pitch in each note's first 150 ms is the note's own`() {
        val notes = listOf(
            ToneKind.AHEAD to listOf(0 to Chime.E5),
            ToneKind.AT_TURN to listOf(0 to Chime.G5, 220 to Chime.E5),
            ToneKind.START to listOf(0 to Chime.E5, 160 to Chime.A5),
        )
        for ((kind, starts) in notes) {
            for ((start, hertz) in starts) {
                val window = all.getValue(kind).copyOfRange(at(start), at(start + 150))
                val own = power(window, hertz)
                // A semitone up and down, and the other notes of the family.
                val others = listOf(hertz * SEMITONE, hertz / SEMITONE, Chime.E5, Chime.G5, Chime.A5).filter { abs(it - hertz) > 1.0 }
                for (other in others) assertTrue(own > 10 * power(window, other), "$kind at $start ms: $hertz against $other")
            }
        }
    }

    @Test
    fun `each note rings and fades by itself, 10 to 25 dB down 300 to 400 ms after it starts`() {
        for (spec in listOf(Chime.AHEAD, Chime.AT_TURN, Chime.START)) {
            for (note in spec.notes) {
                val alone = Chime.render(ChimeSpec(listOf(note.copy(startMillis = 0)), lengthMillis = 700))
                val strike = rms(alone.copyOfRange(0, at(100)))
                val ring = rms(alone.copyOfRange(at(300), at(400)))
                val down = 20 * log10(strike / ring)
                assertTrue(down in 10.0..25.0, "${note.hertz} Hz, ${note.decayMillis} ms: $down dB")
            }
        }
    }

    @Test
    fun `no click, no step between two samples is steeper than a 2640 Hz sine at the peak`() {
        for ((kind, samples) in all) {
            val peak = samples.maxOf { abs(it) }
            val bound = peak * 2 * PI * 2_640 / rate
            val steepest = (1 until samples.size).maxOf { abs(samples[it] - samples[it - 1]) }
            assertTrue(steepest < bound, "$kind: $steepest against $bound")
        }
    }

    @Test
    fun `a lead-in of 300 ms puts 14 400 zeros in front and changes nothing after them`() {
        for (kind in ToneKind.entries) {
            val plain = all.getValue(kind)
            val led = Chime.render(Chime.spec(kind), leadInMillis = 300)
            assertEquals(plain.size + 14_400, led.size)
            assertTrue((0 until 14_400).all { led[it] == 0f }, "$kind")
            assertArrayEquals(plain, led.copyOfRange(14_400, led.size), "$kind")
        }
    }

    @Test
    fun `the pair ends on the one tone's note and falls, the start chime rises`() {
        assertEquals(Chime.AHEAD.notes.single().hertz, Chime.AT_TURN.notes.last().hertz)
        assertTrue(Chime.AT_TURN.notes.first().hertz > Chime.AT_TURN.notes.last().hertz)
        assertTrue(Chime.START.notes.first().hertz < Chime.START.notes.last().hertz)
        assertEquals(Chime.AHEAD.notes.single().hertz, Chime.START.notes.first().hertz)
        assertEquals(220, Chime.AT_TURN.notes.last().startMillis)
        assertEquals(160, Chime.START.notes.last().startMillis)
        // Every pitch lies where a phone's speaker and road noise leave it heard, and calm.
        for (note in listOf(Chime.AHEAD, Chime.AT_TURN, Chime.START).flatMap { it.notes }) assertTrue(note.hertz in 600.0..900.0)
    }

    @Test
    fun `the same input gives the same samples`() {
        for (kind in ToneKind.entries) {
            assertArrayEquals(Chime.render(Chime.spec(kind), leadInMillis = 150), Chime.render(Chime.spec(kind), leadInMillis = 150))
        }
    }

    /** The power of [samples] at [hertz]: one single-frequency sum. */
    private fun power(samples: FloatArray, hertz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in samples.indices) {
            val phase = 2 * PI * hertz * i / rate
            re += samples[i] * cos(phase)
            im -= samples[i] * sin(phase)
        }
        return re * re + im * im
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private companion object {
        val SEMITONE = 2.0.pow(1.0 / 12)
    }
}
