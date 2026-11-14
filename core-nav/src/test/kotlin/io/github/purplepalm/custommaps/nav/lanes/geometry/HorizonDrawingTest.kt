package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonConvention
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence
import io.github.purplepalm.custommaps.nav.lanes.HorizonLanes
import io.github.purplepalm.custommaps.nav.lanes.HorizonLanesLine
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.bare
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.RouteHorizon
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing of the app's own lanes with the electronic horizon's counts (OL9: [HorizonEvidence], [HorizonLanes]) on the
 * invented approaches of [io.github.purplepalm.custommaps.nav.lanes.HorizonLanesTest]: two lane points 3,450 and 1,050 m
 * before IA's exit at [GORE], a road without lane data leaving on the far side between them, along a made-up line at sea
 * due north of 0, 0.
 */
class HorizonDrawingTest {

    private val w = LaneTopology.FREEWAY_LANE_METERS
    private val p = GORE - 3_450.0
    private val road = GORE - 2_250.0
    private val q = GORE - 1_050.0

    private fun edge(length: Double, lanes: Int?) =
        HorizonEdge(length, lanes, motorway = true, ramp = false, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    private fun gapScene(first: Int, id: String) = LaneScenes.approach(
        listOf(LaneScenes.At(3_450.0, List(first) { s() }), bare(2_250.0, EXIT_LEFT), LaneScenes.At(1_050.0, List(5) { s() })),
        exit = LaneScenes.iaExit(),
        id = id,
    )

    /** A horizon from a car 550 m before the first point: [first] lanes to the far-side road, five from it to the gore. */
    private fun horizon(route: LaneRoute, first: Int) = HorizonAhead.of(
        route.routeId,
        p - 550.0,
        0.5,
        listOf(edge(1_100.0, first), edge(1_200.0, first), edge(1_200.0, 5), edge(1_050.0, 6), HorizonEdge(400.0, 2, motorway = false, ramp = true, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)),
    )

    private class Built(val plan: LanePlan, val evidence: LaneEvidence, val section: CrossSection, val path: RoutePath, val drawing: LaneDrawing)

    /** [evidence]'s stretches and zones up to [x], cut there: what a drawing shows before it. */
    private fun upTo(evidence: LaneEvidence, x: Double) =
        evidence.stretches.filter { it.from < x }.map { it.copy(to = minOf(it.to, x)) } to
            evidence.zones.filter { it.from < x }.map { it.copy(to = minOf(it.to, x)) }

    private fun build(plan: LanePlan, evidence: LaneEvidence, line: List<GeoPoint>, version: Int): Built {
        val section = CrossSection.of(plan, evidence)
        val path = RoutePath.of(plan, line, version, evidence, section)
        return Built(plan, evidence, section, path, LaneDrawing.of(plan, path, evidence, section))
    }

    @Test
    fun `the horizon changes nothing before the window on screen ends, the asphalt's taper and a card window held there included`() {
        val taper = RoutePath.ASPHALT_TAPER_METERS
        assertEquals(taper, HorizonEvidence.START_PAST_WINDOW_M)
        assertEquals(1_000.0 + taper, HorizonEvidence.startFor(1_000.0, emptyList()))
        // A card window holding that station: past its end.
        assertEquals(2_500.0, HorizonEvidence.startFor(1_000.0, listOf(1_020.0..2_500.0)))
        // One that ends at it or begins at it does not.
        assertEquals(1_000.0 + taper, HorizonEvidence.startFor(1_000.0, listOf(500.0..1_000.0 + taper)))
        assertEquals(1_000.0 + taper, HorizonEvidence.startFor(1_000.0, listOf(1_000.0 + taper..2_500.0)))
    }

    @Test
    fun `a send takes the newest pairs from its start on and keeps those taken before, and none with the switch off`() {
        val route = gapScene(5, "gap")
        val plan = SceneLines.planOf(route)
        val empty = HorizonEvidence.of(plan)
        assertTrue(empty.isEmpty)
        assertEquals(plan.evidence().stretches, empty.evidence.stretches)
        val ahead = horizon(route, 5)
        val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
        // The window on screen ends before the first point: both pairs are the send's.
        val first = empty.next(ahead, p - 130.0, cards)
        assertEquals(listOf(p, q), first.lanes.between.map { it.from })
        assertEquals(p - 130.0 + HorizonEvidence.START_PAST_WINDOW_M, first.start)
        assertEquals(first.lanes.stretchesUsed, first.taken)
        assertTrue(first.evidence.stretches.any { it.horizon && it.from == p })
        // The next send, its window ending past the first point: the first pair stays as it was taken; the second is the
        // newest update's (one where the horizon gives nothing there now).
        val later = first.next(HorizonAhead.of(route.routeId, p + 300.0, 0.0, emptyList()), p + 500.0, cards)
        assertEquals(listOf(p), later.lanes.between.map { it.from })
        assertEquals(0, later.taken)
        assertEquals(first.evidence.stretches.filter { it.to <= q - 1_000.0 }, later.evidence.stretches.filter { it.to <= q - 1_000.0 })
        // The same update at that send: the second pair is taken again, its stretches counted once, by the first send.
        val again = first.next(ahead, p + 500.0, cards)
        assertEquals(listOf(p, q), again.lanes.between.map { it.from })
        assertEquals(0, again.taken)
        // A pair is counted once it agrees: the first pair under neither reading at one send, under all at the next.
        val none = empty.next(horizon(route, 4), p - 130.0, cards)
        assertEquals(HorizonConvention.NONE, none.lanes.between.first().convention)
        assertEquals(none.lanes.stretchesUsed, none.taken)
        assertEquals(first.lanes.between.first().stretches, none.next(ahead, p - 130.0, cards).taken)
        // Switched off: nothing from the horizon past the window, what was taken before it stays.
        val off = first.next(null, p + 500.0, cards)
        assertEquals(listOf(p), off.lanes.between.map { it.from })
        assertEquals(0, off.taken)
        // Another route's update gives nothing.
        assertTrue(empty.next(HorizonAhead.of("another", p - 550.0, 0.5, listOf(edge(1_100.0, 5))), p - 130.0, cards).isEmpty)
    }

    @Test
    fun `a send takes a pair the window on screen has partly passed from its start on, the drawing before it as it was`() {
        val route = gapScene(5, "gap")
        val plan = SceneLines.planOf(route)
        val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
        val empty = HorizonEvidence.of(plan)
        // The window on screen ends 430 m past the first point; the horizon, from the car 550 m before it, covers the pair.
        val windowEnd = p + 430.0
        val held = empty.next(horizon(route, 5), windowEnd, cards)
        val start = windowEnd + HorizonEvidence.START_PAST_WINDOW_M
        val pair = held.lanes.between.first()
        assertEquals(0, pair.before)
        assertEquals(start, pair.from)
        assertEquals(HorizonConvention.ALL, pair.convention)
        assertEquals(held.lanes.stretchesUsed, held.taken)
        // Bridged from the start on; up to it the evidence is the plan's own, and so what the window on screen draws.
        assertTrue(held.evidence.stretches.any { it.horizon && it.kind == LaneEvidence.Kind.BRIDGED && it.from == start })
        assertTrue(held.evidence.stretches.filter { it.horizon }.all { it.from >= start })
        assertEquals(upTo(empty.evidence, windowEnd), upTo(held.evidence, windowEnd))
        // At the next send it is kept as taken, the window on screen drawing it.
        val later = held.next(horizon(route, 5), q - 700.0, cards)
        assertEquals(start, later.lanes.between.first { it.before == 0 }.from)
        assertEquals(0, later.taken)
    }

    @Test
    fun `a pair kept from before under neither reading draws nothing and is taken anew from the start on`() {
        val route = gapScene(5, "gap")
        val plan = SceneLines.planOf(route)
        val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
        val none = HorizonEvidence.of(plan).next(horizon(route, 4), p - 130.0, cards)
        assertEquals(HorizonConvention.NONE, none.lanes.between.first().convention)
        // The next send's window ends past the first point: the pair is the newest update's from the start on, and counted.
        val windowEnd = p + 430.0
        val agreed = none.next(horizon(route, 5), windowEnd, cards)
        val pair = agreed.lanes.between.single { it.before == 0 }
        assertEquals(HorizonConvention.ALL, pair.convention)
        assertEquals(windowEnd + HorizonEvidence.START_PAST_WINDOW_M, pair.from)
        assertEquals(pair.stretches, agreed.taken)
        assertEquals(upTo(none.evidence, windowEnd), upTo(agreed.evidence, windowEnd))
    }

    /**
     * A drive along a freeway with two lane points of five lanes [length] metres apart, the first [first] metres along, a
     * road without lane data leaving on the far side half way between them and IA's exit 1,050 m past the second: the
     * horizon's edges end at every intersection and every 410 m between, five lanes each, six on the one to the gore; an
     * update every 23 m, cut as the navigator cuts it (whole edges until one reaches [HorizonAhead.HORIZON_AHEAD_M] past the
     * car), held as the engine holds it ([RouteHorizon]); each window sent when [DrawingWindows.due] says, with the
     * holder's next send as the map's own line makes it. What it gives: the gap's metres the drive bridged, those that lay
     * past the window on screen at the first send whose update reached past the second point, and whether any send changed
     * what lay before the end of the window on screen.
     */
    private fun drive(first: Double, length: Double): Triple<Double, Double, Boolean> {
        val second = first + length
        val gore = second + 1_050.0
        val five = List(5) { s() }.toTypedArray()
        val route = LaneScenes.route(
            LaneScenes.node(0.0, maneuver = LaneScenes.DEPART, planMps = 27.0),
            LaneScenes.node(first, *five, planMps = 27.0),
            LaneScenes.node(first + length / 2.0, roads = EXIT_LEFT, planMps = 27.0),
            LaneScenes.node(second, *five, planMps = 27.0),
            LaneScenes.node(gore, *LaneScenes.iaExit().toTypedArray(), road = RoadKind.RAMP, maneuver = LaneScenes.OFF_RIGHT, planMps = 20.0),
            LaneScenes.node(gore + 400.0, road = RoadKind.STREET, maneuver = LaneScenes.TURN_LEFT, planMps = 15.0),
            LaneScenes.node(gore + 500.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = 15.0),
            id = "sweep-$first-$length",
        )
        val plan = SceneLines.planOf(route)
        val nodes = route.nodes.map { it.station }
        val ends = (nodes + (1..40).map { it * 410.0 }.filter { x -> nodes.none { abs(it - x) < 50.0 } && x < route.lengthMeters })
            .filter { it > 0.0 }.distinct().sorted()
        val starts = listOf(0.0) + ends.dropLast(1)
        fun meta(i: Int): HorizonEdge {
            val ramp = ends[i] > gore
            return HorizonEdge(ends[i] - starts[i], if (ramp) 2 else if (ends[i] == gore) 6 else 5, motorway = !ramp, ramp = ramp, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)
        }
        fun update(car: Double): HorizonAhead {
            val i = ends.indexOfFirst { it > car }
            val edges = ArrayList<HorizonEdge>()
            var k = i
            while (k < ends.size) {
                edges.add(meta(k))
                if (ends[k] - car >= HorizonAhead.HORIZON_AHEAD_M) break
                k++
            }
            return HorizonAhead.of(route.routeId, car, (car - starts[i]) / (ends[i] - starts[i]), edges)
        }
        val gap = plan.evidence().stretches.filter { it.kind == LaneEvidence.Kind.NONE && it.to > first && it.from < second }
        val windows = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters)
        val held = RouteHorizon()
        var holder = HorizonEvidence.of(plan)
        var shown = windows.windowAt(0.0)
        var taken: HorizonAhead? = null
        var reachable = Double.NaN
        var moved = false
        var car = 0.0
        while (car < gore - 300.0) {
            held.take(update(car))
            if (windows.due(car, shown, changing = false)) {
                val ahead = held.aheadOf(route.routeId)
                if (shown.holds(car) && ahead != null && ahead !== taken) {
                    val start = HorizonEvidence.startFor(shown.to, windows.cards)
                    if (reachable.isNaN() && ahead.to >= second + HorizonLanesLine.MATCH_TOLERANCE_M) {
                        reachable = gap.sumOf { max(0.0, it.to - max(it.from, start)) }
                    }
                    val next = holder.next(ahead, shown.to, windows.cards)
                    if (upTo(holder.evidence, shown.to) != upTo(next.evidence, shown.to)) moved = true
                    if (!next.drawsAs(holder)) holder = next
                    taken = ahead
                }
                shown = windows.windowAt(car)
            }
            car += 23.0
        }
        val bridged = holder.evidence.stretches.filter { it.horizon && it.kind == LaneEvidence.Kind.BRIDGED }
            .sumOf { b -> gap.sumOf { g -> max(0.0, min(b.to, g.to) - max(b.from, g.from)) } }
        return Triple(bridged, if (reachable.isNaN()) 0.0 else reachable, moved)
    }

    @Test
    fun `on a drive the counts bridge every metre of a gap past the window on screen once the horizon reaches past it`() {
        for (length in listOf(1_490.0, 2_010.0, 2_530.0, 2_970.0)) {
            var some = 0
            for (j in 0 until 33) {
                val first = 3_010.0 + j * 59.0
                val (bridged, reachable, moved) = drive(first, length)
                assertFalse(moved, "a send changed the drawing before the window's end: $length m apart from $first")
                assertEquals(reachable, bridged, 1e-6, "$length m apart from $first")
                if (bridged > 0.0) some++
            }
            // Most placements leave part of the gap past the window on screen when the horizon first reaches the second point.
            assertTrue(some >= 22, "$length m apart: bridged at $some of 33")
        }
    }

    @Test
    fun `a send whose counts change nothing of the drawing draws as the holder before it, and needs no path anew`() {
        val route = gapScene(5, "gap")
        val plan = SceneLines.planOf(route)
        val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
        val empty = HorizonEvidence.of(plan)
        assertTrue(empty.drawsAs(HorizonEvidence.of(plan)))
        val first = empty.next(horizon(route, 5), p - 130.0, cards)
        assertFalse(first.drawsAs(empty))
        // The first pair alone, its bridge over the stretch without lane data: the stretches change, though no zone does.
        val firstPair = HorizonAhead.of(route.routeId, p - 550.0, 0.5, listOf(edge(1_100.0, 5), edge(1_200.0, 5), edge(1_200.0, 5), edge(130.0, 6)))
        val bridged = empty.next(firstPair, p - 130.0, cards)
        assertEquals(listOf(p), bridged.lanes.between.map { it.from })
        assertEquals(empty.evidence.zones, bridged.evidence.zones)
        assertFalse(bridged.drawsAs(empty))
        // The same counts from a later window on, in a newer update: the drawing stays as the first send made it.
        val again = first.next(horizon(route, 5), p + 500.0, cards)
        assertTrue(again.drawsAs(first))
        // Switched off: the second pair goes past the window, and with it what it drew in the run-up.
        assertFalse(first.next(null, p + 500.0, cards).drawsAs(first))
        // IA, every count over a lane point's own reach: nothing of the plan's own drawing changes.
        val ia = LaneScenes.ia()
        val iaPlan = SceneLines.planOf(ia)
        val iaAhead = HorizonAhead.of(
            ia.routeId,
            3_000.0,
            0.5,
            listOf(edge(1_640.0, 5), edge(850.0, 5), edge(640.0, 5), edge(50.0, 5), edge(640.0, 6), HorizonEdge(400.0, 2, motorway = false, ramp = true, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)),
        )
        val iaTaken = HorizonEvidence.of(iaPlan).next(iaAhead, 2_000.0, emptyList())
        assertFalse(iaTaken.isEmpty)
        assertTrue(iaTaken.drawsAs(HorizonEvidence.of(iaPlan)))
        // Another plan's holder never.
        assertFalse(HorizonEvidence.of(iaPlan).drawsAs(empty))
    }

    @Test
    fun `what the horizon drew in a window, in metres and zones, for the lane_drawing line`() {
        val route = gapScene(4, "far-gain")
        val plan = SceneLines.planOf(route)
        val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
        val held = HorizonEvidence.of(plan).next(horizon(route, 4), p - 130.0, cards)
        val zone = 2.0 * LaneTopology.addedTaperMeters(27.0, RoadKind.FREEWAY) + LaneEvidence.ZONE_MIDDLE_METERS
        // The gap from the first point to the second point's reach, bridged in two counts with the placed gain's zone between.
        assertEquals((q - 1_000.0) - p, held.drawnMeters(p - 500.0, q - 1_000.0), 1e-6)
        assertEquals(1, held.zones(p, q))
        assertEquals(zone, held.drawnMeters(road - zone, road), 1e-6)
        assertEquals(0.0, held.drawnMeters(0.0, p))
        assertEquals(0, held.zones(0.0, p))
        assertEquals(0.0, HorizonEvidence.of(plan).drawnMeters(0.0, route.lengthMeters))
    }

    @Test
    fun `a gain the horizon placed on the far side opens late inside its zone, the road widening by half a lane on each side`() {
        val route = gapScene(4, "far-gain")
        val plan = SceneLines.planOf(route)
        val line = SceneLines.of(route)
        val base = build(plan, plan.evidence(), line, 1)
        val lanes = HorizonLanes.of(plan, horizon(route, 4))
        val drawn = build(plan, plan.evidence(lanes), line, 2)
        val taper = LaneTopology.addedTaperMeters(27.0, RoadKind.FREEWAY)
        val zone = 2.0 * taper + LaneEvidence.ZONE_MIDDLE_METERS
        // Without the horizon the far-side lane opens late before the second point.
        assertEquals(4, base.section.lanes(road + 13.0).size)
        assertEquals(4, drawn.section.lanes(road - taper - 1.0).size)
        assertEquals(5, drawn.section.lanes(road + 13.0).size)
        assertTrue(drawn.section.lanes(road + 13.0).all { it.state == CrossSection.State.FULL })
        // Over the zone both edges move out by half a lane; before it the carriageway lies as without the horizon.
        val before = requireNotNull(drawn.section.edges(road - zone - 13.0))
        val after = requireNotNull(drawn.section.edges(road + 13.0))
        assertEquals(requireNotNull(base.section.edges(road - zone - 13.0)), before)
        assertEquals(w / 2.0, after.left - before.left, 1e-6)
        assertEquals(-w / 2.0, after.right - before.right, 1e-6)
        // The plan is the same: its lanes, its named lanes and its counts from the exit's edge.
        assertEquals(base.plan.segments, drawn.plan.segments)
        assertEquals(4_895.0, plan.topology.lanes.first { it.addedAt == 1 }.opens, 1.0)
    }

    @Test
    fun `the horizon's stretches get lane lines and no tints, and the lit lane where the plan names one`() {
        val route = gapScene(5, "gap")
        val plan = SceneLines.planOf(route)
        val line = SceneLines.of(route)
        val base = build(plan, plan.evidence(), line, 1)
        val drawn = build(plan, plan.evidence(HorizonLanes.of(plan, horizon(route, 5))), line, 2)
        // The 50 m between the second point and the gore's reach lie inside the run-up: a zone without the horizon.
        val a = q + 5.0
        val b = q + 43.0
        assertTrue(plan.windows.any { a > it.from && b < it.to })
        fun features(d: Built, kind: LaneDrawing.Kind) = d.drawing.features(a, b).filter { it.kind == kind && it.to > a && it.from < b }
        assertTrue(features(base, LaneDrawing.Kind.LANE_LINE).isEmpty())
        assertTrue(features(drawn, LaneDrawing.Kind.LANE_LINE).isNotEmpty())
        assertEquals(features(base, LaneDrawing.Kind.TINT).map { it.id }, features(drawn, LaneDrawing.Kind.TINT).map { it.id })
        assertEquals(features(base, LaneDrawing.Kind.LIT).map { it.id }, features(drawn, LaneDrawing.Kind.LIT).map { it.id })
        // The gap outside the run-up is drawn now, and nothing is lit or tinted there.
        val gap = drawn.drawing.features(p + 130.0, q - 1_100.0)
        assertTrue(gap.any { it.kind == LaneDrawing.Kind.LANE_LINE && it.from < q - 1_100.0 && it.to > p + 130.0 })
        assertTrue(gap.none { (it.kind == LaneDrawing.Kind.TINT || it.kind == LaneDrawing.Kind.LIT) && it.from < q - 1_100.0 && it.to > p + 130.0 })
        assertTrue(base.drawing.features(p + 130.0, q - 1_100.0).none { it.kind == LaneDrawing.Kind.LANE_LINE && it.from < q - 1_100.0 && it.to > p + 130.0 })
    }

    @Test
    fun `the path up to the end of the window on screen stays point for point, and the new one takes over past it`() {
        for (first in listOf(5, 4)) {
            val route = gapScene(first, "gap-$first")
            val plan = SceneLines.planOf(route)
            val line = SceneLines.of(route)
            val base = build(plan, plan.evidence(), line, 1)
            val cards = DrawingWindows(plan.windows.map { it.from..it.to }, route.lengthMeters).cards
            // The window on screen ends 120 m before the first point, whose reach the drawing on screen holds.
            val windowEnd = p - 120.0
            val held = HorizonEvidence.of(plan).next(horizon(route, first), windowEnd, cards)
            val drawn = build(plan, held.evidence, line, 2)
            val (points, stations) = RoutePath.spliced(base.path.points, base.path.stations, drawn.path.points, drawn.path.stations, windowEnd)
            val kept = base.path.stations.count { it <= windowEnd }
            assertEquals(base.path.points.subList(0, kept), points.subList(0, kept))
            assertEquals(base.path.stations.take(kept), stations.take(kept))
            assertTrue(stations.drop(kept).all { it > windowEnd })
            assertTrue(stations.asList().zipWithNext().all { (x, y) -> y >= x })
            // The two paths lie on one another at the splice, to well under a centimetre.
            assertEquals(base.path.offsetAt(windowEnd), drawn.path.offsetAt(windowEnd), 1e-3)
            // Past it the new path keeps to the drawing through the gap, where the old one went back to the road's line.
            val mid = (p + q - 1_000.0) / 2.0
            assertTrue(drawn.path.drawings.any { it.from < mid && it.to > mid })
            assertTrue(base.path.drawings.none { it.from < mid && it.to > mid })
            // The station table of the spliced path measures it as the map does: the same lengths up to the window's end.
            val oldTable = StationTable.of(base.path)
            val newTable = StationTable.of(route.routeId, 2, base.path.route, base.path.routeStations, points, stations)
            for (s in listOf(0.0, windowEnd / 2.0, windowEnd)) {
                assertEquals(oldTable.pathFraction(s) * oldTable.pathLength, newTable.pathFraction(s) * newTable.pathLength, 1e-15)
            }
            // The new path differs in length only by its moves across the lanes past the window (a few centimetres where the
            // placed gain's zone moves the band's middle by half a lane, none for five open lanes): until both data have
            // loaded, the old trim through the new table is off at the car by no more than that.
            val car = windowEnd - 1_000.0
            val longer = abs(newTable.pathLength - oldTable.pathLength)
            val shift = abs(newTable.pathFraction(car) - oldTable.pathFraction(car)) * newTable.pathLength
            assertTrue(longer * EARTH_CIRCUMFERENCE_M < 0.1, "$longer")
            assertTrue(shift <= longer + 1e-15, "$shift $longer")
        }
    }

    @Test
    fun `a splice keeps the first path's points up to and at the station and the second's after it`() {
        val a = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.003, 0.0))
        val b = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0015, 0.00001), GeoPoint(0.003, 0.00001))
        val (points, stations) = RoutePath.spliced(a, doubleArrayOf(0.0, 111.0, 222.0, 333.0), b, doubleArrayOf(0.0, 166.0, 333.0), 222.0)
        assertEquals(listOf(a[0], a[1], a[2], b[2]), points)
        assertEquals(listOf(0.0, 111.0, 222.0, 333.0), stations.asList())
        val (early, _) = RoutePath.spliced(a, doubleArrayOf(0.0, 111.0, 222.0, 333.0), b, doubleArrayOf(0.0, 166.0, 333.0), 120.0)
        assertEquals(listOf(a[0], a[1], b[1], b[2]), early)
    }

    private companion object {
        /** The world's width at the equator, in metres: a projected length is a fraction of it. */
        const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    }
}
