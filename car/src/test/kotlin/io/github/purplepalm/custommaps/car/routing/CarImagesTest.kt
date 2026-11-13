package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The colours of the car's own images, their edges, and the cache that draws each image once. */
class CarImagesTest {

    @Test
    fun `amber for what the driver does now in both looks, with the route line's casing of each`() {
        val day = CarImageColors.of(Look.DAY)
        val night = CarImageColors.of(Look.NIGHT)
        assertEquals(CarInk(0xFFF5A524.toInt(), 0xFF5C3B00.toInt()), day.lit)
        assertEquals(CarInk(0xFFF5A524.toInt(), 0xFF3A2800.toInt()), night.lit)
        // The other lanes: a lighter grey by day, for the sun, the phone's night grey at night.
        assertEquals(0xFF9AA1AB.toInt(), day.dim.fill)
        assertEquals(0xFF6E747C.toInt(), night.dim.fill)
        // The Then step in the card's ink.
        assertEquals(0xFFE5E4E2.toInt(), day.then.fill)
        assertNotEquals(day, night)
        for (colors in listOf(day, night)) {
            for (ink in listOf(colors.lit, colors.dim, colors.then)) assertNotEquals(ink.fill, ink.edge)
        }
    }

    @Test
    fun `the edge is one and a half dp, thinner on the narrow lanes of a wide road`() {
        assertEquals(1.5f, CarImageColors.edgePx(density = 1f, side = 128f))
        assertEquals(3f, CarImageColors.edgePx(density = 2f, side = 256f))
        assertEquals(0.03f * 30f, CarImageColors.edgePx(density = 1f, side = 30f), 1e-6f)
    }

    @Test
    fun `an image is drawn once for what it shows, the newest few kept`() {
        val cache = ImageCache<String, Any>(capacity = 2)
        val first = cache.get("a") { Any() }
        assertSame(first, cache.get("a") { error("drawn again") })
        cache.get("b") { Any() }
        // "a" was used after "b" came, so "b" is the one to go when "c" comes.
        cache.get("a") { error("drawn again") }
        cache.get("c") { Any() }
        assertEquals(2, cache.size)
        assertSame(first, cache.get("a") { error("drawn again") })
        var drawnAgain = false
        cache.get("b") { drawnAgain = true; Any() }
        assertEquals(true, drawnAgain)
        assertEquals(4, cache.drawn)
    }

    @Test
    fun `an image that could not be drawn is not kept`() {
        val cache = ImageCache<String, Any>(capacity = 2)
        assertNull(cache.get("a") { null })
        assertEquals(0, cache.size)
        assertEquals(0, cache.drawn)
    }
}
