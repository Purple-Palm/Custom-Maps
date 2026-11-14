package io.github.purplepalm.custommaps.nav.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The camera line's "render" object: the render thread's calls against the frames it drew. The
 * numbers are those of a map capped against a display rate it gets wrong, as on the car's surface.
 */
class RenderStatsTest {

    @Test
    fun `without a cap every call draws`() {
        val fields = RenderStats.fields(callbacks = 60, pacedSkips = 0, missedVsync = 2, windowMillis = 1_000, p50Ms = 8.04, p95Ms = 13.96, maxMs = 21.0)
        assertEquals(60, fields["frames"])
        assertEquals(60.0, fields["fps"])
        assertEquals(60L, fields["drawn"])
        assertEquals(60.0, fields["drawnFps"])
        assertEquals(2L, fields["missedVsync"])
        assertEquals(8.0, fields["p50Ms"])
        assertEquals(14.0, fields["p95Ms"])
        assertEquals(21.0, fields["maxMs"])
    }

    @Test
    fun `paced calls are counted as calls and not as frames drawn`() {
        // A cap of 30 against a believed 60 Hz, called 120 times a second: every second call draws.
        val fields = RenderStats.fields(callbacks = 120, pacedSkips = 60, missedVsync = 0, windowMillis = 1_000, p50Ms = 0.1, p95Ms = 15.2, maxMs = 18.4)
        assertEquals(120, fields["frames"])
        assertEquals(120.0, fields["fps"])
        assertEquals(60L, fields["drawn"])
        assertEquals(60.0, fields["drawnFps"])
    }

    @Test
    fun `the rates are per second of the window`() {
        val fields = RenderStats.fields(callbacks = 45, pacedSkips = 15, missedVsync = 0, windowMillis = 1_500, p50Ms = null, p95Ms = null, maxMs = null)
        assertEquals(30.0, fields["fps"])
        assertEquals(30L, fields["drawn"])
        assertEquals(20.0, fields["drawnFps"])
    }

    @Test
    fun `an empty window draws nothing and has no frame times`() {
        val fields = RenderStats.fields(callbacks = 0, pacedSkips = 0, missedVsync = 0, windowMillis = 0, p50Ms = null, p95Ms = null, maxMs = null)
        assertEquals(0.0, fields["fps"])
        assertEquals(0L, fields["drawn"])
        assertEquals(0.0, fields["drawnFps"])
        assertNull(fields["p50Ms"])
        assertNull(fields["maxMs"])
    }

    @Test
    fun `more skips than calls never count below nothing drawn`() {
        // A call can come between the recorder's two halves of a stop and a start: never a negative count.
        assertEquals(0L, RenderStats.fields(callbacks = 3, pacedSkips = 4, missedVsync = 0, windowMillis = 1_000, p50Ms = null, p95Ms = null, maxMs = null)["drawn"])
    }

    @Test
    fun `the frames drawn that the car's cap takes are the line's own`() {
        for ((calls, paced) in listOf(60 to 0L, 120 to 60L, 45 to 15L, 3 to 4L)) {
            val fields = RenderStats.fields(callbacks = calls, pacedSkips = paced, missedVsync = 0, windowMillis = 1_000, p50Ms = null, p95Ms = null, maxMs = null)
            assertEquals(fields["drawn"], RenderStats.drawn(calls, paced), "$calls calls, $paced paced")
        }
    }

    @Test
    fun `the fields keep the camera line's order, the frames drawn after the calls`() {
        val keys = RenderStats.fields(callbacks = 1, pacedSkips = 0, missedVsync = 0, windowMillis = 1_000, p50Ms = 1.0, p95Ms = 1.0, maxMs = 1.0).keys.toList()
        assertEquals(listOf("frames", "fps", "drawn", "drawnFps", "missedVsync", "p50Ms", "p95Ms", "maxMs"), keys)
    }
}
