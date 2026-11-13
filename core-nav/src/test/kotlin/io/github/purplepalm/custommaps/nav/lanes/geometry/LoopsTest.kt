package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.tan

/** Lines of the drawing without the loops of a curve too tight for them ([Loops]). */
class LoopsTest {

    private fun p(x: Double, y: Double) = LocalPoint(x, y)

    private fun cut(points: List<LocalPoint>, stations: List<Double>) = Loops.cut(points, stations, { it }, { it })

    @Test
    fun `a line that turns back and crosses itself is cut at the crossing, which it reaches twice`() {
        // East along y = 0 past x = 0, back to the south-west, north along x = 0: the first and the third pieces cross at 0, 0.
        val line = listOf(p(-5.0, 0.0), p(1.0, 0.0), p(0.0, -1.0), p(0.0, 5.0))
        val stations = listOf(100.0, 106.0, 107.0, 113.0)
        // It turns back at both ends of the second piece.
        assertEquals(listOf(1, 2), Loops.turnsBack(line))
        assertNotNull(DrawingProbe.selfCrossing(line, closed = false))
        val (points, at) = cut(line, stations)
        assertEquals(listOf(line[0], p(0.0, 0.0), p(0.0, 0.0), line[3]), points)
        // Reached five sixths along the first piece and a sixth along the third.
        assertEquals(listOf(100.0, 105.0, 108.0, 113.0), at)
        assertNull(DrawingProbe.selfCrossing(points, closed = false))
    }

    @Test
    fun `a line that does not turn back, or does without crossing itself, is left as it is`() {
        val straight = listOf(p(0.0, 0.0), p(10.0, 0.0), p(20.0, 1.0), p(30.0, 3.0))
        val stations = listOf(0.0, 10.0, 20.0, 30.0)
        val same = cut(straight, stations)
        assertSame(straight, same.first)
        assertSame(stations, same.second)
        assertEquals(emptyList<Int>(), Loops.turnsBack(straight))
        // A hairpin turns back and never crosses itself.
        val hairpin = listOf(p(0.0, 0.0), p(10.0, 0.0), p(9.0, 1.0), p(0.0, 1.0))
        assertEquals(listOf(1), Loops.turnsBack(hairpin))
        assertSame(hairpin, cut(hairpin, stations).first)
    }

    @Test
    fun `a loop is cut only where it closes within its stations' limit`() {
        val line = listOf(p(-5.0, 0.0), p(1.0, 0.0), p(0.0, -1.0), p(0.0, 5.0))
        assertEquals(60.0, Loops.LOOP_METERS)
        val within = cut(line, listOf(0.0, 1.0, 2.0, Loops.LOOP_METERS)).first
        assertEquals(4, within.size)
        assertEquals(p(0.0, 0.0), within[1])
        val beyond = listOf(0.0, 1.0, 2.0, Loops.LOOP_METERS + 0.01)
        assertSame(line, cut(line, beyond).first)
    }

    @Test
    fun `an offset farther out than a fillet's radius is cut where its two straight sides meet`() {
        // A right angle to the left, its arc 1.2 m wide (half a metre from the vertex at most); the offset 3 m to the left
        // runs past the arc's centre and back. Cut, it turns where the offsets of the two straight sides meet: 3 m before the
        // corner along the first side, 3 m out.
        val axis = RoadAxis.of(listOf(p(-50.0, 0.0), p(0.0, 0.0), p(0.0, 50.0)).map { SceneLines.geo(it) }, doubleArrayOf(0.0, 50.0, 100.0))
        val fillet = axis.fillets.single()
        assertTrue(fillet.radius < 3.0)
        val stations = axis.stations().toList()
        val offset = stations.map { axis.offset(it, 3.0) }
        assertNotNull(DrawingProbe.selfCrossing(offset, closed = false))
        val (points, at) = cut(offset, stations)
        assertNull(DrawingProbe.selfCrossing(points, closed = false))
        val corner = points.indices.filter { k -> k > 0 && points[k] == points[k - 1] }.single()
        val x = points[corner]
        val vertex = axis.toLocal(SceneLines.geo(p(0.0, 0.0)))
        assertEquals(-3.0, x.x - vertex.x, 1e-6)
        assertEquals(3.0, x.y - vertex.y, 1e-6)
        // At the stations of the straight sides' points there: 3 m before the vertex and 3 m after it.
        assertEquals(50.0 - 3.0 * tan(Math.toRadians(45.0)), at[corner - 1], 1e-6)
        assertEquals(50.0 + 3.0, at[corner], 1e-6)
    }

    @Test
    fun `a ring that crosses itself is found, and made simple by cutting off the smaller part`() {
        val square = listOf(p(0.0, 0.0), p(4.0, 0.0), p(4.0, 1.0), p(0.0, 1.0), p(0.0, 0.0))
        assertNull(Loops.ringCrossing(square))
        assertSame(square, Loops.cutRing(square))
        // Its two long sides drawn crossed: a bow.
        val bow = listOf(p(0.0, 0.0), p(4.0, 0.0), p(0.0, 1.0), p(4.0, 1.0), p(0.0, 0.0))
        assertEquals(1 to 3, Loops.ringCrossing(bow))
        val simple = Loops.cutRing(bow)
        assertNull(Loops.ringCrossing(simple))
        assertEquals(simple.first(), simple.last())
        assertTrue(simple.contains(p(2.0, 0.5)))
        // A loop of a few centimetres on one side of a long ring is the part cut away.
        val looped = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.05, -0.05), p(9.95, -0.05), p(20.0, 0.0), p(20.0, 3.0), p(0.0, 3.0), p(0.0, 0.0))
        assertNotNull(Loops.ringCrossing(looped))
        val cleaned = Loops.cutRing(looped)
        assertNull(Loops.ringCrossing(cleaned))
        assertTrue(cleaned.containsAll(listOf(p(20.0, 3.0), p(0.0, 3.0))))
    }
}
