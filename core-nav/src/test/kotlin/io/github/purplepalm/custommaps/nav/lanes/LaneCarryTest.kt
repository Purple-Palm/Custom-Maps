package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Made-up routes only ([LaneScenes]) and made-up lines in metres east and north of one origin: a route's
 * line runs north from the car's place on it; a ramp beside the freeway 18 m east of it.
 */
class LaneCarryTest {

    private fun plan(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    private fun LanePlan.at(station: Double): PlanSegment = requireNotNull(segmentAt(station)) { "no plan at $station" }

    /** A straight line north from [x] metres east of the origin, [meters] long. */
    private fun north(x: Double, meters: Double = 200.0) = listOf(LocalPoint(x, 0.0), LocalPoint(x, meters))

    private val freeway = north(0.0)
    private val ramp = north(18.0)

    @Test
    fun `after a moment matched onto a ramp beside the freeway the light stays on the freeway plan's own lane`() {
        // On IW31's freeway, 500 m before the gore, the plan lights the option lane, the second from the right: the
        // exit-only lane is there. The navigator matches the car onto the ramp beside it (a new route), and back.
        val a = plan(LaneScenes.iw31())
        assertEquals(3, a.at(GORE - 500.0).named)
        assertEquals(1, a.at(GORE - 500.0).namedFromEdge)
        val b = plan(LaneScenes.besideRamp())
        val onRamp = requireNotNull(LaneCarry.of(a, GORE - 500.0, freeway, b, 150.0, ramp))
        assertFalse(onRamp.kept, "the ramp is another carriageway")
        assertEquals(0, onRamp.lane)
        assertEquals(150.0 + 20.0 * LanePlanner.REACTION_SECONDS, onRamp.to, 1e-9)
        // Back on the freeway: the ramp's count (its one lane, at the edge) is not carried, so the light stays on the
        // option lane, never on the exit-only lane at the edge, and does not move back.
        val c = plan(LaneScenes.iw31())
        val back = requireNotNull(LaneCarry.of(b, 220.0, ramp, c, GORE - 430.0, freeway))
        assertFalse(back.kept)
        assertEquals(3, back.lane)
        assertEquals(1, back.fromEdge)
        assertEquals(LaneEdge.RIGHT, back.edge)
        assertNull(back.moveTo)
        assertFalse(back.moves)
        assertEquals(GORE - 430.0 + 65.0 * PlanningSpeed.MPS_PER_MPH * LanePlanner.REACTION_SECONDS, back.to, 1e-9)
        // Carried across the two carriageways, the ramp's count would light the exit-only lane and move away from
        // the edge after it: the move back the rule prevents.
        val across = requireNotNull(LaneCarry.of(b, 220.0, freeway, c, GORE - 430.0, freeway))
        assertTrue(across.kept)
        assertEquals(4, across.lane, "the exit-only lane, counted 0 from the edge")
        assertEquals(3, across.moveTo)
    }

    @Test
    fun `a reroute on the same carriageway keeps the old plan's count up to the reaction distance ahead`() {
        // IA lights the option lane 1,500 m before the gore, the second from the right; the new route's plan names the
        // right lane there. Both run on one carriageway: the second from the right stays lit for 2.5 s of driving.
        val old = plan(LaneScenes.ia())
        val new = plan(LaneScenes.ia(farSide = true))
        assertEquals(1, old.at(GORE - 1_500.0).namedFromEdge)
        assertEquals(3, new.at(GORE - 1_500.0).named)
        assertEquals(0, new.at(GORE - 1_500.0).namedFromEdge)
        val carry = requireNotNull(LaneCarry.of(old, GORE - 1_500.0, freeway, new, GORE - 1_500.0, freeway))
        assertTrue(carry.kept)
        assertEquals(2, carry.lane)
        assertEquals(1, carry.fromEdge)
        assertEquals(GORE - 1_500.0 + 27.0 * LanePlanner.REACTION_SECONDS, carry.to, 1e-9)
        assertEquals(3, carry.moveTo, "after it the new plan's lane, toward the edge")
    }

    @Test
    fun `a reroute onto another carriageway lights the new plan's own lane from the car`() {
        val old = plan(LaneScenes.ia())
        val new = plan(LaneScenes.ia(farSide = true))
        val carry = requireNotNull(LaneCarry.of(old, GORE - 1_500.0, freeway, new, GORE - 1_500.0, north(20.0)))
        assertFalse(carry.kept)
        assertEquals(3, carry.lane)
        assertEquals(0, carry.fromEdge)
        assertNull(carry.moveTo)
    }

    @Test
    fun `the count is kept only where both plans count from the same exit edge`() {
        // S5's left exit counts from the left: its count says nothing about IA's right exit.
        val old = plan(LaneScenes.s5())
        assertEquals(LaneEdge.LEFT, old.at(GORE - 1_000.0).edge)
        val carry = requireNotNull(LaneCarry.of(old, GORE - 1_000.0, freeway, plan(LaneScenes.ia()), GORE - 1_500.0, freeway))
        assertFalse(carry.kept)
        assertEquals(3, carry.lane)
    }

    @Test
    fun `a new route's moves start no nearer than the reaction distance ahead of the car`() {
        // S9 moves the light into the deceleration lane 210 m before the gore; at 27 m/s the hold is 67.5 m.
        val plan = plan(LaneScenes.s9())
        assertEquals(listOf(GORE - 210.0), plan.moves.map { it.station })
        val near = requireNotNull(LaneCarry.of(null, 0.0, emptyList(), plan, GORE - 270.0, freeway))
        assertEquals(3, near.lane, "60 m before the move: held")
        assertEquals(GORE - 270.0 + 67.5, near.to, 1e-9)
        assertEquals(5, near.moveTo, "the move comes at the hold's end")
        val far = requireNotNull(LaneCarry.of(null, 0.0, emptyList(), plan, GORE - 280.0, freeway))
        assertEquals(3, far.lane, "70 m before the move")
        assertNull(far.moveTo, "the plan's own move comes after the hold")
        assertFalse(far.kept)
    }

    @Test
    fun `nothing is held where the new plan lights no lane at the car`() {
        assertNull(LaneCarry.of(null, 0.0, freeway, plan(LaneScenes.ia()), GORE - 4_000.0, freeway), "before the run-up")
        assertNull(LaneCarry.of(null, 0.0, freeway, plan(LaneScenes.geomS5()), GORE - 100.0, freeway), "no lane can be named")
        assertNull(LaneCarry.of(null, 0.0, freeway, plan(LaneScenes.ia()), -10.0, freeway), "off the plan")
    }

    @Test
    fun `two lines are one carriageway while they lie within 5 m of each other`() {
        assertTrue(LaneCarry.sameCarriageway(north(0.0), north(4.9), 70.0))
        assertFalse(LaneCarry.sameCarriageway(north(0.0), north(5.1), 70.0))
        assertTrue(LaneCarry.sameCarriageway(north(0.0), north(0.0), 70.0))
        // A road that parts at a slant, 1 m east for every 4 m north: within 5 m for its first 20 m only.
        val parting = listOf(LocalPoint(0.0, 0.0), LocalPoint(50.0, 200.0))
        assertTrue(LaneCarry.sameCarriageway(north(0.0), parting, 19.0))
        assertFalse(LaneCarry.sameCarriageway(north(0.0), parting, 70.0))
        // The other way round: a line going south from the same place is another carriageway.
        assertFalse(LaneCarry.sameCarriageway(north(0.0), listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, -200.0)), 70.0))
        assertEquals(LaneCarry.SAME_CARRIAGEWAY_METERS, 5.0)
    }

    @Test
    fun `odd lines never throw and are never one carriageway`() {
        assertFalse(LaneCarry.sameCarriageway(emptyList(), north(0.0), 70.0))
        assertFalse(LaneCarry.sameCarriageway(north(0.0), emptyList(), 70.0))
        assertFalse(LaneCarry.sameCarriageway(north(0.0), north(0.0), Double.NaN))
        assertFalse(LaneCarry.sameCarriageway(listOf(LocalPoint(Double.NaN, 0.0), LocalPoint(0.0, 10.0)), north(0.0), 70.0))
        assertTrue(LaneCarry.sameCarriageway(listOf(LocalPoint(1.0, 0.0)), listOf(LocalPoint(0.0, 0.0)), 70.0), "two places, no line, 1 m apart")
        val old = plan(LaneScenes.ia())
        val carry = LaneCarry.of(old, GORE - 1_500.0, emptyList(), plan(LaneScenes.ia(farSide = true)), GORE - 1_500.0, freeway)
        assertNotNull(carry)
        assertFalse(requireNotNull(carry).kept)
        assertNotEquals(2, carry.lane)
    }

    // ------------------------------------------------------------------ the car on each route's line (atChange)

    /** A made-up line north along longitude [east] from latitude 0: one step of points every 0.0009 degrees (about 100 m), from station [start]. */
    private fun line(east: Double, points: Int = 61, start: Double = 0.0): LaneCarry.Line =
        LaneCarry.Line.of(listOf((0 until points).map { GeoPoint(it * 0.0009, east) }), listOf(start))

    private val metre = 1.0 / 111_195.0

    @Test
    fun `the car is found on a route's line by the station of its nearest place`() {
        val north = line(0.0)
        val place = requireNotNull(north.nearest(GeoPoint(10.5 * 0.0009, 3.0 * metre)))
        assertEquals(Geo.meters(0.0, 0.0, 10.5 * 0.0009, 0.0), place.station, 0.01)
        assertEquals(3.0, place.meters, 0.01)
        // Looked for only between two stations, as on the route before near the car's last place there.
        val far = requireNotNull(north.nearest(GeoPoint(10.5 * 0.0009, 0.0), from = 3_000.0, to = 4_000.0))
        assertEquals(3_000.0, far.station, 0.01)
        assertNull(north.nearest(GeoPoint(0.0, 0.0), from = 9_000.0, to = 9_500.0), "beyond the line")
        assertNull(LaneCarry.Line.of(emptyList(), emptyList()).nearest(GeoPoint(0.0, 0.0)))
    }

    @Test
    fun `the line ahead of a station is cut where it ends, in metres of the car`() {
        val north = line(0.0, start = 1_000.0)
        val origin = LocalMeters(0.0, 0.0)
        val ahead = north.ahead(1_050.0, 120.0, origin)
        assertEquals(0.0, ahead.first().x, 1e-6)
        assertEquals(50.0, ahead.first().y, 0.01)
        assertEquals(170.0, ahead.last().y, 0.01)
        assertEquals(120.0, LocalLine.length(ahead), 0.01)
        assertTrue(north.ahead(Double.NaN, 10.0, origin).isEmpty())
        assertEquals(1, north.ahead(99_999.0, 10.0, origin).size, "past its end: its last point")
    }

    @Test
    fun `at a change of route the car is found on both lines and their heads compared`() {
        // IA again as the new route: one carriageway, so the old count from the edge is kept.
        val old = plan(LaneScenes.ia())
        val new = plan(LaneScenes.ia(farSide = true))
        val car = GeoPoint((GORE - 1_500.0) / 111_195.0 * 1.0, 0.0)
        val road = LaneCarry.Line.of(listOf((0..70).map { GeoPoint(it * 0.0009, 0.0) }), listOf(0.0))
        val same = requireNotNull(LaneCarry.atChange(old, road, null, new, road, car))
        assertTrue(same.kept)
        // The new route on a road 22 m east: another carriageway.
        val beside = LaneCarry.Line.of(listOf((0..70).map { GeoPoint(it * 0.0009, 0.0002) }), listOf(0.0))
        val other = requireNotNull(LaneCarry.atChange(old, road, null, new, beside, car))
        assertFalse(other.kept)
        // 110 m east: the car is not on the new route at all.
        val away = LaneCarry.Line.of(listOf((0..70).map { GeoPoint(it * 0.0009, 0.001) }), listOf(0.0))
        assertNull(LaneCarry.atChange(old, road, null, new, away, car))
        assertTrue(LaneCarry.NEAR_LINE_METERS < 110.0)
    }
}
