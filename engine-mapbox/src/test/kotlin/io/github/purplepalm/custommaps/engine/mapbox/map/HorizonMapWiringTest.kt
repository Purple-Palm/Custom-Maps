package io.github.purplepalm.custommaps.engine.mapbox.map

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where a map takes the electronic horizon's lane counts for its drawing windows (OL9): from the engine's holder of the
 * newest update, through the own line's build with the horizon and the path kept, and back into the trip summary. The map
 * needs a style and the engine a navigator, so these statements are pinned by their source ([SourceText]); what they do is
 * OwnRouteLineHorizonTest's and HorizonDrawingTest's.
 */
class HorizonMapWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
    private val line = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/OwnRouteLine.kt").readText()

    private fun lines(text: String) = SourceText.code(text).lines().map { it.trim() }

    @Test
    fun `a map's own line asks the engine's holder for the route's newest update, and counts its windows into the summary`() {
        val code = lines(scene)
        assertTrue("horizon = { routeId -> engine.routeHorizon.aheadOf(routeId) }," in code)
        assertTrue("ownLine.onHorizonWindow = { taken, drawn -> engine.onHorizonWindow(taken, drawn) }" in code)
        val counted = SourceText.body(engine, "internal fun onHorizonWindow(taken: Int, drawn: Boolean)").map { it.trim() }
        assertTrue("tripTally.horizon.onStretchesUsed(taken)" in counted)
        assertTrue("if (drawn) tripTally.horizon.onWindow()" in counted)
    }

    @Test
    fun `the map's build passes the horizon's counts and the path kept on to the drawing`() {
        val build = SourceText.body(scene, "private fun ownLineBuild(route: NavigationRoute, plan: RouteLanePlan?)").map { it.trim() }
        assertTrue(
            "return ownLineDrawing(route.id, directions.legs().orEmpty(), directions.stepsGeometryToPoints(), plan, version, lanes, horizon, prefix)" in build,
        )
    }

    @Test
    fun `the horizon is asked for only where a window is due, and nothing of it is logged but numbers`() {
        val progress = SourceText.body(line, "fun onProgress()").map { it.trim() }
        val due = progress.indexOfFirst { it.startsWith("if (!own.windows.due(") }
        val horizon = progress.indexOf("if (withHorizon(drawing, own, car)) return")
        assertTrue(due >= 0 && horizon > due, "only once a window is due")
        // The map's horizon wiring reads no edge, shape or name, and makes no request.
        val code = SourceText.code(line) + SourceText.code(scene)
        for (word in listOf("edgeId", "getEdgeShape", "getEdgeMetadata", "names", "countryCode", "requestRoutes", "startTripSession")) {
            assertFalse(word in code, word)
        }
    }
}
