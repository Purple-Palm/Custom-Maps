package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot

/**
 * What the Then chip remembers from one progress update of a drive to the next: the state of
 * [ThenRule], and the following maneuver it last handed out. The engine keeps one and asks it once
 * per update. Not thread-safe: the updates of a drive arrive on one thread.
 */
class ThenTracker {

    private var state = ThenRule.State.NONE
    private var following: FollowingManeuver? = null

    /** A new drive knows nothing of the last one. */
    fun reset() {
        state = ThenRule.State.NONE
        following = null
    }

    /**
     * [fresh] with the rule's answer for this update, and nothing else changed.
     *
     * The following maneuver is the same object for as long as it is the same maneuver, so a screen
     * that compares by identity can skip the chip on the updates that change nothing in it.
     */
    fun decide(fresh: RouteProgressSnapshot): RouteProgressSnapshot {
        state = ThenRule.reduce(state, fresh)
        if (fresh.following != following) following = fresh.following
        return fresh.copy(following = following, showThen = state.shown)
    }
}
