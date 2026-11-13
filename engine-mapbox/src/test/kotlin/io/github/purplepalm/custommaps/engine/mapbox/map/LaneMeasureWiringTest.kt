package io.github.purplepalm.custommaps.engine.mapbox.map

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the map tells the measuring switch ([io.github.purplepalm.custommaps.nav.lanes.LaneMeasure]) what happens: every way
 * a measured view can end, each a statement of its function's own body ([SourceText.hasStatement]); the measuring's rules
 * themselves are LaneMeasureTest's. A map needs a MapView, the navigation app and a style, so the scene is pinned here by
 * its source, as the car layer's calls are.
 */
class LaneMeasureWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()

    @Test
    fun `the scene tells the measuring every way a view begins or ends`() {
        val calls = listOf(
            // The close view of each progress update: a view begins or lets go.
            "private fun onRouteProgress(progress: RouteProgress)" to "measure { onCloseView(camera.closeView, runUpKey != null, look) }",
            // Guidance starts or ends, at every render.
            "private fun render(state: GuidanceState)" to "measure { onGuidance(state == GuidanceState.ACTIVE) }",
            // The app to the background, Settings over the map, the map put away for the list.
            "fun setVisible(visible: Boolean)" to "measure { setOnScreen(visible && !away && !covered, LaneMeasure.End.BACKGROUND) }",
            "fun setCovered(covered: Boolean)" to "measure { setOnScreen(visible && !away && !covered, LaneMeasure.End.COVERED) }",
            "fun setAway(away: Boolean)" to "measure { setOnScreen(visible && !away && !covered, LaneMeasure.End.AWAY) }",
            "override fun setCameraStyle(style: CameraStyle)" to "measure { onCameraStyle() }",
            // The map made again: this one goes.
            "fun release()" to "measure { release() }",
            // The switch and the setting.
            "fun setMeasureOwnLanes(enabled: Boolean)" to "measure { setSwitch(enabled) }",
            "override fun setLanesOnMap(choice: LanesOnMap)" to "measure { setAppDraws(choice.appDraws) }",
            // The frames of each second of the camera line.
            "private fun takeFrameStats(puckUpdates: Int): Map<String, Any?>?" to "measure { onSecond(frames, stats.totalSkippedVsync, puckUpdates) }",
        )
        for ((function, call) in calls) assertTrue(SourceText.hasStatement(scene, function, call), "$function: $call")
    }

    @Test
    fun `a trip's summary is added up only after every map has closed what it measured of the trip`() {
        val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
        val body = SourceText.code(engine).substringAfter("private fun endTripTally()").substringBefore("\n    }\n")
        val listeners = body.indexOf("listener()")
        assertTrue(listeners >= 0, "the listeners are called")
        assertTrue(listeners < body.indexOf("tripTally.fields("), "before the fields are taken")
    }
}
