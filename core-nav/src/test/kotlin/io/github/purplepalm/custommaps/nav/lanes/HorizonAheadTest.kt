package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The electronic horizon laid along the route ([HorizonAhead]): invented edges and stations, no place. */
class HorizonAheadTest {

    private fun edge(length: Double, lanes: Int? = 5, ramp: Boolean = false) =
        HorizonEdge(length, lanes, motorway = !ramp, ramp = ramp, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    private fun spans(ahead: HorizonAhead) = ahead.stretches.map { it.start to it.end }

    @Test
    fun `the car's edge counts from where the car is on it, the edges after it whole`() {
        val edges = listOf(edge(200.0), edge(300.0), edge(150.0, lanes = 2, ramp = true))
        val ahead = HorizonAhead.of("route-1", 1_000.0, 0.25, edges)

        assertEquals(listOf(1_000.0 to 1_150.0, 1_150.0 to 1_450.0, 1_450.0 to 1_600.0), spans(ahead))
        assertEquals(edges, ahead.stretches.map { it.edge })
        assertEquals(1_600.0, ahead.to)
        assertEquals(1_000.0, ahead.from)
        assertEquals("route-1", ahead.routeId)
        assertEquals(150.0, ahead.stretches[0].meters)
    }

    @Test
    fun `an edge without metadata ends the stretches, a lane count of none stays none`() {
        val ahead = HorizonAhead.of("route-1", 0.0, 0.0, listOf(edge(100.0, lanes = null), null, edge(100.0)))

        assertEquals(listOf(0.0 to 100.0), spans(ahead))
        assertNull(ahead.stretches[0].edge.laneCount)
        // None at all: the stretches end where the car is.
        val none = HorizonAhead.of("route-1", 500.0, 0.5, listOf(null))
        assertEquals(emptyList<HorizonStretch>(), none.stretches)
        assertEquals(500.0, none.to)
    }

    @Test
    fun `the car's share of its edge is taken within 0 and 1`() {
        fun first(along: Double) = HorizonAhead.of("r", 0.0, along, listOf(edge(100.0), edge(50.0))).stretches.map { it.meters }
        assertEquals(listOf(100.0, 50.0), first(0.0))
        assertEquals(100.0 * (1.0 - 0.999), first(0.999)[0], 1e-9)
        // At the edge's end the car's edge adds nothing, and the next one begins at the car.
        assertEquals(listOf(50.0), first(1.0))
        assertEquals(listOf(50.0), first(1.5))
        assertEquals(listOf(100.0, 50.0), first(-0.5))
        assertEquals(listOf(100.0, 50.0), first(Double.NaN))
    }

    @Test
    fun `an edge of no length adds nothing, one below zero or of no number ends the stretches`() {
        assertEquals(listOf(0.0 to 100.0, 100.0 to 150.0), spans(HorizonAhead.of("r", 0.0, 0.0, listOf(edge(100.0), edge(0.0), edge(50.0)))))
        assertEquals(listOf(0.0 to 100.0), spans(HorizonAhead.of("r", 0.0, 0.0, listOf(edge(100.0), edge(-1.0), edge(50.0)))))
        assertEquals(listOf(0.0 to 100.0), spans(HorizonAhead.of("r", 0.0, 0.0, listOf(edge(100.0), edge(Double.NaN), edge(50.0)))))
        assertEquals(emptyList<HorizonStretch>(), HorizonAhead.of("r", Double.NaN, 0.0, listOf(edge(100.0))).stretches)
    }

    @Test
    fun `the stretch at a station holds it from its start up to, not at, its end`() {
        val ahead = HorizonAhead.of("r", 1_000.0, 0.0, listOf(edge(100.0), edge(200.0), edge(300.0)))
        assertSame(ahead.stretches[0], ahead.at(1_000.0))
        assertSame(ahead.stretches[0], ahead.at(1_099.9))
        assertSame(ahead.stretches[1], ahead.at(1_100.0))
        assertSame(ahead.stretches[2], ahead.at(1_599.9))
        assertNull(ahead.at(1_600.0))
        assertNull(ahead.at(999.9))
    }

    @Test
    fun `the nearest edge end lies before or after a station, the earlier at a tie`() {
        val ahead = HorizonAhead.of("r", 0.0, 0.0, listOf(edge(100.0), edge(100.0), edge(100.0)))
        assertEquals(100.0, ahead.endNearest(90.0)?.end)
        assertEquals(100.0, ahead.endNearest(140.0)?.end)
        assertEquals(200.0, ahead.endNearest(160.0)?.end)
        assertEquals(100.0, ahead.endNearest(150.0)?.end)
        assertEquals(300.0, ahead.endNearest(5_000.0)?.end)
        assertEquals(100.0, ahead.endNearest(-50.0)?.end)
        assertNull(HorizonAhead.of("r", 0.0, 0.0, emptyList()).endNearest(12.0))
        assertNull(ahead.endNearest(Double.NaN))
    }

    @Test
    fun `the car's station moves on by its speed, for a progress update at most two seconds old`() {
        val max = HorizonAhead.STATION_MAX_AGE_S
        assertEquals(2.0, max)
        assertEquals(1_000.0 + 23.0 * max, HorizonAhead.carStation(1_000.0, 23.0, max))
        assertNull(HorizonAhead.carStation(1_000.0, 23.0, max + 1e-6))
        assertEquals(1_011.5, HorizonAhead.carStation(1_000.0, 23.0, 0.5))
        // No speed, or none that counts: where the update had it.
        assertEquals(1_000.0, HorizonAhead.carStation(1_000.0, null, 1.0))
        assertEquals(1_000.0, HorizonAhead.carStation(1_000.0, -3.0, 1.0))
        assertEquals(1_000.0, HorizonAhead.carStation(1_000.0, Double.NaN, 1.0))
        // A clock that went back a little: not moved back.
        assertEquals(1_000.0, HorizonAhead.carStation(1_000.0, 23.0, -0.2))
        assertNull(HorizonAhead.carStation(Double.NaN, 23.0, 0.5))
        assertNull(HorizonAhead.carStation(1_000.0, 23.0, Double.NaN))
    }

    @Test
    fun `a newer update keeps the earlier one's edges behind the car, the car's edge whole from where it began`() {
        // The earlier update from 1,000 m, a quarter along an edge of 200 m; the newer from 1,230 m, the second edge's
        // first 80 m behind the car, and an edge more ahead.
        val earlier = HorizonAhead.of("route-1", 1_000.0, 0.25, listOf(edge(200.0), edge(300.0), edge(150.0, lanes = 2, ramp = true)))
        val newer = HorizonAhead.of("route-1", 1_230.0, 80.0 / 300.0, listOf(edge(300.0), edge(150.0, lanes = 2, ramp = true), edge(420.0)))
        assertEquals(1_230.0, newer.start)
        val joined = newer.after(earlier)
        assertEquals(listOf(1_000.0 to 1_150.0, 1_150.0 to 1_450.0, 1_450.0 to 1_600.0, 1_600.0 to 2_020.0), spans(joined))
        assertEquals(listOf(earlier.stretches[0].edge, newer.stretches[0].edge, newer.stretches[1].edge, newer.stretches[2].edge), joined.stretches.map { it.edge })
        // The car is where the newer update has it; the stretches begin behind it, end where it ends.
        assertEquals(1_230.0, joined.from)
        assertEquals(1_000.0, joined.start)
        assertEquals(newer.to, joined.to)
        assertSame(joined.stretches[1], joined.at(1_200.0))
        // Kept again by the next one: the edges the car passed stay, each as the update that last had it ahead laid it.
        val next = HorizonAhead.of("route-1", 1_470.0, 20.0 / 150.0, listOf(edge(150.0, lanes = 2, ramp = true), edge(420.0), edge(500.0)))
        assertEquals(listOf(1_000.0 to 1_150.0, 1_150.0 to 1_450.0, 1_450.0 to 1_600.0, 1_600.0 to 2_020.0, 2_020.0 to 2_520.0), spans(next.after(joined)))
    }

    @Test
    fun `two updates meet where an earlier edge end lies within the join of where the car's edge begins`() {
        val earlier = HorizonAhead.of("route-1", 0.0, 0.0, listOf(edge(500.0), edge(500.0)))
        fun joined(offset: Double): HorizonAhead {
            // The newer update lays the car's edge, 500 m, from [offset] metres past where the earlier one ends its first edge.
            val newer = HorizonAhead.of("route-1", 600.0 + offset, 100.0 / 500.0, listOf(edge(500.0), edge(300.0)))
            return newer.after(earlier)
        }
        val join = HorizonAhead.JOIN_M
        assertEquals(10.0, join)
        assertEquals(0.0, joined(join - 0.1).start)
        assertEquals(0.0, joined(-(join - 0.1)).start)
        // The car's edge begins where the earlier one's end lies, whatever the newer update makes of it.
        assertEquals(500.0, joined(join - 0.1).stretches[1].start)
        assertEquals(600.0 + join + 0.1, joined(join + 0.1).start, 1e-9)
        assertEquals(600.0 - join - 0.1, joined(-(join + 0.1)).start, 1e-9)
    }

    @Test
    fun `edges are kept no farther than the trail behind the car, and not at all from another route, a seek or nothing`() {
        val trail = HorizonAhead.TRAIL_M
        assertEquals(HorizonAhead.HORIZON_AHEAD_M, trail)
        // The earlier update's first edge ends 1,000 m along; the newer's car is on the third, which begins at 2,000 m.
        fun firstKept(car: Double): Double {
            val earlier = HorizonAhead.of("route-1", 0.0, 0.0, listOf(edge(1_000.0), edge(1_000.0), edge(4_000.0)))
            val newer = HorizonAhead.of("route-1", car, (car - 2_000.0) / 4_000.0, listOf(edge(4_000.0), edge(800.0)))
            return newer.after(earlier).start
        }
        assertEquals(0.0, firstKept(1_000.0 + trail))
        assertEquals(1_000.0, firstKept(1_000.0 + trail + 0.1))
        // Another route, a newer update with nothing, an earlier one with nothing: the newer one as it is.
        val earlier = HorizonAhead.of("route-1", 0.0, 0.0, listOf(edge(500.0), edge(500.0)))
        val newer = HorizonAhead.of("route-1", 600.0, 0.2, listOf(edge(500.0)))
        assertSame(newer, newer.after(HorizonAhead.of("route-2", 0.0, 0.0, listOf(edge(500.0), edge(500.0)))))
        assertSame(newer, newer.after(HorizonAhead.of("route-1", 0.0, 0.0, emptyList())))
        val nothing = HorizonAhead.of("route-1", 600.0, 0.2, emptyList())
        assertSame(nothing, nothing.after(earlier))
        // A seek back, behind every edge the earlier update had, and one far past its end: no edge end meets.
        val back = HorizonAhead.of("route-1", 300.0, 0.4, listOf(edge(500.0)))
        assertSame(back, back.after(HorizonAhead.of("route-1", 1_000.0, 0.0, listOf(edge(500.0)))))
        val far = HorizonAhead.of("route-1", 5_200.0, 0.4, listOf(edge(500.0)))
        assertSame(far, far.after(earlier))
        // An earlier edge end within the join of where the car's short edge begins, yet past where it ends: not that node.
        val short = HorizonAhead.of("route-1", 502.0, 0.75, listOf(edge(8.0), edge(400.0)))
        assertSame(short, short.after(HorizonAhead.of("route-1", 0.0, 0.0, listOf(edge(505.0), edge(500.0)))))
    }

    @Test
    fun `the horizon reaches past every drawing window when it is sent, the SDK's default would not`() {
        val reach = DrawingWindows.MAX_METERS - DrawingWindows.BEHIND_METERS
        assertEquals(3_500.0, HorizonAhead.HORIZON_AHEAD_M)
        assertTrue(HorizonAhead.HORIZON_AHEAD_M >= reach)
        assertTrue(SDK_DEFAULT_LENGTH_M < reach)
        assertEquals(1.0, HorizonAhead.HORIZON_UPDATE_SECONDS)
    }

    @Test
    fun `road classes are read by the horizon's own words, any other as other`() {
        for (c in HorizonRoadClass.entries.filter { it != HorizonRoadClass.OTHER }) assertEquals(c, HorizonRoadClass.of(c.name))
        assertEquals(HorizonRoadClass.SERVICE_OTHER, HorizonRoadClass.of("SERVICE_OTHER"))
        assertEquals(HorizonRoadClass.OTHER, HorizonRoadClass.of("motorway"))
        assertEquals(HorizonRoadClass.OTHER, HorizonRoadClass.of("OTHER"))
        assertEquals(HorizonRoadClass.OTHER, HorizonRoadClass.of(null))
        assertEquals("service_other", HorizonRoadClass.SERVICE_OTHER.logName)
    }

    private companion object {
        /** The horizon's own default length, in the navigation SDK's options. */
        const val SDK_DEFAULT_LENGTH_M = 500.0
    }
}
