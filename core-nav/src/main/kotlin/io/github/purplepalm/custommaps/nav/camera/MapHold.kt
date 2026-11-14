package io.github.purplepalm.custommaps.nav.camera

import kotlin.math.ceil
import kotlin.math.min

/**
 * The map the driver holds during guidance: from the moment a gesture or the compass takes it from the
 * camera until it goes back, when its count runs out, on a tap on Recenter, or at the end of a look.
 * Every call takes the time, so tests drive the clock.
 *
 * - The count runs from the later of the last finger lifted and the last start or end of a camera move
 *   of the driver's own (a fling goes on after the finger), for the seconds of Recenter by itself; while
 *   a point is near ([onNear]) for at most [DriveViewTuning.recenterNearSeconds].
 * - While a finger is on the map it stands at its full value. A finger reported down with nothing
 *   moving for [DriveViewTuning.staleFingerSeconds] counts as lifted then: a lost lift cannot keep the
 *   map for ever.
 * - With Recenter by itself off (0 s) a hold never ends by itself.
 * - A look ([Kind.LOOK]) shows the overview for [DriveViewTuning.lookSeconds], whatever Recenter by
 *   itself is set to; a gesture during it turns it into an ordinary hold.
 */
class MapHold(private val tuning: DriveViewTuning = DriveViewTuning.PHONE) {

    enum class Kind {
        NONE,

        /** A gesture or the compass took the map. */
        MAP_MOVED,

        /** The driver asked for a look at the overview. */
        LOOK,
    }

    var kind = Kind.NONE
        private set

    /** When the hold began; not a number without one. */
    var since = Double.NaN
        private set

    /** How often a new touch started the count again during this hold, for the log. */
    var restarts = 0
        private set

    /** The last sign of the driver: a finger down or lifted, a camera move of the driver's own. */
    private var lastActivity = Double.NaN
    private var fingerDown = false
    private var near = false
    private var lookUntil = Double.NaN

    /** A gesture or the compass took the map from the camera: a hold begins, or a look becomes an ordinary hold. */
    fun onTaken(nowSeconds: Double) {
        if (kind == Kind.NONE) {
            since = nowSeconds
            restarts = 0
        }
        kind = Kind.MAP_MOVED
        lastActivity = nowSeconds
    }

    /** A look at the overview begins, a hold of its own. */
    fun onLook(nowSeconds: Double) {
        kind = Kind.LOOK
        since = nowSeconds
        restarts = 0
        lookUntil = nowSeconds + tuning.lookSeconds
    }

    /** The first finger went down on the map, or the last one came up. Heard also before a hold begins. */
    fun onFinger(down: Boolean, nowSeconds: Double) {
        if (down && !fingerDown && kind != Kind.NONE) restarts += 1
        fingerDown = down
        lastActivity = nowSeconds
    }

    /** A camera move of the driver's own started or ended. Called for every animator of a drag: one test, one assignment. */
    fun onMapMoved(nowSeconds: Double) {
        if (kind != Kind.NONE) lastActivity = nowSeconds
    }

    /** Whether a point is near now ([DriveViewPlanner.near]): the count is then at most [DriveViewTuning.recenterNearSeconds]. */
    fun onNear(near: Boolean) {
        this.near = near
    }

    /** Whole seconds until the map goes back by itself; null without a hold, and for a moved map while Recenter by itself is off. */
    fun secondsLeft(nowSeconds: Double, recenterSeconds: Int): Int? = when (kind) {
        Kind.NONE -> null
        Kind.LOOK -> wholeSeconds(lookUntil - nowSeconds)
        Kind.MAP_MOVED -> if (recenterSeconds <= 0) {
            null
        } else {
            val from = countFrom(nowSeconds)
            wholeSeconds(if (from == null) countSeconds(recenterSeconds) else from + countSeconds(recenterSeconds) - nowSeconds)
        }
    }

    /** Whether the hold is over by itself: its count ran out. Never for a moved map while Recenter by itself is off. */
    fun isOver(nowSeconds: Double, recenterSeconds: Int): Boolean = when (kind) {
        Kind.NONE -> false
        Kind.LOOK -> nowSeconds >= lookUntil
        Kind.MAP_MOVED -> {
            val from = countFrom(nowSeconds)
            recenterSeconds > 0 && from != null && nowSeconds >= from + countSeconds(recenterSeconds)
        }
    }

    /** The hold ended; a finger still on the map stays where it is. */
    fun release() {
        kind = Kind.NONE
        since = Double.NaN
        restarts = 0
        lookUntil = Double.NaN
    }

    private fun countSeconds(recenterSeconds: Int): Double =
        if (near) min(recenterSeconds.toDouble(), tuning.recenterNearSeconds) else recenterSeconds.toDouble()

    /** Where the count runs from; null while a finger holds it. */
    private fun countFrom(nowSeconds: Double): Double? {
        if (!fingerDown) return lastActivity
        val lifted = lastActivity + tuning.staleFingerSeconds
        return if (nowSeconds >= lifted) lifted else null
    }

    private fun wholeSeconds(seconds: Double): Int = ceil(seconds - WHOLE_SLACK).toInt().coerceAtLeast(0)

    private companion object {
        /** Ten whole seconds stay ten when the clock's sum is a hair above them. */
        const val WHOLE_SLACK = 1e-6
    }
}
