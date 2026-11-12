package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
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
}
