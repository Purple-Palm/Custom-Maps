package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Made-up next steps in local meters, from the maneuver point at 0, 0, the car coming in heading north. */
class JunctionFrameTest {

    private val tuning = CameraTuning.PHONE

    /** The SDK frames a next step of at most this length whole. */
    private val coalesce = 150.0

    private fun p(x: Double, y: Double) = LocalPoint(x, y)

    /** Straight on for [late] meters, then [degrees] to the left for 3 km. */
    private fun veer(late: Double, degrees: Double): List<LocalPoint> {
        val a = Math.toRadians(degrees)
        return listOf(p(0.0, 0.0), p(0.0, late), p(-sin(a) * 3000.0, late + cos(a) * 3000.0))
    }

    private fun plan(road: List<LocalPoint>, mode: LaneZoom = LaneZoom.LARGE, stepMeters: Double = LocalLine.length(road)) =
        JunctionFrame.plan(mode, road, 0.0, stepMeters, coalesce, tuning)

    @Test
    fun `default adds nothing and looks for no bend`() {
        val plan = plan(veer(62.0, 26.0), LaneZoom.DEFAULT)
        assertNull(plan.bend)
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun `a turn at the maneuver point is in the frame already`() {
        val plan = plan(listOf(p(0.0, 0.0), p(-3000.0, 0.0)))
        assertEquals(0.0, plan.bend!!.meters)
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun `the screenshot's turn adds the road to 30 m past the bend, ending at the maneuver point`() {
        val road = veer(62.0, 26.0)
        val plan = plan(road)
        assertEquals(62.0, plan.bend!!.meters, 1e-9)
        // The last point is the maneuver point, so the frame's bearing stays on the way in.
        assertEquals(p(0.0, 0.0), plan.points.last())
        // The first is 30 m past the bend, on the road after it.
        val past = LocalLine.pointAt(road, 62.0 + tuning.largeMetersAfterManeuver)
        assertEquals(past.x, plan.points.first().x, 1e-9)
        assertEquals(past.y, plan.points.first().y, 1e-9)
        // In between, the road itself, backwards.
        assertEquals(listOf(past, p(0.0, 62.0), p(0.0, 0.0)), plan.points)
    }

    @Test
    fun `a next step the sdk frames whole needs nothing more`() {
        // A right angle 60 m on, in a step of 120 m: the SDK frames the step and 30 m of the next.
        val road = listOf(p(0.0, 0.0), p(0.0, 60.0), p(-60.0, 60.0))
        val plan = plan(road, stepMeters = 120.0)
        assertNotNull(plan.bend)
        assertTrue(plan.points.isEmpty())
        // The same road in a longer step gets the points.
        assertEquals(p(0.0, 0.0), plan(road, stepMeters = 151.0).points.last())
    }

    @Test
    fun `a bend under 15 m past the maneuver point is in the frame already`() {
        assertTrue(plan(veer(14.0, 26.0)).points.isEmpty())
        assertEquals(p(0.0, 0.0), plan(veer(15.0, 26.0)).points.last())
    }

    @Test
    fun `a road with no bend within the search adds nothing`() {
        val plan = plan(listOf(p(0.0, 0.0), p(0.0, 3000.0)))
        assertNull(plan.bend)
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun `a road that leaves the maneuver point a few degrees off and runs on adds nothing`() {
        val a = Math.toRadians(5.0)
        val plan = plan(listOf(p(0.0, 0.0), p(-sin(a) * 3000.0, cos(a) * 3000.0)))
        assertNull(plan.bend)
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun `the bend comes with the tuning's own numbers`() {
        // With 30 degrees the screenshot's 26 degree veer is found only where it is 4 m to the side.
        val wider = tuning.copy(bendDegrees = 30.0)
        val plan = JunctionFrame.plan(LaneZoom.LARGE, veer(62.0, 26.0), 0.0, 3000.0, coalesce, wider)
        assertEquals(62.0 + 4.0 / sin(Math.toRadians(26.0)), plan.bend!!.meters, 1e-9)
    }
}
