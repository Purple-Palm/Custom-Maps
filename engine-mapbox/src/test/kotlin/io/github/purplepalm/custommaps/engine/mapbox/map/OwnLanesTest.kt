package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.MultiPolygon
import com.mapbox.geojson.Polygon
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection
import io.github.purplepalm.custommaps.nav.lanes.geometry.GoreArrow
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.lanes.geometry.RoutePath
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The app's own lanes of a made-up freeway exit as the map gets them ([FreewayExit]): the windows' features as GeoJSON,
 * what is set on them by feature state, the route line's two pairs of gradients, and the maneuver arrow at the gore.
 */
class OwnLanesTest {

    private val id = "route-x#0"
    private val legs = FreewayExit.route(75, listOf(54, 62, 70)).first
    private val shapes = FreewayExit.route(75, listOf(54, 62, 70)).second
    private val plan = requireNotNull(FreewayExit.plan(id, legs, shapes))
    private val drawing = ownLineDrawing(id, legs, shapes, plan, 1, lanes = true)
    private val own = requireNotNull(drawing.lanes)

    /** A station inside the exit's run-up, where a lane is lit. */
    private val inRunUp = own.windows.cards.first().start + 80.0

    @Test
    fun `the lanes are drawn only where they are asked for and the route has a lane plan, on the same path`() {
        assertNull(ownLineDrawing(id, legs, shapes, plan, 1, lanes = false).lanes)
        assertNull(ownLineDrawing(id, legs, shapes, null, 1, lanes = true).lanes)
        assertEquals(ownLineDrawing(id, legs, shapes, plan, 1, lanes = false).points, drawing.points)
        // The drawn lanes are lit in the run-up, and their stretches are fractions of the path.
        assertTrue(own.litStations.isNotEmpty())
        assertEquals(own.litStations.map { drawing.table.pathFraction(it.start)..drawing.table.pathFraction(it.endInclusive) }, own.lit)
        // Lit only inside the plan's card windows.
        for (s in own.litStations) assertTrue(own.windows.cards.any { s.start >= it.start - 1e-6 && s.endInclusive <= it.endInclusive + 1e-6 }, "$s")
    }

    @Test
    fun `a window's features carry what the layers read, pieces with their ids`() {
        val window = own.windows.windowAt(inRunUp)
        val built = own.window(window, drawing.table, drawing.runs)
        val features = own.drawing.features(window.from, window.to)
        val json = requireNotNull(built.collection.features())
        assertEquals(features.size, json.size)
        assertEquals(json.size, built.features)
        assertEquals(features.sumOf { it.coordinates }, built.coordinates)
        assertEquals(built.collection.toJson().length, built.bytes)
        assertTrue(built.withinBudget)
        val kinds = LaneDrawing.Kind.entries.associateBy { it.logName }
        for ((f, g) in features.zip(json)) {
            val kind = kinds.getValue(g.getStringProperty(LaneLayers.Key.KIND))
            assertEquals(f.kind, kind)
            if (kind.polygon) {
                assertTrue(g.geometry() is Polygon || g.geometry() is MultiPolygon, "$kind")
            } else {
                assertTrue(g.geometry() is LineString || g.geometry() is MultiLineString, "$kind")
                assertEquals(LaneLayers.widthName(requireNotNull(f.width)), g.getStringProperty(LaneLayers.Key.WIDTH))
            }
            f.paint?.let { assertEquals(LaneLayers.paintName(it), g.getStringProperty(LaneLayers.Key.PAINT)) }
            // Only the pieces the map changes by feature state have ids.
            assertEquals(f.id?.toString(), g.id())
            assertEquals(f.kind == LaneDrawing.Kind.LIT || f.kind == LaneDrawing.Kind.TINT, g.id() != null, "$kind")
        }
        // Every kind but the ones this exit has none of.
        assertTrue(features.map { it.kind }.toSet().containsAll(listOf(LaneDrawing.Kind.ASPHALT, LaneDrawing.Kind.LIT, LaneDrawing.Kind.LIT_EDGE, LaneDrawing.Kind.LANE_LINE, LaneDrawing.Kind.EDGE_LINE)))
        // The tints' pieces in the order they end, every one a tint of the window.
        val tintIds = features.filter { it.kind == LaneDrawing.Kind.TINT }.map { it.id.toString() }.toSet()
        assertTrue(built.tints.isNotEmpty())
        assertEquals(tintIds, built.tints.map { it.id }.toSet())
        assertEquals(built.tints.sortedBy { it.to }, built.tints)
        assertTrue(built.latitude > 0.0)
    }

    @Test
    fun `a lit piece takes its stretch's traffic by feature state, and the route's amber where it has none`() {
        val window = own.windows.windowAt(inRunUp)
        val low = own.window(window, drawing.table, listOf(TrafficRun(0.0, 1.0, TrafficLevel.LOW)))
        assertTrue(low.levels.isEmpty())
        val heavy = own.window(window, drawing.table, listOf(TrafficRun(0.0, 1.0, TrafficLevel.HEAVY)))
        val lit = own.drawing.features(window.from, window.to).filter { it.kind == LaneDrawing.Kind.LIT }
        assertTrue(lit.isNotEmpty())
        assertEquals(lit.map { it.id.toString() to LaneLayers.Key.LEVEL_HEAVY }, heavy.levels)
        // A refresh gives each lit piece its new level, none where it is back to the amber.
        assertEquals(lit.map { it.id.toString() to null }, own.levels(heavy, drawing.table, listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN))))
        assertNull(OwnLanes.levelName(TrafficLevel.UNKNOWN))
        assertNull(OwnLanes.levelName(TrafficLevel.LOW))
        assertEquals("moderate", OwnLanes.levelName(TrafficLevel.MODERATE))
        assertEquals("heavy", OwnLanes.levelName(TrafficLevel.HEAVY))
        assertEquals("severe", OwnLanes.levelName(TrafficLevel.SEVERE))
        assertEquals("closed", OwnLanes.levelName(TrafficLevel.CLOSED))
    }

    @Test
    fun `a window over road without lane data has nothing in it`() {
        val empty = own.window(own.windows.windowAt(0.0), drawing.table, drawing.runs)
        assertEquals(0, empty.features)
        assertTrue(empty.tints.isEmpty())
        assertTrue(empty.latitude.isNaN())
    }

    @Test
    fun `the eraser is the road's colour over each lit stretch and nothing elsewhere`() {
        val road = MapPalette.lanes(Look.NIGHT).asphalt
        val t = OwnRouteLine.TRANSPARENT
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, t)), OwnRouteLine.eraserGradient(emptyList(), road))
        assertEquals(
            listOf(OwnRouteLine.ColourStop(0.0, t), OwnRouteLine.ColourStop(0.2, road), OwnRouteLine.ColourStop(0.3, t), OwnRouteLine.ColourStop(0.7, road)),
            OwnRouteLine.eraserGradient(listOf(0.7..1.0, 0.2..0.3), road),
        )
        // A stretch from the path's start covers it from there.
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, road), OwnRouteLine.ColourStop(0.15, t)), OwnRouteLine.eraserGradient(listOf(0.0..0.15), road))
    }

    /** The colour [stops] give at [fraction] of the path, as a step gradient. */
    private fun colourAt(stops: List<OwnRouteLine.ColourStop>, fraction: Double): Int = stops.last { it.from <= fraction }.colour

    @Test
    fun `with the lanes the route line's two pairs make the one line, each transparent where the other draws`() {
        val runs = listOf(
            TrafficRun(0.0, 0.5, TrafficLevel.LOW),
            TrafficRun(0.5, 0.7, TrafficLevel.HEAVY),
            TrafficRun(0.7, 1.0, TrafficLevel.UNKNOWN),
        )
        val lit = listOf(0.35..0.62, 0.82..0.9)
        for (look in Look.entries) {
            val gradients = OwnRouteLine.laneGradients(runs, lit, look).associate { it.layerId to it.stops }
            assertEquals(listOf(OwnRouteLine.CASING, OwnRouteLine.LINE, OwnRouteLine.LIT_CASING, OwnRouteLine.LIT_LINE), gradients.keys.toList())
            val single = OwnRouteLine.colourStops(runs, look)
            val casing = OwnRouteLine.casingColour(look)
            var x = 0.0005
            while (x < 1.0) {
                val litHere = lit.any { x >= it.start && x <= it.endInclusive }
                val line = colourAt(single, x)
                val shown = if (litHere) OwnRouteLine.LIT_LINE else OwnRouteLine.LINE
                val hidden = if (litHere) OwnRouteLine.LINE else OwnRouteLine.LIT_LINE
                assertEquals(line, colourAt(gradients.getValue(shown), x), "$look line at $x")
                assertEquals(OwnRouteLine.TRANSPARENT, colourAt(gradients.getValue(hidden), x), "$look at $x")
                assertEquals(casing, colourAt(gradients.getValue(if (litHere) OwnRouteLine.LIT_CASING else OwnRouteLine.CASING), x))
                assertEquals(OwnRouteLine.TRANSPARENT, colourAt(gradients.getValue(if (litHere) OwnRouteLine.CASING else OwnRouteLine.LIT_CASING), x))
                x += 0.001
            }
            // Stops only where a run or a lit stretch begins or ends.
            val ends = (runs.flatMap { listOf(it.from, it.to) } + lit.flatMap { listOf(it.start, it.endInclusive) }).toSet()
            for (stops in gradients.values) for (stop in stops) assertTrue(stop.from in ends, "a stop at ${stop.from}")
        }
        // Without a lit stretch the second pair draws nothing and the first is the line.
        val none = OwnRouteLine.laneGradients(runs, emptyList(), Look.NIGHT).associate { it.layerId to it.stops }
        assertEquals(listOf(OwnRouteLine.ColourStop(0.0, OwnRouteLine.TRANSPARENT)), none.getValue(OwnRouteLine.LIT_LINE))
        assertEquals(OwnRouteLine.colourStops(runs, Look.NIGHT), none.getValue(OwnRouteLine.LINE))
        assertNotNull(none[OwnRouteLine.LIT_CASING])
    }

    /** How far north of the equator [p] lies, and how far east of the route's own line (longitude 0), in metres. */
    private fun north(p: GeoPoint) = Geo.meters(GeoPoint(0.0, 0.0), GeoPoint(p.latitude, 0.0))

    private fun east(p: GeoPoint) = Geo.meters(GeoPoint(p.latitude, 0.0), p) * if (p.longitude < 0.0) -1.0 else 1.0

    @Test
    fun `the maneuver at the gore gets its arrow along the path, from 12 m before the gore to 30 m past it, and no other one does`() {
        // The off ramp begins the leg's second step, at the drawing's gore; the turn and the arrival get none.
        val gore = requireNotNull(plan.station(0, 1, 0.0))
        assertEquals(gore, own.drawing.gores().first(), GoreArrow.SAME_PLACE_METERS)
        assertEquals(setOf(OwnLanes.StepAt(0, 1)), own.arrows.keys)
        val arrow = own.arrows.getValue(OwnLanes.StepAt(0, 1))
        // Along the line due north: it begins 12 m before the gore in the right lane, the lit one, a lane east of the
        // route's own line, where the navigation SDK's arrow would begin, and ends 30 m past the gore.
        assertEquals(gore - GoreArrow.BEFORE_METERS, north(arrow.first()), 0.01)
        assertEquals(LaneTopology.FREEWAY_LANE_METERS, east(arrow.first()), 0.01)
        assertEquals(gore + GoreArrow.AFTER_METERS, north(arrow.last()), 0.01)
        // The points between are the drawn path's own, in order.
        val inner = arrow.subList(1, arrow.size - 1)
        assertTrue(inner.isNotEmpty())
        assertTrue(inner.all { it in drawing.points })
        assertEquals(inner.map { drawing.points.indexOf(it) }.sorted(), inner.map { drawing.points.indexOf(it) })
        assertTrue(arrow.zipWithNext().all { (a, b) -> north(b) > north(a) })
        // Without the lanes, no arrow of theirs: the drawing has none.
        assertNull(ownLineDrawing(id, legs, shapes, plan, 1, lanes = false).lanes?.arrows)
        // A leg's steps that begin at no gore get none, and a gore that begins no step gives none.
        assertTrue(OwnLanes.arrowsOf(own.drawing.gores(), listOf(doubleArrayOf(0.0, gore + 2.0 * GoreArrow.SAME_PLACE_METERS)), routePath()).isEmpty())
        assertTrue(OwnLanes.arrowsOf(emptyList(), listOf(doubleArrayOf(0.0, gore)), routePath()).isEmpty())
        assertEquals(setOf(OwnLanes.StepAt(1, 2)), OwnLanes.arrowsOf(own.drawing.gores(), listOf(doubleArrayOf(0.0), doubleArrayOf(0.0, 1.0, gore)), routePath()).keys)
    }

    /** The path of the made-up exit, as the own line builds it. */
    private fun routePath(): RoutePath {
        val line = shapes.flatten().flatten().map { GeoPoint(it.latitude(), it.longitude()) }
        val section = CrossSection.of(plan.plan, plan.evidence)
        return RoutePath.of(plan.plan, line, 1, plan.evidence, section)
    }
}
