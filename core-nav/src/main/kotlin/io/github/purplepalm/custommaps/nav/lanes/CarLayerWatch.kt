package io.github.purplepalm.custommaps.nav.lanes

import kotlin.math.max

/**
 * The watchdog of the app's own car layer (the own lanes' design 5.4 and R20): with the drawing on, the navigation SDK's puck is
 * invisible during guidance and the app draws the car from the puck's animated position, which the map hands it every frame.
 * Whether those positions still come while the puck is invisible is not known on every phone, so the layer is watched against
 * the fixes the puck is given ([onFix]): a map whose positions come hands one on within a frame or two of each fix, whether
 * the puck glides to it (a position every frame) or, with Android's animations off, jumps to it (a position or two a fix).
 * A fix with no position after it for [NO_POSITION_NANOS], while the layer is [live], the map is on screen and drawing frames
 * and the car moves faster than [MIN_SPEED_MPS], trips it, and the map gives the car back to the SDK's puck for the rest of
 * the drive. So a fix that comes late never trips it, nor does a stretch without fixes, as in a tunnel, where the puck stands
 * still because nothing moves it. A car standing still, a screen turned off, another app in front or a map put away never
 * trips it either: the count starts again whenever it stops counting ([check]). Times are nanoseconds of one clock. Pure;
 * main thread only.
 */
class CarLayerWatch {

    /** When the puck was given the first fix that no position has followed yet; [Long.MIN_VALUE] while none waits. */
    private var waitingSince = Long.MIN_VALUE

    /** Since when the watch has counted without a break; [Long.MIN_VALUE] while it does not count. */
    private var countingSince = Long.MIN_VALUE

    /** The puck was given a fix at [nanos]: the map should hand a position on within a frame or two. */
    fun onFix(nanos: Long) {
        if (waitingSince == Long.MIN_VALUE) waitingSince = nanos
    }

    /** The map handed the layer the puck's position: no fix before it waits any more. */
    fun onPosition() {
        waitingSince = Long.MIN_VALUE
    }

    /**
     * The watch at [nanos], about once a second: true where the layer is to give the car back, as a fix has had no position
     * after it for [NO_POSITION_NANOS] of counting. It counts only while the layer is [live] (its drive's car drawn by it), the
     * map is [onScreen] and drawing frames, and [speedMps] is above [MIN_SPEED_MPS]; a break starts the count again.
     */
    fun check(nanos: Long, live: Boolean, onScreen: Boolean, speedMps: Double?): Boolean {
        val counts = live && onScreen && speedMps != null && speedMps > MIN_SPEED_MPS
        if (!counts) {
            countingSince = Long.MIN_VALUE
            return false
        }
        if (countingSince == Long.MIN_VALUE) countingSince = nanos
        if (waitingSince == Long.MIN_VALUE) return false
        return nanos - max(waitingSince, countingSince) >= NO_POSITION_NANOS
    }

    /** A new drive: nothing of the last one counts. */
    fun reset() {
        waitingSince = Long.MIN_VALUE
        countingSince = Long.MIN_VALUE
    }

    companion object {
        /** A fix with no position after it for this long, while the watch counts, trips it. */
        const val NO_POSITION_NANOS = 1_000_000_000L

        /** A car this slow or slower may stand with its puck at rest: the watch does not count. */
        const val MIN_SPEED_MPS = 2.0
    }
}
