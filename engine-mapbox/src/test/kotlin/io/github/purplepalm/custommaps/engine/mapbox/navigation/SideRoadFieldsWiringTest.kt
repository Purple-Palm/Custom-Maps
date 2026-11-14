package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where Debug's switch Side-road fields on routes reaches the route requests: every request the app sends builds its options
 * with the switch, and the reroutes' watcher reads it at each reroute. The engine needs the navigator, so these statements
 * are pinned by its source ([SourceText]); what the requests and the watcher do with the switch is RouteRequestTest's.
 */
class SideRoadFieldsWiringTest {

    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
    private val code = SourceText.code(engine).lines().map { it.trim() }

    @Test
    fun `every route request the app sends asks with the switch, and none without it`() {
        val requests = code.filter { "routeOptions(" in it && "fun " !in it && "_routeOptions" !in it && "logRoute" !in it }
        assertEquals(
            listOf(
                "val options = routeOptions(from.point, destination, from.originBearing(), sideRoadFields)",
                "val options = routeOptions(origin, destination, deviceFix?.originBearing(), sideRoadFields)",
            ),
            requests,
        )
    }

    @Test
    fun `the reroutes' watcher reads the switch, which the navigator's thread sees as it is set`() {
        assertTrue(
            "private val rerouteOptionsLogger = RerouteOptionsLogger(debugLog, destination = { _destination.value }, sideRoadFields = { sideRoadFields })" in code,
        )
        val field = code.indexOf("private var sideRoadFields = false")
        assertTrue(field > 0, "off until the setting is read")
        assertEquals("@Volatile", code[field - 1])
        assertTrue(SourceText.hasStatement(engine, "internal fun setSideRoadFields(on: Boolean)", "sideRoadFields = on"))
        assertEquals(listOf("sideRoadFields = on"), SourceText.body(engine, "internal fun setSideRoadFields(on: Boolean)").map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `the navigator gets the watcher as it comes, and loses it as it goes`() {
        assertTrue(SourceText.hasStatement(engine, "override fun onAttached(mapboxNavigation: MapboxNavigation)", "setRerouteOptionsAdapter(mapboxNavigation, rerouteOptionsLogger)"))
        assertTrue(SourceText.hasStatement(engine, "override fun onDetached(mapboxNavigation: MapboxNavigation)", "setRerouteOptionsAdapter(mapboxNavigation, null)"))
    }

    @Test
    fun `the facade hands the switch to the engine`() {
        val facade = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/MapboxEngine.kt").readText()
        assertTrue(SourceText.hasStatement(facade, "fun setSideRoadFields(on: Boolean)", "navigationEngine.setSideRoadFields(on)"))
    }
}
