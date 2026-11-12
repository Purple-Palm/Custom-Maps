package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The own route line's watchdog on a fake style that fails: the SDK's line comes back at once, the own drawing goes,
 * one "route_line" line says why, and the next route tries again. Made-up routes and invented route ids only.
 */
class OwnRouteLineWatchdogTest {

    private val scene = OwnLineScene()
    private val style = scene.style
    private val log = scene.log
    private val line = scene.line

    /** The "route_line" lines written, with their fields. */
    private fun routeLines() = log.lines.filter { it.first == OwnRouteLine.ROUTE_LINE }.map { it.second }

    /** The own line given up: the SDK's line shown, the own layers hidden and their source cleared, nothing waiting. */
    private fun assertGivenUp() {
        assertTrue(style.primaryShown, "the SDK's line")
        assertFalse(style.layersVisible)
        assertNull(style.sourceData)
        assertTrue(style.sent.isEmpty())
        assertFalse(style.watching)
        assertFalse(line.drawn)
    }

    @Test
    fun `a loading error of the own source gives the line back to the SDK at once`() {
        scene.drawn()
        line.onSourceError()
        assertGivenUp()
        assertEquals(listOf(mapOf("drawer" to "sdk", "reason" to "fallback", "ownLineFallback" to "source", "route" to 1)), routeLines())
        assertEquals(listOf("route_line"), log.types(), "the map's own error line says the rest")
        // Trims and refreshes change nothing any more.
        style.calls.clear()
        line.onPuck(scene.at(400.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.4, tracking = true)
        line.onRoute("route-a#0", refresh = true, null, scene.noTraffic)
        scene.jobs.runAll()
        assertEquals(emptyList<String>(), style.calls)
    }

    @Test
    fun `a style that throws costs the own line, with one error line and the fallback's line`() {
        scene.drawn()
        style.refuse = "setTrim"
        line.onPuck(scene.at(400.0).latitude, 0.0, accepted = true, sdkRouteId = "route-a#0", offset = 0.4, tracking = true)
        assertGivenUp()
        assertEquals(listOf("error", "route_line"), log.types())
        assertEquals(OwnRouteLine.WHERE, log.lines.first().second["where"])
        assertEquals("exception", routeLines().single()["ownLineFallback"])
    }

    @Test
    fun `a path sent that has not loaded after 10 s is no data, and the SDK's line stays`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, scene.build("route-a#0"), scene.noTraffic)
        scene.jobs.runAll()
        assertEquals(10_000_000_000L, OwnRouteLine.LOAD_WAIT_NANOS)
        scene.now = OwnRouteLine.LOAD_WAIT_NANOS
        line.check()
        assertEquals(emptyList<Map<String, Any?>>(), routeLines(), "10 s is not yet too long")
        scene.now = OwnRouteLine.LOAD_WAIT_NANOS + 1
        line.check()
        assertGivenUp()
        assertEquals("no_data", routeLines().single()["ownLineFallback"])
        // Its data loading after all draws nothing.
        line.onSourceLoaded("own-1")
        assertFalse(line.drawn)
        assertTrue(style.primaryShown)
    }

    @Test
    fun `own layers that left the style while drawn are no data`() {
        scene.drawn()
        line.check()
        assertEquals(emptyList<Map<String, Any?>>(), routeLines(), "drawn and in the style: nothing to do")
        style.added = null
        line.check()
        assertTrue(style.primaryShown)
        assertFalse(line.drawn)
        assertEquals("no_data", routeLines().single()["ownLineFallback"])
    }

    @Test
    fun `the route that failed is not tried again, the next route is`() {
        scene.drawn("route-a#0")
        line.onSourceError()
        line.onSourceError()
        style.refuse = "setColours"
        line.setLook(Look.DAY)
        assertEquals(1, routeLines().size, "one line for the route")
        style.refuse = null
        // A style load keeps it off, with the SDK's line shown.
        style.reload()
        line.onStyleLoaded(Look.NIGHT)
        assertTrue(style.sent.isEmpty(), "no path sent again for the route that failed")
        assertTrue(style.primaryShown)
        line.check()
        assertEquals(1, routeLines().size)
        // A reroute tries again.
        line.onRoute("route-b#0", refresh = false, scene.build("route-b#0"), scene.noTraffic)
        scene.jobs.runAll()
        scene.load()
        assertTrue(line.drawn)
        assertTrue(style.ownShown)
        assertFalse(style.primaryShown)
        assertEquals(listOf("route-a#0", "route-b#0"), scene.built.map { it.routeId })
    }

    @Test
    fun `a path that fails to build falls back once, and a new drive tries again`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, OwnRouteLine.Build { throw IllegalStateException("no path") }, scene.noTraffic)
        scene.jobs.runAll()
        assertEquals("exception", routeLines().single()["ownLineFallback"])
        assertTrue(style.primaryShown)
        line.onGuidanceEnded()
        line.onRoute("route-a#0", refresh = false, scene.build("route-a#0"), scene.noTraffic)
        scene.jobs.runAll()
        scene.load()
        assertTrue(line.drawn, "the same route in a new drive is a new try")
    }

    @Test
    fun `the hidden SDK line is shown at every style load until the own line has loaded again`() {
        scene.drawn()
        assertFalse(style.primaryShown)
        // A style reload: whether the SDK's layers keep their hidden state across it is not known, so the SDK's line
        // is shown at once, also where the reload left it hidden.
        style.reload()
        style.primaryShown = false
        line.onStyleLoaded(Look.NIGHT)
        assertTrue(style.primaryShown)
        assertFalse(line.drawn)
        // On the new style the own source then fails to load: the SDK's line stays.
        line.onSourceError()
        assertGivenUp()
        assertEquals("source", routeLines().single()["ownLineFallback"])
    }

    @Test
    fun `the fallback's line holds numbers and words only`() {
        scene.drawn()
        line.onSourceError()
        val fields = routeLines().single()
        assertEquals(setOf("drawer", "reason", "ownLineFallback", "route"), fields.keys)
        assertTrue(fields.values.all { it is String || it is Int })
        assertEquals(setOf("source", "exception", "no_data"), OwnRouteLine.Fallback.entries.map { it.logName }.toSet())
    }

    @Test
    fun `with the switch off errors and checks change nothing`() {
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute("route-a#0", refresh = false, scene.build("route-a#0"), scene.noTraffic)
        line.onSourceError()
        line.check()
        assertEquals(emptyList<String>(), style.calls)
        assertEquals(emptyList<String>(), log.types())
    }

    @Test
    fun `outside guidance an error is no fallback`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onSourceError()
        line.check()
        assertEquals(emptyList<Map<String, Any?>>(), routeLines())
    }
}
