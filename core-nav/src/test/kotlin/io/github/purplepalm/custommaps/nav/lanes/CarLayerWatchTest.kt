package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The watchdog of the app's own car layer, on a clock of its own: seconds as nanoseconds, made-up speeds. */
class CarLayerWatchTest {

    private fun s(seconds: Double) = (seconds * 1e9).toLong()

    @Test
    fun `no position for a second while it counts trips it, and less does not`() {
        assertEquals(1_000_000_000L, CarLayerWatch.NO_POSITION_NANOS)
        val watch = CarLayerWatch()
        // Positions every frame up to 9 s, and none from there on.
        var t = 0.0
        while (t < 9.0) {
            watch.onPosition(s(t))
            assertFalse(watch.check(s(t), live = true, onScreen = true, speedMps = 23.0))
            t += 0.25
        }
        watch.onPosition(s(9.0))
        assertFalse(watch.check(s(9.999), live = true, onScreen = true, speedMps = 23.0), "0.999 s")
        assertTrue(watch.check(s(9.0) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0), "1 s")
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
            watch.onPosition(0L)
            for (k in 1..30) assertFalse(check(watch, s(k.toDouble())), "$case at $k s")
        }
        // Just above the speed it counts.
        val moving = CarLayerWatch()
        moving.onPosition(0L)
        assertFalse(moving.check(s(1.0), live = true, onScreen = true, speedMps = 2.01))
        assertTrue(moving.check(s(2.0), live = true, onScreen = true, speedMps = 2.01))
    }

    @Test
    fun `the count starts again when the map is back`() {
        val watch = CarLayerWatch()
        watch.onPosition(0L)
        assertFalse(watch.check(s(0.5), live = true, onScreen = true, speedMps = 23.0))
        // The screen goes off for a minute: no positions, no count.
        for (k in 1..60) assertFalse(watch.check(s(k.toDouble()), live = true, onScreen = false, speedMps = 23.0))
        // Back: a full second from here before it may trip, and a position in it resets nothing but the wait.
        assertFalse(watch.check(s(61.0), live = true, onScreen = true, speedMps = 23.0))
        assertFalse(watch.check(s(61.9), live = true, onScreen = true, speedMps = 23.0))
        watch.onPosition(s(61.95))
        assertFalse(watch.check(s(62.5), live = true, onScreen = true, speedMps = 23.0))
        assertTrue(watch.check(s(62.95), live = true, onScreen = true, speedMps = 23.0))
        // A stop of the car starts the count again too.
        val stop = CarLayerWatch()
        stop.onPosition(0L)
        assertFalse(stop.check(s(0.9), live = true, onScreen = true, speedMps = 23.0))
        assertFalse(stop.check(s(4.0), live = true, onScreen = true, speedMps = 0.0))
        assertFalse(stop.check(s(4.5), live = true, onScreen = true, speedMps = 23.0))
        assertTrue(stop.check(s(4.5) + CarLayerWatch.NO_POSITION_NANOS, live = true, onScreen = true, speedMps = 23.0))
    }

    @Test
    fun `a new drive forgets the last one`() {
        val watch = CarLayerWatch()
        watch.onPosition(0L)
        assertFalse(watch.check(s(0.5), live = true, onScreen = true, speedMps = 23.0))
        watch.reset()
        assertFalse(watch.check(s(1.2), live = true, onScreen = true, speedMps = 23.0), "counts from now")
        assertTrue(watch.check(s(2.2), live = true, onScreen = true, speedMps = 23.0))
    }
}
