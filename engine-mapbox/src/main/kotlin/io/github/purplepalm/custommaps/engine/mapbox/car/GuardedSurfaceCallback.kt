package io.github.purplepalm.custommaps.engine.mapbox.car

import android.graphics.Rect
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * The host's calls to the car's surface, passed on to [inner], the map library's own callback. Each
 * one is caught and handed to [failed] with its name: car-app rethrows an exception of a surface
 * callback on the main thread, and the car, the phone and the drive share one process, so it would
 * end the phone's guidance too. Once [isReleased] says so, nothing is passed on: the host may still
 * call while its session ends. Main thread, as the host calls it.
 */
internal class GuardedSurfaceCallback(
    private val inner: SurfaceCallback,
    private val isReleased: () -> Boolean,
    private val failed: (call: String, error: RuntimeException) -> Unit,
) : SurfaceCallback {

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = pass("onSurfaceAvailable") { inner.onSurfaceAvailable(surfaceContainer) }

    override fun onVisibleAreaChanged(visibleArea: Rect) = pass("onVisibleAreaChanged") { inner.onVisibleAreaChanged(visibleArea) }

    override fun onStableAreaChanged(stableArea: Rect) = pass("onStableAreaChanged") { inner.onStableAreaChanged(stableArea) }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = pass("onSurfaceDestroyed") { inner.onSurfaceDestroyed(surfaceContainer) }

    override fun onScroll(distanceX: Float, distanceY: Float) = pass("onScroll") { inner.onScroll(distanceX, distanceY) }

    override fun onFling(velocityX: Float, velocityY: Float) = pass("onFling") { inner.onFling(velocityX, velocityY) }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = pass("onScale") { inner.onScale(focusX, focusY, scaleFactor) }

    override fun onClick(x: Float, y: Float) = pass("onClick") { inner.onClick(x, y) }

    private inline fun pass(call: String, block: () -> Unit) {
        if (isReleased()) return
        try {
            block()
        } catch (e: RuntimeException) {
            failed(call, e)
        }
    }
}
