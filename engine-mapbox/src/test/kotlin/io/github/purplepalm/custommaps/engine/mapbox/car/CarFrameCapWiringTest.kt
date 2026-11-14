package io.github.purplepalm.custommaps.engine.mapbox.car

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the render thread's windows reach the car's frame cap ([io.github.purplepalm.custommaps.nav.car.CarFrameCap]): the
 * scene hands each window the camera line takes to its host with the frames drawn
 * ([io.github.purplepalm.custommaps.nav.debug.RenderStats.drawn]) and the 95th percentile of the frame times beside the
 * ticks, before the host's fields start the next window; the car map hands them on to its cap with the display's rate and
 * whether the frame clock stayed on the display's frames, and each second the display's rate or the timer for the base.
 * The cap's rules are CarFrameCapTest's; the scene needs a map and the car map a car's surface, so these statements are
 * pinned by their source ([SourceText]), as the measuring's calls are.
 */
class CarFrameCapWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
    private val car = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/car/CarNavigationMap.kt").readText()

    private fun body(text: String, declaration: String) = SourceText.body(text, declaration).map { it.trim() }

    @Test
    fun `each window reaches the cap with the frames drawn and its frame times beside the ticks`() {
        // The calls less the paced skips are the frames drawn; the ticks, calls and missed display frames, stay apart.
        val window = "host.onRenderWindow(frames.size, stats.totalSkippedVsync, RenderStats.drawn(frames.size, stats.pacedSkippedVsync), " +
            "stats.percentile95, stats.totalTime)"
        assertTrue(SourceText.hasStatement(scene, "private fun takeFrameStats(puckUpdates: Int): Map<String, Any?>?", window), window)
        val cap = "if (frameCap.onRenderWindow(calls + missedVsync, drawn, p95Ms, windowMillis, frames.displayHz(), frames.stayedOnDisplay())) " +
            "applyCap()"
        val host = "override fun onRenderWindow(calls: Int, missedVsync: Long, drawn: Long, p95Ms: Double?, windowMillis: Long)"
        assertTrue(SourceText.hasStatement(car, host, cap), cap)
    }

    @Test
    fun `a window reaches the cap before the host's fields start the next one`() {
        // Else a window in which the cap changed would count as one with the cap held.
        val sample = body(scene, "private fun cameraSample(): Map<String, Any?>")
        val window = sample.indexOf("\"render\" to takeFrameStats(puckMoves.count),")
        val next = sample.indexOf(") + host.cameraFields()")
        assertTrue(window >= 0 && next > window, "the window first, then the host's fields")
        assertTrue(SourceText.hasStatement(car, "override fun cameraFields(): Map<String, Any?>", "frameCap.onWindowStart()"))
    }

    @Test
    fun `the base takes the display's rate or the timer once a second`() {
        val second = body(car, "private suspend fun followFrameRate()")
        assertTrue("if (frameCap.onSecond(frames.onTimer, frames.displayHz())) applyCap()" in second)
    }
}
