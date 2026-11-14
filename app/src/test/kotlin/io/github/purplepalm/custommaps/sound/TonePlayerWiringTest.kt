package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The player's own statements, which need Android's audio and so are pinned by their source ([Sources]): the gate before
 * anything is asked of Android; the sound asked for a moment, with the tone's own attributes, and given back on its
 * schedule; the track let go of on its schedule and on a failure; the car session's flag into the dip's plan. What the
 * gate and the schedule decide is [PlayRulesTest]'s, and what the plan holds DuckPlanTest's.
 */
class TonePlayerWiringTest {

    private val player = Sources.code("sound/TonePlayer.kt")

    /** The statements of the function [declaration] begins, one a line, trimmed, without blank lines. */
    private fun body(declaration: String): List<String> = Sources.functionBody(player, declaration).lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val playOn = body("private fun playOn(")

    /** Where [statement] stands in the steps of one sound; there must be one. */
    private fun at(statement: String): Int = playOn.indexOf(statement).also { assertTrue(it >= 0, "no \"$statement\"") }

    @Test
    fun `the gate is asked first, so in a call or at volume zero nothing is asked of Android and nothing plays`() {
        val gate = at("PlayGate.check(modeNormal, volume)?.let { return it to null }")
        // Its inputs: the audio mode against the normal one, and the media volume.
        assertTrue(at("val modeNormal = audio.mode == AudioManager.MODE_NORMAL") < gate)
        assertTrue(at("val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)") < gate)
        // Nothing before it asks for the sound, makes a track, reads what plays or plans the dip.
        for (line in playOn.subList(0, gate)) {
            for (call in listOf("requestAudioFocus", "AudioTrack", "outputTypes", "isMusicActive", "DuckPlan", "askFocus")) assertFalse(call in line, line)
        }
        // So it holds whatever Lower the music says: Off asks for no sound that a call could refuse.
        assertTrue(gate < at("if (plan.askFocus) {"))
        assertEquals(1, Regex("""PlayGate\.check\(""").findAll(player).count())
    }

    @Test
    fun `the sound is asked for a moment, others may lower theirs, with the tone's own attributes`() {
        assertEquals(listOf("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"), Regex("""AUDIOFOCUS_GAIN\w*""").findAll(player).map { it.value }.toList())
        val ask = at("if (plan.askFocus) {")
        assertEquals(
            listOf(
                "val asked = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()",
                "if (audio.requestAudioFocus(asked) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {",
                "refused()",
                "return ToneResult.NO_FOCUS to null",
                "}",
                "request = asked",
                "granted(asked)",
            ),
            playOn.subList(ask + 1, ask + 8),
        )
        // Navigation guidance, a sonification: the media volume, the music's output, not muted by silent or vibrate; for the
        // request and the track alike.
        val attributes = player.substringAfter("private val attributes: AudioAttributes = AudioAttributes.Builder()").substringBefore(".build()")
        assertEquals(
            listOf(".setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)", ".setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)"),
            attributes.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(listOf("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"), Regex("""USAGE_\w+""").findAll(player).map { it.value }.toList())
        assertEquals(2, Regex("""setAudioAttributes\(""").findAll(player).count())
        assertEquals(2, Regex("""\.setAudioAttributes\(attributes\)""").findAll(player).count())
    }

    @Test
    fun `once the track plays the sound is given back after the hold, and the track let go of once it has rung out`() {
        val play = at("track.play()")
        val schedule = at("val schedule = PlaySchedule.of(plan, pcm.size)")
        val giveBack = at("request?.let { handler.postDelayed({ giveBack(it) }, schedule.giveBackAfterMillis) }")
        val release = at("handler.postDelayed({ letGo(track) }, schedule.releaseAfterMillis)")
        assertTrue(play < schedule && schedule < giveBack && giveBack < release, "$play $schedule $giveBack $release")
        // The samples the schedule counts are the ones the track plays, the silence in front included.
        assertTrue(at("val pcm = samplesOf(kind, plan.leadInMillis)") < play)
        assertTrue(".setBufferSizeInBytes(pcm.size * BYTES_PER_SAMPLE)" in playOn)
        assertTrue("val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)" in playOn)
        // Nowhere else on a delay.
        assertEquals(1, Regex("""postDelayed\(\{ giveBack""").findAll(player).count())
        assertEquals(1, Regex("""postDelayed\(\{ letGo""").findAll(player).count())
    }

    @Test
    fun `a failure lets go of the track and gives the sound back`() {
        // In the track's own steps the track goes before the failure goes on.
        val written = at("val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)")
        val caught = playOn.indexOf("} catch (e: RuntimeException) {")
        assertTrue(caught > written)
        assertEquals(listOf("letGo(track)", "throw e"), playOn.subList(caught + 1, caught + 3))
        // Above them the sound granted is known before any track exists, and given back on any failure.
        val now = body("private fun playNow(")
        assertTrue(
            "playOn(kind, level, duck, wakeLeadInMillis, car, decided, fields, granted = { focus = FOCUS_GRANTED; request = it }, refused = { focus = FOCUS_FAILED })" in now,
        )
        val failed = now.indexOf("} catch (e: RuntimeException) {")
        assertEquals(listOf("track?.let(::letGo)", "request?.let(::giveBack)"), now.subList(failed + 1, failed + 3))
    }

    @Test
    fun `the car session's flag and the stored dip go into the plan, and each sound plays on the player's own thread`() {
        assertTrue("val output = OutputState(musicActive = audio.isMusicActive, wireless = outputs.any { it in BLUETOOTH }, car = car)" in playOn)
        assertTrue("val plan = DuckPlan.plan(kind, spec.lengthMillis, duck, output, wakeLeadInMillis)" in playOn)
        assertTrue("fields[\"output\"] = outputName(car, outputs)" in playOn)
        assertTrue("private val thread = HandlerThread(THREAD_NAME).apply { start() }" in player)
        assertTrue("private val handler = Handler(thread.looper)" in player)
        assertEquals(
            listOf("val decided = elapsedMillis()", "handler.post { playNow(kind, level, duck, wakeLeadInMillis, car, decided, onResult) }"),
            body("override fun play("),
        )
    }
}
