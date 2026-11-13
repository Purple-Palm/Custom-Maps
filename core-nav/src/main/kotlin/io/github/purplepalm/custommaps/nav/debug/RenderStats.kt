package io.github.purplepalm.custommaps.nav.debug

/**
 * The "render" object of the camera line: what a map's render thread did in one window of the
 * camera line, from the map library's render thread statistics.
 *
 * The render thread is called once for each display frame while the map has something to draw. With
 * a cap on the frame rate it lets some of those calls pass without drawing, on purpose ("paced"), so
 * the calls and the frames drawn are not the same: "frames" and "fps" count the calls, as the camera
 * line always has, and "drawn" and "drawnFps" the frames the map really drew. The map's own estimate
 * of its rate ("renderFps") is worked out against the display rate the library believes, which on
 * the car's surface is not the phone's.
 */
object RenderStats {

    /**
     * The fields of one window of [windowMillis]: [callbacks] calls of the render thread, of which
     * [pacedSkips] drew nothing because of the cap, [missedVsync] display frames missed because a
     * frame took too long, and the frame times' median, 95th percentile and longest, in milliseconds.
     */
    fun fields(
        callbacks: Int,
        pacedSkips: Long,
        missedVsync: Long,
        windowMillis: Long,
        p50Ms: Double?,
        p95Ms: Double?,
        maxMs: Double?,
    ): Map<String, Any?> {
        val millis = windowMillis.coerceAtLeast(1L)
        val drawn = (callbacks - pacedSkips).coerceAtLeast(0L)
        return mapOf(
            "frames" to callbacks,
            "fps" to Motion.round(callbacks * MILLIS_PER_SECOND / millis, 1),
            "drawn" to drawn,
            "drawnFps" to Motion.round(drawn * MILLIS_PER_SECOND / millis, 1),
            "missedVsync" to missedVsync,
            "p50Ms" to p50Ms?.let { Motion.round(it, 1) },
            "p95Ms" to p95Ms?.let { Motion.round(it, 1) },
            "maxMs" to maxMs?.let { Motion.round(it, 1) },
        )
    }

    private const val MILLIS_PER_SECOND = 1_000.0
}
