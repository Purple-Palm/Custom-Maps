package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The car map's five sizes, the one when never chosen, how each is stored and read, and the map's pixel ratio from the host's density. */
class CarMapSizeTest {

    @Test
    fun `five sizes from the car's own to twice it, x1_5 when never chosen`() {
        assertEquals(listOf(1.0, 1.25, 1.5, 1.75, 2.0), CarMapSize.entries.map { it.factor })
        assertEquals(listOf("1", "1.25", "1.5", "1.75", "2"), CarMapSize.entries.map { it.factorText })
        assertEquals(CarMapSize.X1_5, CarMapSize.DEFAULT)
        assertEquals(CarMapSize.X1, CarMapSize.entries.first(), "the size the car asks for comes first")
    }

    @Test
    fun `each size is stored as its percent and read back as itself`() {
        assertEquals(listOf(100, 125, 150, 175, 200), CarMapSize.entries.map { it.stored })
        for (size in CarMapSize.entries) assertEquals(size, CarMapSize.of(size.stored))
    }

    @Test
    fun `nothing stored and a number no size has read as x1_5`() {
        assertEquals(CarMapSize.X1_5, CarMapSize.of(null))
        // As a file from another build could hold them: near misses, the factor itself, zero and below, and far off.
        for (stored in listOf(99, 101, 124, 126, 1, 2, 15, 0, -150, 250, 1_000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(CarMapSize.X1_5, CarMapSize.of(stored), "$stored")
        }
    }

    @Test
    fun `the pixel ratio is the host's density times the size`() {
        // The car's screen at 160 dpi: the size is the ratio.
        assertEquals(listOf(1f, 1.25f, 1.5f, 1.75f, 2f), CarMapSize.entries.map { it.pixelRatio(1f) })
        // A head unit at 240 dpi and one at 320 dpi.
        assertEquals(listOf(1.5f, 1.875f, 2.25f, 2.625f, 3f), CarMapSize.entries.map { it.pixelRatio(1.5f) })
        assertEquals(listOf(2f, 2.5f, 3f, 3.5f, 4f), CarMapSize.entries.map { it.pixelRatio(2f) })
        // The car's own size is the host's density itself, as the map had it before the setting.
        assertEquals(1.33125f, CarMapSize.X1.pixelRatio(1.33125f))
    }

    @Test
    fun `a density that is not a positive number counts as 1`() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1.5f, CarMapSize.X1_5.pixelRatio(density), "$density")
            assertEquals(1.0, CarMapSize.hostDensity(density), "$density")
        }
        assertEquals(2.0, CarMapSize.hostDensity(2f))
    }
}
