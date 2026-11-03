package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.model.Maneuver

/** Audible guidance: short tones by default, optionally a voice. */
interface GuidanceAudio {
    fun setMode(mode: AudioGuidanceMode)

    /** Called at the moments the routing engine schedules a spoken instruction. */
    fun announce(cue: GuidanceCue)
}

enum class AudioGuidanceMode {
    TONES,
    VOICE,
    SILENT,
}

data class GuidanceCue(
    /** Instruction text, used only in [AudioGuidanceMode.VOICE]. */
    val announcement: String,
    val maneuver: Maneuver?,
)
