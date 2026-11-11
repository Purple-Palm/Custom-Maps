package io.github.purplepalm.custommaps.nav.guidance

/**
 * When a position was measured, on the two clocks it can carry: [elapsedNanos], Android's elapsed
 * realtime in nanoseconds, null or zero when the source gave none, and [wallMillis], the wall clock
 * in milliseconds. [replayed] marks a position played by a simulated drive, whose replayer stamps
 * it on both clocks about 100 s ahead of the phone's: it can seem measured after a later position
 * of the phone's own. Positions of a mock location app carry the same mark.
 */
data class FixTime(val elapsedNanos: Long?, val wallMillis: Long, val replayed: Boolean = false)

/**
 * Tells the first updates of a trip that still carry where the navigator had the car before the
 * trip began from those that carry a position of the trip itself.
 *
 * The navigator keeps its last position from one trip to the next, and a new trip session reports
 * an update or two with it before the trip's own first position has come in: as far from the car
 * as the last trip ended. Whatever reacts to the first updates of a trip (the camera, the drawn
 * car, the maneuver card and its Then chip, the lane choices, the refresh of the start plan)
 * leaves such an update alone.
 *
 * Which updates are the trip's own is told by when their positions were measured and whether they
 * were replayed, never by where they are. Before the trip session has handed the navigator a
 * position of its own ([onSourceFix]) every update carries the old one. From then on an update
 * carries the old one when its position was measured before that first position, by more than
 * [SLACK_MILLIS], or when it was replayed while the trip session's newest position was not: the
 * last position of a simulated drive is stamped ahead of the phone's clock, and can seem measured
 * after the first position of a real trip started soon after the drive. Any other update is the
 * trip's. The first position may itself be older than the trip, as the last one the phone knew:
 * it is still where the phone has the car. In a simulated drive every position is replayed and
 * they are compared on the replayer's clock; with a mock location app every position carries the
 * same mark, and only the times count.
 *
 * Main thread only, like the navigator's callbacks.
 */
class StaleStart {

    private var running = false
    private var first: FixTime? = null

    /** Whether the newest position the trip session handed the navigator was replayed. */
    private var sourceReplayed = false

    /** How many updates of the trip were left alone so far; see [countStaleUpdate]. */
    var staleUpdates = 0
        private set

    /** A trip session is about to start: nothing of an earlier trip counts as this one's. */
    fun begin() {
        running = true
        first = null
        sourceReplayed = false
        staleUpdates = 0
    }

    /**
     * The trip session handed the navigator a position from its own source. The first one is the
     * mark the times are compared with; the newest says whether the source replays.
     */
    fun onSourceFix(time: FixTime) {
        if (!running) return
        if (first == null) first = time
        sourceReplayed = time.replayed
    }

    /**
     * Whether a matched position, [fix], of an update is the one from before this trip: true before
     * the trip's first position whatever the update carries; then true for a position measured
     * before the trip's first one, and for a replayed one while the trip session's newest position
     * is not replayed. False outside a trip, and for an update without a position once the trip's
     * own positions have begun.
     */
    fun isStale(fix: FixTime?): Boolean {
        if (!running) return false
        val first = first ?: return true
        if (fix == null) return false
        return (fix.replayed && !sourceReplayed) || measuredBefore(fix, first)
    }

    /** Notes an update that was left alone because [isStale] said so. */
    fun countStaleUpdate() {
        staleUpdates += 1
    }

    /** The trip ended: from now on nothing is stale. */
    fun end() {
        running = false
        first = null
        sourceReplayed = false
    }

    companion object {
        /**
         * How much earlier than the trip's first position a position may have been measured and
         * still count as the trip's: rounding, and a source that hands over two positions a moment
         * apart. The last position of an earlier real trip is many seconds older: a trip ends, a
         * new one is planned, and only then is it started. That of a simulated drive is stamped
         * ahead of the phone's clock, and before a real trip it is told by its mark instead.
         */
        const val SLACK_MILLIS = 1_000L

        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Whether [fix] was measured before [first] by more than [SLACK_MILLIS]: on the elapsed
         * realtime when both have it, otherwise on the wall clock.
         */
        fun measuredBefore(fix: FixTime, first: FixTime): Boolean {
            val fixNanos = fix.elapsedNanos?.takeIf { it > 0L }
            val firstNanos = first.elapsedNanos?.takeIf { it > 0L }
            return if (fixNanos != null && firstNanos != null) {
                fixNanos < firstNanos - SLACK_MILLIS * NANOS_PER_MILLI
            } else {
                fix.wallMillis < first.wallMillis - SLACK_MILLIS
            }
        }
    }
}
