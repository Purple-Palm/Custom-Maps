package io.github.purplepalm.custommaps.sound

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the app makes its one player of tones and hands it to the engine and the car's sessions. The graph and the
 * activity need Android, so the statements are pinned by their source ([Sources]); what the director does with them
 * is [ToneDirectorTest]'s.
 */
class ToneWiringTest {

    private val graph = Sources.code("AppGraph.kt")
    private val lines = graph.lines().map { it.trim() }

    @Test
    fun `one director, made before the engine that tells it of the drive, and following the settings from the start`() {
        assertEquals(1, Regex("""ToneDirector\(""").findAll(graph).count())
        assertEquals(1, Regex("""TonePlayer\(""").findAll(graph).count())
        val tones = graph.indexOf("val tones = ToneDirector(applicationScope, settings, tonePlayer, debugLog, SystemClock::elapsedRealtime)")
        val engine = graph.indexOf("val engine: MapboxEngine =")
        assertTrue(tones in 0 until engine, "the director comes before the engine")
        assertTrue(graph.indexOf("private val tonePlayer = TonePlayer(application, debugLog)") in 0 until tones)
        assertTrue("debugLog, network.status, audio = tones)" in graph.substring(engine, engine + 300))
        val start = Sources.functionBody(graph, "fun start()").lines().map { it.trim() }
        assertTrue("tones.start()" in start)
    }

    @Test
    fun `the car's sessions tell the same director whether a car is there and whether another app took it`() {
        val link = lines.indexOf("sound = CarSoundLink { connected, yielded ->")
        assertTrue(link >= 0)
        assertEquals(listOf("tones.carSession = connected", "tones.carYielded = yielded", "},"), lines.subList(link + 1, link + 4))
    }

    @Test
    fun `the volume keys set the media volume, the tones' own, while the app is in front`() {
        val onCreate = Sources.functionBody(Sources.code("MainActivity.kt"), "override fun onCreate(").lines().map { it.trim() }
        assertTrue("volumeControlStream = AudioManager.STREAM_MUSIC" in onCreate)
        // Nothing in the app plays a sound but the player, and nothing but the director plays through it.
        val players = Sources.filesUnder("").filter { (_, file) -> "AudioTrack" in file.readText() }.keys
        assertEquals(setOf("sound/TonePlayer.kt"), players)
    }
}
