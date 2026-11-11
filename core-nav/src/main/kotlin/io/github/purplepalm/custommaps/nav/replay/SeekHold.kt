package io.github.purplepalm.custommaps.nav.replay

import io.github.purplepalm.custommaps.nav.guidance.FixTime
import io.github.purplepalm.custommaps.nav.guidance.StaleStart

/**
 * Tells the navigator's updates that carry a simulated drive's new place, after a seek, from those
 * still on their way from the place the drive left.
 *
 * From the moment a seek is asked for ([ask]) until the replay plays from the new place
 * ([resume]), the navigator has nothing new to say: whatever it sends meanwhile is held, as the
 * last of the old place or as the time when the navigator, told to forget where the car was,
 * knows no position. After that the first position the replay hands the trip session marks the
 * new place ([onSourceFix]): until it has come every update is held, and from then on only one
 * whose position was measured before it, by the rule of [StaleStart]. A held update is left alone
 * by the drawn car, the camera, the card and its Then chip, and the lane choices.
 *
 * The first update after a seek that is not held is where the car jumped to. Each part of the app
 * that cares tells it by [seeks], which counts the seeks carried out.
 *
 * Main thread only, like the navigator's callbacks.
 */
class SeekHold {

    private var asked = false
    private var resumed = false
    private var first: FixTime? = null

    /** How many seeks have moved a drive so far in this run of the app. */
    var seeks = 0
        private set

    /** A seek is asked for: every update is held until it is carried out ([resume]) or given up ([cancel]). */
    fun ask() {
        asked = true
        resumed = false
        first = null
    }

    /** The replay plays from the new place: updates are held until its first position has come in. */
    fun resume() {
        asked = false
        resumed = true
        first = null
        seeks += 1
    }

    /** The seek was given up, and the drive goes on from where it was: nothing is held. */
    fun cancel() {
        asked = false
        resumed = false
        first = null
    }

    /** The trip session handed the navigator a position from the replay. Only the first after [resume] counts. */
    fun onSourceFix(time: FixTime) {
        if (resumed && first == null) first = time
    }

    /**
     * Whether an update with the matched position [fix] is held: every one while a seek waits to be
     * carried out, and after it every one until the new place's first position, then only one
     * measured before that. An update without a position is held only while the first is awaited.
     */
    fun isHeld(fix: FixTime?): Boolean {
        if (asked) return true
        if (!resumed) return false
        val first = first ?: return true
        return fix != null && StaleStart.measuredBefore(fix, first)
    }

    /** The drive ended: nothing is held any more. */
    fun end() = cancel()
}
