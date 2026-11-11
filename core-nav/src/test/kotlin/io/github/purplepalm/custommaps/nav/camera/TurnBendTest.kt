package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Made-up roads in local meters, the maneuver point at 0, 0 and the car coming in heading north:
 * the shapes of the design's replay of a left turn whose step begins before the bend.
 */
class TurnBendTest {

    private fun p(x: Double, y: Double) = LocalPoint(x, y)

    /** Straight on for [late] meters, then off to the left by [degrees] for 3 km. */
    private fun veer(late: Double, degrees: Double): List<LocalPoint> {
        val a = Math.toRadians(degrees)
        return listOf(p(0.0, 0.0), p(0.0, late), p(-sin(a) * 3000.0, late + cos(a) * 3000.0))
    }

    /** A curve to the left of [radius] meters from the maneuver point, a point every 2 m, as the replay drew it. */
    private fun curve(radius: Double): List<LocalPoint> {
        val points = arrayListOf(p(0.0, 0.0))
        var s = 2.0
        while (s < radius * PI / 2) {
            points += p(-radius + radius * cos(s / radius), radius * sin(s / radius))
            s += 2.0
        }
        return points
    }

    private fun assertBend(meters: Double, degrees: Double, bend: Bend?, tolerance: Double = 1e-6) {
        assertNotNull(bend)
        assertEquals(meters, bend!!.meters, tolerance, "meters")
        assertEquals(degrees, bend.degrees, tolerance, "degrees")
    }

    @Test
    fun `the left turn of the screenshot bends 26 degrees 62 m past the maneuver point`() {
        assertBend(62.0, -26.0, TurnBend.bend(veer(62.0, 26.0), 0.0))
    }

    @Test
    fun `the same veer nearer and farther`() {
        assertBend(45.0, -26.0, TurnBend.bend(veer(45.0, 26.0), 0.0))
        assertBend(90.0, -26.0, TurnBend.bend(veer(90.0, 26.0), 0.0))
    }

    @Test
    fun `a veer under 20 degrees bends where it is a lane to the side`() {
        // 15 degrees from 62 m on: 4 m to the side after 4 / sin 15 = 15.5 m more. The replay,
        // which looked once a meter, found it at 78 m.
        assertBend(62.0 + 4.0 / sin(Math.toRadians(15.0)), -15.0, TurnBend.bend(veer(62.0, 15.0), 0.0))
    }

    @Test
    fun `a curve bends where it has turned 20 degrees`() {
        // Radius 160 m: 20 degrees after 160 m x 0.349 = 56 m; the piece that starts there turns 20.4.
        assertBend(56.0, -Math.toDegrees(57.0 / 160.0), TurnBend.bend(curve(160.0), 0.0), 0.01)
    }

    @Test
    fun `a right angle 60 m on`() {
        assertBend(60.0, -90.0, TurnBend.bend(listOf(p(0.0, 0.0), p(0.0, 60.0), p(-3000.0, 60.0)), 0.0))
    }

    @Test
    fun `a turn at the maneuver point bends at 0 m, and so does a u-turn`() {
        assertBend(0.0, -90.0, TurnBend.bend(listOf(p(0.0, 0.0), p(-3000.0, 0.0)), 0.0))
        assertBend(0.0, -90.0, TurnBend.bend(listOf(p(0.0, 0.0), p(-13.0, 0.0), p(-13.0, -3000.0)), 0.0))
    }

    @Test
    fun `an s-curve bends where it first leaves the way in, whichever side`() {
        val s = listOf(p(0.0, 0.0), p(0.0, 40.0), p(10.0, 61.4), p(10.0, 100.0))
        assertBend(40.0, Math.toDegrees(atan2(10.0, 21.4)), TurnBend.bend(s, 0.0))
    }

    @Test
    fun `a straight step shorter than the search has no bend`() {
        assertNull(TurnBend.bend(listOf(p(0.0, 0.0), p(0.0, 100.0)), 0.0))
        assertNull(TurnBend.bend(listOf(p(0.0, 0.0)), 0.0))
        assertNull(TurnBend.bend(emptyList(), 0.0))
    }

    @Test
    fun `a bend past the search is not looked for`() {
        assertNull(TurnBend.bend(veer(160.0, 26.0), 0.0))
        assertNull(TurnBend.bend(veer(140.0, 15.0), 0.0))
        // The same veer within a longer search.
        assertBend(160.0, -26.0, TurnBend.bend(veer(160.0, 26.0), 0.0, searchMeters = 200.0))
    }

    @Test
    fun `the way in can point anywhere`() {
        // The screenshot's shape turned to come in heading 117 degrees.
        val turn = Math.toRadians(117.0)
        fun rotate(q: LocalPoint) = p(q.x * cos(turn) + q.y * sin(turn), -q.x * sin(turn) + q.y * cos(turn))
        assertBend(62.0, -26.0, TurnBend.bend(veer(62.0, 26.0).map(::rotate), 117.0), 1e-6)
    }

    @Test
    fun `repeated points and points a hand apart do not count as bends`() {
        val veer = veer(62.0, 26.0)
        val noisy = listOf(p(0.0, 0.0), p(0.0, 0.0), p(0.1, 0.2), p(0.0, 30.0), p(0.0, 30.0), veer[1], veer[2])
        assertBend(62.0, -26.0, TurnBend.bend(noisy, 0.0))
    }
}
