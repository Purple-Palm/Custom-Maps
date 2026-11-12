package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.At
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.JOIN_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.NodeAndLanes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.THROUGH
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.hov
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.option
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sl
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.RouteLane
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random

/** The route's path through its planned lanes on the made-up routes of [LaneScenes], drawn along made-up lines at sea. */
class RoutePathTest {

    private val w = LaneTopology.FREEWAY_LANE_METERS
    private val gore = LaneScenes.GORE

    private class Built(val route: LaneRoute, val plan: LanePlan, val section: CrossSection, val path: RoutePath)

    private fun build(route: LaneRoute, line: List<GeoPoint> = SceneLines.of(route)): Built {
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence)
        return Built(route, plan, section, RoutePath.of(plan, line, version = 1, evidence = evidence, section = section))
    }

    @Test
    fun `a move takes six seconds a lane on freeways and four on streets, within its bounds`() {
        val freeway = RoadKind.FREEWAY
        assertEquals(80.0, RoutePath.moveMeters(13.0, freeway))
        assertEquals(80.0, RoutePath.moveMeters(80.0 / 6.0, freeway), 1e-12)
        assertEquals(81.0, RoutePath.moveMeters(13.5, freeway), 1e-12)
        assertEquals(162.0, RoutePath.moveMeters(27.0, freeway), 1e-12)
        assertEquals(269.4, RoutePath.moveMeters(44.9, freeway), 1e-9)
        assertEquals(270.0, RoutePath.moveMeters(45.0, freeway), 1e-12)
        assertEquals(270.0, RoutePath.moveMeters(46.0, freeway))
        assertEquals(RoutePath.moveMeters(27.0, freeway), RoutePath.moveMeters(27.0, RoadKind.RAMP))
        val street = RoadKind.STREET
        assertEquals(25.0, RoutePath.moveMeters(6.0, street))
        assertEquals(25.0, RoutePath.moveMeters(6.25, street), 1e-12)
        assertEquals(26.0, RoutePath.moveMeters(6.5, street), 1e-12)
        assertEquals(79.6, RoutePath.moveMeters(19.9, street), 1e-9)
        assertEquals(80.0, RoutePath.moveMeters(20.0, street), 1e-12)
        assertEquals(80.0, RoutePath.moveMeters(21.0, street))
        // No speed at all: the shortest.
        assertEquals(80.0, RoutePath.moveMeters(Double.NaN, freeway))
    }

    @Test
    fun `IA's path keeps to the road's line until the run-up and moves into the option lane once`() {
        val ia = build(LaneScenes.ia())
        val path = ia.path
        // The drawing: from IA0's reach to the gore, where the route turns onto a ramp without lanes.
        val drawing = path.drawings.single()
        assertEquals(gore - 2_180.0 - 1_000.0, drawing.from, 1e-9)
        assertEquals(gore, drawing.to, 1e-9)
        assertTrue(drawing.turnsAtEnd)
        // Every lane works before the run-up: the band's middle is the road's line.
        for (station in listOf(drawing.from, drawing.from + 200.0, 3_300.0)) assertEquals(0.0, path.offsetAt(station), 1e-9)
        // The run-up begins (the seam) and the plan names the option lane at its first grid station: one move of one lane
        // over 6 s at 27 m/s, 162 m, to its middle.
        val move = path.moves.first()
        assertEquals(RoutePath.Move.Kind.LANE, move.kind)
        val named = ia.plan.segments.first { it.named != null }
        assertEquals(named.from, move.from, 1e-9)
        assertEquals(162.0, move.to - move.from, 1e-9)
        assertEquals(-w, move.offsetTo, 1e-9)
        // Sampled every metre: the S's end lies between two samples.
        assertEquals(-w, path.offsetAt(move.to), 1e-4)
        assertTrue(abs(path.offsetAt(move.to - 1.0)) < w)
        // IA1 and IA2's unexplained gains widen the road by half a lane each side: the lane moves with it, toward the exit.
        assertEquals(-1.5 * w, path.offsetAt(gore - 1_300.0), 1e-9)
        assertEquals(-2.0 * w, path.offsetAt(gore - 300.0), 1e-9)
        // At the gore it goes on straight in its lane until the ramp's line lies farther out, then onto that line.
        val turn = path.moves.last()
        assertEquals(RoutePath.Move.Kind.TURN, turn.kind)
        assertEquals(0.0, path.offsetAt(turn.to + 1.0), 1e-12)
        assertEquals(turn.to, drawing.back, 1.0)
        assertEquals(2, path.moves.size)
        assertTrue(path.folds.isEmpty())
    }

    @Test
    fun `IW31's path moves into the option lane once, stays in it past the gore onto the ramp, and follows it to the split`() {
        val iw31 = build(LaneScenes.iw31())
        val path = iw31.path
        val drawing = path.drawings.single()
        // From P0's reach through the gore and the ramp to the split, where the route takes the left branch.
        assertEquals(gore - 2_600.0 - 1_000.0, drawing.from, 1e-9)
        assertEquals(gore + 260.0, drawing.to, 1e-9)
        // The run-up begins at the seam; g4 is named at the first grid station: a move of one and a half lanes, over
        // 6 s a lane at 65 mph.
        val move = path.moves.first()
        val mps = 65.0 * 0.44704
        assertEquals(-1.5 * w, move.offsetTo, 1e-9)
        assertEquals(mps * 6.0 * 1.5, move.to - move.from, 1e-9)
        // g4 in its place all the way to the gore: the exit-only lane may be there from P1, drawn beside it, and the gap
        // from P1 to the entrance is a zone; nothing moves the light.
        for (station in listOf(move.to + 1.0, gore - 1_850.0, gore - 1_700.0, gore - 620.0, gore - 100.0)) {
            assertEquals(-1.5 * w, path.offsetAt(station), 1e-9, "at $station")
        }
        // On the ramp: g4 is its left lane.
        assertEquals(0.5 * w, path.offsetAt(gore + 150.0), 1e-9)
        // Past the gore the path goes straight on in g4, 1.5 lanes east of the freeway's line: never the gore's diagonal.
        val axis = path.axisAt(gore + 5.0)!!
        val fillet = axis.fillets.first { abs(it.station - gore) < 1e-6 }
        for (station in listOf(fillet.from, gore, gore + 10.0)) {
            assertEquals(1.5 * w, axis.offset(station, path.offsetAt(station)).x, 1e-6, "at $station")
        }
        // At the split it goes on straight in its lane and onto the left branch's line.
        val turn = path.moves.last()
        assertEquals(RoutePath.Move.Kind.TURN, turn.kind)
        assertEquals(gore + 260.0, turn.from, 1.0)
        assertEquals(0.0, path.offsetAt(drawing.back), 1e-9)
        assertEquals(2, path.moves.size)
    }

    @Test
    fun `into a deceleration lane the path follows the lane's taper, with no S`() {
        // S9: the plan names the exit lane where it opens, 210 m before the gore, and the lane beside it feeds it.
        val s9 = build(LaneScenes.s9())
        val path = s9.path
        assertEquals(-w, path.offsetAt(gore - 210.0), 1e-9)
        assertEquals(-2.0 * w, path.offsetAt(gore - 149.0), 1e-9)
        var last = path.offsetAt(gore - 210.0)
        var station = gore - 209.0
        while (station <= gore - 150.0) {
            val now = path.offsetAt(station)
            assertTrue(now <= last + 1e-9, "never back at $station")
            last = now
            station += 1.0
        }
        assertTrue(path.moves.none { it.kind == RoutePath.Move.Kind.LANE && it.from > gore - 300.0 && it.from < gore })
        // Its drawing begins inside the run-up: the path enters from the road's line there with one move.
        val enter = path.moves.first()
        assertEquals(RoutePath.Move.Kind.ENTER, enter.kind)
        assertEquals(path.drawings.single().from, enter.from, 1e-9)
        assertEquals(-w, enter.offsetTo, 1e-9)
    }

    @Test
    fun `where a drawing ends outside a card window the path returns to the road's line over 60 m`() {
        // Four lanes, the left one leaving by a road on the left at a point 5,000 m before a right exit, the next lane point
        // 2,000 m before it with another count: a long stretch without data between, outside any card window.
        val route = LaneScenes.approach(
            listOf(At(5_000.0, listOf(s(false), s(), s(), s()), EXIT_LEFT), At(2_000.0, listOf(s(), s(), s(), s(), s()))),
            exit = listOf(s(false), s(false), s(false), s(false), sr()),
        )
        val built = build(route)
        val path = built.path
        val first = path.drawings.first()
        val end = gore - 5_000.0
        assertEquals(end, first.to, 1e-9)
        assertFalse(first.turnsAtEnd)
        // The band's middle: the three lanes that work, half a lane right of the line.
        assertEquals(-0.5 * w, path.offsetAt(end), 1e-9)
        val back = path.moves.first { it.kind == RoutePath.Move.Kind.RETURN }
        assertEquals(end, back.from, 1e-9)
        assertEquals(end + RoutePath.RETURN_METERS, back.to, 1e-9)
        assertEquals(-0.25 * w, path.offsetAt(end + 30.0), 1e-9)
        assertTrue(abs(path.offsetAt(end + 59.0)) > 1e-6)
        assertEquals(0.0, path.offsetAt(end + RoutePath.RETURN_METERS), 1e-12)
        assertEquals(end + RoutePath.RETURN_METERS, first.back, 1e-9)
        assertEquals(first.back + RoutePath.ASPHALT_TAPER_METERS, first.asphaltTo, 1e-12)
        // The return lies outside every card window.
        assertFalse(built.plan.segmentAt(back.from)?.inWindow ?: false)
        assertEquals(2, path.drawings.size)
    }

    @Test
    fun `where a lane between others changes kinds the path stays in its lane`() {
        // The third lane of four is kept for car pools from 50 m past the first lane point on; the plan names the fourth,
        // the option lane, through the card window: nothing moves where the third lane's kinds change.
        val built = build(LaneScenes.thirdLaneForCarPools())
        val changed = gore - 2_600.0 + LaneTopology.KINDS_EARLY_METERS
        val segment = built.plan.segmentAt(changed)!!
        assertTrue(segment.inWindow)
        assertEquals(3, segment.named)
        for (station in listOf(changed - 1.0, changed - 1e-6, changed, changed + 1.0, gore - 1_000.0)) {
            assertEquals(-1.5 * w, built.path.offsetAt(station), 1e-9, "at $station")
        }
        assertEquals(1, built.path.moves.count { it.kind == RoutePath.Move.Kind.LANE })
    }

    @Test
    fun `the path is the route's own line away from the drawings and meets it at every drawing end`() {
        for ((name, route) in LaneScenes.all()) {
            val built = build(route)
            assertContinuous(name, built.path)
        }
    }

    @Test
    fun `a long drawing is cut into windows of at most 3 km that meet without a step`() {
        // Lane points every 1.4 km on a 13 km freeway: one drawing in several windows.
        val exitAt = 13_000.0
        val nodes = listOf(LaneScenes.node(0.0, maneuver = LaneScenes.DEPART, planMps = 27.0)) +
            (1..8).map { LaneScenes.node(1_400.0 * it - 600.0, s(), s(), s(), s(), planMps = 27.0) } +
            listOf(
                LaneScenes.node(exitAt, s(false), s(false), s(false), sr(), road = RoadKind.RAMP, maneuver = LaneScenes.OFF_RIGHT, planMps = 20.0),
                LaneScenes.node(exitAt + 400.0, road = RoadKind.STREET, maneuver = LaneScenes.TURN_LEFT, planMps = 15.0),
                LaneScenes.node(exitAt + 500.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = 15.0),
            )
        val route = LaneScenes.route(*nodes.toTypedArray(), id = "long")
        val line = SceneLines.line(route.lengthMeters, listOf(2_000.0 to 3.0, 4_100.0 to -4.0, 7_000.0 to 2.0, 9_250.0 to 5.0, exitAt to -17.0, exitAt + 400.0 to 90.0))
        val built = build(route, line)
        val path = built.path
        val drawing = path.drawings.single()
        assertTrue(drawing.to - drawing.from > 2.0 * RoutePath.WINDOW_METERS)
        assertTrue(path.windowCount >= 3)
        assertContinuous("long", path)
        // Where windows meet the path has no step: consecutive points stay close and turn little.
        for (i in 1 until path.points.size) {
            val step = Geo.meters(path.points[i - 1], path.points[i])
            val along = path.stations[i] - path.stations[i - 1]
            assertTrue(step <= along + 0.5, "step $step over $along m at ${path.stations[i]}")
        }
    }

    @Test
    fun `the same route gives the same path`() {
        val a = build(LaneScenes.iw31()).path
        val b = build(LaneScenes.iw31()).path
        assertEquals(a.points, b.points)
        assertTrue(a.stations.contentEquals(b.stations))
        assertEquals(1, a.version)
        assertEquals("iw31", a.routeId)
    }

    @Test
    fun `a path without lanes is the route's line`() {
        val route = LaneScenes.roundabout()
        val line = SceneLines.of(route)
        val path = build(route, line).path
        assertTrue(path.drawings.isEmpty())
        assertEquals(line, path.points)
        assertEquals(0.0, path.offsetAt(100.0))
    }

    @Test
    fun `where an offset would fold the path keeps to the road's line`() {
        // A right lane named into a sharp left kink of the freeway's line (a mapping kink): the offset folds there.
        val route = LaneScenes.s9()
        val line = SceneLines.line(route.lengthMeters, listOf(gore - 100.0 to 120.0, gore - 99.0 to -120.0, gore to -17.0, gore + 400.0 to 90.0))
        val path = build(route, line).path
        assertTrue(path.folds.isNotEmpty())
        val fold = path.folds.first()
        assertEquals(0.0, path.offsetAt((fold.from + fold.to) / 2.0), 1e-12)
        assertTrue(path.foldMeters > 0.0)
        // Nowhere does the path's own offset fold.
        for (station in generateSequence(path.drawings.first().from) { it + 0.5 }.takeWhile { it < gore - 20.0 }) {
            val axis = path.axisAt(station) ?: continue
            assertFalse(axis.folds(station, path.offsetAt(station)), "at $station")
        }
    }

    // ------------------------------------------------------------------ the design's properties (10.1, LaneTrust 4, 5, 8, 9)

    @Test
    fun `the path keeps the design's rules on every scene`() {
        for ((name, route) in LaneScenes.all()) checkRules(name, build(route))
    }

    @Test
    fun `the path keeps the design's rules on seeded random routes`() {
        val rng = Random(20261112)
        var checked = 0
        repeat(RANDOM_ROUTES) { k ->
            val route = randomApproach(rng, "random-$k")
            val bends = (1..8).map { rng.nextDouble(200.0, gore - 400.0) to rng.nextDouble(-4.0, 4.0) }
            val line = SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first })
            checked += checkRules("random-$k", build(route, line))
        }
        assertTrue(checked > RANDOM_ROUTES)
    }

    /**
     * Checks [built] against the design's rules and returns how many card windows it checked: inside a card window the
     * path is in the named lane except inside a move and never moves away from the exit's edge; inside the drawings it
     * moves only toward the plan's lane, then never back; it is back on the road's line only where a drawing ends outside
     * card windows; it meets the route's line at every drawing end.
     */
    private fun checkRules(name: String, built: Built): Int {
        val path = built.path
        val plan = built.plan
        assertContinuous(name, path)
        val excluded = path.moves.map { it.from - 1e-6 to it.to + 1e-6 } + turns(built) + path.folds.map { it.from - RoutePath.RETURN_METERS to it.to + RoutePath.RETURN_METERS }
        fun free(station: Double) = excluded.none { station >= it.first && station <= it.second }
        var windows = 0
        for (window in plan.windows) {
            val doubtful = plan.moves.any { it.back && it.station >= window.from && it.station <= window.to } ||
                plan.flags.any { it.kind == LanePlan.Flag.Kind.CONFLICT && it.to > window.from && it.from < window.to }
            var last: Double? = null
            var lastStation = window.from
            var station = window.from
            while (station < window.to) {
                val seg = plan.segmentAt(station)
                val drawn = path.drawings.any { station >= it.from && station < it.to }
                val named = seg?.named
                if (seg != null && seg.inWindow && named != null && seg.noLane == null && drawn && free(station)) {
                    val axis = path.axisAt(station)!!
                    val offset = path.offsetAt(station)
                    val places = built.section.lanes(station, axis)
                    val lane = places.first { it.lane == named }
                    val feeder = built.route.let { built.plan.topology.lanes[named].fedBy }?.let { f -> places.firstOrNull { it.lane == f } }
                    val left = maxOf(lane.left, feeder?.left ?: lane.left)
                    val right = minOf(lane.right, feeder?.right ?: lane.right)
                    // 4: inside its lane (or, along a fed lane's taper, inside it and its feeder).
                    assertTrue(offset <= left + 1e-9 && offset >= right - 1e-9, "$name at $station: $offset outside $right..$left")
                    // 5 (inside card windows): back on the road's line only where the named lane lies on it.
                    if (abs(offset) < 1e-6) assertTrue(abs(lane.centre) < 1e-6 || feeder != null, "$name at $station: on the road's line")
                    // 9: never away from the exit's edge.
                    val sign = if (seg.edge == LaneEdge.RIGHT) -1.0 else if (seg.edge == LaneEdge.LEFT) 1.0 else 0.0
                    val prev = last
                    if (!doubtful && prev != null && sign != 0.0 && station - lastStation < 1.5 && sameRoad(built, lastStation, station)) {
                        assertTrue((offset - prev) * sign >= -1e-9, "$name at $station: away from the exit's edge ($prev to $offset)")
                    }
                    last = offset
                    lastStation = station
                } else {
                    last = null
                }
                station += 1.0
            }
            windows++
        }
        // 5: inside the drawings the path moves only toward the plan's lane: its first move either way, then only toward
        // the exit's edge, never back.
        for (drawing in path.drawings) {
            var station = drawing.from
            var first = 0.0 // the first move's direction while it lasts; 2 once it is over
            var prev = path.offsetAt(station)
            var approach = approachOf(built, station)
            while (station < drawing.to) {
                station += 1.0
                val now = path.offsetAt(station)
                val here = approachOf(built, station)
                val inTurn = turns(built).any { station >= it.first && station <= it.second }
                if (here != approach) {
                    approach = here
                    first = 0.0
                } else if (!inTurn && path.folds.none { station >= it.from - RoutePath.RETURN_METERS && station <= it.to + RoutePath.RETURN_METERS } &&
                    !plan.moves.any { it.back }
                ) {
                    val d = now - prev
                    if (abs(d) > 1e-9) {
                        val sign = if (d > 0) 1.0 else -1.0
                        val seg = plan.segmentAt(station)
                        val edge = if (seg?.edge == LaneEdge.RIGHT) -1.0 else if (seg?.edge == LaneEdge.LEFT) 1.0 else sign
                        when {
                            first == 0.0 -> first = sign
                            first != 2.0 && sign == first -> Unit
                            else -> {
                                first = 2.0
                                assertEquals(edge, sign, "$name at $station: the path moves back ($prev to $now)")
                            }
                        }
                    }
                }
                prev = now
            }
        }
        // 8: the returns to the road's line lie outside card windows.
        for (move in path.moves) {
            if (move.kind == RoutePath.Move.Kind.RETURN) assertFalse(plan.segmentAt(move.from + 0.5)?.inWindow == true, "$name: a return inside a card window")
        }
        return windows
    }

    /** The stretches where the route turns onto another road with lanes, from where the arc begins to the end of the blend. */
    private fun turns(built: Built): List<Pair<Double, Double>> = built.section.rampStarts.map {
        it.station - 60.0 to it.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS
    } + built.path.moves.filter { it.kind == RoutePath.Move.Kind.TURN }.map { it.from to it.to }

    /** Which approach [station] lies on: the index of the next maneuver the plan counts. */
    private fun approachOf(built: Built, station: Double): Int =
        built.route.nodes.indexOfFirst { it.station >= station && LaneEvidence.planTurns(it) }

    private fun sameRoad(built: Built, a: Double, b: Double) = built.route.nodes.none { it.station > a && it.station <= b && LaneEvidence.planTurns(it) }

    /**
     * Away from the drawings the path is the route's line, point for point; at every drawing end it meets that line within
     * 1 cm, heading within 1 degree of it.
     */
    private fun assertContinuous(name: String, path: RoutePath) {
        // Drawings close together are drawn as one: the path keeps to their axis between them.
        val spans = ArrayList<Pair<Double, Double>>()
        for (d in path.drawings.sortedBy { it.from }) {
            val last = spans.lastOrNull()
            val reach = CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + RoutePath.RETURN_METERS
            if (last != null && d.from <= last.second + reach) spans[spans.size - 1] = last.first to maxOf(last.second, d.back) else spans.add(d.from to d.back)
        }
        for ((i, p) in path.points.withIndex()) {
            val s = path.stations[i]
            if (spans.any { s > it.first - 1e-6 && s < it.second + 1e-6 }) continue
            val k = path.routeStations.indexOfFirst { abs(it - s) < 1e-9 }
            assertTrue(k >= 0, "$name: a point at $s off the route's line")
            assertEquals(path.route[k], p)
        }
        for (span in spans) {
            for (end in listOf(span.first, span.second)) {
                if (end <= path.routeStations.first() || end >= path.routeStations.last()) continue
                val onPath = pointAt(path.points, path.stations, end)
                val onRoute = pointAt(path.route, path.routeStations, end)
                assertTrue(Geo.meters(onPath, onRoute) < 0.01, "$name: ${Geo.meters(onPath, onRoute)} m off the line at $end")
                val a = headingAt(path.points, path.stations, end)
                val b = headingAt(path.route, path.routeStations, end)
                assertTrue(abs(((a - b + 540.0) % 360.0) - 180.0) < 1.0, "$name: heading $a against $b at $end")
            }
        }
    }

    private fun pointAt(points: List<GeoPoint>, stations: DoubleArray, s: Double): GeoPoint {
        val i = RoutePath.indexAtOrBefore(stations, s).coerceIn(0, points.size - 2)
        val f = if (stations[i + 1] > stations[i]) ((s - stations[i]) / (stations[i + 1] - stations[i])).coerceIn(0.0, 1.0) else 0.0
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }

    /** The heading of the segment just after [s], in degrees. */
    private fun headingAt(points: List<GeoPoint>, stations: DoubleArray, s: Double): Double {
        var i = RoutePath.indexAtOrBefore(stations, s + 1e-6).coerceIn(0, points.size - 2)
        while (i < points.size - 2 && stations[i + 1] - stations[i] < 1e-6) i++
        val a = SceneLines.local(points[i])
        val b = SceneLines.local(points[i + 1])
        return Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
    }

    /** A made-up approach to an exit: random lane points, counts, unusable edge lanes, roads, an exit to either side, a split. */
    private fun randomApproach(rng: Random, id: String): LaneRoute {
        val right = rng.nextBoolean()
        val carpool = rng.nextDouble() < 0.25
        var count = rng.nextInt(3, 6)
        val points = ArrayList<At>()
        var before = rng.nextDouble(2_400.0, 3_400.0)
        while (true) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0 || points.size >= 3) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            val lanes = ArrayList<RouteLane>()
            if (carpool) lanes.add(hov())
            repeat(count) { lanes.add(s()) }
            val roads = when (rng.nextInt(5)) {
                0 -> {
                    lanes[if (carpool) 1 else 0] = s(false)
                    EXIT_LEFT
                }
                1 -> {
                    lanes[lanes.size - 1] = s(false)
                    EXIT_RIGHT
                }
                2 -> JOIN_RIGHT
                else -> THROUGH
            }
            points.add(At(before, lanes, roads))
        }
        val exit = ArrayList<RouteLane>()
        if (carpool) exit.add(hov())
        repeat(count) { exit.add(s(false)) }
        val usable = rng.nextInt(1, 3)
        val edge = if (right) exit.size - 1 else if (carpool) 1 else 0
        exit[edge] = if (right) sr() else sl()
        if (usable == 2) {
            val inner = if (right) edge - 1 else edge + 1
            if (inner in exit.indices && !exit[inner].designated) exit[inner] = option()
        }
        if (rng.nextBoolean()) exit.add(if (right) exit.size else (if (carpool) 1 else 0), if (right) sr() else sl())
        val split = rng.nextDouble() < 0.3
        val maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT
        val rampPoints = if (split) {
            listOf(rng.nextDouble(150.0, 340.0) to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT))
        } else {
            emptyList()
        }
        return LaneScenes.approach(points, exit, maneuver, planMps = rng.nextDouble(22.0, 34.0), rampPoints = rampPoints, rampMeters = 600.0, id = id)
    }

    private companion object {
        /** How many seeded random routes the rules are checked on. */
        const val RANDOM_ROUTES = 60
    }
}
