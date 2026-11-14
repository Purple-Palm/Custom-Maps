package io.github.purplepalm.custommaps.nav.sound

/**
 * One sound at a time. A sound asked for while another still plays waits for its end if it is the pair
 * or the start chime, which have to be heard; the one tone is dropped, since it only says that something
 * comes. A newer pair or chime takes the place of one that waits. Pure; the times are the caller's clock.
 */
object TonePacing {

    /** Room after a sound's end before the next one starts, so two sounds never run into each other. */
    const val AFTER_MILLIS = 300

    /**
     * Until when the sound playing now keeps the player busy, and the sound that waits for it, with its
     * length; nothing waits in [IDLE].
     */
    data class State(val busyUntilMillis: Long = 0, val held: ToneKind? = null, val heldLengthMillis: Int = 0) {
        companion object {
            val IDLE = State()
        }
    }

    enum class Verdict { PLAY, HOLD, DROP }

    /**
     * A sound [lengthMillis] long is asked for at [nowMillis]: play it now, hold it until the one playing
     * has ended, or drop it. One that waits goes first: while one waits, a newer one is held in its place
     * or dropped.
     */
    fun offer(state: State, kind: ToneKind, lengthMillis: Int, nowMillis: Long): Pair<State, Verdict> {
        val busy = nowMillis < state.busyUntilMillis || state.held != null
        if (!busy) return State(busyUntilMillis = nowMillis + lengthMillis + AFTER_MILLIS) to Verdict.PLAY
        if (kind == ToneKind.AHEAD) return state to Verdict.DROP
        return state.copy(held = kind, heldLengthMillis = lengthMillis) to Verdict.HOLD
    }

    /** The sound that waited, to play now that the one before has ended; null while it plays on, or when none waits. */
    fun due(state: State, nowMillis: Long): Pair<State, ToneKind?> {
        val held = state.held ?: return state to null
        if (nowMillis < state.busyUntilMillis) return state to null
        return State(busyUntilMillis = nowMillis + state.heldLengthMillis + AFTER_MILLIS) to held
    }

    /** Guidance ended: nothing waits any more. A sound that plays now plays to its end. */
    fun ended(state: State): State = state.copy(held = null, heldLengthMillis = 0)
}
