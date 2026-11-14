package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.sound.ToneMoment

/**
 * The audible side of guidance. The engine says where the drive is; what sounds, and whether anything
 * does, is decided behind this port, never in the engine. Each call returns at once; the engine catches
 * what one throws anyway, so a sound never costs the drive. Main thread.
 */
interface GuidanceAudio {

    /**
     * Once per navigator update of an active drive that the screen takes, right after it is published:
     * never for one the screen leaves alone, as an update that still carries where the trip before left the
     * car or one from before a seek's new place.
     */
    fun onProgress(moment: ToneMoment)

    /** The navigator reports the arrival at the final destination, before guidance ends. */
    fun onArrived()

    /**
     * A drive begins or ends, or a seek of a simulated drive put the car somewhere else: nothing of what
     * was is kept, and the next update is a new start.
     */
    fun onGuidanceEnded()

    /** No sound at all: where no player is given, and in tests. */
    object Silent : GuidanceAudio {
        override fun onProgress(moment: ToneMoment) = Unit

        override fun onArrived() = Unit

        override fun onGuidanceEnded() = Unit
    }
}
