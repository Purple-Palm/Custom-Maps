package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.At
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Kind
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Part
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Pattern
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Width
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2

/**
 * The app's own freeway lanes drawn on the made-up routes of [LaneScenes], along made-up lines at sea that run due north
 * from 0, 0: before a route's first bend a point's station is its northing and its offset from the road's line, left
 * positive, its westing.
 */
class LaneDrawingTest {

    private val w = LaneTopology.FREEWAY_LANE_METERS
    private val apron = CrossSection.APRON_METERS
    private val gore = LaneScenes.GORE

    private class Drawn(
        val route: LaneRoute,
        val plan: LanePlan,
        val evidence: LaneEvidence,
        val path: RoutePath,
        val drawing: LaneDrawing,
        val features: List<LaneDrawing.Feature>,
    ) {
        fun of(kind: Kind, part: Part = Part.ROUTE) = features.filter { it.kind == kind && it.part == part }
    }

    private fun draw(route: LaneRoute, line: List<GeoPoint> = SceneLines.of(route)): Drawn {
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence)
        val path = RoutePath.of(plan, line, version = 1, evidence = evidence, section = section)
        val drawing = LaneDrawing.of(plan, path, evidence, section)
        return Drawn(route, plan, evidence, path, drawing, drawing.features())
    }

    /** A point's station before the route's first bend: its northing. */
    private fun station(p: GeoPoint) = SceneLines.local(p).y

    /** Its offset from the road's line there, left positive: its westing. */
    private fun offset(p: GeoPoint) = -SceneLines.local(p).x

    private fun meters(a: GeoPoint, b: GeoPoint) = SceneLines.local(a).distanceTo(SceneLines.local(b))

    private fun width(edges: Pair<Double, Double>?) = requireNotNull(edges).let { it.first - it.second }

    /** Whether [feature] covers [station] (its stations, both ends in). */
    private fun covers(feature: LaneDrawing.Feature, station: Double) = station >= feature.from - 1e-6 && station <= feature.to + 1e-6

    @Test
    fun `asphalt is the lanes and an apron outside each edge line, from a taper out of the map's band`() {
        val d = draw(LaneScenes.iw31())
        val drawing = d.drawing
        // Four lanes, centred on the road's line where the run's lanes begin, and an apron outside each edge line.
        assertEquals(2.0 * w + apron, drawing.asphaltAt(gore - 3_000.0)!!.first, 1e-9)
        assertEquals(-2.0 * w - apron, drawing.asphaltAt(gore - 3_000.0)!!.second, 1e-9)
        // Where the exit-only lane may be there, five.
        assertEquals(5.0 * w + 2.0 * apron, width(drawing.asphaltAt(gore - 1_000.0)), 1e-9)
        // The drawing begins at the reach of its first lane point; 40 m before, the map's own band, 9 m about the line.
        val from = d.path.drawings.first().from
        assertEquals(gore - 3_600.0, from, 1e-9)
        val taper = RoutePath.ASPHALT_TAPER_METERS
        assertNull(drawing.asphaltAt(from - taper - 0.01))
        assertEquals(LaneDrawing.STANDARD_FREEWAY_BAND_METERS, width(drawing.asphaltAt(from - taper)), 1e-9)
        assertEquals((LaneDrawing.STANDARD_FREEWAY_BAND_METERS + 4.0 * w + 2.0 * apron) / 2.0, width(drawing.asphaltAt(from - taper / 2.0)), 1e-9)
        assertEquals(4.0 * w + 2.0 * apron, width(drawing.asphaltAt(from)), 1e-9)
        // The feature itself: its first cross-section is the band's width, its points on the asphalt's edges.
        val asphalt = d.of(Kind.ASPHALT).single()
        val ring = asphalt.parts.single()
        assertEquals(from - taper, asphalt.from, 1e-6)
        assertEquals(ring.first(), ring.last())
        assertEquals(LaneDrawing.STANDARD_FREEWAY_BAND_METERS, meters(ring.first(), ring[ring.size - 2]), MM)
        val west = ring.filter { abs(station(it) - (gore - 3_000.0)) < 1_000.0 }.maxOf { offset(it) }
        assertEquals(2.0 * w + apron, west, MM)
    }

    @Test
    fun `where a drawing ends on its road the asphalt goes on through the path's return and tapers into the map's band past it`() {
        val d = draw(endsOnItsRoad())
        val first = d.path.drawings.first()
        val end = gore - 5_000.0
        assertEquals(end, first.to, 1e-9)
        assertFalse(first.turnsAtEnd)
        // Four lanes there (the left one leaves by its road just past the point), held through the 60 m return without lines,
        // and the taper past it.
        val held = d.drawing.asphaltAt(end - 1e-3)
        assertEquals(4.0 * w + 2.0 * apron, width(held), 1e-9)
        assertEquals(held!!.first, d.drawing.asphaltAt(end + RoutePath.RETURN_METERS / 2.0)!!.first, 1e-9)
        assertEquals(LaneDrawing.STANDARD_FREEWAY_BAND_METERS, width(d.drawing.asphaltAt(first.asphaltTo)), 1e-9)
        assertNull(d.drawing.asphaltAt(first.asphaltTo + 0.01).takeIf { first.asphaltTo + 0.01 < d.path.drawings[1].asphaltFrom })
        // No line runs on past the drawing's end.
        for (f in d.features.filter { !it.kind.polygon && it.from < end }) assertTrue(f.to <= end + 1e-6, "${f.kind} ${f.between} runs to ${f.to}")
    }

    @Test
    fun `lane lines are broken 3_05 m on 9_15 m from the route's start, two points a dash`() {
        val d = draw(LaneScenes.iw31())
        val line = d.of(Kind.LANE_LINE).first { it.between == Pair(0, 1) && covers(it, gore - 3_000.0) }
        assertEquals(Pattern.BROKEN, line.pattern)
        assertEquals(Width.NORMAL, line.width)
        assertEquals(LaneDrawing.Paint.WHITE, line.paint)
        assertEquals(LaneDrawing.Pattern.BROKEN.periodMeters, LaneDrawing.BROKEN_DASH_METERS + LaneDrawing.BROKEN_GAP_METERS, 1e-12)
        assertEquals(12.2, Pattern.BROKEN.periodMeters, 1e-12)
        var inner = 0
        for (dash in line.parts) {
            assertEquals(2, dash.size)
            // Between lanes 0 and 1: a lane left of the road's line.
            assertEquals(w, offset(dash[0]), MM)
            assertEquals(w, offset(dash[1]), MM)
            val a = station(dash[0])
            val b = station(dash[1])
            if (a > line.from + 1e-6 && b < line.to - 1e-6) {
                inner++
                assertEquals(LaneDrawing.BROKEN_DASH_METERS, b - a, 1e-6)
                val phase = a / Pattern.BROKEN.periodMeters
                assertEquals(Math.round(phase).toDouble(), phase, 1e-6, "a dash begins at a multiple of the period: $a")
            }
        }
        assertTrue(inner > 120)
    }

    @Test
    fun `an exit-only lane has a dotted line beside it, normal for an added lane and wide for a through lane that drops`() {
        // IW31's exit-only lane is added at P2: a normal dotted line from where it is known, its late taper, to the gore.
        val iw31 = draw(LaneScenes.iw31())
        val dotted = iw31.of(Kind.LANE_LINE).single { it.between == Pair(3, 4) && it.pattern == Pattern.DOTTED }
        assertEquals(Width.NORMAL, dotted.width)
        val taper = LaneTopology.ADDED_TAPER_FAST * LaneTopology.FREEWAY_LANE_METERS
        assertEquals(gore - 620.0 - taper, dotted.from, 1e-6)
        assertTrue(dotted.to <= gore + 1e-6 && dotted.to > gore - 1.0)
        // Dashes are cut at the route's stations: on the gore's tight arc an inner line's dash is shorter on the ground, by
        // 1 - kappa t; measured here before it.
        for (dash in dotted.parts.drop(1).filter { station(it[1]) < gore - 20.0 }) {
            assertEquals(LaneDrawing.DOTTED_DASH_METERS, station(dash[1]) - station(dash[0]), 1e-6)
            val phase = station(dash[0]) / Pattern.DOTTED.periodMeters
            assertEquals(Math.round(phase).toDouble(), phase, 1e-6)
        }
        assertEquals(LaneDrawing.DOTTED_DASH_METERS + LaneDrawing.DOTTED_GAP_METERS, Pattern.DOTTED.periodMeters, 1e-12)
        // Where the right lane of four goes on through at a point 1,200 m before and only exits at the gore: a lane drop.
        val drop = draw(dropAtExit())
        val wide = drop.of(Kind.LANE_LINE).single { it.pattern == Pattern.DOTTED }
        assertEquals(Width.WIDE, wide.width)
        assertEquals(Pair(2, 3), wide.between)
        // From where the gore's own list holds: its reach, 1,000 m back, short of the point before, where the lane went on through.
        assertEquals(gore - 1_000.0, wide.from, 1e-6)
        val before = drop.of(Kind.LANE_LINE).single { it.between == Pair(2, 3) && it.pattern == Pattern.BROKEN }
        assertTrue(before.to <= wide.from + 1e-6)
    }

    @Test
    fun `a car-pool lane has one wide solid line and a diamond every 402 m, never in a zone`() {
        val d = draw(LaneScenes.carpoolEnds())
        val wide = d.of(Kind.LANE_LINE).filter { it.width == Width.WIDE }
        assertEquals(1, wide.size, "one line, never a double one")
        assertEquals(Pattern.SOLID, wide.single().pattern)
        assertEquals(Pair(0, 1), wide.single().between)
        val hov = d.plan.topology.lanes[0]
        assertTrue(DesignatedKind.HOV in hov.kinds)
        // The car-pool lane is open to every car again from where the kinds change: the wide line ends there.
        assertEquals(hov.closes, wide.single().to, UM)
        val diamonds = d.of(Kind.DIAMOND)
        assertEquals(4, diamonds.size)
        for (diamond in diamonds) {
            assertEquals(0, hov.id.compareTo(requireNotNull(diamond.lane)))
            val k = diamond.from / LaneDrawing.DIAMOND_EVERY_METERS
            assertEquals(Math.round(k).toDouble(), k, 1e-9, "every 402 m from the route's start")
            val ring = diamond.parts.single()
            assertEquals(5, ring.size)
            assertEquals(ring.first(), ring.last())
            assertEquals(LaneDrawing.DIAMOND_LENGTH_METERS, meters(ring[0], ring[2]), 1e-6)
            assertEquals(LaneDrawing.DIAMOND_WIDTH_METERS, meters(ring[1], ring[3]), 1e-6)
            // In the middle of the car-pool lane, the leftmost of four lanes about the road's line.
            assertEquals(1.5 * w, offset(ring[0]), MM)
            assertTrue(diamond.to <= hov.closes)
        }
        // No diamond on an approach where no lane is named: the car-pool lane's approach to a left exit beyond it is a zone.
        val beyond = draw(LaneScenes.carpoolBeyond())
        val zone = beyond.evidence.zones.single { it.cause == LaneEvidence.Zone.Cause.NO_LANE }
        assertTrue(beyond.of(Kind.DIAMOND).none { it.to > zone.from && it.from < zone.to })
        assertTrue(beyond.of(Kind.LANE_LINE).none { it.to > zone.from + 1e-6 && it.from < zone.to - 1e-6 })
        // Edge lines are kept there.
        assertTrue(beyond.of(Kind.EDGE_LINE).any { it.side == LaneEdge.LEFT && it.from < zone.from + 1.0 && it.to > zone.to - 1.0 })
    }

    @Test
    fun `a lane kept for buses is drawn darker with a solid line beside it and no symbol`() {
        val d = draw(LaneScenes.rightLaneForBuses())
        val kept = d.of(Kind.KEPT_LANE).single()
        val bus = d.plan.topology.lanes[requireNotNull(kept.lane)]
        assertEquals(setOf(DesignatedKind.BUS), bus.kinds)
        val beside = d.of(Kind.LANE_LINE).filter { it.between?.second == bus.id || it.between?.first == bus.id }
        assertTrue(beside.isNotEmpty())
        for (line in beside) {
            assertEquals(Pattern.SOLID, line.pattern)
            assertEquals(Width.NORMAL, line.width)
        }
        assertTrue(d.of(Kind.ARROW).none { it.lane == bus.id })
        assertTrue(d.of(Kind.DIAMOND).isEmpty())
    }

    @Test
    fun `a lane that may already be there has asphalt and the edge line beyond it but no line beside its neighbour`() {
        val d = draw(LaneScenes.iw31())
        val a5 = d.plan.topology.lanes[4]
        val possibleFrom = requireNotNull(a5.possibleFrom)
        assertEquals(gore - 1_850.0, possibleFrom, 1e-9)
        val known = a5.opens
        // Past the gap's zone the right edge line lies beyond the possible lane, at its full width.
        val edge = d.of(Kind.EDGE_LINE).first { it.side == LaneEdge.RIGHT && covers(it, gore - 1_000.0) }
        val at = edge.parts.single().minByOrNull { abs(station(it) - (gore - 1_000.0)) }!!
        assertEquals(-3.0 * w, offset(at), MM)
        assertEquals(CrossSection.State.POSSIBLE, d.drawing.lanesAt(gore - 1_000.0).last().state)
        // No line between the option lane and the possible lane, until it is known.
        val between = d.of(Kind.LANE_LINE).filter { it.between == Pair(3, 4) && it.part == Part.ROUTE }
        assertTrue(between.all { it.from >= known - 1e-6 }, "${between.map { it.from }}")
        // No arrow on it while it is possible.
        assertTrue(d.of(Kind.ARROW).none { it.lane == a5.id && it.from < known })
        assertTrue(d.of(Kind.ARROW).any { it.lane == a5.id && it.from > known })
    }

    @Test
    fun `in a zone where the lanes are not known nothing but asphalt is drawn`() {
        for (route in listOf(LaneScenes.iw31(), LaneScenes.ia())) {
            val d = draw(route)
            val zones = d.evidence.zones.filter { it.cause != LaneEvidence.Zone.Cause.NO_LANE }
            assertTrue(zones.isNotEmpty())
            for (zone in zones) {
                for (f in d.features.filter { !it.kind.polygon || it.kind == Kind.ARROW }) {
                    assertFalse(f.to > zone.from + 1e-6 && f.from < zone.to - 1e-6, "${route.routeId}: ${f.kind} ${f.from}..${f.to} in the zone ${zone.from}..${zone.to}")
                }
                // The asphalt goes on through it.
                assertNotNull(d.drawing.asphaltAt((zone.from + zone.to) / 2.0))
            }
        }
    }

    @Test
    fun `an edge line is broken 15 m on each side of an intersection where a road meets its side`() {
        val d = draw(LaneScenes.ia())
        val ia0 = gore - 2_180.0
        val gap = LaneDrawing.JUNCTION_GAP_METERS
        val left = d.of(Kind.EDGE_LINE).filter { it.side == LaneEdge.LEFT }
        assertTrue(left.any { abs(it.to - (ia0 - gap)) < 1e-3 }, "${left.map { it.from to it.to }}")
        assertTrue(left.any { abs(it.from - (ia0 + gap)) < 1e-3 }, "${left.map { it.from to it.to }}")
        assertTrue(left.none { it.to > ia0 - gap + 1e-3 && it.from < ia0 + gap - 1e-3 })
        // No road meets the right side there: the right edge goes on through.
        assertTrue(d.of(Kind.EDGE_LINE).any { it.side == LaneEdge.RIGHT && it.from < ia0 - gap && it.to > ia0 + gap })
    }

    @Test
    fun `an edge line breaks where the edge steps instead of running across the lane`() {
        // S8: the careful reading ends the right lane at the point 500 m before the gore, where no road meets: a step.
        val d = draw(LaneScenes.s8())
        val step = gore - 500.0 + LaneTopology.LEAVE_PAST_POINT_METERS
        val right = d.of(Kind.EDGE_LINE).filter { it.side == LaneEdge.RIGHT }
        val before = right.single { abs(it.to - step) < 1e-3 }
        val after = right.single { abs(it.from - step) < 1e-3 }
        // Four lanes about the road's line, three past the point: the right edge steps in by a lane, the others stay.
        assertEquals(-2.0 * w, offset(before.parts.single().last()), MM)
        assertEquals(-1.0 * w, offset(after.parts.single().first()), MM)
        // Every edge and lane line moves less than the step's threshold between its points, or more along than across.
        for (f in d.features.filter { it.kind == Kind.EDGE_LINE || it.kind == Kind.LANE_LINE }) {
            for (part in f.parts) {
                for (i in 1 until part.size) {
                    val across = abs(offset(part[i]) - offset(part[i - 1]))
                    val along = abs(station(part[i]) - station(part[i - 1]))
                    if (station(part[i]) > gore - 50.0) continue
                    assertTrue(across <= LaneDrawing.STEP_METERS || across <= along, "${f.kind} ${f.between} at ${station(part[i])}: $across across over $along")
                }
            }
        }
    }

    @Test
    fun `lane arrows show each point's arrows in a near and a far row, at a maneuver in the far row only`() {
        val d = draw(LaneScenes.iw31())
        val arrows = d.of(Kind.ARROW)
        fun row(at: Double) = arrows.filter { abs(it.from - at) < 1e-6 }.sortedBy { it.lane }
        val p2 = gore - 620.0
        val near = row(p2 - LaneDrawing.ARROW_NEAR_METERS)
        assertEquals(listOf(0, 1, 2, 3, 4), near.map { it.lane })
        assertEquals(listOf(TurnModifier.STRAIGHT), near[0].indications)
        assertEquals(listOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT).sortedBy { it.ordinal }, near[3].indications)
        assertEquals(listOf(TurnModifier.SLIGHT_RIGHT), near[4].indications)
        assertEquals(2, near[3].parts.size, "a straight arrow and a bent one")
        // The far row: the exit-only lane is only possible there, so it has none.
        assertEquals(listOf(0, 1, 2, 3), row(p2 - LaneDrawing.ARROW_FAR_METERS).map { it.lane })
        // At the gore only the far row, at 170 m: the maneuver arrow begins 12 m before the gore.
        assertEquals(listOf(0, 1, 2, 3, 4), row(gore - LaneDrawing.ARROW_FAR_MIN_METERS).map { it.lane })
        assertTrue(row(gore - LaneDrawing.ARROW_NEAR_METERS).isEmpty())
        assertTrue(row(gore - LaneDrawing.ARROW_FAR_METERS).isEmpty())
        // Every arrow lies in its lane, at full width, inside the point's own stretch.
        for (arrow in arrows) {
            val lanes = d.drawing.lanesAt(arrow.from)
            val lane = lanes.single { it.lane == arrow.lane }
            assertEquals(CrossSection.State.FULL, lane.state)
            assertFalse(d.plan.topology.lanes[lane.lane].designated)
        }
        // A straight arrow: 7.16 m from tail to tip, its head 1.05 m wide, its stem 0.2 m.
        val straight = near[0].parts.single()
        val tail = straight.minOf { station(it) }
        val tip = straight.maxOf { station(it) }
        assertEquals(LaneDrawing.ARROW_LENGTH_METERS, tip - tail, MM)
        assertEquals(p2 - LaneDrawing.ARROW_NEAR_METERS, tail, MM)
        val across = straight.map { offset(it) }
        assertEquals(LaneDrawing.ARROW_HEAD_WIDTH_METERS, across.max() - across.min(), MM)
        val stem = straight.filter { station(it) < tail + 1.0 }.map { offset(it) }
        assertEquals(LaneDrawing.ARROW_STEM_METERS, stem.max() - stem.min(), MM)
    }

    @Test
    fun `the far row moves within 170 to 230 m to lie in its point's own stretch, and is left out where none does`() {
        // Two lane points of four lanes; a right exit. The second point's own stretch reaches back to the first.
        fun far(apart: Double): List<Double> {
            val d = draw(twoPoints(apart))
            val second = gore - 1_000.0
            // The second point's rows, not the first point's near row, which lies [apart] + [LaneDrawing.ARROW_NEAR_METERS] before the second.
            return d.of(Kind.ARROW).map { second - it.from }.filter { it > LaneDrawing.ARROW_NEAR_METERS + 1.0 && it <= apart + 1e-6 }
                .map { Math.round(it * 1e6) / 1e6 }.distinct()
        }
        assertEquals(listOf(200.0), far(250.0))
        assertEquals(listOf(190.0), far(198.0))
        assertEquals(listOf(170.0), far(178.0))
        assertEquals(listOf(170.0), far(170.0))
        assertEquals(emptyList<Double>(), far(169.9))
    }

    @Test
    fun `at a gore the lanes left go on for the stub's length with their lines, two wide lines run from the split to the nose`() {
        val d = draw(LaneScenes.iw31())
        val stub = d.features.filter { it.part == Part.STUB && it.from < gore + 1.0 }
        // The three lanes the route does not take go on straight, with their lane lines and the far edge, for the stub's length.
        val lines = stub.filter { it.kind == Kind.LANE_LINE }
        assertEquals(listOf(Pair(0, 1), Pair(1, 2)), lines.map { it.between })
        for (line in lines) {
            assertEquals(gore, line.from, 1e-6)
            assertEquals(gore + LaneDrawing.STUB_METERS, line.to, 1e-6)
            // Nothing of a stub's lines past its length.
            for (dash in line.parts) for (p in dash) assertTrue(stubAlong(p) <= LaneDrawing.STUB_METERS + MM, "${stubAlong(p)}")
        }
        val far = stub.single { it.kind == Kind.EDGE_LINE && it.side == LaneEdge.LEFT }
        assertEquals(LaneDrawing.Paint.YELLOW, far.paint)
        assertEquals(gore + LaneDrawing.STUB_METERS, far.to, 1e-6)
        // Its asphalt tapers over 40 m into the map's band past them.
        val asphalt = stub.single { it.kind == Kind.ASPHALT }
        assertEquals(gore + LaneDrawing.STUB_METERS + RoutePath.ASPHALT_TAPER_METERS, asphalt.to, 1e-6)
        // The channelizing lines: wide and white from the split, on the stub and on the ramp, to where they lie 2.4 m apart.
        val noses = d.drawing.noses
        assertEquals(2, noses.size)
        val (stubNose, routeNose) = noses.first()
        val channel = d.of(Kind.CHANNEL_LINE, Part.STUB).single { it.from < gore + 1.0 }
        assertEquals(gore, channel.from, 1e-6)
        assertEquals(stubNose, channel.to, UM)
        assertEquals(Width.WIDE, channel.width)
        val ramp = d.of(Kind.CHANNEL_LINE, Part.ROUTE).single()
        assertEquals(gore, ramp.from, 1e-6)
        assertTrue(abs(ramp.to - routeNose) < 1.0, "${ramp.to} $routeNose")
        assertTrue(stubNose > gore + 12.0 && stubNose < gore + LaneDrawing.STUB_METERS)
        // Up to the nose the ramp's side lies closer to the stub's than the nose's gap, past it farther.
        val stubLine = channel.parts.single()
        fun gapAt(p: GeoPoint) = ramp.parts.single().minOf { SceneLines.local(it).distanceTo(SceneLines.local(p)) }
        assertTrue(gapAt(stubLine.first()) < LaneDrawing.NOSE_GAP_METERS)
        val beyondNose = d.of(Kind.EDGE_LINE, Part.STUB).single { it.side == LaneEdge.RIGHT && it.from < gore + LaneDrawing.STUB_METERS }
        assertEquals(stubNose, beyondNose.from, UM)
        assertEquals(2.0 * CrossSection.APRON_METERS, LaneDrawing.NOSE_GAP_METERS, 1e-12)
        // The ramp's side past its nose: its yellow left edge line.
        assertTrue(d.of(Kind.EDGE_LINE).any { it.side == LaneEdge.LEFT && it.paint == LaneDrawing.Paint.YELLOW && abs(it.from - ramp.to) < 1.5 })
    }

    @Test
    fun `past a turn without lane data the lanes the route takes narrow to one lane by where the path is on the new road`() {
        val d = draw(LaneScenes.ia())
        val beyond = d.of(Kind.ASPHALT, Part.BEYOND).single()
        val drawing = d.path.drawings.single()
        assertTrue(drawing.turnsAtEnd)
        val ring = beyond.parts.single()
        // It begins where the turn's arc begins, as wide as the two lanes the route can be followed from with their aprons...
        assertEquals(2.0 * w + 2.0 * apron, meters(ring.first(), ring[ring.size - 2]), MM)
        // ...and ends with the ramp's band past the path's settling.
        assertEquals(drawing.back + RoutePath.ASPHALT_TAPER_METERS, beyond.to, 1e-6)
        // The end's cut: the ring's edge farthest on, its middle the most northerly of the edges' middles.
        val end = (0 until ring.size - 1).maxByOrNull { station(ring[it]) + station(ring[it + 1]) }!!
        assertEquals(LaneDrawing.STANDARD_RAMP_BAND_METERS, meters(ring[end], ring[end + 1]), MM)
        val right = ring.indexOfFirst { abs(it.latitude - ring.maxOf { p -> p.latitude }) < 1e-12 }
        assertTrue(right > 0)
        val edges = d.of(Kind.EDGE_LINE, Part.BEYOND)
        assertEquals(drawing.back, edges.maxOf { it.to }, UM)
        // One lane at the end of its lines: the two edge lines' last points 3.66 m apart.
        val l = edges.single { it.side == LaneEdge.LEFT }.parts.single().last()
        val r = edges.single { it.side == LaneEdge.RIGHT }.parts.single().last()
        assertEquals(w, meters(l, r), 1e-3)
        assertTrue(d.features.none { it.part == Part.BEYOND && it.kind == Kind.LANE_LINE }, "no count past the turn: no lane line")
    }

    @Test
    fun `a line breaks where it would move more than a metre sideways, and more across than along`() {
        assertFalse(LaneDrawing.steps(LaneDrawing.STEP_METERS, 0.0))
        assertTrue(LaneDrawing.steps(LaneDrawing.STEP_METERS + 0.01, 0.5))
        assertTrue(LaneDrawing.steps(-LaneDrawing.STEP_METERS - 0.01, 1e-6))
        // Along a taper or at a ramp's start a line moves sideways less than along.
        assertFalse(LaneDrawing.steps(1.5, 1.5))
        assertTrue(LaneDrawing.steps(1.5, 1.49))
    }

    @Test
    fun `a line between two lanes begins where an opening lane is half as wide as a lane`() {
        // S9's deceleration lane the plan names opens late: 60 m of taper ending 150 m before the gore.
        val d = draw(LaneScenes.s9())
        val line = d.of(Kind.LANE_LINE).single { it.pattern == Pattern.DOTTED && it.from > gore - 250.0 }
        val lane = requireNotNull(line.between).second
        fun widthAt(station: Double) = d.drawing.lanesAt(station).single { it.lane == lane }.width
        assertTrue(widthAt(line.from) >= LaneDrawing.LINE_LANE_SHARE * w - 1e-9)
        assertTrue(widthAt(line.from - 1.0) < LaneDrawing.LINE_LANE_SHARE * w)
        assertEquals(gore - 180.0, line.from, 1.0)
        assertEquals(0.5, LaneDrawing.LINE_LANE_SHARE)
    }

    @Test
    fun `the far row is tried at 200 m first, within 170 to 230 m next, at a maneuver at 170 m only`() {
        assertEquals(listOf(200.0, 190.0, 210.0, 180.0, 220.0, 170.0, 230.0), LaneDrawing.farRows(maneuver = false))
        assertEquals(listOf(170.0), LaneDrawing.farRows(maneuver = true))
        assertTrue(LaneDrawing.farRows(false).all { it >= LaneDrawing.ARROW_FAR_MIN_METERS && it <= LaneDrawing.ARROW_FAR_MAX_METERS })
    }

    @Test
    fun `the gore's two lines end where they lie 2_4 m apart, the aprons parting`() {
        val d = draw(LaneScenes.iw31())
        val stub = d.of(Kind.CHANNEL_LINE, Part.STUB).first { it.from < gore + 1.0 }
        val ramp = d.of(Kind.CHANNEL_LINE, Part.ROUTE).single()
        val rampSide = d.drawing.lanesAt(gore + 1.0).first()
        assertEquals(3, rampSide.lane)
        // The stub's side, the third lane's right edge, runs due north a lane east of the road's line; the ramp's side bends
        // away east.
        val stubSide = -1.0 * w
        assertEquals(stubSide, offset(stub.parts.single().first()), MM)
        val nose = ramp.parts.single().last()
        assertTrue(stubSide - offset(nose) >= LaneDrawing.NOSE_GAP_METERS - 0.05, "${stubSide - offset(nose)} at the nose")
        val before = ramp.parts.single().dropLast(1).last()
        assertTrue(stubSide - offset(before) < LaneDrawing.NOSE_GAP_METERS, "${stubSide - offset(before)} before it")
    }

    @Test
    fun `widths are zoom-only exponential stops of their ground width, never under 1 dp`() {
        assertEquals(LaneDrawing.LINE_METERS, Width.NORMAL.meters)
        assertEquals(0.15, LaneDrawing.LINE_METERS)
        assertEquals(LaneDrawing.WIDE_LINE_METERS, Width.WIDE.meters)
        assertEquals(0.30, LaneDrawing.WIDE_LINE_METERS)
        // At latitude 0 a 0.15 m line is 1 dp at zoom log2(78,271.5 / 0.15), and its true width above it: doubling with each zoom.
        val stops = LaneDrawing.widthStops(LaneDrawing.LINE_METERS, 0.0)
        val floorZoom = log2(40_075_016.686 / 512.0 / LaneDrawing.LINE_METERS)
        assertEquals(floorZoom, stops[0].first, 1e-9)
        assertEquals(1.0, stops[0].second, 1e-9)
        assertEquals(LaneDrawing.MAX_ZOOM, stops[1].first)
        assertEquals(stops[0].second * Math.pow(2.0, LaneDrawing.MAX_ZOOM - floorZoom), stops[1].second, 1e-6)
        // A wider line reaches 1 dp a zoom earlier; at 35 degrees everything comes 0.29 zooms earlier.
        assertEquals(floorZoom - 1.0, LaneDrawing.widthStops(LaneDrawing.WIDE_LINE_METERS, 0.0)[0].first, 1e-9)
        assertEquals(floorZoom + log2(cos(Math.toRadians(35.0))), LaneDrawing.widthStops(LaneDrawing.LINE_METERS, 35.0)[0].first, 1e-9)
        // A width that is wider than 1 dp at zoom 0 starts at zoom 0.
        assertEquals(0.0, LaneDrawing.widthStops(1e6, 0.0)[0].first)
        // The minimum zooms, at latitude 0 and lower farther from it.
        assertEquals(15.5, LaneDrawing.ASPHALT_MIN_ZOOM)
        assertEquals(16.5, LaneDrawing.SYMBOL_MIN_ZOOM)
        assertEquals(15.5, LaneDrawing.zoomAt(LaneDrawing.ASPHALT_MIN_ZOOM, 0.0), 1e-12)
        assertEquals(15.5 - 0.2877, LaneDrawing.zoomAt(LaneDrawing.ASPHALT_MIN_ZOOM, 35.0), 1e-4)
        // The stops are made again when the latitude has moved half a degree.
        assertFalse(LaneDrawing.stopsStale(35.0, 35.4999))
        assertTrue(LaneDrawing.stopsStale(35.0, 35.5))
        assertTrue(LaneDrawing.stopsStale(35.0, 34.5))
        assertFalse(LaneDrawing.stopsStale(35.0, 34.5001))
    }

    @Test
    fun `a window's features lie inside it, cut at its ends, and symbols are in where they begin`() {
        val d = draw(LaneScenes.iw31())
        val a = gore - 2_000.0
        val b = gore - 1_000.0
        val window = d.drawing.features(a, b)
        assertTrue(window.isNotEmpty())
        for (f in window) {
            if (f.kind == Kind.ARROW || f.kind == Kind.DIAMOND) {
                assertTrue(f.from >= a && f.from < b)
                continue
            }
            assertTrue(f.from >= a - 1e-6 && f.to <= b + 1e-6, "${f.kind} ${f.from}..${f.to}")
            for (part in f.parts) for (p in part) assertTrue(station(p) >= a - 1e-6 && station(p) <= b + 1e-6, "${f.kind} at ${station(p)}")
        }
        // The same window cut again, and a lane line's dashes at the same places as in the whole drawing.
        assertEquals(window, d.drawing.features(a, b))
        val whole = d.of(Kind.LANE_LINE).first { it.between == Pair(0, 1) && covers(it, a + 120.0) }.parts.map { station(it[0]) }.toSet()
        val cut = window.first { it.kind == Kind.LANE_LINE && it.between == Pair(0, 1) }.parts.map { station(it[0]) }.filter { it > a + 1e-6 }
        for (s in cut) assertTrue(whole.any { abs(it - s) < 1e-6 }, "a dash at $s")
    }

    @Test
    fun `the same route gives the same features`() {
        val one = draw(LaneScenes.iw31()).features
        val two = draw(LaneScenes.iw31()).features
        assertEquals(one, two)
        assertEquals(one.toString(), two.toString())
    }

    /** How far [p] lies along IW31's stub at the gore: its northing past the gore, the old road going on due north. */
    private fun stubAlong(p: GeoPoint) = station(p) - gore

    private companion object {
        /** Points go through each drawing window's own frame and back: a few micrometres. */
        const val MM = 1e-4

        /** A run ends a micrometre before the station where it changes. */
        const val UM = 1e-5
    }

    /** Four lanes, the left one leaving by a road on the left at a point 5,000 m before a right exit; five 2,000 m before it. */
    private fun endsOnItsRoad() = LaneScenes.approach(
        listOf(At(5_000.0, listOf(s(false), s(), s(), s()), EXIT_LEFT), At(2_000.0, listOf(s(), s(), s(), s(), s()))),
        exit = listOf(s(false), s(false), s(false), s(false), sr()),
        id = "ends-on-its-road",
    )

    /** Four lanes that all go on through at a point 1,200 m before a right exit whose list makes the right one exit-only. */
    private fun dropAtExit() = LaneScenes.approach(
        listOf(At(1_200.0, listOf(s(), s(), s(), s()))),
        exit = listOf(s(false), s(false), s(false), sr()),
        id = "drop-at-exit",
    )

    /** Four lanes at two points [apart] metres apart, the second 1,000 m before a right exit that takes the right lane. */
    private fun twoPoints(apart: Double): LaneRoute {
        val lanes: List<RouteLane> = listOf(s(), s(), s(), s())
        return LaneScenes.approach(
            listOf(At(1_000.0 + apart, lanes), At(1_000.0, lanes)),
            exit = listOf(s(false), s(false), s(false), sr()),
            id = "two-points",
        )
    }
}
