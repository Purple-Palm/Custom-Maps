package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalLine
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A made-up step near 0, 0: north from the origin for about 333 m, a point every 0.0003 degrees (about 33 m). */
class StepShapesTest {

    private val step = (0..10).map { Point.fromLngLat(0.0, it * 0.0003) }
    private val local = LocalMeters(0.0, 0.0)
    private val spacing = Geo.meters(0.0, 0.0, 0.0003, 0.0)

    @Test
    fun `the head reaches as far as asked and one point more`() {
        val head = StepShapes.head(step, local, 50.0)
        // 0, 33 and 67 m: the last one is the first at or past 50 m.
        assertEquals(3, head.size)
        assertEquals(2 * spacing, LocalLine.length(head), 1e-6)
    }

    @Test
    fun `the tail ends with the step's last point, in the step's order`() {
        val tail = StepShapes.tail(step, local, 50.0)
        assertEquals(3, tail.size)
        assertEquals(local.toLocal(step[10].latitude(), 0.0), tail.last())
        assertEquals(local.toLocal(step[8].latitude(), 0.0), tail.first())
    }

    @Test
    fun `a step shorter than asked for comes whole`() {
        assertEquals(step.size, StepShapes.head(step, local, 1_000.0).size)
        assertEquals(step.size, StepShapes.tail(step, local, 1_000.0).size)
    }

    @Test
    fun `a point goes back to the map where it came from`() {
        val point = Point.fromLngLat(0.0007, 0.0011)
        val back = StepShapes.toPoint(StepShapes.head(listOf(point), local, 1.0).single(), local)
        assertEquals(point.latitude(), back.latitude(), 1e-12)
        assertEquals(point.longitude(), back.longitude(), 1e-12)
    }
}
