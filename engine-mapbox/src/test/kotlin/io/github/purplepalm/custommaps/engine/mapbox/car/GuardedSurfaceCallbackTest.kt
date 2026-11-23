package io.github.purplepalm.custommaps.engine.mapbox.car

import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's surface callback keeps every exception of the map library inside: car-app would rethrow
 * it on the main thread and end the phone's guidance with it; and it hands the library the host's
 * surface again for a new map, destroyed first and then available. Invented surfaces of 800 by 480
 * and 800 by 400, without a Surface object, which the JVM has none of.
 */
class GuardedSurfaceCallbackTest {

    private val passed = mutableListOf<String>()
    private val failures = mutableListOf<Pair<String, String?>>()
    private var released = false
    private var throwing = false

    /** The containers the library was handed, with each call's name. */
    private val handed = mutableListOf<Pair<String, SurfaceContainer>>()

    /** The calls that throw, by their first word. */
    private val throwingCalls = mutableSetOf<String>()

    private val inner = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            handed += "available" to surfaceContainer
            record("available ${surfaceContainer.width}x${surfaceContainer.height}")
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            handed += "destroyed" to surfaceContainer
            record("destroyed")
        }

        override fun onScroll(distanceX: Float, distanceY: Float) = record("scroll")

        override fun onFling(velocityX: Float, velocityY: Float) = record("fling")

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = record("scale")

        override fun onClick(x: Float, y: Float) = record("click")

        private fun record(call: String) {
            if (throwing || call.substringBefore(' ') in throwingCalls) throw IllegalStateException("$call failed")
            passed += call
        }
    }

    private val guarded = GuardedSurfaceCallback(inner, isReleased = { released }) { call, error -> failures += call to error.message }

    private val container = SurfaceContainer(null, 800, 480, 160)

    @Test
    fun `every call of the host reaches the map library`() {
        guarded.onSurfaceAvailable(container)
        guarded.onScroll(1f, 2f)
        guarded.onFling(3f, 4f)
        guarded.onScale(5f, 6f, 1.1f)
        guarded.onClick(7f, 8f)
        guarded.onSurfaceDestroyed(container)
        assertEquals(listOf("available 800x480", "scroll", "fling", "scale", "click", "destroyed"), passed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an exception is reported with its call and goes no further`() {
        throwing = true
        guarded.onSurfaceAvailable(container)
        guarded.onScale(5f, 6f, 1.1f)
        assertEquals(
            listOf("onSurfaceAvailable" to "available 800x480 failed", "onScale" to "scale failed"),
            failures,
        )
        assertTrue(passed.isEmpty())
    }

    @Test
    fun `after the release nothing is passed on`() {
        released = true
        guarded.onSurfaceAvailable(container)
        guarded.onScroll(1f, 2f)
        guarded.onSurfaceDestroyed(container)
        assertTrue(passed.isEmpty())
        assertTrue(failures.isEmpty())
    }

    /** The host's surface again in a container of its own, as the car map makes it: here of the same size. */
    private val renewed = SurfaceContainer(null, 800, 480, 160)

    @Test
    fun `a replay tells the library the surface was destroyed, then hands it the renewed one`() {
        guarded.onSurfaceAvailable(container)
        handed.clear()
        passed.clear()
        var asked: SurfaceContainer? = null
        assertTrue(guarded.replay { asked = it; renewed })
        assertSame(container, asked, "renewed from the surface the library has")
        assertEquals(listOf("destroyed" to container, "available" to renewed), handed)
        assertEquals(listOf("destroyed", "available 800x480"), passed)
        assertSame(renewed, guarded.shown, "the next replay starts from the one the library has now")
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `the surface the host gives is kept until the host destroys it, and a new one takes its place`() {
        assertNull(guarded.shown)
        guarded.onSurfaceAvailable(container)
        assertSame(container, guarded.shown)
        val other = SurfaceContainer(null, 800, 400, 160)
        guarded.onSurfaceAvailable(other)
        assertSame(other, guarded.shown)
        assertTrue(guarded.replay { renewed })
        // The host's own call after a replay goes through as ever, and leaves nothing to replay.
        guarded.onSurfaceDestroyed(other)
        assertNull(guarded.shown)
        assertEquals(listOf("available", "available", "destroyed", "available", "destroyed"), handed.map { it.first })
    }

    @Test
    fun `without a surface, or with none to renew, a replay passes nothing on`() {
        var asked = 0
        assertFalse(guarded.replay { asked += 1; renewed }, "none given yet")
        guarded.onSurfaceAvailable(container)
        guarded.onSurfaceDestroyed(container)
        assertFalse(guarded.replay { asked += 1; renewed }, "destroyed by the host")
        assertEquals(0, asked, "nothing to renew")
        guarded.onSurfaceAvailable(container)
        handed.clear()
        assertFalse(guarded.replay { null }, "a surface no longer valid")
        assertEquals(emptyList<Pair<String, SurfaceContainer>>(), handed)
        assertSame(container, guarded.shown)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a renewal that throws is reported and passes nothing on`() {
        guarded.onSurfaceAvailable(container)
        handed.clear()
        assertFalse(guarded.replay { throw IllegalStateException("no parcel") })
        assertEquals(listOf("renewSurface" to "no parcel"), failures)
        assertEquals(emptyList<Pair<String, SurfaceContainer>>(), handed)
    }

    @Test
    fun `a destroy that throws is reported, and the renewed surface is still handed on`() {
        guarded.onSurfaceAvailable(container)
        handed.clear()
        throwingCalls += "destroyed"
        assertFalse(guarded.replay { renewed })
        assertEquals(listOf("replaySurfaceDestroyed" to "destroyed failed"), failures)
        assertEquals(listOf("destroyed" to container, "available" to renewed), handed)
        throwingCalls.clear()
        throwingCalls += "available"
        assertFalse(guarded.replay { renewed })
        assertEquals("replaySurfaceAvailable" to "available 800x480 failed", failures.last())
    }

    @Test
    fun `after the release a replay does nothing`() {
        guarded.onSurfaceAvailable(container)
        handed.clear()
        released = true
        var asked = 0
        assertFalse(guarded.replay { asked += 1; renewed })
        assertEquals(0, asked)
        assertEquals(emptyList<Pair<String, SurfaceContainer>>(), handed)
    }
}
