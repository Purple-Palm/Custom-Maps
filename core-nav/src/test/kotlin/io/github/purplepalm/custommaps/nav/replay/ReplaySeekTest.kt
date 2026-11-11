package io.github.purplepalm.custommaps.nav.replay

import io.github.purplepalm.custommaps.nav.replay.ReplaySeek.Answer
import io.github.purplepalm.custommaps.nav.replay.ReplaySeek.Why
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A made-up simulated drive of 600 s, ten minutes on the replay's clock. */
class ReplaySeekTest {

    private val drive = 600.0

    private fun at(seconds: Double) = ReplayPosition(seconds = seconds, durationSeconds = drive)

    private fun go(seconds: Double) = Answer.Go(seconds)

    private fun stay(why: Why) = Answer.Stay(why)

    @Test
    fun `the played part is the share of the replay's seconds`() {
        assertEquals(0.0, at(0.0).fraction)
        assertEquals(0.25, at(150.0).fraction)
        assertEquals(1.0, at(600.0).fraction)
        assertEquals(25, at(150.0).percent)
        // 1/3 of the way rounds to the nearest whole percent.
        assertEquals(33, at(200.0).percent)
        assertEquals(67, at(400.0).percent)
    }

    @Test
    fun `a position outside the replay is shown at its ends, and an empty replay at its start`() {
        assertEquals(0.0, at(-1.0).fraction)
        assertEquals(1.0, at(601.0).fraction)
        assertEquals(0.0, ReplayPosition(seconds = 0.0, durationSeconds = 0.0).fraction)
        assertEquals(0, ReplayPosition(seconds = 3.0, durationSeconds = 0.0).percent)
    }

    @Test
    fun `a release ahead goes forward, to the share of the replay it was let go at`() {
        assertEquals(go(300.0), ReplaySeek.answer(at(60.0), 0.5, rerouting = false))
        assertEquals(go(150.0), ReplaySeek.answer(at(60.0), 0.25, rerouting = false))
    }

    @Test
    fun `a release behind goes back, as far as the start`() {
        assertEquals(go(60.0), ReplaySeek.answer(at(300.0), 0.1, rerouting = false))
        assertEquals(go(0.0), ReplaySeek.answer(at(300.0), 0.0, rerouting = false))
        assertEquals(go(0.0), ReplaySeek.answer(at(300.0), -0.2, rerouting = false))
    }

    @Test
    fun `the end of the slider goes to the last stretch, and the drive arrives by itself`() {
        val latest = drive - ReplaySeek.END_MARGIN_SECONDS
        assertEquals(go(latest), ReplaySeek.answer(at(300.0), 1.0, rerouting = false))
        assertEquals(go(latest), ReplaySeek.answer(at(300.0), 1.3, rerouting = false))
        assertEquals(go(latest), ReplaySeek.answer(at(300.0), 0.999, rerouting = false))
    }

    @Test
    fun `in the last stretch the end asks for nothing, but a release behind still goes back`() {
        assertEquals(stay(Why.END), ReplaySeek.answer(at(597.0), 1.0, rerouting = false))
        assertEquals(stay(Why.END), ReplaySeek.answer(at(595.0), 1.0, rerouting = false))
        assertEquals(go(300.0), ReplaySeek.answer(at(597.0), 0.5, rerouting = false))
    }

    @Test
    fun `a release next to the dot moves nothing`() {
        assertEquals(stay(Why.NEAR), ReplaySeek.answer(at(300.0), 0.5, rerouting = false))
        // 1.8 s ahead and 1.8 s behind.
        assertEquals(stay(Why.NEAR), ReplaySeek.answer(at(300.0), 0.503, rerouting = false))
        assertEquals(stay(Why.NEAR), ReplaySeek.answer(at(300.0), 0.497, rerouting = false))
        // 2.4 s is far enough.
        assertEquals(go(302.4), ReplaySeek.answer(at(300.0), 0.504, rerouting = false))
    }

    @Test
    fun `nothing moves without a replay, while it is empty, or for a release that is no number`() {
        assertEquals(stay(Why.NO_REPLAY), ReplaySeek.answer(null, 0.5, rerouting = false))
        assertEquals(stay(Why.NO_REPLAY), ReplaySeek.answer(ReplayPosition(0.0, 0.0), 0.5, rerouting = false))
        assertEquals(stay(Why.NEAR), ReplaySeek.answer(at(300.0), Double.NaN, rerouting = false))
    }

    @Test
    fun `a replay no longer than the last stretch has nowhere to go`() {
        val short = ReplayPosition(seconds = 1.0, durationSeconds = ReplaySeek.END_MARGIN_SECONDS)
        assertEquals(stay(Why.END), ReplaySeek.answer(short, 0.0, rerouting = false))
    }

    @Test
    fun `while the navigator changes the route nothing moves`() {
        assertEquals(stay(Why.REROUTE), ReplaySeek.answer(at(60.0), 0.5, rerouting = true))
        // The replay's position does not matter then; only an empty replay is named first.
        assertEquals(stay(Why.NO_REPLAY), ReplaySeek.answer(null, 0.5, rerouting = true))
    }

    @Test
    fun `a second release during a seek is measured from where that seek goes`() {
        val seeking = ReplayPosition(seconds = 300.0, durationSeconds = drive, seeking = true)
        assertEquals(stay(Why.NEAR), ReplaySeek.answer(seeking, 0.5, rerouting = false))
        assertEquals(go(450.0), ReplaySeek.answer(seeking, 0.75, rerouting = false))
    }

    @Test
    fun `every reason has its own name in the log`() {
        val names = Why.entries.map { it.logName }
        assertEquals(names.toSet().size, names.size)
        assertEquals(listOf("no_replay", "reroute", "end", "near", "replaced", "ended", "refused"), names)
    }
}
