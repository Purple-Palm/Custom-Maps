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
 * the shapes of the design's replay of a left turn whose step begins before the bend. Every bend is
 * looked for with the camera's own numbers, so the tests pin those too.
 */
class TurnBendTest {

    private val tuning = CameraTuning.PHONE

    private fun p(x: Double, y: Double) = LocalPoint(x, y)

    /** The bend of [points] by the camera's rule, the way in heading [approach]. */
    private fun bend(points: List<LocalPoint>, approach: Double, searchMeters: Double = tuning.bendSearchMeters): Bend? =
        TurnBend.bend(points, approach, tuning.bendDegrees, tuning.bendSideMeters, searchMeters, tuning.bendMinMeters)

    /** Straight on for [late] meters, then off to the left by [degrees] for 3 km; to the right when negative. */
    private fun veer(late: Double, degrees: Double): List<LocalPoint> {
        val a = Math.toRadians(degrees)
        return listOf(p(0.0, 0.0), p(0.0, late), p(-sin(a) * 3000.0, late + cos(a) * 3000.0))
    }

    /** Straight from the maneuver point for 3 km, [degrees] off the way in, to the left when positive. */
    private fun leave(degrees: Double): List<LocalPoint> {
        val a = Math.toRadians(degrees)
        return listOf(p(0.0, 0.0), p(-sin(a) * 3000.0, cos(a) * 3000.0))
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
        assertBend(62.0, -26.0, bend(veer(62.0, 26.0), 0.0))
    }

    @Test
    fun `the same veer nearer and farther`() {
        assertBend(45.0, -26.0, bend(veer(45.0, 26.0), 0.0))
        assertBend(90.0, -26.0, bend(veer(90.0, 26.0), 0.0))
    }

    @Test
    fun `a veer under 20 degrees bends where it is a lane to the side`() {
        // 15 degrees from 62 m on: 4 m to the side after 4 / sin 15 = 15.5 m more. The replay,
        // which looked once a meter, found it at 78 m.
        assertBend(62.0 + 4.0 / sin(Math.toRadians(15.0)), -15.0, bend(veer(62.0, 15.0), 0.0))
        // The same veer to the right.
        assertBend(62.0 + 4.0 / sin(Math.toRadians(15.0)), 15.0, bend(veer(62.0, -15.0), 0.0))
    }

    @Test
    fun `a road that leaves the maneuver point a few degrees off the way in and runs on straight has no bend`() {
        // It drifts a lane away from the line in, 4 / sin 5 = 46 m on for 5 degrees, but never
        // leaves the line it set out on.
        for (degrees in listOf(1.6, 2.0, 5.0, 10.0, 15.0, 19.0, -5.0)) {
            assertNull(bend(leave(degrees), 0.0), "$degrees degrees")
        }
    }

    @Test
    fun `a short jog or taper back alongside the way in has no bend`() {
        // A meter to the side within 8 m, then on alongside the way in: never a lane off it,
        // although it does leave the line of its own first 15 m.
        assertNull(bend(listOf(p(0.0, 0.0), p(-1.0, 8.0), p(-1.0, 3000.0)), 0.0))
        // 5 degrees for 30 m, 2.6 m to the side, then alongside the way in again.
        val a = Math.toRadians(5.0)
        assertNull(bend(listOf(p(0.0, 0.0), p(-sin(a) * 30.0, cos(a) * 30.0), p(-sin(a) * 30.0, 3000.0)), 0.0))
    }

    @Test
    fun `a slight angle at the maneuver point and a veer after it bend where the road is a lane off the way in`() {
        // 5 degrees at the point, then 10 degrees more from 62 m on: the road leaves its own line
        // there, so it bends where it is 4 m off the line in, 4 / sin 5 = 45.9 m on.
        val a = Math.toRadians(5.0)
        val b = Math.toRadians(15.0)
        val kink = p(-sin(a) * 62.0, cos(a) * 62.0)
        val road = listOf(p(0.0, 0.0), kink, p(kink.x - sin(b) * 3000.0, kink.y + cos(b) * 3000.0))
        assertBend(4.0 / sin(a), -5.0, bend(road, 0.0))
    }

    @Test
    fun `a curve bends where it has turned 20 degrees`() {
        // Radius 160 m: 20 degrees after 160 m x 0.349 = 56 m; the piece that starts there turns 20.4.
        assertBend(56.0, -Math.toDegrees(57.0 / 160.0), bend(curve(160.0), 0.0), 0.01)
    }

    @Test
    fun `a right angle 60 m on`() {
        assertBend(60.0, -90.0, bend(listOf(p(0.0, 0.0), p(0.0, 60.0), p(-3000.0, 60.0)), 0.0))
    }

    @Test
    fun `a turn at the maneuver point bends at 0 m, and so does a u-turn`() {
        assertBend(0.0, -90.0, bend(listOf(p(0.0, 0.0), p(-3000.0, 0.0)), 0.0))
        assertBend(0.0, -90.0, bend(listOf(p(0.0, 0.0), p(-13.0, 0.0), p(-13.0, -3000.0)), 0.0))
    }

    @Test
    fun `an s-curve bends where it first leaves the way in, whichever side`() {
        val s = listOf(p(0.0, 0.0), p(0.0, 40.0), p(10.0, 61.4), p(10.0, 100.0))
        assertBend(40.0, Math.toDegrees(atan2(10.0, 21.4)), bend(s, 0.0))
    }

    @Test
    fun `a straight step shorter than the search has no bend`() {
        assertNull(bend(listOf(p(0.0, 0.0), p(0.0, 100.0)), 0.0))
        assertNull(bend(listOf(p(0.0, 0.0)), 0.0))
        assertNull(bend(emptyList(), 0.0))
    }

    @Test
    fun `a bend past the search is not looked for`() {
        assertNull(bend(veer(160.0, 26.0), 0.0))
        assertNull(bend(veer(140.0, 15.0), 0.0))
        // The same veer within a longer search.
        assertBend(160.0, -26.0, bend(veer(160.0, 26.0), 0.0, searchMeters = 200.0))
    }

    @Test
    fun `the search reaches 150 m past the maneuver point and no farther`() {
        assertBend(150.0, -26.0, bend(veer(150.0, 26.0), 0.0))
        assertNull(bend(veer(150.5, 26.0), 0.0))
    }

    @Test
    fun `the way in can point anywhere`() {
        // The screenshot's shape turned to come in heading 117 degrees.
        val turn = Math.toRadians(117.0)
        fun rotate(q: LocalPoint) = p(q.x * cos(turn) + q.y * sin(turn), -q.x * sin(turn) + q.y * cos(turn))
        assertBend(62.0, -26.0, bend(veer(62.0, 26.0).map(::rotate), 117.0), 1e-6)
        // And the veer found to the side.
        assertBend(62.0 + 4.0 / sin(Math.toRadians(15.0)), -15.0, bend(veer(62.0, 15.0).map(::rotate), 117.0), 1e-6)
    }

    @Test
    fun `repeated points and points a hand apart do not count as bends`() {
        val veer = veer(62.0, 26.0)
        val noisy = listOf(p(0.0, 0.0), p(0.0, 0.0), p(0.1, 0.2), p(0.0, 30.0), p(0.0, 30.0), veer[1], veer[2])
        assertBend(62.0, -26.0, bend(noisy, 0.0))
    }
}
