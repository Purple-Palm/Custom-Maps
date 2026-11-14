package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "horizon_lanes" comparison ([HorizonLanesLine]) on invented scenes: IA ([LaneScenes.ia], five general lanes at
 * every point, its exit at [GORE]) and the car-pool lane beside three open lanes of [LaneScenes.carpoolBeyond], against
 * synthetic horizons laid from the car.
 */
class HorizonLanesLineTest {

    private fun edge(length: Double, lanes: Int?, ramp: Boolean = false) =
        HorizonEdge(length, lanes, motorway = !ramp, ramp = ramp, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    private val ia = LaneScenes.ia()
    private val iaPoints = HorizonLanesLine.points(ia) { "ia/0/0/$it" }

    /**
     * IA's approach as a horizon would give it, the car at 3,000 m, half way along an edge of 1,640 m whose end is IA0: an
     * edge end at every lane point, five lanes to the gore, six on its last edge, the ramp's two after it.
     */
    private fun iaHorizon(car: Double = 3_000.0, along: Double = 0.5, firstLength: Double = 1_640.0) = HorizonAhead.of(
        "ia",
        car,
        along,
        listOf(edge(firstLength, 5), edge(850.0, 5), edge(640.0, 5), edge(50.0, 5), edge(640.0, 6), edge(400.0, 2, ramp = true)),
    )

    @Test
    fun `the lane points of a route, with their lanes, the road to them and how far before the route turns`() {
        assertEquals(listOf(GORE - 2_180.0, GORE - 1_330.0, GORE - 690.0, GORE - 640.0, GORE), iaPoints.map { it.station })
        assertEquals(listOf(5, 5, 5, 5, 6), iaPoints.map { it.listed })
        assertEquals(listOf(5, 5, 5, 5, 6), iaPoints.map { it.general })
        assertEquals(listOf(2_180.0, 1_330.0, 690.0, 640.0, 0.0), iaPoints.map { it.toGore })
        assertTrue(iaPoints.all { it.road == RoadKind.FREEWAY })
        assertEquals("ia/0/0/${iaPoints[0].node}", iaPoints[0].key)
        // A lane kept for car pools is listed, and not a general lane.
        val point = HorizonLanesLine.points(LaneScenes.carpoolBeyond()) { null }.first()
        assertEquals(4, point.listed)
        assertEquals(3, point.general)
        assertNull(point.key)
    }

    @Test
    fun `every point of IA's approach agrees with every listed lane, each compared once`() {
        val logged = HashSet<Int>()
        val compared = HorizonLanesLine.compare(iaPoints, iaHorizon(), logged)

        assertEquals(iaPoints, compared.map { it.point })
        assertEquals(listOf(5, 5, 5, 5, 6), compared.map { it.count })
        assertTrue(compared.all { it.convention == HorizonConvention.ALL })
        assertEquals(listOf(820.0, 1_670.0, 2_310.0, 2_360.0, 3_000.0), compared.map { it.aheadM })
        assertTrue(compared.all { it.endOffsetM == 0.0 })
        // The next update covers the same points: none again.
        assertEquals(emptyList<HorizonLanesLine.Comparison>(), HorizonLanesLine.compare(iaPoints, iaHorizon(car = 3_100.0, along = 0.56), logged))
    }

    @Test
    fun `the counts agree with the general lanes where a car-pool lane is listed, with neither where they differ from both`() {
        val points = HorizonLanesLine.points(LaneScenes.carpoolBeyond()) { null }
        val point = points.first()
        fun convention(count: Int?) = HorizonLanesLine.compare(
            points,
            HorizonAhead.of("carpool-beyond", point.station - 100.0, 0.0, listOf(edge(100.0, count), edge(500.0, 5))),
            HashSet(),
        ).first().convention
        assertEquals(HorizonConvention.GENERAL, convention(3))
        assertEquals(HorizonConvention.ALL, convention(4))
        assertEquals(HorizonConvention.NONE, convention(2))
        assertEquals(HorizonConvention.NONE, convention(null))
        assertEquals(HorizonConvention.ALL, HorizonConvention.of(5, 5, 5))
        assertEquals(HorizonConvention.GENERAL, HorizonConvention.of(3, 4, 3))
    }

    @Test
    fun `the edge that leads to a point ends within 20 m of it, before or after`() {
        assertEquals(20.0, HorizonLanesLine.MATCH_TOLERANCE_M)
        fun at(offset: Double): HorizonLanesLine.Comparison {
            // The car's edge ends [offset] metres past IA0; the edges after it keep their lengths.
            val car = 3_000.0
            val ahead = iaHorizon(car = car, along = 0.0, firstLength = iaPoints[0].station - car + offset)
            return HorizonLanesLine.compare(iaPoints, ahead, HashSet()).first()
        }
        for (offset in listOf(19.9, -19.9)) {
            val c = at(offset)
            assertEquals(5, c.count, "$offset")
            assertEquals(offset, c.endOffsetM!!, 1e-9)
            assertEquals(HorizonConvention.ALL, c.convention)
        }
        for (offset in listOf(20.1, -20.1)) {
            val c = at(offset)
            assertNull(c.edge, "$offset")
            assertNull(c.count)
            assertEquals(offset, c.endOffsetM!!, 1e-9)
            assertEquals(HorizonConvention.NONE, c.convention)
        }
    }

    @Test
    fun `a point is compared once the stretches reach 20 m past it, and never behind the car`() {
        val logged = HashSet<Int>()
        // To IA1 and no farther: IA0 is covered, IA1 not yet.
        val short = HorizonAhead.of("ia", 3_000.0, 0.5, listOf(edge(1_640.0, 5), edge(850.0, 5)))
        assertEquals(listOf(iaPoints[0]), HorizonLanesLine.compare(iaPoints, short, logged).map { it.point })
        // 20 m farther: IA1 now, IA0 not again.
        val reach = HorizonAhead.of("ia", 3_000.0, 0.5, listOf(edge(1_640.0, 5), edge(850.0, 5), edge(20.0, 5)))
        assertEquals(listOf(iaPoints[1]), HorizonLanesLine.compare(iaPoints, reach, logged).map { it.point })
        // A car past IA0 compares the points ahead of it only.
        val past = HorizonAhead.of("ia", iaPoints[0].station + 1.0, 0.0, listOf(edge(3_000.0, 5)))
        val ahead = HorizonLanesLine.compare(iaPoints, past, HashSet()).map { it.point }
        assertFalse(iaPoints[0] in ahead)
        // No stretches, no comparison.
        assertEquals(emptyList<HorizonLanesLine.Comparison>(), HorizonLanesLine.compare(iaPoints, HorizonAhead.of("ia", 0.0, 0.0, emptyList()), HashSet()))
    }

    @Test
    fun `the line holds counts, metres from the point and the car, booleans and kinds only`() {
        val c = HorizonLanesLine.compare(iaPoints, iaHorizon(), HashSet()).last()
        assertEquals(
            linkedMapOf(
                "key" to "ia/0/0/${iaPoints.last().node}",
                "toGoreM" to 0L,
                "aheadM" to 3_000L,
                "road" to "freeway",
                "lanes" to 6,
                "general" to 6,
                "matched" to true,
                "endM" to 0.0,
                "count" to 6,
                "motorway" to true,
                "ramp" to false,
                "oneWay" to true,
                "roadClass" to "motorway",
                "convention" to "all",
            ),
            HorizonLanesLine.fields(c),
        )
        // Without an edge that ends at the point: no count and nothing of an edge.
        val far = HorizonLanesLine.fields(
            HorizonLanesLine.compare(iaPoints, iaHorizon(car = 3_000.0, along = 0.0, firstLength = iaPoints[0].station - 3_000.0 + 47.0), HashSet()).first(),
        )
        assertEquals(false, far["matched"])
        assertEquals(47.0, far["endM"])
        assertEquals(listOf(null, null, null, null, null), listOf("count", "motorway", "ramp", "oneWay", "roadClass").map { far[it] })
        assertEquals("none", far["convention"])
    }
}
