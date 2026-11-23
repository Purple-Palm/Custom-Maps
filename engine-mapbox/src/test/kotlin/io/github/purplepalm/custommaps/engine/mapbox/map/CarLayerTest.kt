package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.engine.mapbox.navigation.RouteLanePlan
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.CardWindow
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.log2

/**
 * The car drawn by the app itself, on a fake style and location component: the puck's settings only at Start and at the
 * drive's end, two property sets a frame, the dot in every view, the close views too, the watchdog and its line; and no car
 * bar left anywhere. Made-up routes and invented ids only.
 */
class CarLayerTest {

    /** The location component's puck and the own layer, as calls. */
    private class Port : CarLayer.Port {
        val calls = ArrayList<String>()
        val moves = ArrayList<Triple<Double, Double, Double>>()
        var refuse: String? = null
        override var usable = true

        override fun hidePuck() = record("hidePuck")
        override fun showPuck() = record("showPuck")
        override fun addLayer() = record("addLayer")
        override fun removeLayer() = record("removeLayer")
        override fun showDot() = record("showDot")

        override fun moveTo(latitude: Double, longitude: Double, bearing: Double) {
            record("moveTo")
            moves += Triple(latitude, longitude, bearing)
        }

        private fun record(call: String) {
            if (call == refuse) throw IllegalStateException("refused")
            calls += call
        }

        /** The calls that set the location component: the puck made invisible or visible again. */
        fun puckSettings() = calls.filter { it == "hidePuck" || it == "showPuck" }
    }

    private val port = Port()
    private val log = OwnLineScene.Lines()
    private val later = ArrayList<() -> Unit>()
    private var now = 0L
    private val layer = CarLayer(port, log, later = { later += it }) { now }

    private fun carLines() = log.lines.filter { it.first == CarLayer.LINE }.map { it.second }

    /**
     * Where the run-up's card window ends, at the gore. The street turn's window, the junction view's from the ramp's start,
     * meets it there, and the two are one card on to the turn.
     */
    private fun runUpEnd(plan: RouteLanePlan): Double = plan.plan.windows.first { it.kind == CardWindow.Kind.RUN_UP }.to

    /** A map on screen, its style loaded, before any drive. */
    private fun ready(layer: CarLayer = this.layer) {
        layer.setShown(true)
        layer.onStyleLoaded()
        layer.onGuidance(false)
    }

    /**
     * Positions on longitude 0 every frame of [seconds] at [SPEED] from [fromMeters] north, the clock moving with them, a fix of
     * the puck about once a second as the engine gives it, and the watchdog's check about once a second where [check]; with
     * each second's fix, [eachSecond] at the car's metres north, as a progress update comes. How many frames.
     */
    private fun frames(seconds: Double, fromMeters: Double = 0.0, check: Boolean = true, eachSecond: (Double) -> Unit = {}): Int {
        val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        var t = 0.0
        var count = 0
        while (t < seconds) {
            now += FRAME
            t += FRAME / 1e9
            count++
            if (now % SECOND < FRAME) {
                layer.onFix()
                eachSecond(fromMeters + SPEED * t)
            }
            layer.onPosition((fromMeters + SPEED * t) / metersPerDegree, 0.0, 12.0)
            if (check && now % SECOND < FRAME) layer.check(onScreen = true, speedMps = SPEED)
        }
        return count
    }

    @Test
    fun `with the switch off nothing of it exists and the puck is the SDK's`() {
        ready()
        layer.onGuidance(true)
        frames(4.0)
        layer.onStyleLoaded()
        layer.setShown(false)
        layer.setShown(true)
        layer.onGuidance(false)
        assertEquals(emptyList<String>(), port.calls)
        assertEquals(emptyList<Map<String, Any?>>(), carLines())
    }

    @Test
    fun `the puck goes invisible once at Start and comes back at the drive's end, and nothing sets it in between`() {
        layer.setDrawing(true)
        ready()
        assertEquals(emptyList<String>(), port.calls, "no drive yet")
        layer.onGuidance(true)
        assertEquals(listOf("hidePuck", "addLayer"), port.calls)
        assertEquals(listOf(mapOf("drawer" to "own", "reason" to "start")), carLines())
        // A drive: frames, a style load, the map away for the list and back, the screen off and on, the same words again.
        frames(3.0)
        layer.onStyleLoaded()
        frames(2.0, fromMeters = 75.0)
        layer.setShown(false)
        layer.onGuidance(true)
        layer.setShown(true)
        layer.setDrawing(true)
        frames(3.0, fromMeters = 125.0)
        assertEquals(listOf("hidePuck"), port.puckSettings(), "one setting from Start on")
        assertEquals(2, port.calls.count { it == "addLayer" }, "again on the new style, which has none")
        layer.onGuidance(false)
        assertEquals(listOf("hidePuck", "showPuck"), port.puckSettings())
        assertEquals("removeLayer", port.calls.dropLast(1).last())
        assertEquals(mapOf("drawer" to "sdk", "reason" to "guidance_end"), carLines().last())
        // The next drive hides it again.
        layer.onGuidance(true)
        assertEquals(listOf("hidePuck", "showPuck", "hidePuck"), port.puckSettings())
    }

    @Test
    fun `each frame moves the layer to the puck's place with its bearing, the dot shown once`() {
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        port.calls.clear()
        val count = frames(2.0, check = false)
        assertEquals(listOf("showDot") + List(count) { "moveTo" }, port.calls)
        val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        val last = port.moves.last()
        assertEquals(SPEED * count * FRAME / 1e9 / metersPerDegree, last.first, 1e-6 / metersPerDegree)
        assertEquals(0.0, last.second)
        assertEquals(12.0, last.third, "the SDK's own bearing for the dot")
    }

    @Test
    fun `in a close view over the drawn lanes, the lit lane in the route line's place, the car is the dot at the puck's place`() {
        // The own line with its lanes drawn along a made-up freeway exit, the car in the run-up's card window, and the close
        // view latched with a drawn lane 13 dp wide: the lit lane shows in the route line's place, where the car was a bar.
        val id = "route-x#0"
        val (legs, shapes) = FreewayExit.route(75, listOf(54, 62, 70))
        val plan = requireNotNull(FreewayExit.plan(id, legs, shapes))
        val reference = ownLineDrawing(id, legs, shapes, plan, 0, lanes = true)
        val card = requireNotNull(reference.lanes).windows.cards.first()
        val gore = runUpEnd(plan)
        val scene = OwnLineScene()
        val line = scene.line
        fun puck(station: Double) {
            val place = scene.at(station)
            line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = id, offset = reference.table.routeFraction(station), tracking = true)
        }
        line.setLanes(true)
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        puck(card.start + 9.0)
        line.onRoute(id, refresh = false, OwnRouteLine.Build { version, lanes -> ownLineDrawing(id, legs, shapes, plan, version, lanes) }, OwnRouteLine.Traffic { it.runs })
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
        line.onCameraZoom(log2(MapScale.metersPerDp(0.0, 0.0) * 13.0 / LaneTopology.FREEWAY_LANE_METERS), 0.0)
        line.onLatched(true)
        assertEquals(false, scene.style.litPair, "the lit lane in the route line's place")
        // Through the run-up's card window, the scene's frames and a progress update a second: the swap holds all along, and
        // the car is the dot, shown once, at the puck's place with the SDK's own bearing every frame, and nothing else.
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        port.calls.clear()
        val count = frames((gore - card.start - 12.0) / SPEED, fromMeters = card.start + 9.0, check = false) { meters ->
            puck(meters)
            line.onLatched(true)
            line.onProgress()
            assertEquals(false, scene.style.litPair, "the swap at $meters m")
        }
        assertTrue(count > 600, "$count frames")
        assertEquals(listOf("showDot") + List(count) { "moveTo" }, port.calls)
        assertTrue(port.moves.all { it.second == 0.0 && it.third == 12.0 }, "on the road's line, with the SDK's own bearing")
        val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        assertEquals(card.start + 9.0 + SPEED * count * FRAME / 1e9, port.moves.last().first * metersPerDegree, 1e-3)
        // The view let go: the line back over the lit stretches, and the car the same dot, nothing set again.
        line.onLatched(false)
        assertEquals(true, scene.style.litPair)
        port.calls.clear()
        val more = frames(0.5, fromMeters = gore - 3.0, check = false)
        assertEquals(List(more) { "moveTo" }, port.calls)
    }

    @Test
    fun `no car bar is left, the own layer showing the dot alone and nothing of the bar in the map's or the core's sources`() {
        // What the layer draws through: the puck hidden and back, the layer added and taken out, shown, moved. Nothing else.
        assertEquals(
            listOf("addLayer", "getUsable", "hidePuck", "moveTo", "removeLayer", "showDot", "showPuck"),
            CarLayer.Port::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.sorted(),
        )
        val roots = listOf(File("src/main/kotlin"), File("../core-nav/src/main/kotlin"))
        assertTrue(roots.all { it.isDirectory })
        val found = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> BAR.findAll(code(file.readText())).map { "${file.name}: ${it.value}" } }
                .toList()
        }
        assertEquals(emptyList<String>(), found)
        for (line in listOf("val bar = CarBar.of(drawing, from, to)", "port.showBar(width, twoWay, latitude)", "ownLine.carBar", "\"custommaps-car-bar\"")) {
            assertTrue(BAR.containsMatchIn(line), line)
        }
        for (line in listOf("port.showDot()", "LANE_BAR_GAP", "val barrier = 1", "the guidance bar")) {
            assertFalse(BAR.containsMatchIn(line), line)
        }
    }

    @Test
    fun `the watchdog gives the car back once when no position comes, never while the map is stopped or covered or the car stands`() {
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        frames(3.0)
        // A fix every second and no positions from here on: the screen off, a cover, a stop. Ten minutes of each, and nothing
        // changes.
        for ((onScreen, speed) in listOf(false to SPEED, true to 0.0, true to 2.0, true to null)) {
            repeat(600) {
                now += SECOND
                layer.onFix()
                layer.check(onScreen = onScreen, speedMps = speed)
            }
        }
        assertEquals(listOf("hidePuck"), port.puckSettings())
        // Moving and on screen: the count starts now, and a second later the car goes back to the SDK's puck.
        now += SECOND / 2
        layer.check(onScreen = true, speedMps = SPEED)
        assertEquals(listOf("hidePuck"), port.puckSettings())
        now += SECOND
        layer.check(onScreen = true, speedMps = SPEED)
        assertEquals(listOf("hidePuck", "showPuck"), port.puckSettings())
        assertTrue("removeLayer" in port.calls)
        assertEquals(mapOf("drawer" to "sdk", "reason" to "fallback", "carLayerFallback" to "no_position"), carLines().last())
        // For the rest of the drive the own layer draws nothing and nothing is set again.
        val after = port.calls.size
        frames(4.0)
        layer.setDrawing(true)
        layer.onStyleLoaded()
        assertEquals(after, port.calls.size)
        assertEquals(1, carLines().count { it["reason"] == "fallback" })
        // The next drive tries again.
        layer.onGuidance(false)
        layer.onGuidance(true)
        assertEquals(listOf("hidePuck", "showPuck", "hidePuck"), port.puckSettings())
    }

    @Test
    fun `with Android's animations off a position comes only with each fix, and the car stays the app's`() {
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        port.calls.clear()
        // Ten minutes of fixes about a second apart, up to 30 ms early or 90 ms late: the puck jumps to each one, so a position
        // comes on the frame after it and one more on the next, and none in between. A check every 1.003 s.
        val random = kotlin.random.Random(11)
        val start = now
        var nextCheck = start + SECOND / 2
        for (k in 1..600) {
            val fix = start + k * SECOND + (random.nextDouble(-0.03, 0.09) * SECOND).toLong()
            while (nextCheck < fix) {
                now = nextCheck
                layer.check(onScreen = true, speedMps = SPEED)
                nextCheck += SECOND + SECOND * 3 / 1_000
            }
            now = fix
            layer.onFix()
            for (f in 1..2) {
                now = fix + f * FRAME
                layer.onPosition(0.001 * k, 0.0, 12.0)
            }
        }
        assertEquals(emptyList<String>(), port.puckSettings(), "never given back")
        assertTrue(carLines().none { it["reason"] == "fallback" })
        // A fix the map hands on no position after: a second later the car goes back to the SDK's puck.
        layer.onFix()
        now += SECOND - 1
        layer.check(onScreen = true, speedMps = SPEED)
        assertEquals(emptyList<String>(), port.puckSettings())
        now += 1
        layer.check(onScreen = true, speedMps = SPEED)
        assertEquals(listOf("showPuck"), port.puckSettings())
        assertEquals(mapOf("drawer" to "sdk", "reason" to "fallback", "carLayerFallback" to "no_position"), carLines().last())
    }

    @Test
    fun `a map made during a drive hides its puck with its first setting and no further one, as does a surface made again`() {
        // As the car's surface makes its scene mid-drive: the switch first, the style and the drive's state after it.
        for (surface in 1..2) {
            val port = Port()
            val log = OwnLineScene.Lines()
            val scene = CarLayer(port, log, later = { it() }) { now }
            scene.setDrawing(true)
            scene.setShown(true)
            scene.onStyleLoaded()
            scene.onGuidance(true)
            assertEquals(listOf("hidePuck", "addLayer"), port.calls, "surface $surface")
            assertEquals(listOf(mapOf("drawer" to "own", "reason" to "scene")), log.lines.map { it.second })
            repeat(90) {
                now += FRAME
                scene.onPosition(0.001, 0.0, 0.0)
                if (it % 60 == 0) scene.check(onScreen = true, speedMps = SPEED)
            }
            assertEquals(listOf("hidePuck"), port.puckSettings(), "no further setting on surface $surface")
            // The surface goes: its map goes with it, and nothing is set on it any more.
            scene.release()
            val calls = port.calls.size
            scene.onGuidance(false)
            scene.onPosition(0.002, 0.0, 0.0)
            assertEquals(calls, port.calls.size)
        }
    }

    @Test
    fun `while the map is away nothing is set or drawn, and back the due change is made`() {
        layer.setDrawing(true)
        ready()
        layer.setShown(false)
        layer.onGuidance(true)
        frames(2.0)
        assertEquals(emptyList<String>(), port.calls)
        layer.setShown(true)
        assertEquals(listOf("hidePuck", "addLayer"), port.calls)
        // Its first frame back brings the puck's place.
        frames(0.1)
        assertEquals("moveTo", port.calls.last())
        // The drive ends while it is away: the puck comes back once it is back.
        layer.setShown(false)
        layer.onGuidance(false)
        assertEquals(listOf("hidePuck"), port.puckSettings())
        layer.setShown(true)
        assertEquals(listOf("hidePuck", "showPuck"), port.puckSettings())
    }

    @Test
    fun `a style that refuses a frame costs the car once, with one error line, outside the puck's own callback`() {
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        frames(0.2, check = false)
        port.refuse = "moveTo"
        frames(0.2, check = false)
        // Nothing set inside the frame: the puck comes back once the frame's callback has returned.
        assertEquals(listOf("hidePuck"), port.puckSettings())
        assertEquals(1, log.types().count { it == "error" })
        assertEquals(1, later.size)
        later.removeAt(0)()
        assertEquals(listOf("hidePuck", "showPuck"), port.puckSettings())
        assertEquals(mapOf("drawer" to "sdk", "reason" to "fallback", "carLayerFallback" to "exception"), carLines().last())
        assertEquals(1, log.types().count { it == "error" })
    }

    @Test
    fun `the switch turned off during a drive gives the car back, and on again draws it`() {
        layer.setDrawing(true)
        ready()
        layer.onGuidance(true)
        layer.setDrawing(false)
        assertEquals(listOf("hidePuck", "addLayer", "removeLayer", "showPuck"), port.calls)
        assertEquals(mapOf("drawer" to "sdk", "reason" to "switch"), carLines().last())
        layer.setDrawing(true)
        assertEquals(mapOf("drawer" to "own", "reason" to "switch"), carLines().last())
        assertEquals(listOf("hidePuck", "showPuck", "hidePuck"), port.puckSettings())
    }

    @Test
    fun `only the scene's first settings, its release and the car layer set the location component, no camera does`() {
        val root = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox")
        val setters = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { file ->
                val found = LOCATION_SETTINGS.findAll(code(file.readText())).map { it.value.replace(Regex("\\s+"), "") }
                file.relativeTo(root).invariantSeparatorsPath to found.toList()
            }
            .filterValues { it.isNotEmpty() }
        // MapScene: its first settings (one call that sets the puck, whether its bearing shows and which bearing) and turning it
        // off at release; CarLayer: the puck invisible at Start and back at the drive's end. No camera, no lane zoom, nothing
        // else.
        val hideOrShow = listOf("location.updateSettings", "locationPuck=")
        val first = listOf("location.updateSettings", "locationPuck=", "puckBearingEnabled=", "puckBearing=")
        assertEquals(mapOf("map/CarLayer.kt" to hideOrShow + hideOrShow, "map/MapScene.kt" to first + "location.updateSettings"), setters)
    }

    @Test
    fun `the scan finds a setting changed through any name of the location component, a setter or a block`() {
        for (line in listOf(
            "locationComponent.updateSettings { puckBearingEnabled = true }",
            "plugins.location.puckBearingEnabled = true",
            "mapView.location.enabled = false",
            "location.pulsingEnabled = true",
            "plugins.location.apply { showAccuracyRing = true }",
            "with(plugins.location) { locationPuck = puck }",
            "carLocation.slot = \"top\"",
        )) {
            assertTrue(LOCATION_SETTINGS.containsMatchIn(line), line)
        }
        for (line in listOf(
            "if (location.enabled == true) return",
            "val bearing = location.bearing",
            "ownLine.enabled = true",
            "layer.onPosition(latitude, longitude, puckBearing)",
        )) {
            assertFalse(LOCATION_SETTINGS.containsMatchIn(line), line)
        }
    }

    @Test
    fun `the scene tells its car layer the drive's state at every render, the puck's place and bearing each frame and each fix`() {
        val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
        // At every render, not only where a drive begins: the puck comes back at the drive's end, however it ends.
        assertTrue(hasStatement(scene, "private fun render(state: GuidanceState)", "carLayer.onGuidance(state == GuidanceState.ACTIVE)"))
        // Each frame of the puck: its place and the SDK's own bearing, and nothing of the view; the dot in every one.
        val frame = "carLayer.onPosition(point.latitude(), point.longitude(), lastBearing ?: 0.0)"
        assertTrue(hasStatement(scene, "private fun onPuckMoved(point: Point)", frame))
        assertEquals(1, SourceText.code(scene).lines().count { "carLayer.onPosition(" in it }, "the one frame's call")
        // Each matched position the engine gave the drawn car, for the watchdog.
        assertTrue(hasStatement(scene, "override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult)", "carLayer.onFix()"))
        // Lanes on the map: the car is the app's with Drawn by the app, the SDK's puck with Off and Mapbox's lane details.
        assertTrue(hasStatement(scene, "override fun setLanesOnMap(choice: LanesOnMap)", "appDrawsLanes = choice.appDraws"))
        assertTrue(hasStatement(scene, "override fun setLanesOnMap(choice: LanesOnMap)", "carLayer.setDrawing(appDrawsLanes)"))
    }

    @Test
    fun `a statement counts only at its function's own level, outside every block and condition`() {
        val text = """
            |class A {
            |    fun f(x: Int) {
            |        if (x > 0) b.c()
            |        if (x < 0) {
            |            b.d()
            |        }
            |        b.e() // a comment
            |    }
            |
            |    fun g() {
            |        b.c()
            |    }
            |}
        """.trimMargin()
        assertTrue(hasStatement(text, "fun f(x: Int)", "b.e()"))
        assertFalse(hasStatement(text, "fun f(x: Int)", "b.c()"), "under a condition, and another function's")
        assertFalse(hasStatement(text, "fun f(x: Int)", "b.d()"), "inside a block")
        assertTrue(hasStatement(text, "fun g()", "b.c()"))
    }

    private fun hasStatement(text: String, declaration: String, statement: String): Boolean = SourceText.hasStatement(text, declaration, statement)

    private fun code(text: String): String = SourceText.code(text)

    private companion object {
        /** Every location-component setting the SDK has: each change rebuilds the puck and cancels its glide. */
        private const val SETTINGS = "enabled|locationPuck|puckBearing|puckBearingEnabled|pulsingEnabled|pulsingColor|pulsingMaxRadius|" +
            "showAccuracyRing|accuracyRingColor|accuracyRingBorderColor|layerAbove|layerBelow|slot"

        /** The settings no other object of the app has a property of: set in a block, whatever the block's receiver. */
        private const val OWN_SETTINGS = "locationPuck|puckBearing|puckBearingEnabled|pulsingEnabled|pulsingColor|pulsingMaxRadius|" +
            "showAccuracyRing|accuracyRingColor|accuracyRingBorderColor"

        /**
         * A change of a location-component setting: `updateSettings` or a setter on anything named for the location component,
         * and an assignment to one of its own settings anywhere (inside `updateSettings`, `apply` or `with`).
         */
        val LOCATION_SETTINGS = Regex("""\b\w*[Ll]ocation\w*\s*\.\s*(updateSettings\b|($SETTINGS)\s*=(?!=))|(?<![.\w])($OWN_SETTINGS)\s*=(?!=)""")

        /**
         * The names the car bar had (CarBar, the own line's and the lanes' window's carBar, the layer's showBar, its image and
         * the bar's own perspective): none may come back.
         */
        val BAR = Regex("""\b(CarBar|carBar|showBar|barBitmap|BAR_IMAGE|BAR_PERSPECTIVE)\b|custommaps-car-bar""")

        /** A display frame of 60 Hz, in nanoseconds. */
        const val FRAME = 16_666_667L

        /** The made-up car's speed, in metres a second. */
        const val SPEED = 23.0
        const val SECOND = 1_000_000_000L
    }
}
