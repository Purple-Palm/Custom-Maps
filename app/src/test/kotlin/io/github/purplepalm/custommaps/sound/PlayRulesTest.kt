package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.nav.sound.Chime
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.DuckPlan
import io.github.purplepalm.custommaps.nav.sound.OutputState
import io.github.purplepalm.custommaps.nav.sound.PlayPlan
import io.github.purplepalm.custommaps.nav.sound.ToneKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The player's own rules, which [TonePlayerWiringTest] holds the player to: whether a tone may play, and when it gives back. */
class PlayRulesTest {

    private val outputs = listOf(
        OutputState(musicActive = true, wireless = false, car = false),
        OutputState(musicActive = false, wireless = false, car = false),
        OutputState(musicActive = false, wireless = true, car = false),
        OutputState(musicActive = true, wireless = true, car = true),
        OutputState(musicActive = false, wireless = true, car = true),
    )

    @Test
    fun `a call or a ringing phone stops a tone, also at volume zero, and then a media volume at zero`() {
        assertEquals(ToneResult.IN_CALL, PlayGate.check(modeNormal = false, mediaVolume = 9))
        assertEquals(ToneResult.IN_CALL, PlayGate.check(modeNormal = false, mediaVolume = 0))
        assertEquals(ToneResult.VOLUME_ZERO, PlayGate.check(modeNormal = true, mediaVolume = 0))
        assertEquals(ToneResult.VOLUME_ZERO, PlayGate.check(modeNormal = true, mediaVolume = -1))
        assertNull(PlayGate.check(modeNormal = true, mediaVolume = 1))
        assertNull(PlayGate.check(modeNormal = true, mediaVolume = 15))
    }

    @Test
    fun `the sound is given back after the dip's hold, in milliseconds, for every tone, dip and output`() {
        for (kind in ToneKind.entries) {
            for (duck in DuckMode.entries) {
                for (output in outputs) {
                    val plan = DuckPlan.plan(kind, Chime.spec(kind).lengthMillis, duck, output)
                    val schedule = PlaySchedule.of(plan, Chime.render(Chime.spec(kind), plan.leadInMillis).size)
                    assertEquals(plan.holdMillis.toLong(), schedule.giveBackAfterMillis, "$kind $duck $output")
                    // The track goes only after the sound is given back, and never before it has rung out.
                    assertTrue(schedule.releaseAfterMillis > schedule.giveBackAfterMillis, "$kind $duck $output")
                }
            }
        }
        // A little: a quarter of a second for the one tone, so the dip stays short; in the car the whole tone.
        val little = DuckPlan.plan(ToneKind.AHEAD, Chime.AHEAD.lengthMillis, DuckMode.LITTLE, outputs[0])
        assertEquals(250L, PlaySchedule.of(little, samples = 1).giveBackAfterMillis)
        val car = DuckPlan.plan(ToneKind.AHEAD, Chime.AHEAD.lengthMillis, DuckMode.OFF, outputs[3])
        assertEquals(Chime.AHEAD.lengthMillis.toLong(), PlaySchedule.of(car, samples = 1).giveBackAfterMillis)
    }

    @Test
    fun `the track is let go of 250 ms after its last sample, the silence in front counted`() {
        val plan = PlayPlan(askFocus = true, leadInMillis = 0, holdMillis = 250)
        assertEquals(250L, PlaySchedule.RELEASE_AFTER_MILLIS)
        // 48,000 samples are a second at 48 kHz; 24,000 at 24 kHz too.
        assertEquals(1_250L, PlaySchedule.of(plan, samples = 48_000).releaseAfterMillis)
        assertEquals(1_250L, PlaySchedule.of(plan, samples = 24_000, sampleRate = 24_000).releaseAfterMillis)
        // Each sound as the player renders it: its length, the silence in front, and 250 ms.
        for (kind in ToneKind.entries) {
            for (lead in listOf(0, 300, 600)) {
                val samples = Chime.render(Chime.spec(kind), lead).size
                assertEquals(Chime.spec(kind).lengthMillis + lead + 250L, PlaySchedule.of(plan, samples).releaseAfterMillis, "$kind $lead")
            }
        }
    }
}
