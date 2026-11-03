package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.model.CameraMode
import kotlinx.coroutines.flow.StateFlow

/** Draws the map and the route and moves the camera, on the phone or in the car. */
interface MapRenderer {
    val cameraMode: StateFlow<CameraMode>

    fun setCameraMode(mode: CameraMode)

    /** Screen area covered by UI, which the camera must keep the route out of. */
    fun setViewportPadding(padding: ViewportPadding)
}

/** Insets in pixels from each edge of the map surface. */
data class ViewportPadding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) { "Padding must not be negative: $this" }
    }

    companion object {
        val NONE = ViewportPadding(0, 0, 0, 0)
    }
}
