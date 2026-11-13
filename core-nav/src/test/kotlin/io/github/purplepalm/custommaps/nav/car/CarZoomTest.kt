package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarZoomTest {

    @Test
    fun `the buttons step by half a level between 6 and 20`() {
        assertEquals(0.5, CarZoom.STEP)
        assertEquals(6.0, CarZoom.MIN)
        assertEquals(20.0, CarZoom.MAX)
        assertEquals(15.5, CarZoom.after(15.0, CarZoom.STEP))
        assertEquals(14.5, CarZoom.after(15.0, -CarZoom.STEP))
        assertEquals(20.0, CarZoom.after(19.8, CarZoom.STEP))
        assertEquals(6.0, CarZoom.after(6.2, -CarZoom.STEP))
        assertEquals(20.0, CarZoom.after(20.0, CarZoom.STEP))
    }

    @Test
    fun `from outside the limits a button goes to the nearest one`() {
        // The whole world, before the car's first position.
        assertEquals(6.0, CarZoom.after(1.0, CarZoom.STEP))
        assertEquals(20.0, CarZoom.after(21.0, -CarZoom.STEP))
    }

    @Test
    fun `a pinch that would leave the limits is dropped`() {
        assertTrue(CarZoom.scaleAllowed(15.0, 1.1))
        assertTrue(CarZoom.scaleAllowed(15.0, 0.9))
        // A double tap arrives as a scale of two: one level in.
        assertTrue(CarZoom.scaleAllowed(15.0, 2.0))
        assertFalse(CarZoom.scaleAllowed(19.95, 1.1))
        assertFalse(CarZoom.scaleAllowed(6.05, 0.9))
        assertFalse(CarZoom.scaleAllowed(19.5, 2.0))
    }

    @Test
    fun `from outside the limits a pinch toward them is passed on`() {
        assertTrue(CarZoom.scaleAllowed(1.0, 1.5))
        assertFalse(CarZoom.scaleAllowed(1.0, 0.5))
        assertTrue(CarZoom.scaleAllowed(21.0, 0.5))
        assertFalse(CarZoom.scaleAllowed(21.0, 1.5))
    }
}
