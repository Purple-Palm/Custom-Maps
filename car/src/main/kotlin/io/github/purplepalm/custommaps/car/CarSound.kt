package io.github.purplepalm.custommaps.car

import java.util.IdentityHashMap

/**
 * What the car's sessions tell the app's one player of guidance tones, which serves the phone and the car alike:
 * which car sessions are connected, and which of them another navigation app has taken over. The car module plays
 * no sound of its own. Main thread, as the car's host calls the sessions.
 */
interface CarSound {
    /** [session] began, at its ON_CREATE ([connected] true), or ended, at its ON_DESTROY. */
    fun onSession(session: Any, connected: Boolean)

    /**
     * The car's host asked [session] to stop navigating, as when another navigation app starts ([yielded] true), or
     * the app navigates on the car's screen again: the driver took it back, or a new drive began there.
     */
    fun onYielded(session: Any, yielded: Boolean)

    companion object {
        /** Nothing hears of the car. */
        val NONE: CarSound = object : CarSound {
            override fun onSession(session: Any, connected: Boolean) = Unit

            override fun onYielded(session: Any, yielded: Boolean) = Unit
        }
    }
}

/**
 * [CarSound] for the player: it keeps the sessions that are connected and whether another app took each, and hands
 * the player two things at each change, whether a car session is connected and whether one of them was taken over.
 * A session that ends takes its state with it: on a disconnect the tones come back. Main thread.
 */
class CarSoundLink(private val apply: (connected: Boolean, yielded: Boolean) -> Unit) : CarSound {

    /** The sessions connected now, by identity, and whether another app took each. */
    private val sessions = IdentityHashMap<Any, Boolean>()

    override fun onSession(session: Any, connected: Boolean) {
        if (connected) {
            if (!sessions.containsKey(session)) sessions[session] = false
        } else {
            sessions.remove(session)
        }
        publish()
    }

    override fun onYielded(session: Any, yielded: Boolean) {
        // Only a session that is connected counts; one that ended says nothing any more.
        if (!sessions.containsKey(session)) return
        sessions[session] = yielded
        publish()
    }

    private fun publish() = apply(sessions.isNotEmpty(), sessions.values.any { it })
}
