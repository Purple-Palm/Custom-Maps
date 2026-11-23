package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.car.CarMapSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The scale of the images a map takes as bitmaps: the puck's scale expression and the density the route line's end icons
 * are drawn at, for each car map size; at x1, and on the phone, nothing changes.
 */
class ImageScaleTest {

    @Test
    fun `the puck's images take the size as their scale, and at x1 the puck stays as it comes`() {
        assertNull(ImageScale.puckScaleJson(ImageScale.NONE))
        assertNull(ImageScale.puckScaleJson(CarMapSize.X1.factor))
        assertEquals(listOf(null, "1.25", "1.5", "1.75", "2.0"), CarMapSize.entries.map { ImageScale.puckScaleJson(it.factor) })
    }

    @Test
    fun `the route line's end icons are drawn at the host's density times the size`() {
        // A car at 160 dpi, the server's Desktop Head Unit profile.
        assertEquals(listOf(160, 200, 240, 280, 320), CarMapSize.entries.map { ImageScale.densityDpi(160, it.factor) })
        // A head unit at 240 dpi, and the phone's own density untouched.
        assertEquals(listOf(240, 300, 360, 420, 480), CarMapSize.entries.map { ImageScale.densityDpi(240, it.factor) })
        assertEquals(420, ImageScale.densityDpi(420, ImageScale.NONE))
        // A density that does not divide evenly rounds to the nearest whole dpi.
        assertEquals(266, ImageScale.densityDpi(213, CarMapSize.X1_25.factor))
    }
}
