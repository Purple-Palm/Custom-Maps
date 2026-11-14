package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.nav.sound.Chime
import io.github.purplepalm.custommaps.nav.sound.PlayPlan

/**
 * Whether a tone may play at all: the player's first question, before it asks Android for anything ([TonePlayer]). Never
 * during a call or while the phone rings, when the audio mode is not normal: a tone would sound in the call. That holds
 * whatever Lower the music says, since Off asks for no sound that a call could refuse. Then never with the media volume
 * at zero, where nothing would be heard.
 */
internal object PlayGate {

    /** Why the tone must not play now; null where it may. A call comes first: it is the reason also at volume zero. */
    fun check(modeNormal: Boolean, mediaVolume: Int): ToneResult? = when {
        !modeNormal -> ToneResult.IN_CALL
        mediaVolume <= 0 -> ToneResult.VOLUME_ZERO
        else -> null
    }
}

/**
 * When the player gives back what a tone took, counted from the moment its track starts ([TonePlayer]): the sound after the
 * dip's hold ([PlayPlan.holdMillis]), which ends the dip, and the track once its last sample has played and
 * [RELEASE_AFTER_MILLIS] more, so that a track that still sounds is never cut.
 */
internal data class PlaySchedule(val giveBackAfterMillis: Long, val releaseAfterMillis: Long) {

    companion object {
        /** After a sound's last sample: never cut a track that still sounds. */
        const val RELEASE_AFTER_MILLIS = 250L

        private const val MILLIS_PER_SECOND = 1_000L

        /** For a sound of [samples] samples at [sampleRate], silence in front included, played by [plan]. */
        fun of(plan: PlayPlan, samples: Int, sampleRate: Int = Chime.SAMPLE_RATE): PlaySchedule = PlaySchedule(
            giveBackAfterMillis = plan.holdMillis.toLong(),
            releaseAfterMillis = samples * MILLIS_PER_SECOND / sampleRate + RELEASE_AFTER_MILLIS,
        )
    }
}
