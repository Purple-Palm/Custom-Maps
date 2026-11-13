package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The watchdog of the app's own car layer, on a clock of its own: seconds as nanoseconds, made-up speeds and fix times. */
class CarLayerWatchTest {

    private fun s(seconds: Double) = (seconds * 1e9).toLong()

    /** A display frame of 60 Hz. */
    private val frame = s(1.0 / 60.0)

    @Test
    fun `a fix with no position after it for a second while it counts trips it, and less does not`() {
        assertEquals(1_000_000_000L, CarLayerWatch.NO_POSITION_NANOS)
        val watch = CarLayerWatch()
        // A fix every second and the puck's glide handing a position every frame, up to 9 s.
        var t = 0L
        while (t < s(9.0)) {
            if (t % s(1.0) < frame) watch.onFix(t)
            watch.onPosition()
            assertFalse(watch.check(t, live = true, onScreen = true, speedMps = 23.0))
            t += frame
        }
        // A fix at 9 s, and no position from there on.
        watch.onFix(s(9.0))
        assertFalse(watch.check(s(9.0) + CarLayerWatch.NO_POSITION_NANOS - 1, live = true, onScreen = true, speedMps = 23.0), "just under 1 s")
        assertTrue(watch.check(s(9.0) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0), "1 s")
    }

    @Test
    fun `with Android's animations off a position follows each fix only, and a late fix never trips it`() {
        val random = Random(13)
        val watch = CarLayerWatch()
        // Ten minutes of fixes about a second apart, each up to 30 ms early or 90 ms late; with no glide the puck jumps to each
        // fix, so the map hands a position on the next frame and one more a frame later, and none in between.
        val events = ArrayList<Pair<Long, Boolean>>() // (time, a fix) or (time, a position)
        for (k in 1..600) {
            val fix = s(k.toDouble()) + s(random.nextDouble(-0.03, 0.09))
            events += fix to true
            events += fix + frame to false
            events += fix + 2 * frame to false
        }
        events.sortBy { it.first }
        // A check every 1.003 s, which drifts across every gap between two fixes.
        var next = 0
        var check = s(0.5)
        var trips = 0
        while (check < s(600.0)) {
            while (next < events.size && events[next].first <= check) {
                val (t, fix) = events[next++]
                if (fix) watch.onFix(t) else watch.onPosition()
            }
            if (watch.check(check, live = true, onScreen = true, speedMps = 23.0)) trips++
            check += s(1.003)
        }
        assertEquals(0, trips)
    }

    @Test
    fun `fixes with no position after them trip it a second after the first, whatever comes after it`() {
        val watch = CarLayerWatch()
        // Counting already, as during a drive.
        assertFalse(watch.check(s(19.5), live = true, onScreen = true, speedMps = 23.0))
        watch.onFix(s(20.0))
        var t = 20.25
        while (t < 21.0) {
            // More fixes come; the first one still waits.
            if (t == 20.5) watch.onFix(s(20.5))
            assertFalse(watch.check(s(t), live = true, onScreen = true, speedMps = 23.0), "at $t s")
            t += 0.25
        }
        assertTrue(watch.check(s(21.0), live = true, onScreen = true, speedMps = 23.0))
        // A position before the second is up answers every fix before it.
        val answered = CarLayerWatch()
        assertFalse(answered.check(s(19.5), live = true, onScreen = true, speedMps = 23.0))
        answered.onFix(s(20.0))
        answered.onFix(s(20.5))
        answered.onPosition()
        assertFalse(answered.check(s(22.0), live = true, onScreen = true, speedMps = 23.0))
    }

    @Test
    fun `no fix is no silence, as in a tunnel where the puck stands still`() {
        val watch = CarLayerWatch()
        watch.onFix(0L)
        watch.onPosition()
        // A minute without fixes, and so without positions, at speed.
        for (k in 1..60) assertFalse(watch.check(s(k.toDouble()), live = true, onScreen = true, speedMps = 23.0), "at $k s")
        // Out of it, a fix the map answers.
        watch.onFix(s(60.5))
        watch.onPosition()
        assertFalse(watch.check(s(62.0), live = true, onScreen = true, speedMps = 23.0))
    }

    @Test
    fun `a standing car, a stopped or covered map and a layer that does not draw never trip it`() {
        assertEquals(2.0, CarLayerWatch.MIN_SPEED_MPS)
        for ((case, check) in listOf<Pair<String, (CarLayerWatch, Long) -> Boolean>>(
            "standing" to { w, t -> w.check(t, live = true, onScreen = true, speedMps = 0.0) },
            "at 2 m/s" to { w, t -> w.check(t, live = true, onScreen = true, speedMps = 2.0) },
            "no speed" to { w, t -> w.check(t, live = true, onScreen = true, speedMps = null) },
            "screen off or the map away" to { w, t -> w.check(t, live = true, onScreen = false, speedMps = 23.0) },
            "the SDK's puck drawn" to { w, t -> w.check(t, live = false, onScreen = true, speedMps = 23.0) },
        )) {
            val watch = CarLayerWatch()
            for (k in 1..30) {
                // A fix every second and never a position.
                watch.onFix(s(k - 0.5))
                assertFalse(check(watch, s(k.toDouble())), "$case at $k s")
            }
        }
        // Just above the speed it counts.
        val moving = CarLayerWatch()
        moving.onFix(0L)
        assertFalse(moving.check(s(0.5), live = true, onScreen = true, speedMps = 2.01))
        assertTrue(moving.check(s(1.5), live = true, onScreen = true, speedMps = 2.01))
    }

    @Test
    fun `the count starts again when the map is back`() {
        val watch = CarLayerWatch()
        watch.onFix(0L)
        watch.onPosition()
        assertFalse(watch.check(s(0.5), live = true, onScreen = true, speedMps = 23.0))
        // The screen goes off for a minute: fixes, no positions, no count.
        for (k in 1..60) {
            watch.onFix(s(k - 0.5))
            assertFalse(watch.check(s(k.toDouble()), live = true, onScreen = false, speedMps = 23.0))
        }
        // Back: a full second from here before it may trip, and a position in it answers the fixes that waited.
        assertFalse(watch.check(s(61.0), live = true, onScreen = true, speedMps = 23.0))
        assertFalse(watch.check(s(61.9), live = true, onScreen = true, speedMps = 23.0))
        watch.onPosition()
        watch.onFix(s(61.95))
        assertFalse(watch.check(s(62.5), live = true, onScreen = true, speedMps = 23.0))
        assertTrue(watch.check(s(61.95) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0))
        // A stop of the car starts the count again too.
        val stop = CarLayerWatch()
        stop.onFix(0L)
        assertFalse(stop.check(s(0.9), live = true, onScreen = true, speedMps = 23.0))
        assertFalse(stop.check(s(4.0), live = true, onScreen = true, speedMps = 0.0))
        assertFalse(stop.check(s(4.5), live = true, onScreen = true, speedMps = 23.0))
        assertTrue(stop.check(s(4.5) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0))
    }

    @Test
    fun `a new drive forgets the last one`() {
        val watch = CarLayerWatch()
        watch.onFix(0L)
        assertFalse(watch.check(s(0.5), live = true, onScreen = true, speedMps = 23.0))
        watch.reset()
        assertFalse(watch.check(s(1.2), live = true, onScreen = true, speedMps = 23.0), "no fix of this drive waits")
        watch.onFix(s(1.4))
        assertFalse(watch.check(s(2.3), live = true, onScreen = true, speedMps = 23.0), "a second from this drive's first fix")
        assertTrue(watch.check(s(1.4) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0))
    }
}
