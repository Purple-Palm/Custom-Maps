package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.at
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.bus
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.hov
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.option
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sl
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.StreetWays
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/** The made-up routes of [LaneScenes] laid out across the road; stations are metres from each route's start. */
class CrossSectionTest {

    private val w = LaneTopology.FREEWAY_LANE_METERS
    private val gore = LaneScenes.GORE

    /** The cross-section of [route], every street of it one-way or two-way as [carriageway] says. */
    private fun section(route: LaneRoute, carriageway: CrossSection.Carriageway = CrossSection.Carriageway.ONE_WAY) =
        CrossSection.of(SceneLines.planOf(route), streets = StreetWays.of(route) { carriageway == CrossSection.Carriageway.ONE_WAY })

    private fun bounds(places: List<CrossSection.Place>) = listOf(places.first().left) + places.map { it.right }

    private fun assertBounds(expected: List<Double>, places: List<CrossSection.Place>) {
        val got = bounds(places)
        assertEquals(expected.size, got.size, "$got")
        for (i in expected.indices) assertEquals(expected[i], got[i], 1e-9, "boundary $i of $got")
    }

    @Test
    fun `lanes are a freeway lane wide, a street lane or a bicycle lane narrower`() {
        assertEquals(3.66, CrossSection.laneMeters(RoadKind.FREEWAY))
        assertEquals(3.66, CrossSection.laneMeters(RoadKind.RAMP))
        assertEquals(3.35, CrossSection.laneMeters(RoadKind.STREET))
        assertEquals(1.8, CrossSection.laneMeters(RoadKind.STREET, setOf(DesignatedKind.BICYCLE)))
        // A lane for bicycles and buses is a vehicle lane.
        assertEquals(3.35, CrossSection.laneMeters(RoadKind.STREET, setOf(DesignatedKind.BICYCLE, DesignatedKind.BUS)))
        assertEquals(3.66, CrossSection.laneMeters(RoadKind.FREEWAY, setOf(DesignatedKind.HOV)))
    }

    @Test
    fun `a one-way carriageway is centred on the road's line where its lanes begin, with an apron outside each edge`() {
        val ia = section(LaneScenes.ia())
        assertBounds(listOf(2.5 * w, 1.5 * w, 0.5 * w, -0.5 * w, -1.5 * w, -2.5 * w), ia.lanes(1_000.0))
        assertEquals(CrossSection.Edges(2.5 * w, -2.5 * w, 2.5 * w + 1.2, -2.5 * w - 1.2), ia.edges(1_000.0))
        assertEquals(0.0, ia.place(2, 1_000.0)!!.centre, 1e-12)
        // The middle of some of its lanes.
        assertEquals(0.0, ia.middle(listOf(1, 2, 3), 1_000.0)!!, 1e-12)
        assertEquals(-1.5 * w, ia.middle(listOf(3, 4), 1_000.0)!!, 1e-12)
        assertEquals(RoadKind.FREEWAY, ia.roadAt(1_000.0))
        // Before and after the route's lanes: nothing.
        assertTrue(ia.lanes(gore + 10.0).isEmpty())
        assertNull(ia.edges(gore + 10.0))
        assertNull(ia.runAt(gore + 10.0))
    }

    @Test
    fun `a lane that leaves by a road on its side ends there and the other lanes stay`() {
        val ia = section(LaneScenes.ia())
        // IA0's left lane leaves by the road on the left, 5 m past the point.
        val ia0 = gore - 2_180.0
        assertEquals(listOf(0, 1, 2, 3, 4), ia.lanes(ia0 + 4.9).map { it.lane })
        assertEquals(listOf(1, 2, 3, 4), ia.lanes(ia0 + 5.0).map { it.lane })
        assertBounds(listOf(1.5 * w, 0.5 * w, -0.5 * w, -1.5 * w, -2.5 * w), ia.lanes(ia0 + 5.0))
    }

    @Test
    fun `a lane that leaves by a road at the edge with possible lanes beyond it ends at that edge and the others stay`() {
        val route = LaneScenes.leavesBesidePossible()
        val section = section(route)
        val lanes = section.topology.lanes
        // The right lane of three leaves by the road on the right 5 m past the point; the exit's two added lanes may be
        // there from the point on, beyond it, and the plan names neither: both are only possible where it leaves.
        val point = gore - 1_200.0
        val leaves = point + LaneTopology.LEAVE_PAST_POINT_METERS
        assertEquals(leaves, lanes[2].closes, 1e-9)
        for (added in listOf(3, 4)) {
            assertEquals(point, lanes[added].possibleFrom!!, 1e-9)
            assertTrue(lanes[added].opens > leaves)
            assertEquals(CrossSection.State.POSSIBLE, section.place(added, leaves - 1.0)!!.state)
        }
        assertEquals(-w, section.place(2, leaves - 1e-6)!!.centre, 1e-9)
        assertNull(section.place(2, leaves))
        // The lanes that go on keep their place and the left edge stays: no lane steps sideways where it leaves.
        for (station in listOf(leaves - 1.0, leaves - 1e-6, leaves, leaves + 1.0, gore - 300.0)) {
            assertEquals(w, section.place(0, station)!!.centre, 1e-9, "lane 0 at $station")
            assertEquals(0.0, section.place(1, station)!!.centre, 1e-9, "lane 1 at $station")
            assertEquals(1.5 * w, section.edges(station)!!.left, 1e-9, "the left edge at $station")
        }
    }

    @Test
    fun `a lane on the exit's side the plan does not name is drawn from where it may begin, 15 to 1 from 50 mph`() {
        // IW31: the exit-only lane a5 is listed first at P2; it may be there from P1 on, the plan names g4 beside it.
        val iw31 = section(LaneScenes.iw31())
        val p1 = gore - 1_850.0
        val taper = 15.0 * w
        assertEquals(null, iw31.place(4, p1 - 0.1))
        assertEquals(0.0, iw31.place(4, p1)!!.width, 1e-12)
        assertEquals(w / 2.0, iw31.place(4, p1 + taper / 2.0)!!.width, 1e-9)
        assertEquals(w, iw31.place(4, p1 + taper)!!.width, 1e-12)
        assertEquals(CrossSection.State.POSSIBLE, iw31.place(4, p1 + 100.0)!!.state)
        // Known from its late opening (its taper would end at P2), at full width already.
        val opens = gore - 620.0 - taper
        assertEquals(CrossSection.State.POSSIBLE, iw31.place(4, opens - 0.1)!!.state)
        assertEquals(CrossSection.State.FULL, iw31.place(4, opens)!!.state)
        // The lanes beside it stay where they were: g4 on the right of four centred lanes.
        for (station in listOf(p1 - 1.0, p1 + taper / 2.0, opens + 10.0)) {
            assertEquals(-1.5 * w, iw31.place(3, station)!!.centre, 1e-12)
        }
        assertEquals(-2.5 * w, iw31.place(4, opens + 10.0)!!.centre, 1e-12)
        assertEquals(-3.0 * w - 1.2, iw31.edges(opens + 10.0)!!.asphaltRight, 1e-12)
    }

    @Test
    fun `a deceleration lane the plan names opens late, at full width 150 m before the gore after a 60 m taper`() {
        // S9: the exit's lane is entered from the lane beside it along its taper; the plan names it.
        val s9 = section(LaneScenes.s9())
        assertNull(s9.place(5, gore - 210.1))
        assertEquals(0.0, s9.place(5, gore - 210.0)!!.width, 1e-12)
        assertEquals(w / 2.0, s9.place(5, gore - 180.0)!!.width, 1e-9)
        assertTrue(s9.place(5, gore - 150.1)!!.width < w)
        assertEquals(w, s9.place(5, gore - 150.0)!!.width, 1e-12)
        assertEquals(CrossSection.State.OPENING, s9.place(5, gore - 180.0)!!.state)
        assertEquals(CrossSection.State.FULL, s9.place(5, gore - 150.0)!!.state)
        // From its feeder's outer edge.
        assertEquals(s9.place(3, gore - 200.0)!!.right, s9.place(5, gore - 200.0)!!.left, 1e-12)
    }

    /** Three lanes 1,500 m before a right exit and four at 500 m, nothing between: a lane added on the far side, nothing explains it. */
    private fun farGain(mph: Double) = LaneScenes.approach(
        listOf(at(1_500.0, s(), s(), s()), at(500.0, s(), s(), s(), s())),
        exit = listOf(s(false), s(false), s(false), sr()),
        planMps = mph * PlanningSpeed.MPS_PER_MPH,
    )

    @Test
    fun `an added lane tapers 8 to 1 below 50 mph and 15 to 1 from 50`() {
        for ((mph, ratio) in listOf(49.9 to 8.0, 50.0 to 15.0)) {
            val gain = section(farGain(mph))
            val point = gore - 500.0
            val taper = ratio * w
            val added = gain.lanes(point).first().lane
            assertNull(gain.place(added, point - taper - 0.1), "at $mph mph")
            assertEquals(w / 2.0, gain.place(added, point - taper / 2.0)!!.width, 1e-9, "at $mph mph")
            assertEquals(w, gain.place(added, point)!!.width, 1e-12, "at $mph mph")
        }
    }

    @Test
    fun `a count change nothing explains widens the road on both sides by half a lane over its zone`() {
        val gain = section(farGain(50.0))
        val point = gore - 500.0
        val zone = 2.0 * 15.0 * w + 60.0
        // Three lanes centred before the zone, four centred after it: each edge half a lane out.
        assertEquals(CrossSection.Edges(1.5 * w, -1.5 * w, 1.5 * w + 1.2, -1.5 * w - 1.2), gain.edges(point - zone))
        assertEquals(2.0 * w, gain.edges(point)!!.left, 1e-9)
        assertEquals(-2.0 * w, gain.edges(point)!!.right, 1e-9)
        // Half-way through the zone the old lanes have moved a quarter lane toward the exit, the new one not yet open.
        assertEquals(1.25 * w, gain.edges(point - zone / 2.0)!!.left, 1e-9)
        assertEquals(-1.75 * w, gain.edges(point - zone / 2.0)!!.right, 1e-9)
        // GEOM's S6: three lanes, then two; the right one ends early, and over the zone the two lanes are centred again.
        val s6 = section(LaneScenes.geomS6())
        assertEquals(CrossSection.Edges(1.5 * w, -1.5 * w, 1.5 * w + 1.2, -1.5 * w - 1.2), s6.edges(gore - 2_000.0))
        assertEquals(w, s6.edges(gore - 500.0)!!.left, 1e-9)
        assertEquals(-w, s6.edges(gore - 500.0)!!.right, 1e-9)
    }

    @Test
    fun `where a gain nothing explains lies on the exit's side the lanes stay and the road widens on that side`() {
        val route = LaneScenes.exitSideGain()
        val plan = SceneLines.planOf(route)
        val section = CrossSection.of(plan)
        // The scene: three lanes go on past the left lane's road and five are listed 1,000 m before the gore; the topology
        // puts both added lanes on the right, the exit's side, and reads the side only carefully: a rule-5 zone.
        val alignment = plan.topology.alignments.single { it.lanesBefore == 3 && it.lanesAfter == 5 }
        assertEquals(LaneEdge.RIGHT, alignment.side)
        assertEquals(LaneTopology.Alignment.Reason.CAREFUL, alignment.by)
        val zone = plan.evidence().zones.single { it.cause == LaneEvidence.Zone.Cause.CHANGE }
        val point = gore - 1_000.0
        assertEquals(point, zone.to, 1e-9)
        // The three lanes keep their places through the zone and past it, the left edge with them; the two added lanes open
        // on the right, so the road widens on the exit's side only. A zone that widened both sides would move every lane
        // half the gain, a lane, away from the exit's edge.
        val before = section.lanes(zone.from - 1.0)
        assertEquals(listOf(1, 2, 3), before.map { it.lane })
        var station = zone.from - 1.0
        while (station <= point + 100.0) {
            val here = section.lanes(station)
            for (lane in before) assertEquals(lane.centre, here.first { it.lane == lane.lane }.centre, 1e-9, "lane ${lane.lane} at $station")
            assertEquals(before.first().left, section.edges(station)!!.left, 1e-9, "at $station")
            station += 1.0
        }
        assertEquals(before.last().right - 2.0 * w, section.edges(point)!!.right, 1e-9)
    }

    @Test
    fun `a lane that ends narrows over W S from 45 mph and W S squared over 60 below, at least 30 m`() {
        for (mph in listOf(15.0, 44.9, 45.0, 65.0)) {
            val route = LaneScenes.approach(
                listOf(at(1_500.0, s(), s(), s()), at(500.0, s(), s())),
                exit = listOf(s(false), sr()),
                planMps = mph * PlanningSpeed.MPS_PER_MPH,
            )
            val ends = section(route)
            // The right lane goes 30 m after the first point, with nothing to take it: the careful reading's early end.
            val closes = gore - 1_500.0 + LaneTopology.VANISH_EARLY_METERS
            val taper = LaneTopology.endingTaperMeters(mph * PlanningSpeed.MPS_PER_MPH, RoadKind.FREEWAY)
            val feet = 3.66 / 0.3048
            val expected = maxOf(30.0, if (mph >= 45.0) feet * mph * 0.3048 else feet * mph * mph / 60.0 * 0.3048)
            assertEquals(expected, taper, 1e-9, "at $mph mph")
            assertEquals(w, ends.place(2, closes - 0.1)!!.width, 1e-12)
            assertEquals(CrossSection.State.CLOSING, ends.place(2, closes)!!.state)
            assertEquals(w / 2.0, ends.place(2, closes + taper / 2.0)!!.width, 1e-9, "at $mph mph")
            assertNull(ends.place(2, closes + taper), "at $mph mph")
            // It narrows to its inner edge: the lanes beside it stay, the middle one on the road's line.
            assertEquals(0.0, ends.place(1, closes + taper / 2.0)!!.centre, 1e-9)
        }
    }

    @Test
    fun `at a ramp's start the lanes go on straight and settle onto the ramp as a path does, never past their places`() {
        val route = LaneScenes.iw31()
        val section = section(route)
        val start = section.rampStarts.single()
        assertEquals(gore, start.station)
        // The two lanes onto the ramp lay 2 lanes to the right of the ramp's middle.
        assertEquals(-2.0 * w, start.shift, 1e-9)
        val line = SceneLines.of(route)
        val stations = DoubleArray(line.size)
        for (i in 1 until line.size) stations[i] = stations[i - 1] + io.github.purplepalm.custommaps.nav.geo.Geo.meters(line[i - 1], line[i])
        val axis = RoadAxis.of(line, stations, line.first())
        val fillet = axis.fillets.first { Math.abs(it.station - gore) < 1e-6 }
        val crossing = CrossSection.crossing(axis, fillet.from, gore, start.shift)
        assertTrue(crossing > gore)
        // There the ramp's line lies on the old carriageway's middle (the two lanes' line, 2 lanes east).
        assertEquals(2.0 * w, axis.at(crossing).point.x, 0.05)
        // Until halfway to that crossing, g4's middle goes straight on, 1.5 lanes east of the freeway's line, which runs due
        // north, and the two lanes stay two lanes wide on the ground: across the turning axis' normal, wider by its turn.
        val from = (fillet.from + crossing) * 0.5
        for (station in listOf(fillet.from, gore - 1.0, gore, from - 0.5)) {
            val centre = section.place(3, station, axis)!!.centre
            assertEquals(1.5 * w, axis.offset(station, centre).x, 1e-6, "at $station")
        }
        var station = fillet.from
        while (station < from) {
            val left = section.place(3, station, axis)!!.left
            val right = section.place(4, station, axis)!!.right
            assertEquals(2.0 * w, (left - right) * axis.at(station).ty, 1e-6, "two lanes wide at $station")
            station += 0.5
        }
        // From there each lane settles onto its place on the ramp's own carriageway, centred on its line, as the path does
        // past a turn: it closes in from its own side, never past its place, and is on it from where the middle settles.
        val end = start.settled(axis)
        assertEquals(CrossSection.turnOffEnd(axis, gore, start.shift), end, 0.0)
        assertTrue(end > crossing, "settled at $end, the crossing at $crossing")
        for ((lane, place) in listOf(3 to 0.5 * w, 4 to -0.5 * w)) {
            var prev = section.place(lane, from, axis)!!.centre - place
            assertTrue(prev < 0.0, "lane $lane right of its place where it begins to settle")
            station = from
            while (station < end + 50.0) {
                station += 0.25
                val now = section.place(lane, station, axis)!!.centre - place
                assertTrue(now <= 0.0 && now >= prev - 1e-12, "lane $lane at $station: from $prev to $now")
                prev = now
            }
            assertEquals(place, section.place(lane, end, axis)!!.centre, 1e-12)
            assertEquals(place, section.place(lane, end + 60.0, axis)!!.centre, 1e-12)
        }
        assertTrue(Math.abs(section.place(3, end - 1.0, axis)!!.centre - 0.5 * w) > 1e-9)
        // The middle settles by the path's own rule.
        for (s in listOf(from, (from + end) * 0.5, end - 1.0)) {
            val middle = (section.place(3, s, axis)!!.left + section.place(4, s, axis)!!.right) / 2.0
            assertEquals(CrossSection.turnOff(axis, gore, start.shift, s), middle, 0.05, "at $s")
        }
        // Without the axis the ramp's lanes are where they lie on the ramp from the gore on.
        assertEquals(0.5 * w, section.place(3, gore + 1.0)!!.centre, 1e-12)
        // The lanes that do not go onto the ramp are not drawn past the gore.
        assertEquals(listOf(3, 4), section.lanes(gore).map { it.lane })
        assertEquals(RoadKind.RAMP, section.roadAt(gore + 1.0))
        // Before the arc the freeway's lanes are where they were.
        assertEquals(-1.5 * w, section.place(3, fillet.from - 1.0, axis)!!.centre, 1e-12)
    }

    @Test
    fun `where the ramp's line never lies farther out the lanes move onto it from the turn's arc`() {
        // A line that turns left at the gore while the lanes onto it lie to the right: no crossing.
        val axis = RoadAxis.of(SceneLines.line(gore + 400.0, listOf(gore to 17.0)), null, SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(0.0, 0.0)))
        val fillet = axis.fillets.single()
        assertEquals(gore, CrossSection.crossing(axis, fillet.from, gore, -2.0 * w), 1e-9)
        val start = CrossSection.RampStart(0, gore, -2.0 * w)
        assertEquals(fillet.from + CrossSection.RAMP_BLEND_METERS, start.settled(axis), 1e-9)
        assertEquals(0.0, start.at(axis, fillet.from + CrossSection.RAMP_BLEND_METERS), 1e-12)
        assertEquals(-w, start.at(axis, fillet.from + CrossSection.RAMP_BLEND_METERS * 0.5), 1e-12)
        assertEquals(-2.0 * w, start.at(axis, fillet.from - 1.0), 1e-12)
    }

    @Test
    fun `past a turn with no lanes drawn on, the path settles onto the new road's line, never past it, with no kink`() {
        // IW31's line turns right at the gore; a path a lane and a half right of it goes on straight along the freeway's way.
        val line = SceneLines.of(LaneScenes.iw31())
        val stations = DoubleArray(line.size)
        for (i in 1 until line.size) stations[i] = stations[i - 1] + io.github.purplepalm.custommaps.nav.geo.Geo.meters(line[i - 1], line[i])
        val axis = RoadAxis.of(line, stations, line.first())
        val fillet = axis.fillets.first { Math.abs(it.station - gore) < 1e-6 }
        val shift = -1.5 * w
        val crossing = CrossSection.crossing(axis, fillet.from, gore, shift)
        val from = (fillet.from + crossing) * 0.5
        val end = CrossSection.turnOffEnd(axis, gore, shift)
        assertTrue(end > crossing && end <= gore + CrossSection.RAMP_START_MAX_METERS, "settled at $end, the crossing at $crossing")
        // Until halfway to where that straight-on line would cross the ramp's line, the straight-on line itself.
        assertEquals(shift, CrossSection.turnOff(axis, gore, shift, fillet.from - 1.0), 1e-12)
        for (station in listOf(fillet.from, (fillet.from + from) * 0.5, from)) {
            assertEquals(CrossSection.lineOffset(axis, fillet.from, shift, station)!!, CrossSection.turnOff(axis, gore, shift, station), 1e-9, "at $station")
        }
        // From there it closes in on the ramp's line from its own side and never past it, on it for good from where it settles.
        var prev = CrossSection.turnOff(axis, gore, shift, from)
        var station = from
        while (station < end + 50.0) {
            station += 1.0
            val now = CrossSection.turnOff(axis, gore, shift, station)
            assertTrue(now <= 0.0 && now >= prev - 1e-12, "at $station: from $prev to $now")
            prev = now
        }
        assertEquals(0.0, CrossSection.turnOff(axis, gore, shift, end), 0.0)
        assertTrue(CrossSection.turnOff(axis, gore, shift, end - 1.0) < 0.0)
        // With neither slope nor curvature where it settles: a metre before, under a ten-thousandth of where it began settling.
        assertTrue(abs(CrossSection.turnOff(axis, gore, shift, end - 1.0)) * 1e4 < abs(CrossSection.turnOff(axis, gore, shift, from)))
        // A path on the line already stays on it.
        assertEquals(0.0, CrossSection.turnOff(axis, gore, 0.0, crossing), 0.0)
        assertEquals(fillet.from, CrossSection.turnOffEnd(axis, gore, 0.0), 1e-12)
    }

    @Test
    fun `where the straight-on line never closes in, the path settles from the turn's arc over the blend's length`() {
        // A line that turns left at the gore while the path lies to the right: no crossing.
        val axis = RoadAxis.of(SceneLines.line(gore + 400.0, listOf(gore to 17.0)), null, SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(0.0, 0.0)))
        val fillet = axis.fillets.single()
        val shift = -1.5 * w
        assertEquals(fillet.from + CrossSection.RAMP_BLEND_METERS, CrossSection.turnOffEnd(axis, gore, shift), 1e-9)
        assertEquals(shift, CrossSection.turnOff(axis, gore, shift, fillet.from), 1e-12)
        assertEquals(shift * 0.5, CrossSection.turnOff(axis, gore, shift, fillet.from + CrossSection.RAMP_BLEND_METERS * 0.5), 1e-12)
        assertTrue(CrossSection.turnOff(axis, gore, shift, fillet.from + CrossSection.RAMP_BLEND_METERS - 1.0) < 0.0)
        assertEquals(0.0, CrossSection.turnOff(axis, gore, shift, fillet.from + CrossSection.RAMP_BLEND_METERS), 0.0)
    }

    @Test
    fun `on a two-way street the route's lanes lie from the road's line to the right`() {
        val pocket = LaneScenes.streetPocket()
        val street = 3.35
        val twoWay = section(pocket, CrossSection.Carriageway.TWO_WAY)
        val before = gore - 150.0
        assertBounds(listOf(0.0, -street, -2.0 * street), twoWay.lanes(before))
        // The oncoming half as wide as the route's lanes, plain asphalt.
        assertEquals(CrossSection.Edges(0.0, -2.0 * street, 2.0 * street + 1.2, -2.0 * street - 1.2), twoWay.edges(before))
        // The left pocket opens into it; the through lanes stay.
        val atTurn = twoWay.lanes(gore - 20.0)
        assertEquals(street, atTurn.first().left, 1e-9)
        assertBounds(listOf(street, 0.0, -street, -2.0 * street), atTurn)
        // One-way, the same lanes are centred on the line.
        assertBounds(listOf(street, 0.0, -street), section(pocket).lanes(before))
    }

    @Test
    fun `a street no source tells is laid out but not drawn, and listed`() {
        val route = LaneScenes.streetTurn()
        val street = 3.35
        val none = CrossSection.of(SceneLines.planOf(route))
        // Laid out one-way, so the plan's lanes have their places, but drawn nowhere.
        assertBounds(listOf(street, 0.0, -street), none.lanes(gore - 500.0))
        assertEquals(CrossSection.Carriageway.ONE_WAY, none.carriagewayAt(gore - 500.0))
        assertFalse(none.drawsAt(gore - 500.0))
        assertEquals(listOf(CrossSection.UndrawnStreet(gore - 650.0, gore, mixed = false)), none.undrawnStreets)
        assertEquals(650.0, none.undrawnStreets.single().meters, 1e-9)
        val run = requireNotNull(none.runAt(gore - 500.0))
        assertTrue(none.streetParts(0.0, gore + 300.0, run).isEmpty())
        // Told, it is drawn where the evidence has lanes, and nothing is left undrawn.
        for (carriageway in CrossSection.Carriageway.entries) {
            val told = section(route, carriageway)
            assertTrue(told.drawsAt(gore - 500.0))
            assertFalse(told.drawsAt(gore - 700.0))
            assertTrue(told.undrawnStreets.isEmpty())
            assertEquals(listOf(gore - 650.0..gore), told.streetParts(0.0, gore + 300.0, run))
            assertEquals(listOf(gore - 600.0..gore - 300.0), told.streetParts(gore - 600.0, gore - 300.0, run))
            assertEquals(carriageway, told.carriagewayAt(gore - 500.0))
        }
        // Freeways need no source.
        assertTrue(CrossSection.of(SceneLines.planOf(LaneScenes.ia())).drawsAt(1_000.0))
    }

    @Test
    fun `a road sits as the first of its streets a source tells of, and a street told otherwise is not drawn`() {
        val route = LaneScenes.streetTurn()
        // One-way up to the intersection 200 m before the turn and two-way past it, as the horizon reads each street's middle:
        // the road is one-way, as its first street; the last street is not drawn, nor the one before it with it, over the
        // turn's card window and the drawing's way back to the road's line before it.
        val mixed = CrossSection.of(SceneLines.planOf(route), streets = StreetWays.of(route) { it < gore - 200.0 })
        assertTrue(mixed.drawsAt(gore - 500.0))
        assertEquals(CrossSection.Carriageway.ONE_WAY, mixed.carriagewayAt(gore - 500.0))
        assertFalse(mixed.drawsAt(gore - 300.0))
        assertFalse(mixed.drawsAt(gore - 100.0))
        assertEquals(listOf(gore - 650.0..gore - 400.0), mixed.drawnStreets)
        assertEquals(listOf(CrossSection.UndrawnStreet(gore - 400.0, gore, mixed = true)), mixed.undrawnStreets)
        // Two stretches of one road apart, the first street one-way, the second two-way: the road sits as the first, the
        // second is not drawn.
        val apart = LaneScenes.route(
            LaneScenes.street(0.0, maneuver = LaneScenes.DEPART),
            LaneScenes.street(3_000.0, s(), s()),
            LaneScenes.street(4_500.0, s(), s()),
            LaneScenes.street(5_000.0, maneuver = LaneScenes.ARRIVE),
            id = "street-apart",
        )
        val plan = SceneLines.planOf(apart)
        val known = plan.evidence().stretches.filter { it.kind == LaneEvidence.Kind.KNOWN }
        val both = CrossSection.of(plan, streets = StreetWays.of(apart) { it < 2_000.0 })
        assertEquals(2, known.size)
        val (first, second) = known.sortedBy { it.from }
        assertEquals(listOf(CrossSection.UndrawnStreet(second.from, second.to, mixed = true)), both.undrawnStreets)
        assertTrue(both.drawsAt((first.from + first.to) / 2.0))
        assertEquals(CrossSection.Carriageway.ONE_WAY, both.carriagewayAt((first.from + first.to) / 2.0))
        assertFalse(both.drawsAt((second.from + second.to) / 2.0))
        // All alike, both are drawn.
        val alike = CrossSection.of(plan, streets = StreetWays.of(apart) { false })
        assertTrue(alike.undrawnStreets.isEmpty())
        for (stretch in known) assertTrue(alike.drawsAt((stretch.from + stretch.to) / 2.0))
    }

    @Test
    fun `each street is drawn where a source tells of it, and a card window's approach all or none`() {
        val route = LaneScenes.streetTurn()
        val plan = SceneLines.planOf(route)
        val window = plan.windows.single()
        assertEquals(gore - CardWindows.streetWindowMeters()..gore, window.from..window.to)
        val twoWay = StreetWays.Way(CrossSection.Carriageway.TWO_WAY, StreetWays.Source.HORIZON)
        fun told(vararg nodes: Int) = CrossSection.of(plan, streets = StreetWays.taken(route, nodes.associateWith { twoWay }))
        // The streets that leave the intersections at 0, 400 and 200 m before the turn (nodes 0, 1 and 2) lie over the lanes
        // from 650 m before it; the last two over the card window and the 60 m before it.
        assertTrue(window.from - CrossSection.CARD_LEAD_METERS in gore - 400.0..gore - 200.0)
        val stretches = CrossSection.streetStretches(plan)
        assertEquals(listOf(gore - 650.0 to gore - 400.0, gore - 400.0 to gore), stretches.map { it.from to it.to })
        assertEquals(listOf(listOf(0), listOf(1, 2)), stretches.map { it.nodes })
        // The first street without a source: the approach is drawn from where its streets begin, the first is listed.
        val late = told(1, 2)
        assertFalse(late.drawsAt(gore - 500.0))
        assertTrue(late.drawsAt(gore - 300.0) && late.drawsAt(gore - 100.0))
        assertEquals(CrossSection.Carriageway.TWO_WAY, late.carriagewayAt(gore - 100.0))
        assertEquals(listOf(gore - 400.0..gore), late.drawnStreets)
        assertEquals(listOf(CrossSection.UndrawnStreet(gore - 650.0, gore - 400.0, mixed = false)), late.undrawnStreets)
        // A street of the approach without a source: none of the approach is drawn, all of it listed as told by none.
        for (gap in listOf(1, 2)) {
            val cut = told(*intArrayOf(0, 1, 2).filter { it != gap }.toIntArray())
            assertTrue(cut.drawsAt(gore - 500.0), "without node $gap")
            assertFalse(cut.drawsAt(gore - 300.0) || cut.drawsAt(gore - 100.0), "without node $gap")
            assertEquals(listOf(gore - 650.0..gore - 400.0), cut.drawnStreets)
            assertEquals(listOf(CrossSection.UndrawnStreet(gore - 400.0, gore, mixed = false)), cut.undrawnStreets)
        }
        // Every street told: all of it drawn as one stretch.
        assertEquals(listOf(gore - 650.0..gore), told(0, 1, 2).drawnStreets)
        assertTrue(told(0, 1, 2).undrawnStreets.isEmpty())
    }

    @Test
    fun `the oncoming half of a two-way street is as wide as the route's lanes where its road begins, or as far as they reach`() {
        val street = 3.35
        val turn = LaneScenes.streetTurn()
        assertEquals(2.0 * street, requireNotNull(section(turn, CrossSection.Carriageway.TWO_WAY).oncomingEdge(gore - 500.0)), 1e-9)
        assertNull(section(turn).oncomingEdge(gore - 500.0))
        // With a lane kept for buses and one only for bicycles.
        val kept = section(LaneScenes.streetTurn(bicycle = true, bus = true), CrossSection.Carriageway.TWO_WAY)
        assertEquals(3.0 * street + 1.8, requireNotNull(kept.oncomingEdge(gore - 500.0)), 1e-9)
        // Two left pockets on a street with one lane each way open past it.
        val double = LaneScenes.route(
            LaneScenes.street(0.0, maneuver = LaneScenes.DEPART),
            LaneScenes.street(gore - 200.0, s()),
            LaneScenes.street(gore, LaneScenes.left(), LaneScenes.left(), s(false), roads = LaneScenes.CROSSING, maneuver = LaneScenes.TURN_LEFT),
            LaneScenes.street(gore + 300.0, maneuver = LaneScenes.ARRIVE),
            id = "street-double-left",
        )
        val twoWay = section(double, CrossSection.Carriageway.TWO_WAY)
        assertEquals(street, requireNotNull(twoWay.oncomingEdge(gore - 150.0)), 1e-9)
        assertEquals(2.0 * street, requireNotNull(twoWay.oncomingEdge(gore - 15.0)), 1e-9)
        assertEquals(2.0 * street, twoWay.lanes(gore - 15.0).first().left, 1e-9)
        assertEquals(CrossSection.Edges(2.0 * street, -street, 2.0 * street + 1.2, -street - 1.2), twoWay.edges(gore - 15.0))
    }

    @Test
    fun `a turn pocket is drawn from where it may begin, at full width no later than its late taper is`() {
        val street = 3.35
        for (carriageway in CrossSection.Carriageway.entries) {
            // At 35 mph: from 81.8 m before the node along an 8:1 taper, at full width where it opens late.
            val section = section(LaneScenes.pocketAt(35.0), carriageway)
            val pocket = section.topology.lanes.single { it.pocket }
            val from = gore - 10.0 - 45.0 - 8.0 * street
            assertTrue(section.place(pocket.id, from - 0.5).let { it == null || it.width == 0.0 })
            val possible = requireNotNull(section.place(pocket.id, gore - 60.0))
            assertEquals(CrossSection.State.POSSIBLE, possible.state)
            assertTrue(possible.width > 0.0 && possible.width < street)
            assertEquals(street, requireNotNull(section.place(pocket.id, gore - 55.0)).width, 1e-9)
            assertEquals(CrossSection.State.FULL, requireNotNull(section.place(pocket.id, gore - 50.0)).state)
            // The path's entry along its late taper, whatever its width: none before it opens, all of it past the taper.
            assertEquals(0.0, requireNotNull(section.lateEntry(pocket.id, gore - 56.0)))
            assertEquals(0.5, requireNotNull(section.lateEntry(pocket.id, gore - 47.5)), 1e-9)
            assertEquals(1.0, requireNotNull(section.lateEntry(pocket.id, gore - 39.0)))
            assertNull(section.lateEntry(0, gore - 47.5), "no lane but a pocket has a late taper of its own")
        }
        // Where its possible part begins late, half the way back to a junction 120 m before the node, its drawn taper is
        // shorter: at full width where its late taper ends.
        val short = section(LaneScenes.pocketAt(35.0, 120.0))
        val pocket = short.topology.lanes.single { it.pocket }
        assertEquals(gore - 60.0, requireNotNull(pocket.possibleFrom), 1e-9)
        assertTrue(requireNotNull(short.place(pocket.id, gore - 41.0)).width < street)
        assertEquals(street, requireNotNull(short.place(pocket.id, gore - 40.0)).width, 1e-9)
        // A freeway's deceleration lane the plan names still opens late with no possible part, entered along its drawn taper.
        val s9 = section(LaneScenes.s9())
        assertFalse(s9.topology.lanes[5].pocket)
        assertNull(s9.lateEntry(5, gore - 180.0))
        assertNull(s9.place(5, gore - 210.1))
    }

    @Test
    fun `a junction the route turns at on a street is no ramp's start, and the road after it lies from its line`() {
        val route = LaneScenes.streetTurn(receiving = true)
        for (carriageway in CrossSection.Carriageway.entries) {
            val section = section(route, carriageway)
            assertTrue(section.rampStarts.isEmpty())
            val after = section.lanes(gore + 15.0)
            assertEquals(1, after.size)
            assertEquals(if (carriageway == CrossSection.Carriageway.TWO_WAY) 0.0 else 3.35 / 2.0, after.single().left, 1e-9)
        }
    }

    @Test
    fun `where a lane leaves between others, the fewer lanes on one side close up along a taper`() {
        // A left exit: at a point 2,000 m before it a road leaves on the left and takes the open lane beside the
        // car-pool lane; the car-pool lane closes up toward the three lanes that go on.
        val route = LaneScenes.approach(
            listOf(LaneScenes.At(2_000.0, listOf(LaneScenes.hov(), s(false), s(), s(), s()), LaneScenes.EXIT_LEFT)),
            exit = listOf(LaneScenes.hov(), LaneScenes.sl(), s(false), s(false)),
            maneuver = LaneScenes.OFF_LEFT,
        )
        val section = section(route)
        val leaves = gore - 2_000.0 + LaneTopology.LEAVE_PAST_POINT_METERS
        val taper = LaneTopology.endingTaperMeters(27.0, RoadKind.FREEWAY)
        assertEquals(2.0 * w, section.place(0, leaves - 1.0)!!.centre, 1e-12)
        assertEquals(0.0, section.place(2, leaves - 1.0)!!.centre, 1e-12)
        // Half-way along the taper the car-pool lane has moved half a lane; the lanes that go on have not moved.
        assertEquals(1.5 * w, section.place(0, leaves + taper / 2.0)!!.centre, 1e-9)
        assertEquals(CrossSection.State.CLOSING, section.place(1, leaves + taper / 2.0)!!.state)
        assertEquals(0.0, section.place(2, leaves + taper / 2.0)!!.centre, 1e-12)
        assertEquals(w, section.place(0, leaves + taper)!!.centre, 1e-12)
        assertNull(section.place(1, leaves + taper))
        assertEquals(-2.0 * w, section.place(4, leaves + taper)!!.centre, 1e-12)
    }

    /** Where a lane of [section] ends and another of its slot begins, its kinds changed: the old lane, the new one, the station. */
    private fun changesOfKind(section: CrossSection): List<Triple<Int, Int, Double>> {
        val lanes = section.topology.lanes
        return lanes.flatMap { old ->
            lanes.filter { it.run == old.run && it.id != old.id && it.slot == old.slot && Math.abs(it.opens - old.closes) < 1e-9 }
                .map { Triple(old.id, it.id, old.closes) }
        }
    }

    /** Where [old] ends at [station] and [new] takes its place: the edges stay, [new] lies where [old] did, and so does every other lane. */
    private fun assertChangeMovesNothing(name: String, section: CrossSection, old: Int, new: Int, station: Double) {
        val before = station - 1e-6
        val edgesBefore = section.edges(before)!!
        val edgesAfter = section.edges(station)!!
        assertEquals(edgesBefore.left, edgesAfter.left, 1e-6, "$name: the left edge at $station")
        assertEquals(edgesBefore.right, edgesAfter.right, 1e-6, "$name: the right edge at $station")
        assertNull(section.place(old, station), name)
        assertEquals(section.place(old, before)!!.centre, section.place(new, station)!!.centre, 1e-6, "$name: lane $new at $station")
        for (p in section.lanes(before).filter { it.lane != old }) {
            assertEquals(p.centre, section.place(p.lane, station)!!.centre, 1e-6, "$name: lane ${p.lane} at $station")
        }
    }

    @Test
    fun `a lane that changes kinds keeps its place, and the road its width`() {
        for ((name, route) in listOf("right lane for buses" to LaneScenes.rightLaneForBuses(), "third lane for car pools" to LaneScenes.thirdLaneForCarPools())) {
            val section = section(route)
            val changes = changesOfKind(section)
            assertTrue(changes.isNotEmpty(), name)
            for ((old, new, station) in changes) assertChangeMovesNothing(name, section, old, new, station)
        }
        // The right lane of two is kept for buses 50 m past the first point and open to every car again where the exit's
        // late taper would end: two lanes centred on the road's line all along.
        val two = section(LaneScenes.rightLaneForBuses())
        assertEquals(
            listOf(gore - 2_700.0 + LaneTopology.KINDS_EARLY_METERS, gore - 15.0 * w),
            changesOfKind(two).map { it.third }.sorted(),
        )
        for (station in listOf(gore - 2_660.0, gore - 2_640.0, gore - 1_000.0, gore - 60.0, gore - 50.0)) {
            assertEquals(CrossSection.Edges(w, -w, w + 1.2, -w - 1.2), two.edges(station), "at $station")
        }
        // The third lane of four: the four lanes centred before and after it changed.
        val four = section(LaneScenes.thirdLaneForCarPools())
        val changed = changesOfKind(four).single().third
        assertEquals(gore - 2_600.0 + LaneTopology.KINDS_EARLY_METERS, changed, 1e-9)
        assertBounds(listOf(2.0 * w, w, 0.0, -w, -2.0 * w), four.lanes(changed - 1.0))
        assertBounds(listOf(2.0 * w, w, 0.0, -w, -2.0 * w), four.lanes(changed + 1.0))
    }

    @Test
    fun `a change of kinds moves no lane, on seeded random roads`() {
        val rng = Random(20261113)
        var changes = 0
        repeat(RANDOM_ROADS) { k ->
            val section = section(randomChangeOfKind(rng, "kinds-$k"))
            for ((old, new, station) in changesOfKind(section)) {
                assertChangeMovesNothing("kinds-$k", section, old, new, station)
                changes++
            }
        }
        assertTrue(changes >= RANDOM_ROADS / 2, "$changes changes")
    }

    /**
     * A made-up approach of 2 to 6 lanes to an exit on either side: up to three lane points, edge lanes leaving by roads,
     * and one lane, any of them, kept for car pools or buses from one of the points on, open to every car again at the
     * exit or not.
     */
    private fun randomChangeOfKind(rng: Random, id: String): LaneRoute {
        val right = rng.nextBoolean()
        val kept = if (rng.nextBoolean()) hov() else bus()
        val keptFrom = rng.nextInt(0, 3)
        var count = rng.nextInt(2, 7)
        var lane = -1
        val points = ArrayList<LaneScenes.At>()
        var before = rng.nextDouble(2_400.0, 3_400.0)
        while (points.size < 3) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            if (lane < 0 && points.size >= keptFrom) lane = rng.nextInt(count)
            lane = minOf(lane, count - 1)
            val lanes = MutableList(count) { if (it == lane) kept else s() }
            val roads = when (rng.nextInt(4)) {
                0 -> {
                    if (lane != 0) lanes[0] = s(false)
                    LaneScenes.EXIT_LEFT
                }
                1 -> {
                    if (lane != count - 1) lanes[count - 1] = s(false)
                    LaneScenes.EXIT_RIGHT
                }
                else -> LaneScenes.THROUGH
            }
            points.add(LaneScenes.At(before, lanes, roads))
        }
        val exit = MutableList(count) { if (it == lane && rng.nextBoolean()) kept else s(false) }
        exit[if (right) count - 1 else 0] = if (right) sr() else sl()
        if (rng.nextBoolean()) exit.add(if (right) exit.size else 0, if (right) sr() else sl())
        val maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT
        return LaneScenes.approach(points, exit, maneuver, planMps = rng.nextDouble(22.0, 34.0), id = id)
    }

    @Test
    fun `where no lane is named the carriageway moves onto the road's line, by the least, no faster than a move across a lane`() {
        assertEquals(RoutePath.ASPHALT_TAPER_METERS, CrossSection.SHIFT_SMOOTH_METERS)
        // A made-up approach of the drawing's sweeps: lanes leave on one side and join on the other in a careful zone, so the rules
        // lay every lane metres off the road's line, and no lane can be named before the exit (the lanes to use lie beyond a lane
        // kept for buses).
        val (route, _) = RandomRoutes.nth(Random(3_007), 0, "s3", RandomRoutes::keptLane)
        val plan = SceneLines.planOf(route)
        val section = CrossSection.of(plan)
        val approach = plan.noLane.single()
        // On the approach the road's line lies at least half a lane inside the edge lines, and somewhere just half a lane: the
        // least move that does.
        var least = Double.POSITIVE_INFINITY
        var x = approach.from
        while (x < approach.to) {
            val places = section.lanes(x).filter { it.width > 0.0 }
            val inside = minOf(places.first().left, -places.last().right)
            assertTrue(inside >= w / 2.0 - 1e-6, "at $x the road's line lies $inside m inside")
            least = minOf(least, inside)
            x += 1.0
        }
        assertEquals(w / 2.0, least, 0.05)
        // No faster than the path moves across a lane at the route's planning speed, and with no corner: its slope turns over the
        // smoothing's length.
        val slope = w / RoutePath.moveMeters(PlanningSpeed.of(route.nodes.first()), RoadKind.FREEWAY)
        val step = CrossSection.SHIFT_STEP_METERS
        var steepest = 0.0
        var sharpest = 0.0
        var moved = 0.0
        var s = 0.0
        while (s + 2.0 * step < approach.to) {
            val a = (section.shiftAt(s + step) - section.shiftAt(s)) / step
            val b = (section.shiftAt(s + 2.0 * step) - section.shiftAt(s + step)) / step
            steepest = maxOf(steepest, abs(a))
            sharpest = maxOf(sharpest, abs(b - a))
            moved = maxOf(moved, abs(section.shiftAt(s)))
            s += step
        }
        assertTrue(moved > w, "it moves: $moved m")
        assertTrue(steepest <= slope + 1e-9, "slope $steepest against $slope")
        assertTrue(sharpest <= 2.0 * slope * step / CrossSection.SHIFT_SMOOTH_METERS, "slope turning by $sharpest a step")
        // Where it need not, it does not: at the route's start, and on the ramp past the gore, laid on its own road's line.
        assertEquals(0.0, section.shiftAt(1_000.0))
        assertEquals(0.0, section.shiftAt(approach.to + 90.0))
    }

    @Test
    fun `where the road's line lies half a lane inside already, or a lane is named, the carriageway stays where the rules lay it`() {
        for ((name, route) in LaneScenes.all()) {
            val plan = SceneLines.planOf(route)
            val section = CrossSection.of(plan)
            var x = 0.0
            while (x < route.lengthMeters) {
                assertEquals(0.0, section.shiftAt(x), "$name at $x")
                x += 9.0
            }
        }
    }

    @Test
    fun `lanes lie side by side in order, never over each other, on every scene`() {
        for ((name, route) in LaneScenes.all()) {
            val section = section(route)
            var station = 0.0
            while (station <= route.lengthMeters) {
                val places = section.lanes(station)
                for ((i, p) in places.withIndex()) {
                    assertTrue(p.width >= 0.0, "$name at $station: $p")
                    assertTrue(p.width <= 3.66 + 1e-12, "$name at $station: $p")
                    if (i > 0) assertEquals(places[i - 1].right, p.left, 1e-12, "$name at $station")
                    if (i > 0) {
                        val slots = places.map { section.topology.lanes[it.lane].slot }
                        assertTrue(slots[i] > slots[i - 1], "$name at $station: order")
                    }
                }
                station += 5.0
            }
        }
    }

    private companion object {
        /** How many seeded random roads a change of kinds is checked on. */
        const val RANDOM_ROADS = 120
    }
}
