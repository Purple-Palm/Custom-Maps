package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.CardWindow
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
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
import kotlin.math.max
import kotlin.math.tan
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
    fun `a move under way at a ramp's start ends there, but runs at least 15 m a lane`() {
        val freeway = RoadKind.FREEWAY
        // Two and a half lanes: at least 37.5 m.
        assertEquals(50.0, RoutePath.lengthBeforeRampStart(405.0, 50.0, -2.5 * w, freeway), 1e-12)
        assertEquals(37.6, RoutePath.lengthBeforeRampStart(405.0, 37.6, -2.5 * w, freeway), 1e-12)
        assertEquals(37.5, RoutePath.lengthBeforeRampStart(405.0, 37.4, -2.5 * w, freeway), 1e-12)
        // One lane, and less than one counted as one: at least 15 m.
        assertEquals(15.2, RoutePath.lengthBeforeRampStart(162.0, 15.2, w, freeway), 1e-12)
        assertEquals(15.0, RoutePath.lengthBeforeRampStart(162.0, 14.9, w, freeway), 1e-12)
        assertEquals(15.0, RoutePath.lengthBeforeRampStart(162.0, 12.0, 0.5 * w, freeway), 1e-12)
        // Never longer than the move itself, which ends before a ramp's start farther on.
        assertEquals(20.0, RoutePath.lengthBeforeRampStart(20.0, 12.0, -2.5 * w, freeway), 1e-12)
        assertEquals(162.0, RoutePath.lengthBeforeRampStart(162.0, 300.0, w, freeway), 1e-12)
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
        // The asphalt tapers over 40 m into the map's own road past the return, and over 40 m before the drawing.
        assertEquals(40.0, first.asphaltTo - first.back, 1e-12)
        assertEquals(40.0, first.from - first.asphaltFrom, 1e-12)
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
    fun `a card window that begins between the plan's grid stations leads the band to the lane it names`() {
        val built = build(offGridWindow())
        val plan = built.plan
        val path = built.path
        // The scene: the run-up begins between two grid stations, where the plan names no lane yet; it names the second
        // lane from the next station on. Just before it the band is the two left lanes, whose middle lies past that lane
        // toward the exit's edge.
        val window = plan.windows.single { it.kind == CardWindow.Kind.RUN_UP }
        val run = plan.topology.runs[requireNotNull(plan.segmentAt(window.from)).run]
        val offGrid = (window.from - run.from) % LanePlanner.GRID_METERS
        assertTrue(offGrid > 0.5 && offGrid < LanePlanner.GRID_METERS - 0.5, "off the grid by $offGrid m")
        assertEquals(null, plan.segmentAt(window.from)?.named)
        val named = plan.segments.first { it.named != null }
        assertEquals(1, named.named)
        assertTrue(named.from > window.from && named.from - window.from < LanePlanner.GRID_METERS)
        val before = plan.segmentAt(window.from - 1.0)!!
        assertEquals(listOf(0, 1), before.band)
        assertEquals(1.5 * w, built.section.middle(before.band, window.from - 1.0)!!, 1e-9)
        // The path heads for the named lane outside the window and never past it: in it where the window begins, and from
        // there on it moves only toward the exit's edge, the left one, until the turn at the gore.
        assertEquals(w, path.offsetAt(window.from), 1e-9)
        val turn = path.moves.last()
        assertEquals(RoutePath.Move.Kind.TURN, turn.kind)
        var last = path.offsetAt(path.drawings.single().from)
        var station = path.drawings.single().from
        while (station < turn.from) {
            val now = path.offsetAt(station)
            assertTrue(now >= last - 1e-9, "back at $station: $last to $now")
            if (station >= window.from) assertEquals(w, now, 1e-9, "in the named lane at $station")
            last = now
            station += 1.0
        }
        assertEquals(listOf(RoutePath.Move.Kind.ENTER, RoutePath.Move.Kind.LANE, RoutePath.Move.Kind.TURN), path.moves.map { it.kind })
        assertTrue(path.moves[1].to < window.from)
        checkRules("off-grid window", built)
    }

    @Test
    fun `ahead of an approach where no lane is named the path keeps to the road's line`() {
        val scenes = listOf(
            "car-pool lane, right exit" to beyondKeptLane(hov(), LaneEdge.RIGHT),
            "bus lane, right exit" to beyondKeptLane(LaneScenes.bus(), LaneEdge.RIGHT),
            "car-pool lane, left exit" to beyondKeptLane(hov(), LaneEdge.LEFT),
            "lane turned into a bus lane, no run-up" to beyondBusLane(),
        )
        for ((name, route) in scenes) {
            val built = build(route)
            val plan = built.plan
            val path = built.path
            // The scene: no lane can be named on the exit's approach (C1), whose lane lies beyond a lane kept for car pools
            // or buses at the exit's edge. The approach begins between the plan's grid stations; before it the band is the
            // lane beyond the kept one alone, well off the road's line.
            val approach = plan.noLane.single()
            assertEquals(LanePlan.NoLane.Cause.DESIGNATED_BETWEEN, approach.cause, name)
            val run = plan.topology.runs[requireNotNull(plan.segmentAt(approach.from)).run]
            val offGrid = (approach.from - run.from) % LanePlanner.GRID_METERS
            assertTrue(offGrid > 0.5 && offGrid < LanePlanner.GRID_METERS - 0.5, "$name: off the grid by $offGrid m")
            val band = plan.segmentAt(approach.from - 1.0)!!.band
            assertEquals(1, band.size, name)
            val beyond = built.section.place(band.single(), approach.from - 1.0)!!
            assertTrue(abs(beyond.centre) > w, "$name: the band's lane at ${beyond.centre}")
            // The path heads for the road's line, where it runs on the approach: it never leaves it before the turn at the gore.
            assertEquals(listOf(RoutePath.Move.Kind.TURN), path.moves.map { it.kind }, name)
            val turn = path.moves.single()
            var station = path.drawings.single().from
            while (station < turn.from) {
                assertEquals(0.0, path.offsetAt(station), 1e-12, "$name at $station")
                station += 1.0
            }
            checkRules(name, built)
        }
    }

    @Test
    fun `past an approach where no lane is named the path keeps to the road's line through the ramp's start, into its lane after`() {
        val built = build(beyondKeptLaneWithSplit())
        val plan = built.plan
        val section = built.section
        val path = built.path
        // The scene: no lane can be named on the exit's approach (C1); past the gore the route goes on onto a ramp with lanes,
        // whose card window names the ramp's lane toward the split. At the gore the ramp's lanes go on straight along the
        // freeway, across the ramp's line, until it lies farther out, and blend onto the ramp from there.
        assertEquals(LanePlan.NoLane.Cause.DESIGNATED_BETWEEN, plan.noLane.single().cause)
        val ramp = plan.windows.single { it.kind == CardWindow.Kind.RAMP }
        assertEquals(gore, ramp.from, 1e-9)
        val start = section.rampStarts.single()
        assertEquals(gore, start.station, 1e-9)
        assertTrue(abs(start.shift) > w)
        val axis = requireNotNull(path.axisAt(gore + 1.0))
        val settled = max(gore, CrossSection.crossing(axis, start.begin(axis), gore, start.shift)) + CrossSection.RAMP_BLEND_METERS
        assertTrue(plan.segments.first { it.named != null }.from < settled - CrossSection.RAMP_BLEND_METERS)
        // The path keeps to the road's line from the drawing's start until the ramp's lanes lie on the ramp, and enters the
        // named lane from there with one S: never beside the drawn ramp.
        val enter = path.moves.first()
        assertEquals(RoutePath.Move.Kind.ENTER, enter.kind)
        assertTrue(enter.from >= settled && enter.from < settled + 1.0, "the path enters at ${enter.from}, the lanes settle at $settled")
        var station = path.drawings.single().from
        while (station < enter.from) {
            assertEquals(0.0, path.offsetAt(station), 1e-12, "at $station")
            station += 1.0
        }
        station = ramp.from
        while (station < ramp.to) {
            val offset = path.offsetAt(station)
            val edges = requireNotNull(section.edges(station, requireNotNull(path.axisAt(station))))
            assertTrue(abs(offset) < 1e-12 || offset <= edges.asphaltLeft && offset >= edges.asphaltRight, "at $station: $offset off the ramp")
            station += 1.0
        }
        assertEquals(listOf(RoutePath.Move.Kind.ENTER, RoutePath.Move.Kind.TURN), path.moves.map { it.kind })
        checkRules("past an approach without a lane, a split", built)
    }

    @Test
    fun `where a drawing ends at a turn with a move under way the turn takes the move over from where the path is`() {
        val built = build(lateMove())
        val path = built.path
        val drawing = path.drawings.single()
        assertTrue(drawing.turnsAtEnd)
        // The scene: the plan names the exit's lane only 50 m before the gore, two and a half lanes right of the road's line,
        // and the move there runs on past the reach of the turn at the gore.
        val lane = path.moves.single { it.kind == RoutePath.Move.Kind.LANE }
        assertEquals(gore - 50.0, lane.from, 1e-9)
        assertEquals(-2.5 * w, lane.offsetTo, 1e-9)
        assertTrue(lane.to > drawing.to + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS)
        // The turn goes straight on from where the path is when its arc begins, a little way into the move, and blends onto
        // the ramp's line: back on it for good where the blend ends, and nothing moves it past the drawing.
        val turn = path.moves.last()
        assertEquals(RoutePath.Move.Kind.TURN, turn.kind)
        assertEquals(path.offsetAt(turn.from), turn.offsetFrom, 1e-9)
        assertTrue(turn.offsetFrom < 0.0 && turn.offsetFrom > -w, "the turn begins at ${turn.offsetFrom}")
        assertEquals(turn.to, drawing.back, 1.0)
        assertEquals(0.0, path.offsetAt(drawing.back), 1e-12)
        assertEquals(2, path.moves.size)
        checkRules("late move", built)
    }

    @Test
    fun `a move toward a lane named late ends at the ramp's start, in its lane, on the drawn asphalt`() {
        for ((mps, end) in listOf(27.0 to gore, 20.0 to gore + 12.5)) {
            val name = "late move onto a ramp's lanes at $mps m/s"
            val built = build(lateMoveOntoRampLanes(mps))
            val section = built.section
            val path = built.path
            // The scene: the plan names the exit's lane only where it opens to every car, two and a half lanes right of the
            // road's line, late (later at 20 m/s, where the lane opens along a shorter taper); the drawing goes on past the
            // gore onto the ramp's lanes, and only the lanes onto the ramp are drawn past the turn.
            val start = section.rampStarts.single()
            assertEquals(gore, start.station, 1e-9)
            assertTrue(path.drawings.single().let { gore > it.from && gore < it.to }, name)
            val move = path.moves.first { it.kind == RoutePath.Move.Kind.LANE }
            val named = requireNotNull(built.plan.segmentAt(move.from)?.named)
            assertEquals(-2.5 * w, move.offsetTo, 1e-9, name)
            assertTrue(move.from + RoutePath.moveMeters(mps, RoadKind.FREEWAY) * 2.5 > gore + 50.0, name)
            // The move ends at the gore, or 15 m a lane past where it began where that lies farther: the path is in its lane at
            // the gore at 27 m/s, on the drawn asphalt at 20 m/s, and on the drawn asphalt at every metre up to the split.
            assertEquals(end, move.to, 1e-9, name)
            if (end == gore) assertEquals(section.place(named, gore, requireNotNull(path.axisAt(gore)))!!.centre, path.offsetAt(gore), 1e-9, name)
            var station = gore
            while (station < gore + 180.0) {
                val offset = path.offsetAt(station)
                val edges = requireNotNull(section.edges(station, requireNotNull(path.axisAt(station))))
                assertTrue(offset <= edges.asphaltLeft + 1e-6 && offset >= edges.asphaltRight - 1e-6, "$name at $station: $offset off the ramp's asphalt")
                station += 1.0
            }
            checkRules(name, built)
        }
    }

    @Test
    fun `where a drawing ends at a gap with a move under way its return takes the move over from where the path is`() {
        val built = build(moveBeforeGap())
        val path = built.path
        val first = path.drawings.first()
        assertFalse(first.turnsAtEnd)
        assertEquals(gore - 1_650.0, first.to, 1e-9)
        // The scene: just before the last point ahead of the gap, the band jumps to the lane beyond the lane kept for car
        // pools, two and a half lanes right of the road's line, and the move there runs on past the drawing's return. Before
        // it no lane works, and the path keeps to the middle of the lanes drawn there, right of the road's line once the
        // left lane has left.
        val lane = path.moves.single { it.kind == RoutePath.Move.Kind.LANE && abs(it.offsetTo + 2.5 * w) < 1e-9 }
        assertTrue(lane.offsetFrom < 0.0, "the move begins at ${lane.offsetFrom}")
        assertTrue(lane.from < first.to && lane.to > first.back)
        // The return begins where the path is, a little way into the move, and goes only toward the road's line, onto it
        // 60 m on.
        val back = path.moves.single { it.kind == RoutePath.Move.Kind.RETURN }
        assertEquals(first.to, back.from, 1e-9)
        assertEquals(path.offsetAt(first.to), back.offsetFrom, 1e-9)
        assertTrue(back.offsetFrom < lane.offsetFrom && back.offsetFrom > lane.offsetTo, "the return begins at ${back.offsetFrom}")
        assertEquals(first.to + RoutePath.RETURN_METERS, first.back, 1e-9)
        var prev = back.offsetFrom
        var station = first.to
        while (station < first.back) {
            station += 1.0
            val now = path.offsetAt(station)
            assertTrue(now <= 0.0 && now >= prev - 1e-12, "at $station: $prev to $now")
            prev = now
        }
        assertEquals(0.0, path.offsetAt(first.back), 1e-12)
        checkRules("move before a gap", built)
    }

    @Test
    fun `where no lane works the path keeps to the drawn carriageway and goes on from there into the lane named after`() {
        val built = build(conflictDrift())
        val section = built.section
        val path = built.path
        // The scene: no lane works up to 900 m before the gore, and before that the drawn carriageway moves off the road's
        // line to its right.
        val conflict = built.plan.flags.single { it.kind == LanePlan.Flag.Kind.CONFLICT }
        assertEquals(gore - 900.0, conflict.to, 1e-9)
        val drawing = path.drawings.single()
        assertTrue(drawing.from < gore - 1_250.0 && abs(drawing.to - gore) < 1e-9)
        val end = requireNotNull(section.edges(conflict.to, requireNotNull(path.axisAt(conflict.to))))
        assertTrue(end.asphaltLeft < -w, "the drawn asphalt ends ${end.asphaltLeft} m right of the road's line")
        // From where the drawing begins to well past the conflict's end the path lies on the drawn asphalt, moving only right,
        // toward the exit's edge, and it is in the lane the plan names 200 m past the conflict's end.
        var prev = path.offsetAt(drawing.from)
        var station = drawing.from
        while (station < conflict.to + 200.0) {
            station += 1.0
            val offset = path.offsetAt(station)
            val edges = requireNotNull(section.edges(station, requireNotNull(path.axisAt(station))))
            assertTrue(offset <= edges.asphaltLeft + 1e-6 && offset >= edges.asphaltRight - 1e-6, "at $station: $offset off the drawn asphalt ${edges.asphaltRight}..${edges.asphaltLeft}")
            assertTrue(offset <= prev + 1e-9, "at $station: the path moves left, from $prev to $offset")
            prev = offset
        }
        val named = requireNotNull(built.plan.segmentAt(conflict.to + 200.0)?.named)
        assertEquals(section.place(named, conflict.to + 200.0, path.axisAt(conflict.to + 200.0))!!.centre, path.offsetAt(conflict.to + 200.0), 1e-6)
        checkRules("conflict drift", built)
    }

    @Test
    fun `a move into a lane still opening waits for the lane and never comes back`() {
        val built = build(openingSwing())
        val section = built.section
        val path = built.path
        // The scene: no lane works up to 850 m before the gore, where the plan names a lane that opens near the path and ends
        // more than a metre from where the path is.
        val conflict = built.plan.flags.single { it.kind == LanePlan.Flag.Kind.CONFLICT }
        assertEquals(gore - 850.0, conflict.to, 1e-9)
        val start = path.offsetAt(conflict.to)
        val named = requireNotNull(built.plan.segmentAt(conflict.to + 1.0)?.named)
        val at = { station: Double -> requireNotNull(section.place(named, station, path.axisAt(station))) }
        assertEquals(CrossSection.State.OPENING, at(conflict.to + 1.0).state)
        val end = at(conflict.to + 300.0).centre
        assertTrue(abs(end - start) > 1.0, "the lane ends at $end")
        // The move sets off only the way the lane lies where the move ends: the path moves only that way, never past the
        // lane's middle, and is in the lane 300 m on.
        val way = if (end > start) 1.0 else -1.0
        var prev = start
        var station = conflict.to
        while (station < conflict.to + 300.0) {
            station += 1.0
            val now = path.offsetAt(station)
            assertTrue((now - prev) * way >= -1e-9 && (now - end) * way <= 1e-9, "at $station: from $prev to $now, the lane's middle at $end")
            prev = now
        }
        assertEquals(end, path.offsetAt(conflict.to + 300.0), 1e-9)
        checkRules("opening swing", built)
    }

    @Test
    fun `where a lane opens with as many lanes on each side the lanes toward the exit's edge make room for it`() {
        val built = build(openingOnTie())
        val section = built.section
        val path = built.path
        // The scene: the plan names the exit's lane where it opens, 210 m before the gore, between the lane that feeds it and
        // the lane beyond it, one lane on each side.
        val named = requireNotNull(built.plan.segmentAt(gore - 205.0)?.named)
        val feeder = requireNotNull(built.plan.topology.lanes[named].fedBy)
        val axis = { station: Double -> requireNotNull(path.axisAt(station)) }
        assertEquals(CrossSection.State.OPENING, section.place(named, gore - 205.0, axis(gore - 205.0))!!.state)
        // The feeder stays where it is while the lane opens beside it, and the lane beyond moves out toward the exit's edge:
        // the path, which follows the opening lane's taper from the feeder's middle, moves only right, toward that edge.
        val before = section.place(feeder, gore - 215.0, axis(gore - 215.0))!!.centre
        var prev = path.offsetAt(gore - 215.0)
        var station = gore - 215.0
        while (station < gore - 140.0) {
            station += 1.0
            assertEquals(before, section.place(feeder, station, axis(station))!!.centre, 1e-9, "the feeder at $station")
            val now = path.offsetAt(station)
            assertTrue(now <= prev + 1e-9, "at $station: the path moves away from the exit's edge, from $prev to $now")
            prev = now
        }
        assertEquals(section.place(named, gore - 140.0, axis(gore - 140.0))!!.centre, path.offsetAt(gore - 140.0), 1e-9)
        checkRules("opening on a tie", built)
    }

    @Test
    fun `where a gain nothing explains lies on the exit's side the path never moves away from the exit's edge`() {
        val built = build(LaneScenes.exitSideGain())
        val plan = built.plan
        val path = built.path
        // The scene: the run-up's window holds the rule-5 zone of the gain; the plan names the right lane open to every car
        // and, from where it opens, the lane added beside it, which it feeds along its taper.
        val zone = plan.evidence().zones.single { it.cause == LaneEvidence.Zone.Cause.CHANGE }
        val window = plan.windows.single { it.kind == CardWindow.Kind.RUN_UP }
        assertTrue(window.from < zone.from)
        val move = plan.moves.single()
        assertEquals(3, move.from)
        assertEquals(4, move.to)
        assertEquals(3, plan.topology.lanes[4].fedBy)
        // In the window the path keeps to the right lane through the zone and follows the added lane's taper toward the
        // exit's edge: it never moves to the left, away from the edge, and never back.
        val turn = path.moves.last()
        assertEquals(RoutePath.Move.Kind.TURN, turn.kind)
        var last = path.offsetAt(window.from)
        var station = window.from
        while (station < turn.from) {
            val now = path.offsetAt(station)
            assertTrue(now <= last + 1e-9, "away from the exit's edge at $station: $last to $now")
            if (station >= zone.from && station < move.station) assertEquals(built.section.place(3, station)!!.centre, now, 1e-9)
            last = now
            station += 1.0
        }
        assertEquals(built.section.place(4, gore - 100.0)!!.centre, path.offsetAt(gore - 100.0), 1e-9)
        checkRules("exit-side gain", built)
    }

    @Test
    fun `where a lane leaves by a road at the edge with possible lanes beyond it the path keeps to its lane with no step`() {
        val built = build(LaneScenes.leavesBesidePossible())
        val plan = built.plan
        val path = built.path
        // The scene: the plan names the middle lane of three through the run-up; the right lane leaves by a road at the edge
        // 5 m past the point 1,200 m before the gore, with the exit's added lanes only possible beyond it there.
        val point = gore - 1_200.0
        val leaves = point + LaneTopology.LEAVE_PAST_POINT_METERS
        val window = plan.windows.single { it.kind == CardWindow.Kind.RUN_UP }
        assertTrue(window.from < point)
        assertEquals(1, plan.segmentAt(leaves)!!.named)
        assertEquals(CrossSection.State.POSSIBLE, built.section.place(3, leaves - 1.0)!!.state)
        // The named lane keeps its place where the right lane leaves, and so does the path in it.
        for (station in listOf(leaves - 1.0, leaves - 0.25, leaves, leaves + 0.25, leaves + 1.0, gore - 300.0)) {
            assertEquals(0.0, path.offsetAt(station), 1e-9, "at $station")
        }
        assertEquals(listOf(RoutePath.Move.Kind.TURN), path.moves.map { it.kind })
        checkRules("leaves beside possible lanes", built)
    }

    @Test
    fun `the path is the route's own line away from the drawings and meets it at every drawing end`() {
        for ((name, route) in LaneScenes.all()) {
            val built = build(route)
            assertContinuous(name, built.path)
        }
    }

    @Test
    fun `where a drawing begins the path takes the road's heading within 1 degree, next to a bend of the line too`() {
        // The measure on a straight road due north: a path that leaves the line at 100 m turned 0.98 degrees from it meets
        // it; one turned 1.02 degrees does not.
        val line = SceneLines.line(200.0)
        val stations = doubleArrayOf(0.0, 100.0, 200.0)
        val axis = RoadAxis.of(line, stations)
        for ((degrees, meets) in listOf(0.98 to true, 1.02 to false)) {
            val k = tan(Math.toRadians(degrees))
            val turn = startTurn(axis, { s -> k * max(0.0, s - 100.0) }, line, stations, 100.0)
            assertEquals(degrees, turn, 1e-6)
            assertEquals(meets, turn < END_DEGREES, "turned $degrees degrees")
        }
        // Next to a bend: IA's drawing begins 2.5 m before its line bends by 2.5 degrees. The axis rounds the bend with an
        // arc that begins where the drawing does, tangent to the line; the path's first drawn chord spans the arc and turns
        // half the bend from the line before it, while the path's own heading there is the line's.
        val ia = LaneScenes.ia()
        val start = gore - 3_180.0
        val path = build(ia, SceneLines.line(ia.lengthMeters, (SceneLines.bendsOf(ia) + (start + 2.5 to 2.5)).sortedBy { it.first })).path
        assertEquals(start, path.drawings.single().from, 1e-9)
        val fillet = requireNotNull(path.axisAt(start + 1e-6)).fillets.first()
        assertEquals(start, fillet.from, 1e-9)
        assertTrue(startTurn(requireNotNull(path.axisAt(start + 1e-6)), path::offsetAt, path.route, path.routeStations, start) < 1e-6)
        val chord = turnBetween(headingAt(path.points, path.stations, start), headingAt(path.route, path.routeStations, start))
        assertEquals(1.25, chord, 0.05)
        assertContinuous("ia, bent near its drawing's start", path)
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
        // Each window at most 3 km, give or take the half segments of the line its cuts move to.
        val axes = generateSequence(drawing.from) { it + 1.0 }.takeWhile { it < drawing.back }.mapNotNull { path.axisAt(it) }.distinct().toList()
        assertEquals(path.windowCount, axes.size)
        for (axis in axes) assertTrue(axis.to - axis.from <= 3_000.0 + SceneLines.STEP, "a window from ${axis.from} to ${axis.to}")
        assertContinuous("long", path)
        // Where windows meet the path has no step: consecutive points stay close and turn little.
        for (i in 1 until path.points.size) {
            val step = Geo.meters(path.points[i - 1], path.points[i])
            val along = path.stations[i] - path.stations[i - 1]
            assertTrue(step <= along + 0.5, "step $step over $along m at ${path.stations[i]}")
        }
    }

    @Test
    fun `a drawing just under 3 km is one window and one just over it two`() {
        // Four lanes at three lane points on a straight freeway, the last one with no lane data after it: one drawing
        // from the first point's reach to the last point, and its return; the exit has no lane data.
        for ((spacing, windows) in listOf(969.75 to 1, 970.25 to 2)) {
            val first = 1_500.0
            val last = first + 2.0 * spacing
            val exitAt = last + 1_500.0
            val route = LaneScenes.route(
                LaneScenes.node(0.0, maneuver = LaneScenes.DEPART, planMps = 27.0),
                LaneScenes.node(first, s(), s(), s(), s(), planMps = 27.0),
                LaneScenes.node(first + spacing, s(), s(), s(), s(), planMps = 27.0),
                LaneScenes.node(last, s(), s(), s(), s(), planMps = 27.0),
                LaneScenes.node(exitAt, road = RoadKind.RAMP, maneuver = LaneScenes.OFF_RIGHT, planMps = 20.0),
                LaneScenes.node(exitAt + 400.0, road = RoadKind.STREET, maneuver = LaneScenes.TURN_LEFT, planMps = 15.0),
                LaneScenes.node(exitAt + 500.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = 15.0),
                id = "straight-$spacing",
            )
            val path = build(route, SceneLines.line(route.lengthMeters)).path
            val drawing = path.drawings.single()
            assertEquals(first - 1_000.0, drawing.from, 1e-9)
            assertEquals(last, drawing.to, 1e-9)
            assertFalse(drawing.turnsAtEnd)
            // The stretch cut into windows: the drawing and its return, 2,999.5 m and 3,000.5 m.
            val stretch = drawing.to + RoutePath.RETURN_METERS - drawing.from
            assertEquals(if (windows == 1) 2_999.5 else 3_000.5, stretch, 1e-9)
            assertEquals(windows, path.windowCount, "over $stretch m")
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

    @Test
    fun `the path keeps the design's rules where lanes are kept for car pools or buses, on seeded random routes`() {
        // Every route of randomKeptLane with its line bent as the routes above, and once more with its line bent by a few
        // degrees a few metres after its first drawing begins, so the axis' first arc begins with the drawing.
        val rng = Random(KEPT_LANE_SEED)
        var inside = 0
        var beyond = 0
        var pastNamed = 0
        var atBend = 0
        var underWay = 0
        repeat(KEPT_LANE_ROUTES) { k ->
            val name = "kept-$k"
            val route = randomKeptLane(rng, name)
            val bends = (1..8).map { rng.nextDouble(200.0, gore - 400.0) to rng.nextDouble(-4.0, 4.0) }
            val all = (bends + SceneLines.bendsOf(route)).sortedBy { it.first }
            val built = build(route, SceneLines.line(route.lengthMeters, all))
            checkRules(name, built)
            val plan = built.plan
            // What the route builds: a kept lane with lanes open to every car on both sides; an exit whose lane lies beyond
            // a kept lane, where no lane is named (C1); a run-up that begins off the plan's grid, with the band's middle
            // just before it past the lane it names, toward the exit's edge.
            if (route.nodes.any { node -> node.lanes.withIndex().any { (i, lane) -> lane.designated && i > 0 && i < node.lanes.size - 1 } }) inside++
            if (plan.noLane.any { it.cause == LanePlan.NoLane.Cause.DESIGNATED_BETWEEN }) beyond++
            if (plan.windows.any { leadsPastNamed(built, it) }) pastNamed++
            // A drawing that ends at a turn while a move into the drawing or across lanes is still under way.
            val across = built.path.moves.filter { it.kind == RoutePath.Move.Kind.ENTER || it.kind == RoutePath.Move.Kind.LANE }
            if (built.path.drawings.any { d -> d.turnsAtEnd && across.any { it.from < d.to && it.to > d.to } }) underWay++
            val start = built.path.drawings.firstOrNull()?.from ?: return@repeat
            val bent = build(route, SceneLines.line(route.lengthMeters, (all + (start + 0.25 + 0.5 * (k % 15) to if (k % 2 == 0) 2.5 else -3.5)).sortedBy { it.first }))
            assertEquals(start, bent.path.drawings.first().from, 1e-9, name)
            checkRules("$name, bent where its drawing begins", bent)
            if (abs(requireNotNull(bent.path.axisAt(start + 1e-6)).fillets.first().from - start) < 1e-9) atBend++
        }
        assertTrue(inside >= 70, "$inside routes with a kept lane between lanes open to every car")
        assertTrue(beyond >= 30, "$beyond routes with an exit lane beyond a kept lane")
        assertTrue(pastNamed >= 8, "$pastNamed routes with a window off the grid and the band past its lane")
        assertTrue(atBend >= 140, "$atBend routes whose drawing begins on a bend's arc")
        assertTrue(underWay >= 7, "$underWay routes whose drawing ends at a turn with a move under way")
    }

    /**
     * Whether [window] begins between the plan's grid stations, where no lane is named yet, and the band's middle just
     * before it lies past the lane it names first, toward the exit's edge: the shape that left a window without a lane to
     * lead the band to.
     */
    private fun leadsPastNamed(built: Built, window: CardWindow): Boolean {
        val plan = built.plan
        val at = plan.segmentAt(window.from) ?: return false
        if (at.named != null) return false
        val offGrid = (window.from - plan.topology.runs[at.run].from) % LanePlanner.GRID_METERS
        if (offGrid < 1e-6 || offGrid > LanePlanner.GRID_METERS - 1e-6) return false
        val first = plan.segments.firstOrNull { it.named != null && it.from > window.from && it.from < window.to } ?: return false
        val named = built.section.place(requireNotNull(first.named), first.from)?.centre ?: return false
        val middle = built.section.middle(at.band, window.from) ?: return false
        val sign = if (at.edge == LaneEdge.RIGHT) -1.0 else if (at.edge == LaneEdge.LEFT) 1.0 else return false
        return (middle - named) * sign > 1e-6
    }

    /**
     * Checks [built] against the design's rules and returns how many card windows it checked: inside a card window the
     * path is in the named lane except inside a move and never moves away from the exit's edge; inside the drawings it
     * moves only toward the plan's lane, then never back; it is back on the road's line only where a drawing ends outside
     * card windows; it is on the road's line on an approach where no lane is named (C1); it meets the route's line at every
     * drawing end; it never steps sideways inside a drawing; at a ramp's start inside a drawing it lies on the asphalt of the
     * lanes that go on; where no lane works it keeps to the drawn carriageway, on its asphalt where that stretch ends inside a
     * drawing and through the moves under way there, and a move from there into a lane still opening goes one way; where a
     * drawing ends, its turn or its return takes over a move still under way.
     */
    private fun checkRules(name: String, built: Built): Int {
        val path = built.path
        val plan = built.plan
        assertContinuous(name, path)
        // No step inside the drawings: its moves, tapers and blends move the path far less than a step over a quarter metre.
        for (drawing in path.drawings) {
            var station = drawing.from
            var prev = path.offsetAt(station)
            while (station + STEP_SAMPLE_METERS < drawing.to) {
                station += STEP_SAMPLE_METERS
                val now = path.offsetAt(station)
                assertTrue(abs(now - prev) < STEP_METERS, "$name at $station: a step from $prev to $now")
                prev = now
            }
        }
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
                // On the drawn asphalt or on the road's line wherever the plan names a lane, inside moves and at a ramp's
                // start too; the blends around a fold aside.
                val folded = path.folds.any { station >= it.from - RoutePath.RETURN_METERS && station <= it.to + RoutePath.RETURN_METERS }
                if (seg != null && seg.inWindow && named != null && seg.noLane == null && drawn && !folded) {
                    val offset = path.offsetAt(station)
                    val edges = built.section.edges(station, requireNotNull(path.axisAt(station)))
                    if (edges != null && abs(offset) > 1e-9) {
                        assertTrue(
                            offset <= edges.asphaltLeft + 1e-6 && offset >= edges.asphaltRight - 1e-6,
                            "$name at $station: $offset off the drawn asphalt ${edges.asphaltRight}..${edges.asphaltLeft}",
                        )
                    }
                }
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
        // C1: on an approach where no lane is named the path is on the road's line, but at a turn or a fold and while a move
        // begun before the approach is still under way.
        for (approach in plan.noLane) {
            val begun = path.moves.filter { it.from < approach.from - 1e-6 }.map { it.from to it.to + 1e-6 }
            val skip = turns(built) + begun + path.folds.map { it.from - RoutePath.RETURN_METERS to it.to + RoutePath.RETURN_METERS }
            var station = approach.from
            while (station < approach.to) {
                val drawn = path.drawings.any { station >= it.from && station < it.to }
                if (drawn && skip.none { station >= it.first && station <= it.second }) {
                    assertEquals(0.0, path.offsetAt(station), 1e-9, "$name at $station: off the road's line on an approach where no lane is named")
                }
                station += 1.0
            }
        }
        // At a ramp's start inside a drawing only the lanes that go on onto the new road are drawn past the turn: the path
        // lies on their asphalt there (or on the road's line, past an approach where no lane is named), so a move toward a
        // lane named late has ended, not left the path beside the drawn ramp.
        for (start in built.section.rampStarts) {
            if (path.drawings.none { start.station > it.from && start.station < it.to }) continue
            if (path.folds.any { start.station >= it.from - RoutePath.RETURN_METERS && start.station <= it.to + RoutePath.RETURN_METERS }) continue
            val offset = path.offsetAt(start.station)
            if (abs(offset) < 1e-9) continue
            val edges = requireNotNull(built.section.edges(start.station, requireNotNull(path.axisAt(start.station))))
            assertTrue(
                offset <= edges.asphaltLeft + 1e-6 && offset >= edges.asphaltRight - 1e-6,
                "$name: at the ramp's start at ${start.station} the path at $offset lies beside the lanes that go on, ${edges.asphaltRight}..${edges.asphaltLeft}",
            )
        }
        // Where no lane works (a conflict the plan flags) the path keeps to the middle of the lanes drawn there, so where that
        // stretch ends inside a drawing, it lies on the drawn asphalt or on the road's line, and so it does through the moves
        // under way there, within [CONFLICT_SLACK_METERS]; the turns and the blends around a fold aside, and an approach where
        // no lane is named, which keeps to the road's line.
        for (conflict in plan.flags) {
            if (conflict.kind != LanePlan.Flag.Kind.CONFLICT) continue
            val drawing = path.drawings.firstOrNull { conflict.to > it.from && conflict.to < it.to } ?: continue
            val moving = path.moves.filter { (it.kind == RoutePath.Move.Kind.ENTER || it.kind == RoutePath.Move.Kind.LANE) && it.from <= conflict.to + 1.0 && it.to >= conflict.to }
            val until = minOf(drawing.to, moving.maxOfOrNull { it.to } ?: conflict.to)
            val skip = turns(built) + path.folds.map { it.from - RoutePath.RETURN_METERS to it.to + RoutePath.RETURN_METERS }
            var station = conflict.to
            while (station <= until) {
                val offset = path.offsetAt(station)
                if (abs(offset) > 1e-9 && plan.segmentAt(station)?.noLane == null && skip.none { station >= it.first && station <= it.second }) {
                    val edges = requireNotNull(built.section.edges(station, requireNotNull(path.axisAt(station))))
                    assertTrue(
                        offset <= edges.asphaltLeft + CONFLICT_SLACK_METERS && offset >= edges.asphaltRight - CONFLICT_SLACK_METERS,
                        "$name at $station: where no lane works up to ${conflict.to}, $offset off the drawn asphalt ${edges.asphaltRight}..${edges.asphaltLeft}",
                    )
                }
                station += 1.0
            }
        }
        // A move that begins where a stretch where no lane works ends, into a lane still opening there, goes one way, from
        // where it begins toward where it ends (up to the next move), and never past it: it waits for the lane rather than set
        // off the wrong way and come back. The turns and the blends around a fold aside.
        val across = path.moves.filter { it.kind == RoutePath.Move.Kind.ENTER || it.kind == RoutePath.Move.Kind.LANE }.sortedBy { it.from }
        for (conflict in plan.flags) {
            if (conflict.kind != LanePlan.Flag.Kind.CONFLICT) continue
            val drawing = path.drawings.firstOrNull { conflict.to > it.from && conflict.to < it.to } ?: continue
            val skip = turns(built) + path.folds.map { it.from - RoutePath.RETURN_METERS to it.to + RoutePath.RETURN_METERS }
            for ((i, move) in across.withIndex()) {
                if (move.from < conflict.to - 1e-6 || move.from > conflict.to + 1.0) continue
                val lane = plan.segmentAt(move.from + 1e-6)?.named ?: continue
                if (built.section.place(lane, move.from + 1e-6, path.axisAt(move.from))?.state != CrossSection.State.OPENING) continue
                val end = minOf(move.to, drawing.to, across.getOrNull(i + 1)?.from ?: move.to)
                val to = path.offsetAt(end)
                val sign = if (to >= path.offsetAt(move.from)) 1.0 else -1.0
                var prev = path.offsetAt(move.from)
                var station = move.from
                while (station < end - 1e-9) {
                    station = minOf(end, station + 1.0)
                    val now = path.offsetAt(station)
                    if (skip.none { station >= it.first && station <= it.second }) {
                        assertTrue(
                            (now - prev) * sign >= -1e-9 && (now - to) * sign <= 1e-9,
                            "$name at $station: the move into the opening lane $lane goes from $prev to $now, toward $to",
                        )
                    }
                    prev = now
                }
            }
        }
        // At a drawing's end its turn or its return takes over a move still under way: past the turn the path is the turn's
        // straight-on line from where the path was when the turn began; past the return it moves only toward the road's line.
        for ((i, drawing) in path.drawings.withIndex()) {
            // Where the path keeps to the road's line around a fold, the fold's blends move it there.
            fun folded(from: Double) = path.folds.any { it.to > from - RoutePath.RETURN_METERS && it.from < drawing.back + RoutePath.RETURN_METERS }
            if (drawing.turnsAtEnd) {
                val turn = path.moves.lastOrNull { it.kind == RoutePath.Move.Kind.TURN && it.from <= drawing.to + 1e-6 && it.from >= drawing.to - CrossSection.RAMP_START_MAX_METERS } ?: continue
                if (folded(turn.from)) continue
                val axis = requireNotNull(path.axisAt(turn.from + 1e-6))
                assertEquals(path.offsetAt(turn.from), turn.offsetFrom, 1e-9, "$name: the turn at ${drawing.to} begins where the path is")
                for (station in axis.stations()) {
                    if (station < turn.from || station > drawing.back) continue
                    val straight = CrossSection.continuation(axis, drawing.to, turn.offsetFrom, station)
                    assertEquals(straight, path.offsetAt(station), END_SAME_METERS, "$name at $station: off the turn's straight-on line")
                }
            } else if (!folded(drawing.to)) {
                val until = minOf(drawing.back, path.drawings.getOrNull(i + 1)?.from ?: drawing.back)
                var prev = path.offsetAt(drawing.to)
                var station = drawing.to
                while (station < until) {
                    station = minOf(until, station + 1.0)
                    val now = path.offsetAt(station)
                    assertTrue(abs(now) <= abs(prev) + 1e-9 && now * prev >= 0.0, "$name at $station: the return moves away from the road's line ($prev to $now)")
                    prev = now
                }
                if (until == drawing.back) assertEquals(0.0, path.offsetAt(drawing.back), 1e-9, "$name: off the road's line where the return ends")
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
     * [END_METERS], and its heading is the line's within [END_DEGREES]: where a drawing begins, the path's own heading
     * ([startTurn]); where one ends, the path's next segment, which is the line's own.
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
                val meters = Geo.meters(pointAt(path.points, path.stations, end), pointAt(path.route, path.routeStations, end))
                assertTrue(meters < END_METERS, "$name: $meters m off the line at $end")
                val turn = if (end == span.first) {
                    startTurn(requireNotNull(path.axisAt(end + 1e-6)), path::offsetAt, path.route, path.routeStations, end)
                } else {
                    turnBetween(headingAt(path.points, path.stations, end), headingAt(path.route, path.routeStations, end))
                }
                assertTrue(turn < END_DEGREES, "$name: a heading $turn degrees off the line's at $end")
            }
        }
    }

    /**
     * How far, in degrees, the heading of a path along [axis] at the offsets [offsetAt] turns from that of the road's line
     * [route] (its points at [routeStations]) where a drawing begins at [start]. The path's heading there is its own: its
     * axis' direction turned by the slope of its offset over the first [END_SLOPE_METERS] (the offset curve's tangent,
     * design 4.1). Not the chord to its next drawn point: where the road's line bends a few metres on, the axis rounds the
     * bend with an arc that may begin at the drawing's start, tangent to the line, and the 5 cm simplification may span
     * that arc with one chord, whose heading lies between the line's before and after the bend.
     */
    private fun startTurn(axis: RoadAxis, offsetAt: (Double) -> Double, route: List<GeoPoint>, routeStations: DoubleArray, start: Double): Double {
        val t = offsetAt(start)
        val slope = (offsetAt(start + END_SLOPE_METERS) - t) / END_SLOPE_METERS
        val place = axis.at(start)
        val along = axis.regularity(start, t)
        val from = axis.offset(start, t)
        val to = LocalPoint(from.x + place.tx * along + place.nx * slope, from.y + place.ty * along + place.ny * slope)
        val a = SceneLines.local(axis.toGeo(from))
        val b = SceneLines.local(axis.toGeo(to))
        return turnBetween(Math.toDegrees(atan2(b.x - a.x, b.y - a.y)), headingAt(route, routeStations, start))
    }

    /** The turn from heading [b] to heading [a], in degrees, 0 to 180. */
    private fun turnBetween(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

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

    /**
     * A left exit, four lanes and a car-pool lane on the right. At a lane point 2,950 m before the gore the third and fourth
     * lanes cannot be used (no road leaves there, they go on); 2,000 m before, the left lane leaves by a road on the left.
     * The exit takes the leftmost lane left. At 27 m/s the seam begins the run-up 162 m before the band drops the left lane,
     * between two of the plan's grid stations.
     */
    private fun offGridWindow() = LaneScenes.approach(
        listOf(
            At(2_950.0, listOf(s(), s(), s(false), s(false), hov())),
            At(2_000.0, listOf(s(false), s(), s(false), s(false), hov()), EXIT_LEFT),
        ),
        exit = listOf(sl(), s(false), s(false), hov()),
        maneuver = LaneScenes.OFF_LEFT,
        id = "off-grid-window",
    )

    /**
     * An exit on the [edge] side whose lane lies beyond the lane [kept] (kept for car pools or buses) at that edge: three lanes
     * open to every car and the kept lane 2,200 m before the gore; 1,100 m before, a lane beyond the kept one, the only lane
     * the exit can be taken from. Planned at 26.5 m/s, so the exit's run-up begins between the plan's grid stations.
     */
    private fun beyondKeptLane(kept: RouteLane, edge: LaneEdge): LaneRoute {
        val right = edge == LaneEdge.RIGHT
        fun side(vararg lanes: RouteLane) = if (right) lanes.toList() else lanes.reversed()
        return LaneScenes.approach(
            listOf(At(2_200.0, side(s(), s(), s(), kept)), At(1_100.0, side(s(), s(), s(), kept, s()))),
            exit = side(s(false), s(false), s(false), kept, if (right) sr() else sl()),
            maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT,
            planMps = 26.5,
            id = "beyond-kept-lane",
        )
    }

    /**
     * [beyondKeptLane]'s right exit beyond a lane kept for car pools, with lane data on its ramp: the ramp splits 180 m past
     * the gore, where the route keeps left.
     */
    private fun beyondKeptLaneWithSplit() = LaneScenes.approach(
        listOf(At(2_200.0, listOf(s(), s(), s(), hov())), At(1_100.0, listOf(s(), s(), s(), hov(), s()))),
        exit = listOf(s(false), s(false), s(false), hov(), sr()),
        planMps = 26.5,
        rampPoints = listOf(180.0 to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT)),
        id = "beyond-kept-lane-with-split",
    )

    /**
     * A right exit with too few lanes open to every car for a run-up: two lanes 2,000 m before the gore, the right one kept for
     * buses from a point 1,300 m before, a lane beyond it from a point 800 m before, the only one the exit can be taken from.
     * Planned at 26.5 m/s.
     */
    private fun beyondBusLane() = LaneScenes.approach(
        listOf(At(2_000.0, listOf(s(), s())), At(1_300.0, listOf(s(), LaneScenes.bus())), At(800.0, listOf(s(), LaneScenes.bus(), s()))),
        exit = listOf(s(false), LaneScenes.bus(), sr()),
        planMps = 26.5,
        id = "beyond-bus-lane",
    )

    /**
     * A right exit that takes the lane kept for car pools: six lanes 2,400 and 1,200 m before the gore, the right one kept
     * for car pools; the exit lists it open to every car, the only lane to take, so the plan names it only where it opens,
     * late, and the move to it begins on the road's line.
     */
    private fun lateMove() = LaneScenes.approach(
        listOf(At(2_400.0, listOf(s(), s(), s(), s(), s(), hov())), At(1_200.0, listOf(s(), s(), s(), s(), s(), hov()))),
        exit = listOf(s(false), s(false), s(false), s(false), s(false), sr()),
        id = "late-move",
    )

    /**
     * [lateMove]'s right exit planned at [mps], with lane data on its ramp: the ramp splits 180 m past the gore, where the
     * route keeps left, so the drawing goes on past the gore onto the ramp's lanes.
     */
    private fun lateMoveOntoRampLanes(mps: Double) = LaneScenes.approach(
        listOf(At(2_400.0, listOf(s(), s(), s(), s(), s(), hov())), At(1_200.0, listOf(s(), s(), s(), s(), s(), hov()))),
        exit = listOf(s(false), s(false), s(false), s(false), s(false), sr()),
        planMps = mps,
        rampPoints = listOf(180.0 to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT)),
        id = "late-move-onto-ramp-lanes",
    )

    /**
     * A right exit beyond a gap in the lane data: five lanes 2,400 m before the gore; 2,000 m before, the left one leaves by
     * a road on the left and the right one is kept for car pools; 1,650 m before, a lane beyond it, and no lane point from
     * there to the exit. The exit's lanes lie beyond the kept one.
     */
    private fun moveBeforeGap() = LaneScenes.approach(
        listOf(
            At(2_400.0, listOf(s(), s(), s(), s(), s())),
            At(2_000.0, listOf(s(false), s(), s(), hov()), EXIT_LEFT),
            At(1_650.0, listOf(s(), s(), s(), hov(), s())),
        ),
        exit = listOf(s(false), s(false), s(false), hov(), sr(), sr()),
        id = "move-before-gap",
    )

    /**
     * A right exit past a stretch where no lane works: three lanes 1,900 m before the gore, the middle one kept for car pools;
     * 1,250 m before, the left one leaves by a road on the left; 850 m before, four lanes, and five at the exit, the right
     * two to take. No lane works up to 900 m before the gore, where lanes open left of the right lane and the plan names
     * one; before that, as the left lanes leave and those open, the drawn carriageway moves off the road's line to its right.
     */
    private fun conflictDrift() = LaneScenes.approach(
        listOf(
            At(1_900.0, listOf(s(), hov(), s())),
            At(1_250.0, listOf(s(false), hov(), s()), EXIT_LEFT),
            At(850.0, listOf(s(), hov(), s(), s(false))),
        ),
        exit = listOf(s(false), s(false), s(false), sr(), sr()),
        id = "conflict-drift",
    )

    /**
     * A right exit past a lane that opens next to the path: two lanes 1,600 m before the gore and three 800 m before, the
     * right one not to take; four at the exit, the right two to take. No lane works up to 850 m before the gore, where two
     * lanes open beside the lane drawn there and the plan names the right one of them.
     */
    private fun openingSwing() = LaneScenes.approach(
        listOf(At(1_600.0, listOf(s(), s(false))), At(800.0, listOf(s(), s(), s(false)))),
        exit = listOf(s(false), s(false), sr(), sr()),
        id = "opening-swing",
    )

    /**
     * A right exit whose lane opens between two lanes: three lanes 1,800 m before the gore, the left one kept for car pools;
     * 1,100 m before, two, the kept one leaving by a road on the left; three at the exit, the right two to take, and the
     * ramp splits 180 m past the gore, where the route keeps left. The exit's right lane lies beyond the two lanes as a
     * possible lane, and the lane the plan names opens 210 m before the gore between the lane that feeds it and that one.
     */
    private fun openingOnTie() = LaneScenes.approach(
        listOf(At(1_800.0, listOf(hov(), s(), s())), At(1_100.0, listOf(hov(), s()), EXIT_LEFT)),
        exit = listOf(s(false), sr(), sr()),
        rampPoints = listOf(180.0 to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT)),
        id = "opening-on-tie",
    )

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

    /**
     * A made-up approach to an exit with a lane kept for car pools or buses: two to six lanes at up to three random lane
     * points, one lane at a random index (either side or between others) kept for car pools or buses from a random point on,
     * edge lanes leaving by roads, an exit on either side whose lanes may keep that lane or open it to every car again, one
     * or two lanes to take, an added exit lane that may lie beyond the kept one, and a split after the gore in 30 %.
     */
    private fun randomKeptLane(rng: Random, id: String): LaneRoute {
        val right = rng.nextBoolean()
        val bus = rng.nextBoolean()
        var count = rng.nextInt(2, 6)
        val points = ArrayList<At>()
        var before = rng.nextDouble(2_400.0, 3_400.0)
        var lane = -1
        val from = rng.nextInt(0, 3)
        while (true) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0 || points.size >= 3) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            if (lane < 0 && points.size >= from) lane = rng.nextInt(count)
            if (lane >= count) lane = count - 1
            val lanes = ArrayList<RouteLane>()
            repeat(count) { i -> lanes.add(if (i == lane) (if (bus) LaneScenes.bus() else hov()) else s()) }
            val roads = when (rng.nextInt(5)) {
                0 -> {
                    if (lane != 0) lanes[0] = s(false)
                    EXIT_LEFT
                }
                1 -> {
                    if (lane != lanes.size - 1) lanes[lanes.size - 1] = s(false)
                    EXIT_RIGHT
                }
                2 -> JOIN_RIGHT
                else -> THROUGH
            }
            points.add(At(before, lanes, roads))
        }
        val exit = ArrayList<RouteLane>()
        val keep = rng.nextBoolean()
        repeat(count) { i -> exit.add(if (i == lane && keep) (if (bus) LaneScenes.bus() else hov()) else s(false)) }
        val usable = rng.nextInt(1, 3)
        val edge = if (right) exit.size - 1 else 0
        exit[edge] = if (right) sr() else sl()
        if (usable == 2) {
            val inner = if (right) edge - 1 else edge + 1
            if (inner in exit.indices && !exit[inner].designated) exit[inner] = option()
        }
        if (rng.nextBoolean()) exit.add(if (right) exit.size else 0, if (right) sr() else sl())
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

        /** How many seeded random routes with a lane kept for car pools or buses the rules are checked on... */
        const val KEPT_LANE_ROUTES = 200

        /** ...and their seed. */
        const val KEPT_LANE_SEED = 7

        /** At a drawing end the path meets the road's line within this distance (design 10.1, RoutePath)... */
        const val END_METERS = 0.01

        /** ...with its heading within this many degrees of the line's. */
        const val END_DEGREES = 1.0

        /** Past a turn at a drawing's end the path is the turn's straight-on line within this distance. */
        const val END_SAME_METERS = 1e-3

        /** Where a drawing begins, the path's heading is taken from the slope of its offset over this distance. */
        const val END_SLOPE_METERS = 0.1

        /**
         * Inside a drawing the path moves less than this over every [STEP_SAMPLE_METERS]: its S between lanes, the tapers it
         * follows and the blends at a ramp's start move it less than half a metre a metre, while a step of a lane between
         * two of its samples, a metre apart, shows as almost a metre a quarter metre.
         */
        const val STEP_METERS = 0.2

        /** ...measured this often along the drawing. */
        const val STEP_SAMPLE_METERS = 0.25

        /**
         * How far beside the drawn asphalt the path may lie where a stretch where no lane works ends: where the asphalt steps
         * in past it (a lane at the edge ending with no taper) or the lane the plan heads for opens beside the lanes drawn,
         * the path holds where it is rather than move back.
         */
        const val CONFLICT_SLACK_METERS = 0.3
    }
}
