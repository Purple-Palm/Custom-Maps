package io.github.purplepalm.custommaps.engine.mapbox.car

import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's surface callback keeps every exception of the map library inside: car-app would rethrow
 * it on the main thread and end the phone's guidance with it. An invented surface of 800 by 480.
 */
class GuardedSurfaceCallbackTest {

    private val passed = mutableListOf<String>()
    private val failures = mutableListOf<Pair<String, String?>>()
    private var released = false
    private var throwing = false

    private val inner = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = record("available ${surfaceContainer.width}x${surfaceContainer.height}")

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = record("destroyed")

        override fun onScroll(distanceX: Float, distanceY: Float) = record("scroll")

        override fun onFling(velocityX: Float, velocityY: Float) = record("fling")

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = record("scale")

        override fun onClick(x: Float, y: Float) = record("click")

        private fun record(call: String) {
            if (throwing) throw IllegalStateException("$call failed")
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
}
