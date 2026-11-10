package io.github.purplepalm.custommaps.nav.camera

/**
 * For a camera that wants one new frame for each update of the navigator: says which of the
 * update's two messages the frame is computed on.
 *
 * The navigator sends the location and, right after it, the route progress of the same update. The
 * frame is computed on the progress, from both, so the location normally computes nothing. But the
 * navigator sends no progress while it replaces its routes. The location then computes the frame
 * itself, once the last progress is older than [waitMillis]; without that the camera would stand
 * still through every reroute. A jump of the matched position cannot wait either.
 *
 * Times are monotonic milliseconds.
 */
class OncePerUpdate(private val waitMillis: Long = PROGRESS_WAIT_MILLIS) {

    private var progressAt = NEVER

    /** A route progress came, and the frame was computed on it. */
    fun onProgress(nowMillis: Long) {
        progressAt = nowMillis
    }

    /** Whether the location that came at [nowMillis] has to compute the frame itself. */
    fun locationComputes(nowMillis: Long, isTeleport: Boolean): Boolean =
        isTeleport || progressAt == NEVER || nowMillis - progressAt > waitMillis

    companion object {
        /**
         * Longer than the updates are apart, also when one is late: a progress that has not come by
         * then is not coming.
         */
        const val PROGRESS_WAIT_MILLIS = 1_500L

        private const val NEVER = Long.MIN_VALUE
    }
}
