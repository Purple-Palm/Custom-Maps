package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.VoiceInstructions
import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the engine tells the audio port of the drive: each update the screen takes, after it is published and behind the
 * guards of the stale start and the seek; the arrival before guidance ends; and a new start wherever the Then chip starts
 * over, the seek's included. The engine needs the navigator, so these statements are pinned by its source ([SourceText]);
 * what the port does with them is the tone rule's and the director's tests'.
 */
class AudioWiringTest {

    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
    private val code = SourceText.code(engine)

    private fun body(declaration: String) = SourceText.body(engine, declaration).map { it.trim() }

    @Test
    fun `each update the screen takes reaches the port after it is published, never one the guards leave alone`() {
        val observer = body("private val routeProgressObserver = RouteProgressObserver { progress ->")
        val stale = observer.indexOf("if (active && staleStart.isStale(fix)) {")
        val seek = observer.indexOf("if (active && seekHold.isHeld(fix)) {")
        val published = observer.indexOf("_routeProgress.value = snapshot")
        val told = observer.indexOf("if (active) tellAudio(progress, snapshot)")
        assertTrue(stale in 0 until seek && seek < published && published < told, "$stale $seek $published $told")
        // Each guard returns before the port hears of the update.
        assertTrue(observer.subList(stale, seek).contains("return@RouteProgressObserver"))
        assertTrue(observer.subList(seek, published).contains("return@RouteProgressObserver"))
        assertEquals(1, Regex("tellAudio\\(").findAll(code).count() - 1, "one call, besides its declaration")
        // The moment takes the snapshot, the route's id, the card's row and the run-up's start.
        val tell = body("private fun tellAudio(progress: RouteProgress, snapshot: RouteProgressSnapshot)")
        assertTrue("val key = laneChoices.shownKey" in tell)
        assertTrue("audio.onProgress(ToneMoment.of(snapshot, progress.navigationRoute.id, key, meters, laneChoices.runUpFromExitWindow))" in tell)
    }

    @Test
    fun `the port starts over wherever the Then chip does, the seek's included, and hears the arrival before guidance ends`() {
        val resets = Regex("thenTracker\\.reset\\(\\)").findAll(code).count()
        val ended = Regex("audioCall \\{ audio\\.onGuidanceEnded\\(\\) \\}").findAll(code).count()
        assertEquals(4, resets, "a new drive, the drive's end and the seek's two answers")
        assertEquals(resets, ended)
        for (line in code.lines().withIndex().filter { it.value.trim() == "thenTracker.reset()" }) {
            assertTrue(code.lines().drop(line.index + 1).take(3).any { it.trim() == "audioCall { audio.onGuidanceEnded() }" }, "after line ${line.index}")
        }
        val arrival = body("override fun onFinalDestinationArrival(routeProgress: RouteProgress) {")
        assertTrue(arrival.indexOf("audioCall { audio.onArrived() }") in 0 until arrival.indexOf("dispatch(GuidanceEvent.Arrived)"))
    }

    @Test
    fun `every call to the port is caught, and a failure writes one error line in a drive`() {
        val call = body("private inline fun audioCall(call: () -> Unit)")
        assertTrue("} catch (e: RuntimeException) {" in call)
        assertTrue("debugLog.error(AUDIO_ERROR, e)" in call)
        assertTrue("if (!audioFailed) {" in call)
        assertTrue(SourceText.hasStatement(engine, "is GuidanceCommand.CommitRoutes -> {", "audioFailed = false"))
        // Outside audioCall the engine never calls the port.
        for (method in listOf("onProgress", "onArrived", "onGuidanceEnded")) {
            for (line in code.lines().filter { "audio.$method(" in it }) assertTrue("audioCall" in line || "audio.onProgress(ToneMoment.of" in line, line)
        }
    }

    @Test
    fun `the tones ask the navigator for nothing of their own`() {
        // No voice observer, no other observer, no call into the SDK for the sound.
        assertFalse("VoiceInstructionsObserver" in code)
        assertFalse("registerVoiceInstructions" in code)
        assertFalse("SpeechApi" in code || "VoiceInstructionsPlayer" in code)
    }

    @Test
    fun `a step's voice instructions go into its route line as whole metres, numbers only`() {
        fun voice(meters: Double?) = VoiceInstructions.builder().distanceAlongGeometry(meters).announcement("In a quarter mile, turn right").build()
        assertEquals(listOf(402L, 88L), voiceAtFields(listOf(voice(402.3), voice(Double.NaN), voice(null), voice(87.6))))
        assertNull(voiceAtFields(emptyList()))
        assertNull(voiceAtFields(null))
        assertNull(voiceAtFields(listOf(voice(null))))
    }
}
