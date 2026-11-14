package io.github.purplepalm.custommaps.nav.sound

import io.github.purplepalm.custommaps.nav.sound.TonePacing.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** One sound at a time: what plays, what waits for the one playing, and what is dropped. */
class TonePacingTest {

    private val pair = Chime.AT_TURN.lengthMillis
    private val single = Chime.AHEAD.lengthMillis

    @Test
    fun `a sound while nothing plays plays at once, and keeps the player busy for its length and the room after it`() {
        val (state, verdict) = TonePacing.offer(TonePacing.State.IDLE, ToneKind.AHEAD, single, nowMillis = 1_000)
        assertEquals(Verdict.PLAY, verdict)
        assertEquals(1_000L + single + TonePacing.AFTER_MILLIS, state.busyUntilMillis)
        // Once it has rung out, the next one plays at once.
        assertEquals(Verdict.PLAY, TonePacing.offer(state, ToneKind.AHEAD, single, state.busyUntilMillis).second)
    }

    @Test
    fun `a pair asked for while a sound plays waits, and comes out when that one has ended`() {
        val (playing, _) = TonePacing.offer(TonePacing.State.IDLE, ToneKind.START, Chime.START.lengthMillis, 0)
        val (waiting, verdict) = TonePacing.offer(playing, ToneKind.AT_TURN, pair, 400)
        assertEquals(Verdict.HOLD, verdict)
        // Not before the end.
        assertNull(TonePacing.due(waiting, playing.busyUntilMillis - 1).second)
        val (after, due) = TonePacing.due(waiting, playing.busyUntilMillis)
        assertEquals(ToneKind.AT_TURN, due)
        assertEquals(playing.busyUntilMillis + pair + TonePacing.AFTER_MILLIS, after.busyUntilMillis)
        assertNull(after.held)
        // Nothing else waits.
        assertNull(TonePacing.due(after, after.busyUntilMillis).second)
    }

    @Test
    fun `a single asked for while a sound plays, or while one waits, is dropped`() {
        val (playing, _) = TonePacing.offer(TonePacing.State.IDLE, ToneKind.AT_TURN, pair, 0)
        assertEquals(Verdict.DROP, TonePacing.offer(playing, ToneKind.AHEAD, single, 500).second)
        val (waiting, _) = TonePacing.offer(playing, ToneKind.START, Chime.START.lengthMillis, 500)
        // The player is free again, but the start chime waits to go first.
        assertEquals(Verdict.DROP, TonePacing.offer(waiting, ToneKind.AHEAD, single, playing.busyUntilMillis).second)
    }

    @Test
    fun `a newer pair takes the place of one that waits`() {
        val (playing, _) = TonePacing.offer(TonePacing.State.IDLE, ToneKind.AHEAD, single, 0)
        val (first, _) = TonePacing.offer(playing, ToneKind.START, Chime.START.lengthMillis, 200)
        val (second, verdict) = TonePacing.offer(first, ToneKind.AT_TURN, pair, 300)
        assertEquals(Verdict.HOLD, verdict)
        assertEquals(ToneKind.AT_TURN, second.held)
        assertEquals(ToneKind.AT_TURN, TonePacing.due(second, playing.busyUntilMillis).second)
    }

    @Test
    fun `nothing waits once guidance ended, and the sound playing plays on`() {
        val (playing, _) = TonePacing.offer(TonePacing.State.IDLE, ToneKind.AHEAD, single, 0)
        val (waiting, _) = TonePacing.offer(playing, ToneKind.AT_TURN, pair, 100)
        val ended = TonePacing.ended(waiting)
        assertNull(ended.held)
        assertNull(TonePacing.due(ended, playing.busyUntilMillis).second)
        assertEquals(playing.busyUntilMillis, ended.busyUntilMillis)
        assertEquals(Verdict.DROP, TonePacing.offer(ended, ToneKind.AHEAD, single, 200).second)
    }
}
