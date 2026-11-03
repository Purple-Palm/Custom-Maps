package io.github.purplepalm.custommaps.nav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DistanceTest {

    @Test
    fun `one mile is 5280 feet`() {
        assertEquals(5280.0, Distance.miles(1.0).feet, 1e-9)
    }

    @Test
    fun `feet and meters convert both ways`() {
        val distance = Distance.feet(1000.0)
        assertEquals(304.8, distance.meters, 1e-9)
        assertEquals(1000.0, Distance.meters(distance.meters).feet, 1e-9)
    }

    @Test
    fun `quarter mile in meters`() {
        assertEquals(402.336, Distance.miles(0.25).meters, 1e-9)
    }

    @Test
    fun `distances add up and compare`() {
        val sum = Distance.meters(100.0) + Distance.meters(250.0)
        assertEquals(350.0, sum.meters, 1e-9)
        assertTrue(Distance.feet(1000.0) > Distance.meters(300.0))
        assertTrue(Distance.ZERO < Distance.feet(1.0))
    }

    @Test
    fun `negative or non-finite distances are rejected`() {
        assertThrows<IllegalArgumentException> { Distance.meters(-1.0) }
        assertThrows<IllegalArgumentException> { Distance.meters(Double.NaN) }
        assertThrows<IllegalArgumentException> { Distance.miles(Double.POSITIVE_INFINITY) }
    }
}
