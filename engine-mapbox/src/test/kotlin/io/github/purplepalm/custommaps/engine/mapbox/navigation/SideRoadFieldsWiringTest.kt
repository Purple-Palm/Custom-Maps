package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where Debug's switch Side-road fields on routes reaches the route requests: every request the app sends builds its options
 * with the switch, and the reroutes' watcher reads it at each reroute; and where the routes' "side_roads" lines are written,
 * with the switch on only. The engine needs the navigator, so these statements are pinned by its source ([SourceText]); what
 * the requests, the watcher and the line do is RouteRequestTest's, SideRoadsTest's and SideRoadsLineTest's.
 */
class SideRoadFieldsWiringTest {

    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
    private val code = SourceText.code(engine).lines().map { it.trim() }

    /** The statements of [declaration]'s body, without its comments and blank lines. */
    private fun body(declaration: String) = SourceText.body(engine, declaration).map { it.trim() }.filter { it.isNotEmpty() }

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
    fun `the side roads' line is written only with the switch on, on the route lines' worker`() {
        val write = body("private fun logSideRoads(route: NavigationRoute, reason: SideRoadsLine.Reason)")
        assertEquals("if (!sideRoadFields) return", write.first { it.isNotEmpty() })
        assertTrue("logRoutes(SideRoadsLine.TYPE) {" in write)
        assertTrue("sideRoadsFields(route.id, reason, route.directionsRoute, onSizeError = { debugLog.error(SideRoadsLine.TYPE, it) })" in write)
        // The worker the route lines are put together on: one at a time, so each line keeps its place after the ones before it.
        assertTrue("private val routeLines = Dispatchers.Default.limitedParallelism(1)" in code)
        assertTrue(SourceText.body(engine, "private fun logRoutes(type: String, fields: () -> Map<String, Any?>): Job =").any { "scope.launch(routeLines)" in it })
    }

    @Test
    fun `each route of a preview's answer gets a line after the answer's own`() {
        val preview = body("override suspend fun previewRoutes(destination: Place): RouteRequestResult")
        val response = preview.indexOf("logRouteResponse(response.routes, options, response.routerOrigin, inTrip = false)")
        val sideRoads = preview.indexOf("for (route in response.routes) logSideRoads(route, SideRoadsLine.Reason.PREVIEW)")
        assertTrue(response >= 0)
        assertEquals(response + 1, sideRoads)
    }

    @Test
    fun `the route a drive starts on gets its line right after its lane evidence`() {
        val commit = body("is GuidanceCommand.CommitRoutes -> {")
        val plan = commit.indexOf("planLanes(primary, it)")
        assertTrue(plan >= 0)
        assertEquals(plan + 1, commit.indexOf("logSideRoads(primary, SideRoadsLine.Reason.START)"))
        assertTrue(commit[plan - 1].startsWith("lanePlans.onCommitted(primary.id)?.let {"), "once, as the lane evidence is")
    }

    @Test
    fun `each new primary route of a drive gets its line right after its lane evidence, a reroute's as a reroute`() {
        val changed = body("private val routesObserver = RoutesObserver { result ->")
        val plan = changed.indexOf("planLanes(primary, it)")
        assertTrue(plan >= 0)
        assertEquals(
            plan + 1,
            changed.indexOf("logSideRoads(primary, SideRoadsLine.Reason.ofRouteChange(reroute = reason == RoutesExtra.ROUTES_UPDATE_REASON_REROUTE))"),
        )
        assertTrue(changed.any { it.startsWith("lanePlans.onRoutesChanged(primary.id, refresh = reason == RoutesExtra.ROUTES_UPDATE_REASON_REFRESH)?.let {") })
        // Nowhere else: no line for a refresh, a route request inside the trip or anything a progress update does.
        assertEquals(3, code.count { "logSideRoads(" in it && "private fun" !in it })
    }

    @Test
    fun `the facade hands the switch to the engine`() {
        val facade = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/MapboxEngine.kt").readText()
        assertTrue(SourceText.hasStatement(facade, "fun setSideRoadFields(on: Boolean)", "navigationEngine.setSideRoadFields(on)"))
    }
}
