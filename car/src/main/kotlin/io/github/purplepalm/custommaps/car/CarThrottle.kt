package io.github.purplepalm.custommaps.car

/**
 * When the car hears of a progress update: at once when what the card shows changes other than its
 * numbers (the maneuver, the step after it, the lane row, loading), otherwise at most once
 * [intervalMillis]; an update held back goes out as soon as its interval is over, with the newest
 * content then. So the card keeps up with the navigator's update a second, and a burst of updates
 * costs the host nothing more. Times in milliseconds of a monotonic clock; plain state, main thread.
 */
class CarThrottle(private val intervalMillis: Long = INTERVAL_MILLIS) {

    /** When an update goes out. */
    sealed interface Decision {
        data object Now : Decision

        /** At [atMillis], unless a newer update goes out before. */
        data class At(val atMillis: Long) : Decision
    }

    private var sentAt: Long? = null
    private var sentKey: Any? = null

    /** When an update showing [key] at [nowMillis] goes out. */
    fun offer(nowMillis: Long, key: Any): Decision {
        val last = sentAt ?: return Decision.Now
        if (key != sentKey || nowMillis - last >= intervalMillis || nowMillis < last) return Decision.Now
        return Decision.At(last + intervalMillis)
    }

    /** An update showing [key] went out at [nowMillis]. */
    fun sent(nowMillis: Long, key: Any) {
        sentAt = nowMillis
        sentKey = key
    }

    /** The drive is over: the next one's first update goes out at once. */
    fun reset() {
        sentAt = null
        sentKey = null
    }

    companion object {
        const val INTERVAL_MILLIS = 1_000L
    }
}
