package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the own route line writes to the debug log: a "route_line" line at each change of who draws the route line, and
 * the camera line's "routeLine" object. Made-up routes and invented route ids; the test's clock in nanoseconds.
 */
class OwnRouteLineLogTest {

    private val scene = OwnLineScene()
    private val line = scene.line
    private val log = scene.log

    private val ms = 1_000_000L

    private fun routeLines() = log.lines.filter { it.first == OwnRouteLine.ROUTE_LINE }.map { it.second }

    /** A build that takes [took] nanoseconds on the test's clock. */
    private fun slowBuild(id: String, took: Long) = OwnRouteLine.Build { version, lanes ->
        scene.now += took
        scene.build(id).drawing(version, lanes)
    }

    @Test
    fun `a drive writes who draws the line at its start, each load, a reroute and its end, with the swap's times`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        scene.now = 1_000 * ms
        line.onRoute("route-a#0", refresh = false, slowBuild("route-a#0", 40 * ms), scene.noTraffic)
        scene.jobs.runAll()
        scene.now += 25 * ms
        scene.load()
        scene.now = 5_000 * ms
        line.onRoute("route-b#0", refresh = false, slowBuild("route-b#0", 30 * ms), scene.noTraffic)
        scene.jobs.runAll()
        scene.now += 70 * ms
        scene.load()
        line.onGuidanceEnded()
        assertEquals(
            listOf(
                mapOf("drawer" to "sdk", "reason" to "start", "route" to 1),
                mapOf("drawer" to "own", "reason" to "loaded", "route" to 1, "swapMs" to 65L, "loadMs" to 25L, "buildMs" to 40L, "points" to 11, "version" to 1),
                mapOf("drawer" to "sdk", "reason" to "route_change", "route" to 2),
                mapOf("drawer" to "own", "reason" to "loaded", "route" to 2, "swapMs" to 100L, "loadMs" to 70L, "buildMs" to 30L, "points" to 11, "version" to 2),
                mapOf("drawer" to "sdk", "reason" to "guidance_end", "route" to 2),
            ),
            routeLines(),
        )
    }

    @Test
    fun `a style load and the switch turned off during a drive say so`() {
        scene.drawn()
        scene.now = 2_000 * ms
        scene.style.reload()
        line.onStyleLoaded(Look.NIGHT)
        scene.now += 15 * ms
        scene.load()
        line.setEnabled(false)
        val reasons = routeLines().map { it["reason"] to it["drawer"] }
        assertEquals(
            listOf("start" to "sdk", "loaded" to "own", "style_load" to "sdk", "loaded" to "own", "switch" to "sdk"),
            reasons,
        )
        assertEquals(15L, routeLines()[3]["swapMs"], "from the style load to the path loaded again")
        // The switch on again during the drive: the own line begins with the route the map names, as at a start.
        line.setEnabled(true)
        line.onRoute("route-a#0", refresh = false, scene.build("route-a#0"), scene.noTraffic)
        assertEquals("start" to "sdk", routeLines().last().let { it["reason"] to it["drawer"] })
    }

    @Test
    fun `a refresh, a look and the trims change who draws nothing and write no line`() {
        scene.drawn()
        val before = routeLines().size
        line.onRoute("route-a#0", refresh = true, null) { listOf(TrafficRun(0.0, 1.0, TrafficLevel.MODERATE)) }
        scene.jobs.runAll()
        line.setLook(Look.DAY)
        line.onPuck(scene.at(400.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.4, tracking = true)
        assertEquals(before, routeLines().size)
    }

    @Test
    fun `the camera line's routeLine counts the trims, the lag behind the SDK's point and the time`() {
        val drawing = scene.drawn()
        scene.style.calls.clear()
        assertEquals(mapOf("routeLine" to mapOf("own" to true, "trims" to 0)), line.diagnostics(), "no puck update yet")
        val table = drawing.table
        // Two updates on the SDK's point: no lag.
        line.onPuck(scene.at(300.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = table.routeFraction(300.0), tracking = true)
        line.onPuck(scene.at(310.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = table.routeFraction(310.0), tracking = true)
        val followed = line.diagnostics()["routeLine"] as Map<*, *>
        assertEquals(2, followed["trims"])
        assertEquals(0.0, followed["trimLagM"] as Double, 0.05)
        // The SDK skips while the car goes on to 330 m: the own projection trims there, 20 m past the SDK's point.
        line.onPuck(scene.at(330.0).latitude, 0.0, accepted = false, sdkRouteId = null, offset = Double.NaN, tracking = true)
        val skipped = line.diagnostics()["routeLine"] as Map<*, *>
        assertEquals(1, skipped["trims"])
        assertEquals(20.0, skipped["trimLagM"] as Double, 0.05)
        // Each trim counted is one call to the style, and the puck updates made no other.
        assertEquals(List(3) { "setTrim" }, scene.style.calls)
        // Counted afresh each second.
        assertEquals(mapOf("routeLine" to mapOf("own" to true, "trims" to 0)), line.diagnostics())
    }

    @Test
    fun `the time a trim takes is the longest of the second`() {
        val slow = OwnLineScene()
        val drawing = slow.drawn()
        // A style whose trim takes 0.3 ms on the test's clock.
        val table = drawing.table
        val style = object : OwnRouteLine.Layers by slow.style {
            override fun setTrim(fraction: Double) {
                slow.now += 300_000L
                slow.style.setTrim(fraction)
            }
        }
        val timed = OwnRouteLine(style, slow.style, slow.jobs, slow.log) { slow.now }
        timed.setEnabled(true)
        timed.onStyleLoaded(Look.NIGHT)
        timed.onRoute("route-a#0", refresh = false, slow.build("route-a#0"), slow.noTraffic)
        slow.jobs.runAll()
        val dataId = slow.style.sent.keys.last()
        slow.style.parse(dataId)
        timed.onSourceLoaded(dataId)
        timed.diagnostics()
        timed.onPuck(slow.at(300.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = table.routeFraction(300.0), tracking = true)
        assertEquals(0.3, (timed.diagnostics()["routeLine"] as Map<*, *>)["trimMaxMs"] as Double, 1e-9)
    }

    @Test
    fun `the lines hold numbers and words only, and stay small`() {
        scene.drawn()
        scene.line.onSourceError()
        line.onPuck(scene.at(300.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.3, tracking = true)
        val time = Instant.parse("2000-01-01T00:00:00Z")
        for (fields in routeLines()) {
            assertTrue(fields.values.all { it is String || it is Number }, "$fields")
            val text = JsonLines.event(time, OwnRouteLine.ROUTE_LINE, fields)
            assertTrue(text.length < 200, "${text.length} bytes: $text")
            assertFalse(text.contains("lat") || text.contains("lon") || text.contains("route-a"), text)
        }
        // A second with every field: about 70 bytes more in each camera line, with the switch on only.
        val second = OwnLineScene()
        val table = second.drawn().table
        second.line.onPuck(second.at(300.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = table.routeFraction(300.0), tracking = true)
        second.now += 123_456L
        second.line.onPuck(second.at(310.0).latitude, 0.0, accepted = false, sdkRouteId = null, offset = Double.NaN, tracking = true)
        val camera = JsonLines.encode(second.line.diagnostics())
        assertTrue(camera.length < 80, "${camera.length} bytes: $camera")
    }

    @Test
    fun `with the switch off no line is written`() {
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, scene.build("route-a#0"), scene.noTraffic)
        line.onGuidanceEnded()
        assertEquals(emptyList<Map<String, Any?>>(), routeLines())
    }
}
