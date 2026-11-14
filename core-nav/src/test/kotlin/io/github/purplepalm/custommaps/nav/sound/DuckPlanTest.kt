package io.github.purplepalm.custommaps.nav.sound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Who lowers the music and for how long, row by row, and the silence in front of a tone on an output that may sleep. */
class DuckPlanTest {

    private val music = OutputState(musicActive = true, wireless = false, car = false)
    private val quietSpeaker = OutputState(musicActive = false, wireless = false, car = false)
    private val quietBluetooth = OutputState(musicActive = false, wireless = true, car = false)
    private val musicBluetooth = OutputState(musicActive = true, wireless = true, car = false)

    private fun plan(kind: ToneKind, duck: DuckMode, output: OutputState = music, lead: Int = DuckPlan.WAKE_LEAD_IN_MILLIS) =
        DuckPlan.plan(kind, Chime.spec(kind).lengthMillis, duck, output, lead)

    @Test
    fun `Off asks for nothing and leaves the music alone`() {
        for (kind in ToneKind.entries) assertEquals(PlayPlan(askFocus = false, leadInMillis = 0, holdMillis = 0), plan(kind, DuckMode.OFF))
    }

    @Test
    fun `A little asks and gives the sound back early, so the dip stays short`() {
        assertEquals(PlayPlan(true, 0, 250), plan(ToneKind.AHEAD, DuckMode.LITTLE))
        assertEquals(PlayPlan(true, 0, 300), plan(ToneKind.AT_TURN, DuckMode.LITTLE))
        assertEquals(PlayPlan(true, 0, 300), plan(ToneKind.START, DuckMode.LITTLE))
    }

    @Test
    fun `More asks 300 ms before the note and holds through its body`() {
        assertEquals(PlayPlan(true, 300, 700), plan(ToneKind.AHEAD, DuckMode.MORE))
        assertEquals(PlayPlan(true, 300, 850), plan(ToneKind.AT_TURN, DuckMode.MORE))
        assertEquals(PlayPlan(true, 300, 800), plan(ToneKind.START, DuckMode.MORE))
    }

    @Test
    fun `nothing playing over Bluetooth gets the silence in front and no deeper hold`() {
        assertEquals(PlayPlan(true, 300, 250), plan(ToneKind.AHEAD, DuckMode.LITTLE, quietBluetooth))
        assertEquals(PlayPlan(false, 300, 0), plan(ToneKind.AHEAD, DuckMode.OFF, quietBluetooth))
        // The Debug row's other lengths, and More keeps its own lead where that is longer.
        for (lead in DuckPlan.WAKE_LEAD_INS) assertEquals(lead, plan(ToneKind.AT_TURN, DuckMode.LITTLE, quietBluetooth, lead).leadInMillis)
        assertEquals(PlayPlan(true, 300, 850), plan(ToneKind.AT_TURN, DuckMode.MORE, quietBluetooth, lead = 150))
        assertEquals(PlayPlan(true, 600, 1_150), plan(ToneKind.AT_TURN, DuckMode.MORE, quietBluetooth, lead = 600))
        // An awake output, or the phone's own speaker, needs none.
        assertEquals(0, plan(ToneKind.AHEAD, DuckMode.LITTLE, musicBluetooth).leadInMillis)
        assertEquals(0, plan(ToneKind.AHEAD, DuckMode.LITTLE, quietSpeaker).leadInMillis)
        assertEquals(DuckPlan.WAKE_LEAD_INS, listOf(0, 150, 300, 600))
        assertEquals(300, DuckPlan.WAKE_LEAD_IN_MILLIS)
    }

    @Test
    fun `a car session asks even with Off and holds for the whole sound`() {
        val car = OutputState(musicActive = true, wireless = true, car = true)
        for (duck in DuckMode.entries) {
            for (kind in ToneKind.entries) {
                val length = Chime.spec(kind).lengthMillis
                val plan = plan(kind, duck, car)
                assertEquals(true, plan.askFocus, "$duck $kind")
                assertEquals(plan.leadInMillis + length, plan.holdMillis, "$duck $kind")
            }
        }
        // With nothing playing the car's output may sleep too.
        assertEquals(300, plan(ToneKind.AHEAD, DuckMode.OFF, car.copy(musicActive = false, wireless = false)).leadInMillis)
        assertEquals(0, plan(ToneKind.AHEAD, DuckMode.OFF, car).leadInMillis)
    }

    @Test
    fun `the levels are 6 dB apart, the loudest at full scale`() {
        assertEquals(listOf(0.25f, 0.5f, 1f), ToneLevel.entries.map { it.gain })
    }
}
