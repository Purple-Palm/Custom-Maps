package io.github.purplepalm.custommaps.engine.mapbox.car

import android.graphics.Rect
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * The host's calls to the car's surface, passed on to [inner], the map library's own callback. Each
 * one is caught and handed to [failed] with its name: car-app rethrows an exception of a surface
 * callback on the main thread, and the car, the phone and the drive share one process, so it would
 * end the phone's guidance too. Once [isReleased] says so, nothing is passed on: the host may still
 * call while its session ends.
 *
 * It keeps the surface the host gave last and has not destroyed since ([shown]), so that the map
 * library can make its map again on it ([replay]), as for a new car map size. Main thread, as the
 * host calls it.
 */
internal class GuardedSurfaceCallback(
    private val inner: SurfaceCallback,
    private val isReleased: () -> Boolean,
    private val failed: (call: String, error: RuntimeException) -> Unit,
) : SurfaceCallback {

    /** The surface the map library was handed last and the host has not destroyed since; null without one. */
    var shown: SurfaceContainer? = null
        private set

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = pass("onSurfaceAvailable") {
        shown = surfaceContainer
        inner.onSurfaceAvailable(surfaceContainer)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = pass("onVisibleAreaChanged") { inner.onVisibleAreaChanged(visibleArea) }

    override fun onStableAreaChanged(stableArea: Rect) = pass("onStableAreaChanged") { inner.onStableAreaChanged(stableArea) }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = pass("onSurfaceDestroyed") {
        shown = null
        inner.onSurfaceDestroyed(surfaceContainer)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) = pass("onScroll") { inner.onScroll(distanceX, distanceY) }

    override fun onFling(velocityX: Float, velocityY: Float) = pass("onFling") { inner.onFling(velocityX, velocityY) }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = pass("onScale") { inner.onScale(focusX, focusY, scaleFactor) }

    override fun onClick(x: Float, y: Float) = pass("onClick") { inner.onClick(x, y) }

    /**
     * The map library makes its map again on the surface there is ([shown]), as if the host had destroyed the surface
     * and given it again: first the library hears that it was destroyed, which takes the old map down and lets go of its
     * window, then that [renew]'s container is available, which makes the new map on it. [renew] gives the same surface
     * in a container of its own, since the old map releases the Surface object it was given as it goes; null when there
     * is none to give, and then nothing is passed on. Whether both calls went through; false at once without a surface
     * and after the release. What either call throws goes to [failed] with its name, as for the host's own calls, and
     * the second is made also when the first threw, so that the library is not left without a map.
     */
    fun replay(renew: (SurfaceContainer) -> SurfaceContainer?): Boolean {
        if (isReleased()) return false
        val current = shown ?: return false
        val fresh = try {
            renew(current)
        } catch (e: RuntimeException) {
            failed(CALL_RENEW, e)
            null
        } ?: return false
        val destroyed = attempt(CALL_REPLAY_DESTROYED) { inner.onSurfaceDestroyed(current) }
        shown = fresh
        val available = attempt(CALL_REPLAY_AVAILABLE) { inner.onSurfaceAvailable(fresh) }
        return destroyed && available
    }

    private inline fun pass(call: String, block: () -> Unit) {
        attempt(call, block)
    }

    /** Runs [block] unless released; whether it returned without an exception, which goes to [failed] with [call]. */
    private inline fun attempt(call: String, block: () -> Unit): Boolean {
        if (isReleased()) return false
        return try {
            block()
            true
        } catch (e: RuntimeException) {
            failed(call, e)
            false
        }
    }

    companion object {
        /** The names a remake's calls go to [failed] with. */
        const val CALL_RENEW = "renewSurface"
        const val CALL_REPLAY_DESTROYED = "replaySurfaceDestroyed"
        const val CALL_REPLAY_AVAILABLE = "replaySurfaceAvailable"
    }
}
