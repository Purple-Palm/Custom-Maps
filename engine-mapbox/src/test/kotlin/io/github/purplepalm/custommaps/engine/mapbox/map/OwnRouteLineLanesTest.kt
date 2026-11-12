package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneSwap
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.log2
import kotlin.math.max

/**
 * The own line with the app's lanes on a fake style ([OwnLineScene]), along a made-up freeway exit ([FreewayExit]): the
 * first window goes with the path, the next ones while the car drives and never inside a card window or while a new
 * route loads, the feature states, a style load, a look switch, guidance end, the watchdog, the swap of the route
 * line and the lit lane on the camera's state, and the maneuver arrow along the lit lane.
 */
class OwnRouteLineLanesTest {

    private val id = "route-x#0"
    private val legs = FreewayExit.route(75, listOf(54, 62, 70)).first
    private val shapes = FreewayExit.route(75, listOf(54, 62, 70)).second
    private val plan = requireNotNull(FreewayExit.plan(id, legs, shapes))

    /** The route's line and table as every path of it has them: where an SDK fraction lies. */
    private val reference = ownLineDrawing(id, legs, shapes, plan, 0, lanes = true)
    private val own = requireNotNull(reference.lanes)
    private val routeEnd = reference.table.stationAt(1.0)

    private val scene = OwnLineScene()
    private val style = scene.style
    private val lanes = scene.lanes
    private val line = scene.line
    private val built = ArrayList<OwnLineDrawing>()

    private fun build() = OwnRouteLine.Build { version, withLanes ->
        ownLineDrawing(id, legs, shapes, plan, version, withLanes).also { built += it }
    }

    private val traffic = OwnRouteLine.Traffic { it.runs }

    /** The SDK took the car to [station] on the route: an accepted puck update there. */
    private fun puck(station: Double) {
        val place = scene.at(station)
        line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = id, offset = reference.table.routeFraction(station), tracking = true)
    }

    /** The lanes on, the style loaded, the car at [car] and the route given: its path and first window built. */
    private fun start(car: Double = 0.0) {
        line.setLanes(true)
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        puck(car)
        line.onRoute(id, refresh = false, build(), traffic)
        scene.jobs.runAll()
    }

    /** [start], and both loaded: the own line and its lanes drawn. */
    private fun drawn(car: Double = 0.0) {
        start(car)
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
    }

    private fun laneLines() = scene.log.lines.filter { it.first == OwnRouteLine.LANE_DRAWING }.map { it.second }

    @Test
    fun `the first window goes with the path, and the line is drawn once both have loaded`() {
        start()
        assertEquals(1, style.sent.size)
        assertEquals(1, lanes.sent.size)
        assertTrue(lanes.watching)
        scene.load()
        // The path alone is not drawn: its lanes' window has not loaded.
        assertFalse(line.drawn)
        assertTrue(style.primaryShown)
        scene.loadWindow()
        assertTrue(line.drawn)
        assertFalse(style.primaryShown)
        assertTrue(lanes.litShown)
        assertFalse(lanes.watching)
        // Both pairs of the route line coloured, and one line about the window: numbers and words only.
        assertEquals(setOf(OwnRouteLine.CASING, OwnRouteLine.LINE, OwnRouteLine.LIT_CASING, OwnRouteLine.LIT_LINE), style.gradients.keys)
        val logged = laneLines().single()
        assertEquals(setOf("route", "window", "reason", "lengthM", "features", "coordinates", "bytes", "buildMs", "loadMs", "budget"), logged.keys)
        assertEquals(1, logged["route"])
        assertEquals(1, logged["window"])
        assertEquals(OwnRouteLine.REASON_ROUTE, logged["reason"])
        assertEquals(DrawingWindows.MAX_METERS.toLong(), logged["lengthM"])
        assertEquals(true, logged["budget"])
        for (value in logged.values) assertTrue(value is Number || value is Boolean || value is String, "$value")
    }

    @Test
    fun `the window may load before the path, and the line waits for the path`() {
        start()
        scene.loadWindow()
        assertFalse(line.drawn)
        scene.load()
        assertTrue(line.drawn)
    }

    @Test
    fun `while the car drives the next window comes ahead of it, never inside a card window, and holds the car`() {
        drawn()
        var held = own.windows.windowAt(0.0)
        val sentAt = ArrayList<Double>()
        var car = 0.0
        while (car <= routeEnd) {
            puck(car)
            line.onProgress()
            scene.jobs.runAll()
            if (lanes.sent.isNotEmpty()) {
                assertEquals(1, lanes.sent.size)
                assertFalse(own.windows.inCard(car), "a window sent inside a card window at $car")
                sentAt += car
                held = own.windows.windowAt(car)
                scene.loadWindow()
            }
            assertTrue(held.holds(car) || car > held.to - 1e-6 && held.to >= routeEnd - 1e-6, "nothing drawn at $car: $held")
            car += 20.0
        }
        assertTrue(sentAt.size >= 3, "windows sent at $sentAt")
        val logged = laneLines()
        assertEquals(sentAt.size + 1, logged.size)
        assertTrue(logged.drop(1).all { it["reason"] == OwnRouteLine.REASON_AHEAD })
        assertEquals((1..logged.size).toList(), logged.map { it["window"] })
        // A window with nothing in it over one with nothing either is no update: the first ones along the road without lanes.
        assertTrue(sentAt.first() > 1_000.0, "first sent at ${sentAt.first()}")
    }

    @Test
    fun `the tints the car has passed are hidden a few at a time, and set again after each window has loaded`() {
        // The car well into the run-up, where tints are drawn, from the first window on.
        val inRunUp = own.windows.cards.first().start + 20.0
        drawn(car = inRunUp)
        val window = own.window(own.windows.windowAt(inRunUp), reference.table, reference.runs)
        assertTrue(window.tints.size >= 4, "${window.tints.size} tint pieces")
        val behind = { lanes.states.filter { it.second == LaneLayers.Key.STATE_BEHIND }.map { it.first }.toSet() }
        assertEquals(window.tints.filter { it.to <= inRunUp }.map { it.id }.toSet(), behind())
        var car = inRunUp
        while (car + 30.0 <= own.windows.cards.first().endInclusive) {
            car += 30.0
            val before = lanes.states.size
            puck(car)
            line.onProgress()
            scene.jobs.runAll()
            assertEquals(window.tints.filter { it.to <= car }.map { it.id }.toSet(), behind(), "at $car")
            // A few feature states a progress update at most: the tints of every lane that end together are one.
            assertTrue(lanes.states.size - before <= 3, "${lanes.states.size - before} calls at $car")
        }
        // Inside the card window nothing more was sent.
        assertTrue(lanes.sent.isEmpty())
    }

    @Test
    fun `on a wider exit a progress update hides the passed tints of every lane with a feature state or two`() {
        val (wideLegs, wideShapes) = FreewayExit.route(75, listOf(54, 62, 70), lanes = 5)
        val widePlan = requireNotNull(FreewayExit.plan(id, wideLegs, wideShapes))
        val wideReference = ownLineDrawing(id, wideLegs, wideShapes, widePlan, 0, lanes = true)
        val card = requireNotNull(wideReference.lanes).windows.cards.first()
        val wide = OwnLineScene()
        fun wideCar(station: Double) {
            val place = wide.at(station)
            wide.line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = id, offset = wideReference.table.routeFraction(station), tracking = true)
        }
        wide.line.setLanes(true)
        wide.line.setEnabled(true)
        wide.line.onStyleLoaded(Look.NIGHT)
        var car = card.start + 20.0
        wideCar(car)
        wide.line.onRoute(id, refresh = false, OwnRouteLine.Build { version, withLanes -> ownLineDrawing(id, wideLegs, wideShapes, widePlan, version, withLanes) }, traffic)
        wide.jobs.runAll()
        wide.load()
        wide.loadWindow()
        assertTrue(wide.line.drawn)
        val pieces = requireNotNull(wideReference.lanes).drawing.features().filter { it.kind == LaneDrawing.Kind.TINT }
        var most = 0
        var mostPieces = 0
        // A progress update every 27 m, as at 27 m/s.
        while (car + 27.0 <= card.endInclusive) {
            val before = wide.lanes.states.size
            val from = car
            car += 27.0
            wideCar(car)
            wide.line.onProgress()
            wide.jobs.runAll()
            most = max(most, wide.lanes.states.size - before)
            mostPieces = max(mostPieces, pieces.count { it.to > from && it.to <= car })
        }
        // Pieces of three lanes or more ended within one update, and the map hid them with a call or two.
        assertTrue(mostPieces >= 3, "$mostPieces pieces")
        assertTrue(most in 1..2, "$most calls")
    }

    @Test
    fun `a new route's pieces never take the feature states the old route's pieces had`() {
        val card = own.windows.cards.first()
        // On the first route, past the end of a tint piece in the run-up: that piece is hidden.
        val tints = own.window(own.windows.windowAt(card.start + 20.0), reference.table, reference.runs).tints
        val far = tints[tints.size / 2].to + 1.0
        drawn(car = far)
        val hidden = { lanes.live.filterValues { it[LaneLayers.Key.STATE_BEHIND] == true }.keys }
        assertTrue(hidden().isNotEmpty(), "hidden at $far: ${lanes.live}")
        // A new route on the same road, whose pieces have the same ids, and on it the car near the run-up's start.
        val other = "route-y#0"
        val otherPlan = requireNotNull(FreewayExit.plan(other, legs, shapes))
        val near = card.start + 20.0
        val place = scene.at(near)
        line.onPuck(place.latitude, place.longitude, accepted = true, sdkRouteId = other, offset = reference.table.routeFraction(near), tracking = true)
        line.onRoute(other, refresh = false, OwnRouteLine.Build { version, withLanes -> ownLineDrawing(other, legs, shapes, otherPlan, version, withLanes) }, traffic)
        // The old route's states are forgotten with it.
        assertTrue(lanes.live.isEmpty(), "${lanes.live.keys}")
        assertTrue("lanes.resetStates" in style.calls, "${style.calls}")
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn, "not drawn")
        // Only the pieces the car has passed on the new route are hidden: the ones from there to where it was are shown.
        val window = own.window(own.windows.windowAt(near), reference.table, reference.runs)
        assertEquals(window.tints.filter { it.to <= near }.map { it.id }.toSet(), hidden())
        assertTrue(window.tints.any { it.to > near && it.to <= far }, "tints between the two places")
    }

    @Test
    fun `nothing is sent while a new route loads, and the old route's window is no longer wanted`() {
        drawn()
        // The car on and a reroute onto the same road under a new id: the SDK's line until the new path has loaded.
        val other = "route-y#0"
        val otherPlan = requireNotNull(FreewayExit.plan(other, legs, shapes))
        line.onRoute(other, refresh = false, OwnRouteLine.Build { version, withLanes -> ownLineDrawing(other, legs, shapes, otherPlan, version, withLanes) }, traffic)
        assertFalse(line.drawn)
        assertTrue(style.primaryShown)
        assertFalse(lanes.litShown)
        // Before its path is built and loaded the car's progress sends nothing.
        puck(2_500.0)
        line.onProgress()
        assertTrue(lanes.sent.isEmpty())
        scene.jobs.runAll()
        assertEquals(1, lanes.sent.size, "the new route's first window, with its path")
        line.onProgress()
        scene.jobs.runAll()
        assertEquals(1, lanes.sent.size)
    }

    @Test
    fun `guidance end clears the drawing's source and forgets its windows`() {
        drawn(car = own.windows.cards.first().start + 200.0)
        assertTrue(lanes.live.isNotEmpty())
        style.calls.clear()
        line.onGuidanceEnded()
        assertTrue("lanes.clear" in style.calls, "${style.calls}")
        assertNull(lanes.held)
        // And its feature states: the next drive's pieces may have the same ids.
        assertTrue(lanes.live.isEmpty())
        assertTrue(style.primaryShown)
        puck(2_500.0)
        line.onProgress()
        scene.jobs.runAll()
        assertTrue(lanes.sent.isEmpty())
    }

    @Test
    fun `a style load sends the window the map held again with the path`() {
        drawn()
        val held = lanes.held
        scene.reload()
        style.calls.clear()
        line.onStyleLoaded(Look.DAY)
        assertEquals(listOf("show", "lanes.remove", "add", "lanes.add", "watchLoads", "setPath", "lanes.watchLoads", "lanes.setWindow"), style.calls)
        assertEquals(held, lanes.sent.values.single())
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
        assertEquals("style_load", laneLines().last()["reason"])
    }

    @Test
    fun `a look switch colours the lanes and both pairs of the route line`() {
        drawn()
        style.calls.clear()
        line.setLook(Look.DAY)
        assertEquals(listOf("lanes.setColours", "setGradients", "lanes.setEraser"), style.calls)
        assertEquals(MapPalette.lanes(Look.DAY), lanes.shownColours)
        // The eraser in the day's road colour.
        assertEquals(OwnRouteLine.eraserGradient(requireNotNull(built.last().lanes).lit, MapPalette.lanes(Look.DAY).asphalt), lanes.eraserStops)
        assertTrue(style.gradients.getValue(OwnRouteLine.LIT_CASING).any { it.colour == OwnRouteLine.casingColour(Look.DAY) })
    }

    /** The colour [stops] give at [fraction] of the path, as a step gradient. */
    private fun colourAt(stops: List<OwnRouteLine.ColourStop>, fraction: Double): Int = stops.last { it.from <= fraction }.colour

    @Test
    fun `the eraser covers the lit lane behind the car with the route line's own trim, so the amber ends at the car`() {
        val stretch = own.litStations.first()
        drawn(car = stretch.start + 40.0)
        val drawing = built.last()
        val lit = requireNotNull(drawing.lanes).lit
        val road = MapPalette.lanes(Look.NIGHT).asphalt
        val eraser = requireNotNull(lanes.eraserStops)
        // The road's colour exactly over the lit stretches, nothing elsewhere.
        assertEquals(OwnRouteLine.eraserGradient(lit, road), eraser)
        var x = 0.0
        while (x < 1.0) {
            val inLit = lit.any { x >= it.start && x < it.endInclusive }
            assertEquals(if (inLit) road else OwnRouteLine.TRANSPARENT, colourAt(eraser, x), "at $x")
            x += 0.0007
        }
        var car = stretch.start + 40.0
        while (car < stretch.endInclusive) {
            puck(car)
            val trim = requireNotNull(lanes.eraserTrim)
            // The eraser takes the route line's trim, the car's place on the path.
            assertEquals(style.trim!!, trim, 0.0)
            assertEquals(drawing.table.pathFraction(car), trim, 1e-9)
            // The eraser is drawn up to the trim: the first lit fraction it leaves is the trim's, where the car is.
            val litHere = lit.first { trim in it }
            val step = (litHere.endInclusive - litHere.start) / 20_000.0
            var first = litHere.start
            while (first < litHere.endInclusive && first < trim && colourAt(eraser, first) == road) first += step
            assertEquals(trim, first, 2 * step, "the amber begins at the car at $car")
            car += 37.0
        }
    }

    @Test
    fun `a style that refuses a window costs the route its own line, never the drive`() {
        lanes.refuse = "setWindow"
        start()
        assertTrue(style.primaryShown)
        assertFalse(line.drawn)
        assertEquals("exception", scene.log.lines.last { it.first == OwnRouteLine.ROUTE_LINE }.second["ownLineFallback"])
        assertTrue("lanes.clear" in style.calls)
    }

    @Test
    fun `the watchdog gives the line back when the lanes' layers are gone or a window does not load`() {
        drawn()
        lanes.positions = null
        line.check()
        assertTrue(style.primaryShown)
        assertEquals("no_data", scene.log.lines.last { it.first == OwnRouteLine.ROUTE_LINE }.second["ownLineFallback"])

        val late = OwnRouteLineLanesTest()
        late.drawn()
        // The car drives on until the next window is sent, which never loads.
        var car = 0.0
        while (late.lanes.sent.isEmpty() && car < late.routeEnd) {
            late.puck(car)
            late.line.onProgress()
            late.scene.jobs.runAll()
            car += 20.0
        }
        assertEquals(1, late.lanes.sent.size)
        late.scene.now += OwnRouteLine.LOAD_WAIT_NANOS + 1
        late.line.check()
        assertTrue(late.style.primaryShown)
    }

    @Test
    fun `with the lanes switch off nothing of them is touched and the line has its one pair`() {
        line.setEnabled(true)
        line.onStyleLoaded(Look.NIGHT)
        line.onRoute(id, refresh = false, build(), traffic)
        scene.jobs.runAll()
        scene.load()
        assertTrue(line.drawn)
        puck(3_000.0)
        line.onProgress()
        // A close view latched close in: the line is the line, and the camera line has no swap in it.
        line.onLatched(true)
        line.onCameraZoom(zoomOf(13.0), 0.0)
        assertTrue(style.calls.none { it.startsWith("lanes.") }, "${style.calls}")
        assertTrue("setColours" in style.calls && "setGradients" !in style.calls)
        assertTrue("setLitShown" !in style.calls)
        assertEquals(setOf("own", "trims"), routeLine().keys.intersect(setOf("own", "trims", "swapped", "swaps")))
        assertNull(built.last().lanes)
        assertEquals(OwnRouteLine.LAYERS.map { it.id }, line.layerIds)
    }

    /** The zoom at latitude 0 where a drawn freeway lane is [dp] wide. */
    private fun zoomOf(dp: Double) = log2(MapScale.metersPerDp(0.0, 0.0) * dp / LaneTopology.FREEWAY_LANE_METERS)

    @Suppress("UNCHECKED_CAST")
    private fun routeLine() = line.diagnostics().getValue("routeLine") as Map<String, Any?>

    @Test
    fun `the route line and the lit lane swap on the camera's latched view and zoom, written only where the state flips`() {
        drawn()
        // Drawn with the pair over the lit stretches whole: the state a drive starts with.
        assertEquals(listOf(true), style.litPairWrites)
        routeLine()
        style.litPairWrites.clear()
        // Close in with no view latched, or latched with small lanes: the line stays.
        line.onCameraZoom(zoomOf(13.0), 0.0)
        line.onLatched(false)
        line.onCameraZoom(zoomOf(4.0), 0.0)
        line.onLatched(true)
        assertTrue(style.litPairWrites.isEmpty())
        // The close view goes in, a camera change a frame and a progress update every few: the lit lane from the first
        // change where a drawn lane is 10.4 dp wide, one write.
        var dp = 4.0
        var frame = 0
        while (dp < 13.0) {
            line.onCameraZoom(zoomOf(dp), 0.0)
            if (frame++ % 3 == 0) line.onLatched(true)
            dp += 0.05
        }
        assertEquals(listOf(false), style.litPairWrites)
        assertEquals(mapOf("swapped" to true, "swaps" to 1), routeLine().filterKeys { it == "swapped" || it == "swaps" })
        // A view resting between the two widths, progress after progress: nothing written.
        repeat(300) { i ->
            line.onCameraZoom(zoomOf(LaneSwap.SWAP_OUT_DP + 0.05 + (i % 7) * 0.1), 0.0)
            line.onLatched(true)
        }
        assertEquals(listOf(false), style.litPairWrites)
        // Under 9.6 dp the line again; in again; and the view letting go brings the line back at once, at any zoom.
        line.onCameraZoom(zoomOf(9.5), 0.0)
        line.onCameraZoom(zoomOf(12.0), 0.0)
        line.onLatched(false)
        assertEquals(listOf(false, true, false, true), style.litPairWrites)
        assertEquals(mapOf("swapped" to false, "swaps" to 3), routeLine().filterKeys { it == "swapped" || it == "swaps" })
        // Only the swap's own calls: no colour, gradient, trim or lanes call came with it.
        assertTrue(style.calls.takeLast(4).all { it == "setLitShown" }, "${style.calls.takeLast(4)}")
    }

    @Test
    fun `nothing is written before the line is drawn, and each drawing takes the swap's state, after a style load or a reroute too`() {
        start()
        line.onLatched(true)
        line.onCameraZoom(zoomOf(13.0), 0.0)
        assertTrue(style.litPairWrites.isEmpty())
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
        assertEquals(listOf(false), style.litPairWrites)
        // A style load: the new style's pair is whole, and hidden again once the line is drawn on it.
        scene.reload()
        line.onStyleLoaded(Look.DAY)
        assertEquals(true, style.litPair)
        scene.load()
        scene.loadWindow()
        assertEquals(false, style.litPair)
        // A reroute in the run-up with the view still latched: the new route's line is drawn swapped too.
        val other = "route-y#0"
        val otherPlan = requireNotNull(FreewayExit.plan(other, legs, shapes))
        line.onRoute(other, refresh = false, OwnRouteLine.Build { version, withLanes -> ownLineDrawing(other, legs, shapes, otherPlan, version, withLanes) }, traffic)
        style.litPairWrites.clear()
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertTrue(line.drawn)
        assertEquals(listOf(false), style.litPairWrites)
        // Guidance end: the next drive starts with the line over the lit stretches, until a view latches.
        line.onGuidanceEnded()
        style.litPairWrites.clear()
        puck(0.0)
        line.onRoute(id, refresh = false, build(), traffic)
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertEquals(listOf(true), style.litPairWrites)
    }

    @Test
    fun `at the swap the lit lane's amber is nearly as wide as the held route line it takes the place of, and wider closer in`() {
        // The own line, both pairs, holds its width from 16.5 on: 10.38 dp.
        val held = RouteLineWidths.widthAt(RouteLineWidths.LINE, RouteLineWidths.HOLD_ZOOM)
        assertEquals(10.38, held, 0.005)
        for (layer in OwnRouteLine.LAYERS + OwnRouteLine.LIT_LAYERS) {
            assertEquals(RouteLineWidths.widthAt(layer.widths, 19.5), RouteLineWidths.widthAt(layer.widths, RouteLineWidths.HOLD_ZOOM), 1e-9)
        }
        val atSwap = LaneSwap.amberDp(zoomOf(LaneSwap.SWAP_IN_DP), 0.0)
        assertTrue(atSwap / held >= 0.94, "the amber $atSwap dp at the swap, the line $held dp")
        assertTrue(atSwap < held)
        // With lanes of 12 dp the amber is wider than the line was.
        assertTrue(LaneSwap.amberDp(zoomOf(12.0), 0.0) > held)
    }

    @Test
    fun `the arrow along the lit lane is there only while the own line is drawn with its lanes for that route`() {
        val offRamp = OwnLanes.StepAt(0, 1)
        val arrow = own.arrows.getValue(offRamp)
        assertNull(line.goreArrow(id, 0, 1))
        start()
        // Built, not drawn yet: the SDK's line, and its arrow.
        assertNull(line.goreArrow(id, 0, 1))
        scene.load()
        scene.loadWindow()
        assertEquals(arrow, line.goreArrow(id, 0, 1))
        // Only the off ramp's, and only for the route drawn.
        assertNull(line.goreArrow(id, 0, 2))
        assertNull(line.goreArrow(id, 0, 0))
        assertNull(line.goreArrow("route-y#0", 0, 1))
        // A style load: none until the line is drawn on the new style.
        scene.reload()
        line.onStyleLoaded(Look.DAY)
        assertNull(line.goreArrow(id, 0, 1))
        scene.load()
        scene.loadWindow()
        assertEquals(arrow, line.goreArrow(id, 0, 1))
        // A reroute: none while the new route's path loads; once it is drawn, the new route's own.
        val other = "route-y#0"
        val otherPlan = requireNotNull(FreewayExit.plan(other, legs, shapes))
        line.onRoute(other, refresh = false, OwnRouteLine.Build { version, withLanes -> ownLineDrawing(other, legs, shapes, otherPlan, version, withLanes) }, traffic)
        assertNull(line.goreArrow(id, 0, 1))
        assertNull(line.goreArrow(other, 0, 1))
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertEquals(arrow, line.goreArrow(other, 0, 1))
        assertNull(line.goreArrow(id, 0, 1))
        // The lanes switched off: the own line is drawn again without them, and has no such arrow.
        line.setLanes(false)
        scene.jobs.runAll()
        scene.load()
        assertTrue(line.drawn)
        assertNull(line.goreArrow(other, 0, 1))
        // Guidance end: none.
        line.setLanes(true)
        scene.jobs.runAll()
        scene.load()
        scene.loadWindow()
        assertEquals(arrow, line.goreArrow(other, 0, 1))
        line.onGuidanceEnded()
        assertNull(line.goreArrow(other, 0, 1))
    }

    @Test
    fun `a style that refuses the own line gives the navigation SDK's arrow back with its line`() {
        drawn()
        assertTrue(line.goreArrow(id, 0, 1) != null)
        lanes.positions = null
        line.check()
        assertTrue(style.primaryShown)
        assertNull(line.goreArrow(id, 0, 1))
    }
}
