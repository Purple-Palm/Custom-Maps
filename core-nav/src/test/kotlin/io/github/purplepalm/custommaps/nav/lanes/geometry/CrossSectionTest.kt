package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The made-up routes of [LaneScenes] laid out across the road; stations are metres from each route's start. */
class CrossSectionTest {

    private val w = LaneTopology.FREEWAY_LANE_METERS
    private val gore = LaneScenes.GORE

    private fun section(route: LaneRoute, carriageway: CrossSection.Carriageway = CrossSection.Carriageway.ONE_WAY) =
        CrossSection.of(SceneLines.planOf(route), carriageway = { carriageway })

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
    fun `at a ramp's start the lanes go on straight until the ramp's line lies farther out and blend onto it over 25 m`() {
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
        // Until the crossing, g4's middle goes straight on, 1.5 lanes east of the freeway's line, which runs due north.
        for (station in listOf(fillet.from, gore - 1.0, gore, gore + 10.0, crossing - 0.5)) {
            val centre = section.place(3, station, axis)!!.centre
            val world = axis.offset(station, centre)
            assertEquals(1.5 * w, world.x, 1e-6, "at $station")
        }
        // There the ramp's line lies on the old carriageway's middle (the two lanes' line, 2 lanes east).
        assertEquals(2.0 * w, axis.at(crossing).point.x, 0.05)
        // Then the blend onto the ramp's own carriageway, centred on its line: over 25 m, and nothing after.
        assertTrue(Math.abs(section.place(3, crossing + 24.9, axis)!!.centre - 0.5 * w) > 1e-6)
        assertEquals(0.5 * w, section.place(3, crossing + 25.0, axis)!!.centre, 1e-12)
        assertEquals(0.5 * w, section.place(3, crossing + 100.0, axis)!!.centre, 1e-12)
        assertEquals(-0.5 * w, section.place(4, crossing + 100.0, axis)!!.centre, 1e-12)
        // On the way the two lanes stay two lanes wide on the ground: across the turning axis' normal, wider by its turn.
        var station = fillet.from
        while (station < crossing) {
            val left = section.place(3, station, axis)!!.left
            val right = section.place(4, station, axis)!!.right
            assertEquals(2.0 * w, (left - right) * axis.at(station).ty, 1e-6, "two lanes wide at $station")
            station += 0.5
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
    fun `where the ramp's line never lies farther out the lanes blend from the turn`() {
        // A line that turns left at the gore while the lanes onto it lie to the right: no crossing.
        val axis = RoadAxis.of(SceneLines.line(gore + 400.0, listOf(gore to 17.0)), null, SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(0.0, 0.0)))
        val fillet = axis.fillets.single()
        assertEquals(gore, CrossSection.crossing(axis, fillet.from, gore, -2.0 * w), 1e-9)
        assertEquals(0.0, CrossSection.continuation(axis, gore, -2.0 * w, gore + CrossSection.RAMP_BLEND_METERS), 1e-12)
        assertEquals(-2.0 * w, CrossSection.continuation(axis, gore, -2.0 * w, fillet.from - 1.0), 1e-12)
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
