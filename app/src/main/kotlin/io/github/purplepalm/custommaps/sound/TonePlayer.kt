package io.github.purplepalm.custommaps.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.sound.Chime
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.DuckPlan
import io.github.purplepalm.custommaps.nav.sound.OutputState
import io.github.purplepalm.custommaps.nav.sound.ToneKind
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import java.util.EnumMap

/**
 * The only class that touches Android audio: one per process, for the phone and the car screen alike, so a
 * tone is never played twice. Every sound is played on a thread of its own, never on the main thread:
 *
 * 1. During a call, or while the phone rings (the audio mode is not normal), nothing: `in_call`. With the
 *    media volume at zero, nothing: `volume_zero`.
 * 2. What the output is: whether something plays, whether a Bluetooth output is connected, whether a car
 *    session is ([OutputState]); and the plan of the dip ([DuckPlan]).
 * 3. Where the plan asks, the sound for a moment (audio focus, transient, others may duck), with the
 *    tone's own attributes, no listener and no delayed gain. Not granted: `no_focus`.
 * 4. The samples, made once for each sound on this thread ([Chime]) and kept; with silence in front, a copy.
 * 5. A static track of exactly the sound: navigation guidance, sonification, 48 kHz, mono, float; written,
 *    turned to the tone's level, played.
 * 6. The sound given back after the plan's hold, with the same request.
 * 7. The track stopped and let go of after its length and 250 ms more: a track that still sounds is never cut.
 * 8. Anything that throws lets go of what exists and gives the sound back: `failed`.
 *
 * Each sound writes one `tone_played` line: the result, the focus, the hold, the silence in front, what
 * played, the output, the level, the media volume, and the time from the decision to the sound. No
 * permission and no manifest entry are needed for any of it.
 */
class TonePlayer(
    context: Context,
    private val debugLog: DebugLog,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ToneSink {

    private val audio: AudioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** The samples of each sound, made at its first use. Touched on [thread] only. */
    private val samples = EnumMap<ToneKind, FloatArray>(ToneKind::class.java)

    /** A tone of guidance: the media volume, the same output as music, not muted by silent or vibrate. */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun play(kind: ToneKind, level: ToneLevel, duck: DuckMode, wakeLeadInMillis: Int, car: Boolean, onResult: (ToneResult) -> Unit) {
        val decided = elapsedMillis()
        handler.post { playNow(kind, level, duck, wakeLeadInMillis, car, decided, onResult) }
    }

    private fun playNow(
        kind: ToneKind,
        level: ToneLevel,
        duck: DuckMode,
        wakeLeadInMillis: Int,
        car: Boolean,
        decided: Long,
        onResult: (ToneResult) -> Unit,
    ) {
        val fields = linkedMapOf<String, Any?>("tone" to kind.logName(), "level" to level.logName())
        var focus = FOCUS_NOT_ASKED
        var request: AudioFocusRequest? = null
        var track: AudioTrack? = null
        val result = try {
            playOn(kind, level, duck, wakeLeadInMillis, car, decided, fields, granted = { focus = FOCUS_GRANTED; request = it }, refused = { focus = FOCUS_FAILED })
                .also { track = it.second }
                .first
        } catch (e: RuntimeException) {
            // Whatever there is goes: the track, and the sound given back.
            track?.let(::letGo)
            request?.let(::giveBack)
            fields["error"] = e.javaClass.simpleName
            ToneResult.FAILED
        }
        fields["result"] = result.logName
        fields["focus"] = focus
        debugLog.log(PLAYED_LINE, fields.filterValues { it != null })
        try {
            onResult(result)
        } catch (e: RuntimeException) {
            debugLog.log(PLAYED_LINE, mapOf("tone" to kind.logName(), "callback" to e.javaClass.simpleName))
        }
    }

    /** The eight steps for one sound; the result and the track it plays on, if any. */
    private fun playOn(
        kind: ToneKind,
        level: ToneLevel,
        duck: DuckMode,
        wakeLeadInMillis: Int,
        car: Boolean,
        decided: Long,
        fields: MutableMap<String, Any?>,
        granted: (AudioFocusRequest) -> Unit,
        refused: () -> Unit,
    ): Pair<ToneResult, AudioTrack?> {
        // 1. A call, or a ringing phone: a tone would sound in it. The media volume at zero: nothing would be heard.
        if (audio.mode != AudioManager.MODE_NORMAL) return ToneResult.IN_CALL to null
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fields["volume"] = "$volume/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        if (volume == 0) return ToneResult.VOLUME_ZERO to null
        // 2. What plays, where it goes, and the dip's plan.
        val outputs = outputTypes()
        val output = OutputState(musicActive = audio.isMusicActive, wireless = outputs.any { it in BLUETOOTH }, car = car)
        val spec = Chime.spec(kind)
        val plan = DuckPlan.plan(kind, spec.lengthMillis, duck, output, wakeLeadInMillis)
        fields["musicActive"] = output.musicActive
        fields["output"] = outputName(car, outputs)
        fields["holdMs"] = plan.holdMillis
        fields["leadInMs"] = plan.leadInMillis
        // 3. The sound for a moment, where the plan asks for it.
        var request: AudioFocusRequest? = null
        if (plan.askFocus) {
            val asked = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
            if (audio.requestAudioFocus(asked) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                refused()
                return ToneResult.NO_FOCUS to null
            }
            request = asked
            granted(asked)
        }
        // 4. The samples, with the silence in front.
        val pcm = samplesOf(kind, plan.leadInMillis)
        // 5. A static track of exactly the sound.
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Chime.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * BYTES_PER_SAMPLE)
            .build()
        try {
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            check(written == pcm.size) { "wrote $written of ${pcm.size}" }
            track.setVolume(level.gain)
            track.play()
        } catch (e: RuntimeException) {
            letGo(track)
            throw e
        }
        fields["startMs"] = elapsedMillis() - decided
        // 6. The sound given back after the hold; the ring goes on over the music coming back.
        request?.let { handler.postDelayed({ giveBack(it) }, plan.holdMillis.toLong()) }
        // 7. Stopped and let go of once it has rung out.
        val soundMillis = pcm.size * MILLIS_PER_SECOND / Chime.SAMPLE_RATE
        handler.postDelayed({ letGo(track) }, soundMillis + RELEASE_AFTER_MILLIS)
        return ToneResult.PLAYED to track
    }

    /** The samples of [kind] with [leadInMillis] of silence in front: the kept ones themselves without. */
    private fun samplesOf(kind: ToneKind, leadInMillis: Int): FloatArray {
        val sound = samples.getOrPut(kind) { Chime.render(Chime.spec(kind)) }
        if (leadInMillis <= 0) return sound
        val lead = leadInMillis * Chime.SAMPLE_RATE / MILLIS_PER_SECOND.toInt()
        return FloatArray(lead + sound.size).also { sound.copyInto(it, destinationOffset = lead) }
    }

    /** The types of the outputs a tone can go to now: the routed ones where Android says so, else every connected one. */
    private fun outputTypes(): Set<Int> {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audio.getAudioDevicesForAttributes(attributes).takeIf { it.isNotEmpty() }?.map { it.type }
        } else {
            null
        }
        return (devices ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }).toSet()
    }

    private fun outputName(car: Boolean, types: Set<Int>): String = when {
        car -> "car"
        types.any { it in BLUETOOTH } -> "bluetooth"
        types.any { it in WIRED } -> "wired"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> "speaker"
        else -> "other"
    }

    private fun giveBack(request: AudioFocusRequest) {
        try {
            audio.abandonAudioFocusRequest(request)
        } catch (e: RuntimeException) {
            debugLog.log(PLAYED_LINE, mapOf("giveBack" to e.javaClass.simpleName))
        }
    }

    private fun letGo(track: AudioTrack) {
        try {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
        } catch (e: IllegalStateException) {
            // Not playing: only let go of it.
        }
        track.release()
    }

    private companion object {
        const val THREAD_NAME = "tones"
        const val PLAYED_LINE = "tone_played"
        const val FOCUS_NOT_ASKED = "not_asked"
        const val FOCUS_GRANTED = "granted"
        const val FOCUS_FAILED = "failed"
        const val BYTES_PER_SAMPLE = 4
        const val MILLIS_PER_SECOND = 1_000L

        /** After a sound's last sample: never cut a track that still sounds. */
        const val RELEASE_AFTER_MILLIS = 250L

        /** Bluetooth outputs: a car's speakers, headphones, hearing aids. */
        val BLUETOOTH: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }

        /** Wired outputs: headphones, a cable or a USB device. */
        val WIRED: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_AUX_LINE,
        )
    }
}
