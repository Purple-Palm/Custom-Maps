package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.geo.Geo
import kotlin.math.abs
import kotlin.math.round

/*
 * Measurements of how the map moves, for the once-a-second camera line of the debug log: they tell
 * smooth motion (a small change every frame) from steps (long pauses, then jumps).
 */

/**
 * How often something happened during a window, such as a camera change or a puck move, and the
 * longest pause between two of them. Times are monotonic nanoseconds. Not thread-safe: use it
 * from one thread.
 */
class Cadence {

    private var count = 0
    private var last = NEVER
    private var longestGap = 0L

    fun tick(nowNanos: Long) {
        if (last != NEVER) longestGap = maxOf(longestGap, nowNanos - last)
        last = nowNanos
        count += 1
    }

    /**
     * Ends the window at [nowNanos] and starts the next one. A pause still going on at the end of
     * the window counts as a gap; it is counted again, in full, in the window where it ends.
     */
    fun take(nowNanos: Long): Window {
        val window = Window(
            count = count,
            longestGapMillis = if (last == NEVER) null else maxOf(longestGap, nowNanos - last) / NANOS_PER_MILLI,
        )
        count = 0
        longestGap = 0L
        return window
    }

    /**
     * Forgets every tick, for a new measuring period, such as the next guidance: the first tick
     * after this has no gap before it.
     */
    fun reset() {
        count = 0
        longestGap = 0L
        last = NEVER
    }

    /** [longestGapMillis] is null until the first tick since the cadence was made or [reset]. */
    data class Window(val count: Int, val longestGapMillis: Long?)

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The largest change of the camera from one frame to the next during a window: how far the center
 * moved and how much zoom, bearing and pitch changed. A smooth camera changes a little every frame;
 * a jump shows up as one large step. Not thread-safe: use it from one thread.
 */
class CameraSteps {

    private var hasLast = false
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    private var lastZoom = 0.0
    private var lastBearing = 0.0
    private var lastPitch = 0.0

    private var meters = 0.0
    private var zoom = 0.0
    private var bearing = 0.0
    private var pitch = 0.0

    fun onCamera(latitude: Double, longitude: Double, zoom: Double, bearing: Double, pitch: Double) {
        if (hasLast) {
            meters = maxOf(meters, Geo.meters(lastLatitude, lastLongitude, latitude, longitude))
            this.zoom = maxOf(this.zoom, abs(zoom - lastZoom))
            this.bearing = maxOf(this.bearing, Motion.bearingDifference(lastBearing, bearing))
            this.pitch = maxOf(this.pitch, abs(pitch - lastPitch))
        }
        hasLast = true
        lastLatitude = latitude
        lastLongitude = longitude
        lastZoom = zoom
        lastBearing = bearing
        lastPitch = pitch
    }

    /** The largest steps since the last call, rounded for the log; starts the next window. */
    fun take(): Map<String, Any?> {
        val steps = mapOf(
            "m" to Motion.round(meters, 2),
            "zoom" to Motion.round(zoom, 3),
            "bearing" to Motion.round(bearing, 2),
            "pitch" to Motion.round(pitch, 2),
        )
        clearSteps()
        return steps
    }

    /**
     * Forgets the last frame and the largest steps, for a new measuring period, such as the next
     * guidance: the first frame after this is not compared with any before it.
     */
    fun reset() {
        hasLast = false
        clearSteps()
    }

    private fun clearSteps() {
        meters = 0.0
        zoom = 0.0
        bearing = 0.0
        pitch = 0.0
    }
}

/** Small helpers for the numbers of the camera line; distances come from [Geo.meters]. */
object Motion {

    /** The smaller angle between two bearings, 0 to 180 degrees. */
    fun bearingDifference(from: Double, to: Double): Double {
        val difference = abs(to - from) % 360.0
        return if (difference > 180.0) 360.0 - difference else difference
    }

    /** [value] rounded to [decimals] places, so log lines stay short. NaN and infinity stay as they are. */
    fun round(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return value
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return round(value * scale) / scale
    }
}
