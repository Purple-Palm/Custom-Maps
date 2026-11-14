package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Kind
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Paint
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Pattern
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Width
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The app's own lanes on the made-up streets of [LaneScenes.streetTurn], two-way, one-way and without a source, along made-up
 * lines at sea that run due north from 0, 0 to the turn and due east past it: before the turn a point's station is its
 * northing and its offset from the road's line, left positive, its westing.
 */
class StreetDrawingTest {

    private val lane = LaneTopology.STREET_LANE_METERS
    private val apron = CrossSection.APRON_METERS
    private val gore = LaneScenes.GORE

    private class Drawn(
        val plan: LanePlan,
        val evidence: LaneEvidence,
        val section: CrossSection,
        val path: RoutePath,
        val drawing: LaneDrawing,
        val features: List<LaneDrawing.Feature>,
    ) {
        fun of(kind: Kind) = features.filter { it.kind == kind }
    }

    /** [route] along [line] drawn with every street one-way or two-way as [oneWay] says; with no source where it is null. */
    private fun draw(route: LaneRoute, oneWay: Boolean?, line: List<GeoPoint> = SceneLines.of(route)): Drawn {
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence, oneWay?.let { LaneScenes.ways(route, it) })
        val path = RoutePath.of(plan, line, version = 1, evidence = evidence, section = section)
        val drawing = LaneDrawing.of(plan, path, evidence, section)
        return Drawn(plan, evidence, section, path, drawing, drawing.features())
    }

    private fun station(p: GeoPoint) = SceneLines.local(p).y

    private fun offset(p: GeoPoint) = -SceneLines.local(p).x

    private fun bounds(places: List<CrossSection.Place>) = listOf(places.first().left) + places.map { it.right }

    /** The asphalt's edges at a station, [left] and [right], to a millimetre. */
    private fun assertAsphalt(left: Double, right: Double, edges: Pair<Double, Double>?, what: String = "") {
        requireNotNull(edges) { "no asphalt $what" }
        assertEquals(left, edges.first, MM, "the asphalt's left edge $what")
        assertEquals(right, edges.second, MM, "the asphalt's right edge $what")
    }

    private fun assertBounds(expected: List<Double>, places: List<CrossSection.Place>) {
        val got = bounds(places)
        assertEquals(expected.size, got.size, "$got")
        for (i in expected.indices) assertEquals(expected[i], got[i], MM, "boundary $i of $got")
    }

    @Test
    fun `on a two-way street the route's lanes lie right of the road's line, the oncoming half asphalt without a line`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = false)
        val at = gore - 500.0
        assertBounds(listOf(0.0, -lane, -2.0 * lane), d.drawing.lanesAt(at))
        // The asphalt takes the oncoming half too, as wide as the route's lanes, with an apron outside each.
        assertAsphalt(2.0 * lane + apron, -2.0 * lane - apron, d.drawing.asphaltAt(at))
        // No edge line, no centre line: the only line is between the route's own two lanes, broken.
        assertTrue(d.of(Kind.EDGE_LINE).isEmpty())
        val lines = d.of(Kind.LANE_LINE)
        assertTrue(lines.isNotEmpty())
        for (f in lines) {
            assertEquals(Pattern.BROKEN, f.pattern)
            for (p in f.parts.flatten()) assertEquals(-lane, offset(p), MM, "a line at ${station(p)}")
        }
        // The path keeps to the route's own half, never over the oncoming one.
        val drawing = d.path.drawings.single()
        var s = drawing.from
        while (s <= drawing.carriagewayTo) {
            assertTrue(d.path.offsetAt(s) <= 1e-9, "the path at $s")
            s += 5.0
        }
    }

    @Test
    fun `on a one-way street the lanes lie about the road's line with both edge lines, broken at a crossing`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = true)
        val at = gore - 500.0
        assertBounds(listOf(lane, 0.0, -lane), d.drawing.lanesAt(at))
        assertAsphalt(lane + apron, -lane - apron, d.drawing.asphaltAt(at))
        val edges = d.of(Kind.EDGE_LINE)
        for (side in listOf(LaneEdge.LEFT, LaneEdge.RIGHT)) {
            val mine = edges.filter { it.side == side }.sortedBy { it.from }
            // From where the lanes begin to 15 m before the crossing 400 m before the turn, and from 15 m past it to the turn's
            // near side.
            assertEquals(2, mine.size, "$side")
            for ((f, span) in mine.zip(listOf(gore - 650.0 to gore - 415.0, gore - 385.0 to gore - 10.0))) {
                assertEquals(span.first, f.from, MM, "$side")
                assertEquals(span.second, f.to, MM, "$side")
            }
            for (f in mine) {
                assertEquals(if (side == LaneEdge.LEFT) Paint.YELLOW else Paint.WHITE, f.paint)
                assertEquals(Pattern.SOLID, f.pattern)
                for (p in f.parts.flatten()) assertEquals(if (side == LaneEdge.LEFT) lane else -lane, offset(p), MM)
            }
        }
    }

    @Test
    fun `a stop line stands 10 m before an intersection only where a signal or a stop sign is flagged, across the route's lanes`() {
        for (oneWay in listOf(false, true)) {
            val d = draw(LaneScenes.streetTurn(), oneWay)
            // The signal 400 m before the turn and the stop sign at it; none at the intersection between them, which has neither.
            assertEquals(
                listOf(LaneDrawing.StopLine(1, gore - 410.0), LaneDrawing.StopLine(3, gore - 10.0)),
                d.drawing.stopLines,
            )
            val lines = d.of(Kind.STOP_LINE).sortedBy { it.from }
            assertEquals(2, lines.size)
            for ((f, line) in lines.zip(d.drawing.stopLines)) {
                assertEquals(line.station - 0.60, f.from, MM)
                assertEquals(line.station, f.to, MM)
                assertEquals(Width.STOP, f.width)
                assertEquals(0.60, f.width?.meters)
                assertEquals(Paint.WHITE, f.paint)
                assertEquals(Pattern.SOLID, f.pattern)
                // From edge to edge of the route's lanes, at the line's middle: on a two-way street from the road's line.
                val across = f.parts.single().map { offset(it) }.sorted()
                val expected = if (oneWay) listOf(-lane, lane) else listOf(-2.0 * lane, 0.0)
                assertEquals(expected[0], across[0], MM)
                assertEquals(expected[1], across[1], MM)
                for (p in f.parts.single()) assertEquals(line.station - 0.30, station(p), MM)
            }
        }
        // No flag, no stop line, though the lanes are drawn up to the junction.
        val pocket = draw(LaneScenes.streetPocket(), oneWay = false)
        assertTrue(pocket.path.drawings.isNotEmpty())
        assertTrue(pocket.of(Kind.STOP_LINE).isEmpty())
        assertTrue(pocket.drawing.stopLines.isEmpty())
    }

    @Test
    fun `a street's lane arrows lie 9 m before its stop line, 2_9 m long straight on and 2_44 m for a turn`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = false)
        val arrows = d.of(Kind.ARROW)
        // One row at each lane point: 10 m to the stop line, 9 m more.
        assertEquals(listOf(gore - 419.0, gore - 219.0, gore - 19.0), arrows.map { it.from }.distinct().sorted())
        for (arrow in arrows) {
            val straight = arrow.indications == listOf(TurnModifier.STRAIGHT)
            assertEquals(if (straight) 2.9 else 2.44, arrow.to - arrow.from, MM, "${arrow.indications} at ${arrow.from}")
            assertEquals(Paint.WHITE, arrow.paint)
        }
        // At the turn the right lane's arrow turns right; the other lane's goes straight on.
        val atTurn = arrows.filter { abs(it.from - (gore - 19.0)) < MM }.sortedBy { it.lane }
        assertEquals(listOf(listOf(TurnModifier.STRAIGHT), listOf(TurnModifier.RIGHT)), atTurn.map { it.indications })
        // A straight arrow: tail to tip 2.9 m, its head 0.6 m wide, its stem 0.15 m, in the middle of its lane.
        val straight = arrows.first { it.indications == listOf(TurnModifier.STRAIGHT) && abs(it.from - (gore - 419.0)) < MM }.parts.single()
        assertEquals(2.9, straight.maxOf { station(it) } - straight.minOf { station(it) }, MM)
        val across = straight.map { offset(it) }
        assertEquals(0.6, across.max() - across.min(), MM)
        val stem = straight.filter { station(it) < gore - 419.0 + 0.5 }.map { offset(it) }
        assertEquals(0.15, stem.max() - stem.min(), MM)
        val middle = (across.max() + across.min()) / 2.0
        assertTrue(abs(middle + 0.5 * lane) < MM || abs(middle + 1.5 * lane) < MM, "the arrow's middle at $middle")
    }

    @Test
    fun `a lane only for bicycles is a 1_8 m strip beside a solid line, never a vehicle lane, and one kept for buses is darker`() {
        val d = draw(LaneScenes.streetTurn(bicycle = true, bus = true), oneWay = false)
        val lanes = d.plan.topology.lanes
        val bike = lanes.filter { it.kinds == setOf(DesignatedKind.BICYCLE) }.map { it.id }.toSet()
        val bus = lanes.filter { it.kinds == setOf(DesignatedKind.BUS) }.map { it.id }.toSet()
        assertTrue(bike.isNotEmpty() && bus.isNotEmpty())
        val at = gore - 500.0
        val places = d.drawing.lanesAt(at)
        val strip = places.last()
        assertTrue(strip.lane in bike)
        assertEquals(1.8, strip.width, MM)
        // The asphalt: the route's lanes (bus, two lanes, bicycles) and as wide an oncoming half, with their aprons.
        assertAsphalt(3.0 * lane + 1.8 + apron, -3.0 * lane - 1.8 - apron, d.drawing.asphaltAt(at))
        // A solid normal line beside it, one line, never a double one.
        val beside = d.of(Kind.LANE_LINE).filter { it.between?.second in bike || it.between?.first in bike }
        assertTrue(beside.isNotEmpty())
        for (f in beside) {
            assertEquals(Pattern.SOLID, f.pattern)
            assertEquals(Width.NORMAL, f.width)
            assertEquals(1, f.parts.size)
        }
        // No arrow, no darker fill, never lit or tinted.
        for (kind in listOf(Kind.ARROW, Kind.KEPT_LANE, Kind.LIT, Kind.TINT, Kind.LIT_EDGE)) {
            assertTrue(d.of(kind).none { it.lane in bike }, "a $kind on the bicycle lane")
        }
        // The lane kept for buses is darker, with no arrow.
        assertTrue(d.of(Kind.KEPT_LANE).any { it.lane in bus })
        assertTrue(d.of(Kind.ARROW).none { it.lane in bus })
        // The named lane is a general lane.
        assertTrue(d.of(Kind.LIT).all { it.lane !in bike && it.lane !in bus })
    }

    @Test
    fun `inside a junction the route goes on through the map's own road shows, and the lit lane crosses it`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = false)
        // The crossing 400 m before the turn; none at the intersection 200 m before it, which no road meets.
        assertEquals(listOf(gore - 410.0..gore - 390.0), d.drawing.junctionInteriors)
        for ((kind, spans) in listOf(
            Kind.ASPHALT to listOf(gore - 690.0 to gore - 410.0, gore - 390.0 to gore - 10.0),
            Kind.LANE_LINE to listOf(gore - 650.0 to gore - 410.0, gore - 390.0 to gore - 10.0),
        )) {
            val got = d.of(kind).sortedBy { it.from }
            assertEquals(spans.size, got.size, "$kind")
            for ((f, span) in got.zip(spans)) {
                assertEquals(span.first, f.from, MM, "$kind")
                assertEquals(span.second, f.to, MM, "$kind")
            }
        }
        // Square on both sides, at the carriageway's full width; nothing inside, where the lanes go on undrawn.
        for (x in listOf(gore - 410.0, gore - 390.0)) assertAsphalt(2.0 * lane + apron, -2.0 * lane - apron, d.drawing.asphaltAt(x))
        assertNull(d.drawing.asphaltAt(gore - 400.0))
        assertBounds(listOf(0.0, -lane, -2.0 * lane), d.drawing.lanesAt(gore - 400.0))
        // A crossing inside the card window: the lit lane goes on across it, nothing else of ours does.
        val nodes = (LaneScenes.streetTurn().nodes + LaneScenes.street(gore - 100.0, roads = LaneScenes.CROSSING)).sortedBy { it.station }
        val across = draw(LaneScenes.route(*nodes.toTypedArray(), id = "street-crossing-lit"), oneWay = false)
        assertTrue(gore - 110.0..gore - 90.0 in across.drawing.junctionInteriors)
        // A station inside it, off the lit lane's 50 m pieces' ends.
        val x = gore - 105.0
        val axis = requireNotNull(across.path.axisAt(x))
        for (f in across.features) {
            val crossing = DrawingProbe.across(axis, x, f.parts).filter { abs(it) < 30.0 }
            if (f.kind != Kind.LIT && f.kind != Kind.LIT_EDGE) assertTrue(crossing.isEmpty(), "a ${f.kind} inside the junction")
        }
        val lit = across.of(Kind.LIT).filter { it.from < x && it.to > x }
        val edges = DrawingProbe.across(axis, x, lit.flatMap { it.parts })
        assertEquals(2, edges.size, "the lit lane across the junction")
        assertEquals(lane, edges[1] - edges[0], 0.05)
        assertEquals(across.path.offsetAt(x), (edges[0] + edges[1]) / 2.0, 0.05)
    }

    @Test
    fun `at a junction the route turns at, the carriageway ends square at its near side and the path crosses on a curve`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = false)
        val drawing = d.path.drawings.single()
        assertTrue(drawing.junctionEnd && drawing.turnsAtEnd)
        assertEquals(gore, drawing.to, MM)
        assertEquals(gore - 10.0, drawing.carriagewayTo, MM)
        // Square at the near side, at full width; nothing of ours past it.
        assertAsphalt(2.0 * lane + apron, -2.0 * lane - apron, d.drawing.asphaltAt(gore - 10.0))
        assertNull(d.drawing.asphaltAt(gore - 9.0))
        assertTrue(d.drawing.lanesAt(gore - 9.0).isEmpty())
        assertTrue(d.features.all { it.to <= gore - 10.0 + MM }, "${d.features.filter { it.to > gore - 10.0 + MM }.map { it.kind to it.to }}")
        // The path: in the right lane to the near side, and from there a curve to the new road's line at the far side, 10 m along it.
        val junction = d.path.junctions.single()
        assertEquals(3, junction.node)
        assertEquals(gore - 10.0, junction.from, MM)
        assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, junction.to, MM)
        assertEquals(-1.5 * lane, junction.offsetFrom, MM)
        assertEquals(0.0, junction.offsetTo, MM)
        val first = SceneLines.local(junction.points.first())
        val last = SceneLines.local(junction.points.last())
        assertEquals(1.5 * lane, first.x, MM)
        assertEquals(gore - 10.0, first.y, MM)
        assertEquals(10.0, last.x, MM)
        assertEquals(gore, last.y, MM)
        // Along the road's way at both ends, north out of the lane and east onto the new road, turning one way only (each
        // heading here a chord's, about a metre long).
        val local = junction.points.map { SceneLines.local(it) }
        val headings = (1 until local.size).map { Math.toDegrees(atan2(local[it].x - local[it - 1].x, local[it].y - local[it - 1].y)) }
        assertEquals(0.0, headings.first(), CHORD_DEGREES)
        assertEquals(90.0, headings.last(), CHORD_DEGREES)
        for (i in 1 until headings.size) assertTrue(headings[i] >= headings[i - 1] - 1e-9, "the curve turns back at ${junction.stations[i]}")
        // Its stations climb from the near side to the far side, and the path is the curve there.
        for (i in 1 until junction.stations.size) assertTrue(junction.stations[i] > junction.stations[i - 1])
        val inside = d.path.points.indices.filter { d.path.stations[it] > junction.from + MM && d.path.stations[it] < junction.to - MM }
        assertTrue(inside.isNotEmpty())
        for (i in inside) assertTrue(junction.points.contains(d.path.points[i]), "a path point at ${d.path.stations[i]} off the curve")
        // A one-way street's path crosses from its own lane the same way.
        val oneWay = draw(LaneScenes.streetTurn(), oneWay = true).path.junctions.single()
        assertEquals(-0.5 * lane, oneWay.offsetFrom, MM)
    }

    @Test
    fun `the road after a street junction begins square at its far side, with no taper`() {
        val d = draw(LaneScenes.streetTurn(receiving = true), oneWay = false)
        val (before, after) = d.path.drawings
        assertTrue(before.junctionEnd)
        assertTrue(after.junctionStart && !after.junctionEnd)
        assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, after.from, MM)
        assertEquals(after.from, after.asphaltFrom, 0.0)
        val first = d.of(Kind.ASPHALT).filter { it.from > gore }.minBy { it.from }
        assertEquals(after.from, first.from, MM)
        // At its full width there: the lane the route turns into from the new road's line, and as wide an oncoming half.
        assertAsphalt(lane + apron, -lane - apron, d.drawing.asphaltAt(after.from))
        assertNull(d.drawing.asphaltAt(gore + 5.0))
        assertTrue(d.section.rampStarts.isEmpty())
    }

    @Test
    fun `the design's street junction - a left pocket may begin 82 m before the node, opens late and is entered along its late taper`() {
        for (oneWay in listOf(false, true)) {
            val d = draw(LaneScenes.junctionPocket(), oneWay)
            val pocket = d.plan.topology.lanes.single { it.pocket }
            val feeder = requireNotNull(pocket.fedBy)
            // At 35 mph: possibly there at full width 45 m before the stop line after an 8:1 taper; for the plan it opens late,
            // at full width 30 m before the stop line after a 15 m taper.
            val possible = gore - 10.0 - 45.0 - 8.0 * lane
            assertEquals(possible, requireNotNull(pocket.possibleFrom), MM)
            assertEquals(gore - 55.0, pocket.opens, MM)
            assertEquals(15.0, pocket.taperMeters, MM)
            assertTrue(pocket.id in d.plan.named)
            // Drawn as possible from where it may begin, plain asphalt widening toward it, at full width where it opens.
            val widening = d.drawing.lanesAt(gore - 70.0).single { it.lane == pocket.id }
            assertEquals(CrossSection.State.POSSIBLE, widening.state)
            assertTrue(widening.width > 0.0 && widening.width < lane)
            assertTrue(d.drawing.lanesAt(possible - 1.0).none { it.lane == pocket.id })
            assertEquals(lane, d.drawing.lanesAt(gore - 55.0).single { it.lane == pocket.id }.width, MM)
            // The path keeps to the lane feeding it until it opens, and enters it along its late taper, with no step.
            val feederAt = { x: Double -> d.drawing.lanesAt(x).single { it.lane == feeder }.centre }
            val pocketAt = { x: Double -> d.drawing.lanesAt(x).single { it.lane == pocket.id }.centre }
            assertEquals(feederAt(gore - 60.0), d.path.offsetAt(gore - 60.0), PATH_METERS)
            assertEquals(feederAt(gore - 55.0), d.path.offsetAt(gore - 55.0), PATH_METERS)
            assertEquals(0.0, requireNotNull(d.section.lateEntry(pocket.id, gore - 55.0)), MM)
            assertEquals(0.5, requireNotNull(d.section.lateEntry(pocket.id, gore - 47.5)), MM)
            assertEquals(1.0, requireNotNull(d.section.lateEntry(pocket.id, gore - 40.0)), MM)
            assertNull(d.section.lateEntry(feeder, gore - 47.5))
            assertEquals((feederAt(gore - 47.5) + pocketAt(gore - 47.5)) / 2.0, d.path.offsetAt(gore - 47.5), PATH_METERS)
            assertEquals(pocketAt(gore - 40.0), d.path.offsetAt(gore - 40.0), PATH_METERS)
            var x = gore - 60.0
            while (x < gore - 35.0) {
                assertTrue(abs(d.path.offsetAt(x + 0.5) - d.path.offsetAt(x)) < 0.2, "a step in the path at $x")
                x += 0.5
            }
            // The lit lane runs on across where the pocket opens, and across the junction into the receiving road's lane: one lit
            // stretch from the window's start to the junction's far side.
            assertEquals(1, d.drawing.litStretches().size)
            assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, d.drawing.litStretches().single().endInclusive, MM)
            // Beside the lit lane a dotted extension from where the possible pocket is half wide to where the path is in it,
            // solid from there to the stop line, which the signal's flag gives.
            val beside = d.of(Kind.LANE_LINE).filter { it.between == pocket.id to feeder || it.between == feeder to pocket.id }.sortedBy { it.from }
            assertEquals(listOf(Pattern.EXTENSION, Pattern.SOLID), beside.map { it.pattern })
            assertEquals(gore - 40.0, beside[0].to, MM)
            assertEquals(gore - 40.0, beside[1].from, MM)
            assertEquals(gore - 10.0, beside[1].to, MM)
            val half = beside[0].from
            assertEquals(lane / 2.0, d.drawing.lanesAt(half).single { it.lane == pocket.id }.width, 0.05)
            assertTrue(half > possible && half < gore - 55.0)
            assertEquals(0.61, LaneDrawing.Pattern.EXTENSION.dashMeters)
            assertEquals(1.22, LaneDrawing.Pattern.EXTENSION.gapMeters)
            assertEquals(listOf(LaneDrawing.StopLine(2, gore - 10.0)), d.drawing.stopLines)
            // A tint opening toward the pocket where it may be: move over when it opens.
            val tint = d.of(Kind.TINT).filter { it.lane == pocket.id }
            assertTrue(tint.isNotEmpty())
            assertEquals(possible, tint.minOf { it.from }, MM)
            assertEquals(gore - 55.0, tint.maxOf { it.to }, MM)
            val axis = requireNotNull(d.path.axisAt(gore - 60.0))
            val narrow = DrawingProbe.across(axis, gore - 75.0, tint.flatMap { it.parts })
            val wide = DrawingProbe.across(axis, gore - 60.0, tint.flatMap { it.parts })
            assertTrue(narrow[1] - narrow[0] < wide[1] - wide[0], "the tint opens toward the pocket")
            // One-way, the possible pocket widens the asphalt on the left; two-way it opens into the oncoming half.
            val asphalt = requireNotNull(d.drawing.asphaltAt(gore - 70.0))
            if (oneWay) assertTrue(asphalt.first > lane + apron + 1e-6) else assertEquals(2.0 * lane + apron, asphalt.first, MM)
        }
    }

    @Test
    fun `the lit lane crosses a street junction along the path's curve into the receiving road's nearest lane`() {
        for (oneWay in listOf(false, true)) {
            val d = draw(LaneScenes.streetTurn(receiving = true), oneWay)
            val junction = d.path.junctions.single()
            // The road past the turn: its first lane from its line, or about it one-way; the curve ends in its middle.
            val receiving = requireNotNull(junction.receiving)
            val place = d.section.lanes(junction.to).single { it.lane == receiving }
            assertEquals(place.centre, junction.offsetTo, MM)
            assertEquals(if (oneWay) 0.0 else -0.5 * lane, junction.offsetTo, MM)
            assertEquals(junction.offsetTo, d.path.offsetAt(junction.to + 5.0), PATH_METERS)
            // Lit all the way across, one stretch from the window's start to the far side, its edges half a lane off the curve.
            val stretch = d.drawing.litStretches().single()
            assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, stretch.endInclusive, MM)
            val lit = d.of(Kind.LIT).filter { it.to > junction.from + MM }
            assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, lit.maxOf { it.to }, MM)
            val edges = d.of(Kind.LIT_EDGE).filter { it.from >= junction.from - MM }
            assertEquals(2, edges.size)
            val k = junction.points.size / 2
            val middle = SceneLines.local(junction.points[k])
            for (edge in edges) {
                val nearest = edge.parts.single().minOf { SceneLines.local(it).distanceTo(middle) }
                assertEquals(lane / 2.0, nearest, 0.1, "the lit edge ${edge.side} off the curve's middle")
            }
            // The maneuver's arrow along the lit turn, from 4 m before the stop line's place to 30 m past the node.
            assertEquals(listOf(gore), d.drawing.turns())
            val arrow = GoreArrow.alongTurn(d.path.points, d.path.stations, gore)
            assertEquals(gore - 14.0, SceneLines.local(arrow.first()).y, 0.05)
            assertTrue(arrow.size > 3)
            // No tint across the junction, nothing of the receiving road's own inside it.
            assertTrue(d.of(Kind.TINT).none { it.to > junction.from + MM })
            assertNull(d.drawing.asphaltAt(gore))
        }
        // Without a drawn road past the turn no lane is claimed there: the curve ends on its line, nothing is lit across. The
        // maneuver's arrow still follows the path, out of the lit lane, not the road's line.
        val alone = draw(LaneScenes.streetTurn(), oneWay = false)
        assertNull(alone.path.junctions.single().receiving)
        assertEquals(0.0, alone.path.junctions.single().offsetTo, MM)
        assertEquals(gore - 10.0, alone.drawing.litStretches().single().endInclusive, MM)
        assertEquals(listOf(gore), alone.drawing.turns())
        val arrow = GoreArrow.alongTurn(alone.path.points, alone.path.stations, gore)
        assertEquals(alone.path.offsetAt(gore - 14.0), offset(arrow.first()), 0.05)
        assertTrue(abs(offset(arrow.first())) > lane, "the arrow begins in the lit lane, off the road's line")
    }

    @Test
    fun `from two turn lanes the curve ends in the receiving lane counted from the turn's inside as the named one is`() {
        // Two right-turn lanes; the route prefers the inner one, so the plan names it: the second from the inside.
        fun scene(innerActive: Boolean) = LaneScenes.route(
            LaneScenes.street(0.0, maneuver = LaneScenes.DEPART),
            LaneScenes.street(gore - 300.0, s(), s(), s()),
            LaneScenes.street(
                gore, s(false), LaneScenes.right(active = innerActive), LaneScenes.right(active = !innerActive),
                roads = LaneScenes.CROSSING, maneuver = LaneScenes.TURN_RIGHT,
            ),
            LaneScenes.street(gore + 150.0, s(), s()),
            LaneScenes.street(gore + 300.0, maneuver = LaneScenes.ARRIVE),
            id = "double-right",
        )
        for (innerActive in listOf(false, true)) {
            val d = draw(scene(innerActive), oneWay = false)
            val junction = d.path.junctions.single()
            val receiving = d.section.lanes(junction.to).filter { it.width > 1e-6 }
            assertEquals(2, receiving.size, "both turn lanes go on onto the new road")
            val named = requireNotNull(d.plan.segmentAt(junction.from - MM)?.named)
            val turnLanes = d.plan.topology.points.single { it.node == junction.node }.allowed
            val fromInside = turnLanes.count { d.plan.topology.lanes[it].slot > d.plan.topology.lanes[named].slot }
            assertEquals(if (innerActive) 1 else 0, fromInside, "the plan names the lane the route prefers")
            assertEquals(receiving[receiving.size - 1 - fromInside].lane, junction.receiving)
            assertEquals(receiving[receiving.size - 1 - fromInside].centre, junction.offsetTo, MM)
        }
    }

    @Test
    fun `a U-turn ends the lit lane where the U begins and claims no lane past it`() {
        for (oneWay in listOf(false, true)) {
            val d = draw(LaneScenes.streetUTurn(), oneWay)
            val junction = d.path.junctions.single()
            // The road back is drawn from the far side, yet no lane is named or lit there: the route's line goes on into the
            // receiving carriageway's middle.
            assertTrue(d.path.drawings.any { it.junctionStart })
            assertNull(junction.receiving)
            assertEquals(0.0, junction.offsetTo, MM)
            assertTrue(d.of(Kind.LIT).none { it.to > junction.from + MM })
            assertEquals(gore - 10.0, d.drawing.litStretches().single().endInclusive, MM)
            // The app's own U-turn arrow from the route's shape: none along the path.
            assertTrue(d.drawing.turns().isEmpty())
        }
    }

    @Test
    fun `at a U-turn the path reaches the junction on the route's line and turns back where the route does`() {
        val route = LaneScenes.streetUTurn()
        val node = LocalPoint(0.0, gore)
        val lines = listOf(
            "at one point" to (SceneLines.of(route) to listOf(node)),
            "across a median opening" to (SceneLines.uTurnAcross(route) to listOf(node, LocalPoint(-SceneLines.MEDIAN_OPENING_METERS, gore))),
        )
        for ((what, pair) in lines) {
            val (line, corners) = pair
            for (oneWay in listOf(false, true)) {
                val name = "$what ${if (oneWay) "one-way" else "two-way"}"
                val d = draw(route, oneWay, line)
                val junction = d.path.junctions.single()
                assertEquals(gore - 10.0, junction.from, MM, name)
                assertEquals(gore + RoutePath.JUNCTION_FAR_SIDE_METERS, junction.to, MM, name)
                val local = d.path.points.map { SceneLines.local(it) }
                // Up to the node the path only goes on, never back, from its lane onto the route's line at the node.
                val upTo = d.path.points.indices.filter { d.path.stations[it] <= gore + MM }
                for (k in 1 until upTo.size) assertTrue(local[upTo[k]].y >= local[upTo[k - 1]].y - MM, "$name: back at ${d.path.stations[upTo[k]]}")
                for (corner in corners) assertTrue(local.minOf { it.distanceTo(corner) } < 1.0, "$name: ${local.minOf { it.distanceTo(corner) }} m from $corner")
                // From the node to the far side it is the route's own line, turning where the route does.
                val k = junction.stations.indexOfFirst { abs(it - gore) < MM }
                assertTrue(k > 0, name)
                assertEquals(0.0, SceneLines.local(junction.points[k]).distanceTo(node), MM, name)
                for (i in k until junction.points.size) {
                    assertEquals(0.0, SceneLines.local(junction.points[i]).distanceTo(SceneLines.local(routeAt(d.path, junction.stations[i]))), MM, "$name at ${junction.stations[i]}")
                }
                // No lane claimed past it, nothing lit across, the navigation SDK's arrow.
                assertNull(junction.receiving, name)
                assertTrue(d.of(Kind.LIT).none { it.to > junction.from + MM }, name)
                assertTrue(d.drawing.turns().isEmpty(), name)
            }
        }
    }

    /** The route's own line of [path] at [station], between its points. */
    private fun routeAt(path: RoutePath, station: Double): GeoPoint {
        val st = path.routeStations
        val k = RoutePath.indexAtOrBefore(st, station).coerceIn(0, st.size - 2)
        val f = ((station - st[k]) / (st[k + 1] - st[k])).coerceIn(0.0, 1.0)
        val a = path.route[k]
        val b = path.route[k + 1]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }

    @Test
    fun `nothing of ours lies inside a roundabout, its approach a zone from where the junction view begins`() {
        for (oneWay in listOf(false, true)) {
            val d = draw(LaneScenes.roundabout(), oneWay)
            val entry = gore
            val exit = gore + 60.0
            // No lane named from the window's start, the junction view's, to the way out: a zone there, nothing lit or tinted.
            val window = d.plan.windows.single { abs(it.to - entry) < MM }
            assertEquals(entry - CardWindows.streetWindowMeters(), window.from, MM)
            assertTrue(d.plan.segments.filter { it.to > window.from + MM && it.from < exit }.all { it.named == null })
            assertTrue(d.evidence.stretches.any { it.kind == LaneEvidence.Kind.ZONE && abs(it.from - window.from) < MM })
            assertTrue(d.of(Kind.LIT).isEmpty() && d.of(Kind.TINT).isEmpty())
            // No curve of ours into it, nothing of ours from the approach's near side to the way out.
            assertTrue(d.path.junctions.isEmpty())
            assertTrue(d.features.none { it.to > entry - 10.0 + MM && it.from < exit }, "${d.features.filter { it.to > entry - 10.0 + MM }.map { it.kind }}")
            assertTrue(d.drawing.turns().isEmpty())
            // The path keeps to the road's line from the zone into it, as the route's own line does (rounded at a corner only by
            // the road's axis), and is the route's own line past the way out.
            var x = window.from + 1.0
            while (x < exit) {
                assertEquals(0.0, d.path.offsetAt(x), 1e-9, "the path off the road's line at $x")
                x += 1.0
            }
        }
    }

    @Test
    fun `nothing of ours lies on a roundabout's way round whatever lanes it lists, the road out drawn from the way out's far side`() {
        val entry = gore
        val exit = gore + 80.0
        val farSide = exit + RoutePath.JUNCTION_FAR_SIDE_METERS
        for ((atExit, onWayRound, onRamp) in listOf(Triple(true, false, false), Triple(false, true, false), Triple(true, true, false), Triple(true, true, true))) {
            val route = LaneScenes.roundaboutLanesRound(atExit, onWayRound, onRamp)
            for (oneWay in listOf(false, true)) {
                val d = draw(route, oneWay)
                val name = "${route.routeId} ${if (oneWay) "one-way" else "two-way"}"
                // The evidence has lanes on the way round; the drawing none, nor lanes for the car's bar, nor a street counted.
                assertTrue(d.evidence.stretches.any { it.lanes > 0 && it.from >= entry - MM && it.to <= exit + MM && it.to > it.from }, name)
                assertTrue(d.features.none { it.to > entry - 10.0 + MM && it.from < farSide - MM }, "$name: ${d.features.filter { it.to > entry - 10.0 + MM && it.from < farSide - MM }.map { it.kind }}")
                var x = entry - 9.5
                while (x < farSide) {
                    assertTrue(d.drawing.lanesAt(x).isEmpty(), "$name: lanes at $x")
                    if (x > entry) assertFalse(d.section.drawsAt(x), "$name: drawn at $x")
                    x += 1.0
                }
                assertTrue(d.section.drawnStreets.none { it.endInclusive > entry + MM && it.start < farSide - MM }, "$name: ${d.section.drawnStreets}")
                assertTrue(d.section.undrawnStreets.none { it.to > entry + MM && it.from < exit - MM }, "$name: ${d.section.undrawnStreets}")
                // The approach, a street's or a ramp's, ends at the junction's near side; the road out begins square at the way
                // out's far side, and the path is on the road's line all the way round.
                assertEquals(listOf(entry to true, farSide to false), d.path.drawings.map { (if (it.junctionEnd) it.to else it.from) to it.junctionEnd }, name)
                assertTrue(d.path.drawings.last().junctionStart, name)
                assertTrue(d.path.junctions.isEmpty() && d.drawing.turns().isEmpty(), name)
                x = entry - 10.0
                while (x <= farSide) {
                    assertEquals(0.0, d.path.offsetAt(x), 1e-9, "$name: the path off the road's line at $x")
                    x += 1.0
                }
            }
        }
    }

    @Test
    fun `a two-way street that goes on onto a freeway without a turn leaves the freeway one-way, with its edge lines`() {
        // The street's lanes up to 800 m before the gore, the freeway's on to 300 m before it.
        val d = draw(LaneScenes.streetOntoFreeway(), oneWay = false)
        val freeway = gore - 800.0..gore - 300.0
        val drawing = d.path.drawings.single()
        assertEquals(freeway.start, drawing.from, MM)
        assertEquals(freeway.endInclusive, drawing.to, MM)
        // About the road's line, the path on it between the two lanes, an edge line on each side all along.
        var x = freeway.start + 1.0
        while (x < freeway.endInclusive - 1.0) {
            assertEquals(CrossSection.Carriageway.ONE_WAY, d.drawing.carriagewayAt(x))
            assertBounds(listOf(lane, 0.0, -lane), d.drawing.lanesAt(x))
            assertAsphalt(lane + apron, -lane - apron, d.drawing.asphaltAt(x), "at $x")
            assertEquals(0.0, d.path.offsetAt(x), PATH_METERS, "the path at $x")
            for (side in listOf(LaneEdge.LEFT, LaneEdge.RIGHT)) {
                assertTrue(d.of(Kind.EDGE_LINE).any { it.side == side && it.from <= x && it.to >= x }, "no $side edge line at $x")
            }
            x += 10.0
        }
        // The street before it is left to the map, and counted as told otherwise than its road.
        assertTrue(d.features.none { it.from < freeway.start - RoutePath.ASPHALT_TAPER_METERS - MM })
        assertEquals(listOf(CrossSection.UndrawnStreet(gore - 1_250.0, freeway.start, mixed = true)), d.section.undrawnStreets)
    }

    @Test
    fun `without a source nothing of a street is drawn and the path is the route's own line`() {
        val d = draw(LaneScenes.streetTurn(), oneWay = null)
        assertTrue(d.path.drawings.isEmpty())
        assertTrue(d.features.isEmpty())
        assertTrue(d.drawing.stopLines.isEmpty())
        assertTrue(d.drawing.junctionInteriors.isEmpty())
        assertEquals(d.path.route, d.path.points)
        assertEquals(listOf(CrossSection.UndrawnStreet(gore - 650.0, gore, mixed = false)), d.section.undrawnStreets)
        assertFalse(d.section.drawsAt(gore - 500.0))
    }

    private companion object {
        const val MM = 1e-3

        /** The path between its samples, to a centimetre. */
        const val PATH_METERS = 0.01

        /** How far a chord of the junction's curve, about a metre long, may turn from the road's way where the curve ends. */
        const val CHORD_DEGREES = 10.0
    }
}
