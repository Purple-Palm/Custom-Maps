package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * U-turns on made-up roads in local meters: the maneuver point at 0, 0, the car coming up from the
 * south. On a divided road the route goes up to the median opening, across it and back down the far
 * side.
 */
class UTurnArrowTest {

    private fun p(x: Double, y: Double) = LocalPoint(x, y)

    /** The near side of the road, up to the maneuver point. */
    private val approach = listOf(p(0.0, -100.0), p(0.0, 0.0))

    /** Across an opening [width] meters wide, to the left, and back down the far side. */
    private fun divided(width: Double, side: Double = -1.0) = listOf(p(0.0, 0.0), p(side * width, 0.0), p(side * width, -100.0))

    private fun assertNear(expected: LocalPoint, actual: LocalPoint, tolerance: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tolerance, "x of $actual")
        assertEquals(expected.y, actual.y, tolerance, "y of $actual")
    }

    /** The compass bearing of the arrow's last piece, which its head points along. */
    private fun headBearing(arrow: List<LocalPoint>) = LocalLine.bearing(arrow[arrow.size - 2], arrow.last())

    /** The sharpest change of direction from one piece of the arrow to the next, in degrees. */
    private fun sharpestBend(arrow: List<LocalPoint>): Double = (1 until arrow.size - 1).maxOf { i ->
        val turn = LocalLine.bearing(arrow[i], arrow[i + 1]) - LocalLine.bearing(arrow[i - 1], arrow[i])
        abs(((turn + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)
    }

    private fun closest(arrow: List<LocalPoint>, to: LocalPoint) = arrow.minOf { it.distanceTo(to) }

    @Test
    fun `a 13 m opening becomes one arc of half its width`() {
        val arrow = UTurnArrow.shape(approach, divided(13.0))
        assertNotNull(arrow)
        arrow!!
        // From 30 m before the maneuver point to 30 m past it: 13 m across and 17 m back down.
        assertNear(p(0.0, -30.0), arrow.first())
        assertNear(p(-13.0, -17.0), arrow.last())
        // The arc of radius 6.5 m: every point above its center is on it, the top in the middle.
        val center = p(-6.5, -6.5)
        arrow.filter { it.y > center.y + 1e-9 }.forEach { assertEquals(6.5, it.distanceTo(center), 1e-6) }
        assertEquals(0.0, closest(arrow, p(-6.5, 0.0)), 1e-6)
        // The two corners of the road lie r(sqrt 2 - 1), about 2.7 m, outside the arrow.
        assertEquals(6.5 * (Math.sqrt(2.0) - 1.0), closest(arrow, p(0.0, 0.0)), 0.2)
        assertEquals(6.5 * (Math.sqrt(2.0) - 1.0), closest(arrow, p(-13.0, 0.0)), 0.2)
        // No corner is left: the arrow bends a little at each of its points, and its head points back down.
        assertTrue(sharpestBend(arrow) <= 10.5, "sharpest bend ${sharpestBend(arrow)}")
        assertEquals(180.0, headBearing(arrow), 1e-6)
    }

    @Test
    fun `a 6 m opening becomes a tighter arc`() {
        val arrow = UTurnArrow.shape(approach, divided(6.0))!!
        val center = p(-3.0, -3.0)
        arrow.filter { it.y > center.y + 1e-9 }.forEach { assertEquals(3.0, it.distanceTo(center), 1e-6) }
        assertNear(p(-6.0, -24.0), arrow.last())
        assertEquals(180.0, headBearing(arrow), 1e-6)
    }

    @Test
    fun `a 30 m opening keeps its middle straight and still ends going back`() {
        val arrow = UTurnArrow.shape(approach, divided(30.0))!!
        // The radius stops at 8 m; between the two arcs the arrow runs straight across.
        arrow.filter { it.y > -8.0 + 1e-9 && it.x > -8.0 }.forEach { assertEquals(8.0, it.distanceTo(p(-8.0, -8.0)), 1e-6) }
        arrow.filter { it.y > -8.0 + 1e-9 && it.x < -22.0 }.forEach { assertEquals(8.0, it.distanceTo(p(-22.0, -8.0)), 1e-6) }
        val leaves = arrow.indexOfFirst { it.distanceTo(p(-8.0, 0.0)) < 1e-6 }
        assertTrue(leaves > 0)
        assertNear(p(-22.0, 0.0), arrow[leaves + 1])
        // 30 m past the maneuver point is the far corner itself: the arrow goes on 10 m past it,
        // so its head points back down the road.
        assertNear(p(-30.0, -10.0), arrow.last())
        assertEquals(180.0, headBearing(arrow), 1e-6)
    }

    @Test
    fun `an undivided u-turn has no way across and stays as it is`() {
        // The route turns back on itself at one point, or within a meter.
        assertNull(UTurnArrow.shape(approach, listOf(p(0.0, 0.0), p(0.0, -100.0))))
        assertNull(UTurnArrow.shape(approach, listOf(p(0.0, 0.0), p(-1.0, 0.0), p(-1.0, -100.0))))
    }

    @Test
    fun `corners too far apart are two turns, not one u`() {
        assertNull(UTurnArrow.shape(approach, divided(41.0)))
    }

    @Test
    fun `openings from 4 to 40 m wide are one u, and a narrower one is not`() {
        assertNull(UTurnArrow.shape(approach, divided(3.0)))
        // 4 m: an arc of 2 m around a center 2 m across and 2 m down.
        val narrow = UTurnArrow.shape(approach, divided(4.0))!!
        val center = p(-2.0, -2.0)
        narrow.filter { it.y > center.y + 1e-9 }.forEach { assertEquals(2.0, it.distanceTo(center), 1e-6) }
        assertNotNull(UTurnArrow.shape(approach, divided(40.0)))
    }

    @Test
    fun `a road that turns back by less than 135 degrees is no u`() {
        // Two left corners of 60 degrees, 13 m apart: it turns back by 120 degrees.
        val bend = p(-sin(Math.toRadians(60.0)) * 13.0, cos(Math.toRadians(60.0)) * 13.0)
        val onward = listOf(p(0.0, 0.0), bend, p(bend.x + sin(Math.toRadians(240.0)) * 100.0, bend.y + cos(Math.toRadians(240.0)) * 100.0))
        assertNull(UTurnArrow.shape(approach, onward))
    }

    @Test
    fun `a step that ends right after the far corner leaves no room for a curve`() {
        // 1.5 m past the far corner: a curve would have a radius of half a meter.
        assertNull(UTurnArrow.shape(approach, listOf(p(0.0, 0.0), p(-13.0, 0.0), p(-13.0, -1.5))))
    }

    @Test
    fun `a slight veer before the opening is not taken for a corner`() {
        // 40 degrees left at the maneuver point, then two corners of 70 degrees 13 m apart, back
        // down the far side: the U is the two sharp corners.
        fun step(from: LocalPoint, bearing: Double, meters: Double) =
            p(from.x + sin(Math.toRadians(bearing)) * meters, from.y + cos(Math.toRadians(bearing)) * meters)
        val veer = step(p(0.0, 0.0), 320.0, 10.0)
        val far = step(veer, 250.0, 13.0)
        val arrow = UTurnArrow.shape(approach, listOf(p(0.0, 0.0), veer, far, step(far, 180.0, 100.0)))
        assertNotNull(arrow)
        assertEquals(180.0, headBearing(arrow!!), 1e-6)
    }

    @Test
    fun `with traffic on the left the u turns right, the mirror of the same arrow`() {
        val left = UTurnArrow.shape(approach, divided(13.0))!!
        val right = UTurnArrow.shape(approach, divided(13.0, side = 1.0))!!
        assertEquals(left.size, right.size)
        left.zip(right).forEach { (l, r) -> assertNear(p(-l.x, l.y), r, 1e-9) }
        assertEquals(180.0, headBearing(right), 1e-6)
    }

    @Test
    fun `a plain turn and a road that bends back the other way are no u-turn`() {
        assertNull(UTurnArrow.shape(approach, listOf(p(0.0, 0.0), p(-100.0, 0.0))))
        // Left across, then right: an S, not a U.
        assertNull(UTurnArrow.shape(approach, listOf(p(0.0, 0.0), p(-13.0, 0.0), p(-13.0, 100.0))))
    }

    @Test
    fun `a u that begins beyond the arrow's reach is left alone`() {
        val late = listOf(p(0.0, 0.0), p(0.0, 35.0), p(-13.0, 35.0), p(-13.0, -100.0))
        assertNull(UTurnArrow.shape(approach, late))
    }

    @Test
    fun `corners that are not right angles still make one smooth curve`() {
        // As Mapbox's own example of a divided-road u-turn: a left of about 101 degrees across
        // the median, 13 m, and a left of about 82 degrees back down the far side.
        fun dir(bearing: Double) = p(sin(Math.toRadians(bearing)), cos(Math.toRadians(bearing)))
        val inbound = dir(227.0)
        val across = dir(126.0)
        val back = dir(44.0)
        val before = listOf(p(-inbound.x * 100.0, -inbound.y * 100.0), p(0.0, 0.0))
        val corner = p(across.x * 13.0, across.y * 13.0)
        val onward = listOf(p(0.0, 0.0), corner, p(corner.x + back.x * 100.0, corner.y + back.y * 100.0))
        val arrow = UTurnArrow.shape(before, onward)!!
        assertTrue(sharpestBend(arrow) <= 10.5, "sharpest bend ${sharpestBend(arrow)}")
        assertTrue(closest(arrow, p(0.0, 0.0)) > 1.5)
        assertTrue(closest(arrow, corner) > 1.5)
        assertEquals(44.0, headBearing(arrow), 1e-6)
        // From 30 m before the maneuver point to 30 m past it.
        assertNear(p(-inbound.x * 30.0, -inbound.y * 30.0), arrow.first())
        assertNear(p(corner.x + back.x * 17.0, corner.y + back.y * 17.0), arrow.last())
    }

    @Test
    fun `points that repeat or lie a hand apart change nothing`() {
        val noisy = listOf(p(0.0, -100.0), p(0.0, -40.0), p(0.0, -40.0), p(0.0, -0.2), p(0.0, 0.0))
        assertEquals(UTurnArrow.shape(approach, divided(13.0)), UTurnArrow.shape(noisy, divided(13.0)))
    }
}
