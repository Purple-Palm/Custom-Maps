package io.github.purplepalm.custommaps.nav.replay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a simulated drive is, for its replay slider. [seconds] and [durationSeconds] are on the
 * replay's own clock, which runs from the first position the replay still holds to the end of the
 * route. [metres] is how far along the route the navigator had the car at its latest update; null
 * before the first. While a seek is under way ([seeking]), [seconds] is where the seek goes.
 */
data class ReplayPosition(
    val seconds: Double,
    val durationSeconds: Double,
    val metres: Double? = null,
    val seeking: Boolean = false,
) {
    /** How much of the replay has played, from 0 to 1: the amber part of the slider. 0 while it holds nothing. */
    val fraction: Double
        get() = if (durationSeconds > 0.0) (seconds / durationSeconds).coerceIn(0.0, 1.0) else 0.0

    /** [fraction] in whole percent, as the slider says it to TalkBack. */
    val percent: Int get() = (fraction * PERCENT).roundToInt()

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * Where a release of the replay slider takes a simulated drive: forward or back along its route,
 * never past the last stretch before the destination. Pure; the engine carries the answer out.
 *
 * Back is as good as forward. The engine has the navigator forget where the car was before each
 * seek, as Mapbox asks for any large change of position, so the navigator finds the car on the
 * route afresh at its new place, whichever way it went.
 */
object ReplaySeek {

    /** A release this close to where the drive is moves nothing: a tap on the dot, or a slip of the finger. */
    const val MIN_MOVE_SECONDS = 2.0

    /**
     * How far before the end of the route a seek goes at the most. The replay then drives the last
     * stretch itself, and the navigator arrives at the destination as on any drive.
     */
    const val END_MARGIN_SECONDS = 5.0

    /** Why a release moves nothing; [logName] is how the debug log names it. */
    enum class Why(val logName: String) {
        /** No simulated drive runs, or its replay holds no positions yet. */
        NO_REPLAY("no_replay"),

        /** The navigator is changing the route; the replay is about to start over on the new one. */
        REROUTE("reroute"),

        /** The drive is in its last stretch, or what the replay holds is no longer than it. */
        END("end"),

        /** The release is within [MIN_MOVE_SECONDS] of where the drive is. */
        NEAR("near"),

        /** A newer release came before this one was carried out, and goes instead. */
        REPLACED("replaced"),

        /** The drive ended, or the route changed, before the seek was carried out. */
        ENDED("ended"),

        /** The navigator or the replayer would not do it. */
        REFUSED("refused"),
    }

    /** What a release asks for. */
    sealed interface Answer {
        /** Go to [toSeconds], counted as [ReplayPosition.seconds] is. */
        data class Go(val toSeconds: Double) : Answer

        /** Stay where the drive is, for [why]. */
        data class Stay(val why: Why) : Answer
    }

    /**
     * @param position where the drive is; null when no simulated drive runs
     * @param fraction where the finger let go, from 0 at the start of the slider to 1 at its end
     * @param rerouting whether the navigator is fetching or putting in place a new route
     */
    fun answer(position: ReplayPosition?, fraction: Double, rerouting: Boolean): Answer {
        if (position == null || position.durationSeconds <= 0.0) return Answer.Stay(Why.NO_REPLAY)
        if (rerouting) return Answer.Stay(Why.REROUTE)
        val latest = position.durationSeconds - END_MARGIN_SECONDS
        if (latest <= 0.0) return Answer.Stay(Why.END)
        if (fraction.isNaN()) return Answer.Stay(Why.NEAR)
        val to = (fraction.coerceIn(0.0, 1.0) * position.durationSeconds).coerceAtMost(latest)
        // Asked for the end while already in the last stretch: the drive gets there by itself.
        if (to == latest && position.seconds >= latest) return Answer.Stay(Why.END)
        if (abs(to - position.seconds) < MIN_MOVE_SECONDS) return Answer.Stay(Why.NEAR)
        return Answer.Go(to)
    }
}
