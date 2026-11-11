package io.github.purplepalm.custommaps.engine.mapbox.map

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The night colors of the ground, the buildings and the roads, as configured, by the contrast
 * measure of the web's accessibility guidelines (relative luminance). What the phone shows after
 * the night light and the theme is for the eye on the phone; the steps between them are set here.
 */
class MapPaletteTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(rgb: Int): Double =
        0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)

    private fun luminance(hex: String): Double = luminance(hex.removePrefix("#").toInt(16))

    private fun contrast(a: Double, b: Double): Double = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    @Test
    fun `streets stand out from the ground by about two to one`() {
        val ratio = contrast(luminance(MapPalette.ROADS), luminance(MapPalette.LAND))
        assertTrue(ratio in 2.0..2.5, "streets against the ground $ratio")
    }

    @Test
    fun `buildings sit one small step above the ground and below the streets`() {
        val land = luminance(MapPalette.LAND)
        val buildings = luminance(MapPalette.BUILDINGS)
        assertTrue(land < buildings && buildings < luminance(MapPalette.ROADS))
        assertTrue(contrast(buildings, land) >= 1.15, "buildings against the ground ${contrast(buildings, land)}")
    }

    @Test
    fun `water and parks lie a step below the ground`() {
        val land = luminance(MapPalette.LAND)
        for ((name, color) in listOf("water" to MapPalette.WATER, "parks" to MapPalette.GREENSPACE)) {
            val luminance = luminance(color)
            assertTrue(luminance < land && contrast(land, luminance) >= 1.1, "$name against the ground ${contrast(land, luminance)}")
        }
    }

    @Test
    fun `larger roads are lighter`() {
        assertTrue(luminance(MapPalette.ROADS) < luminance(MapPalette.TRUNKS))
        assertTrue(luminance(MapPalette.TRUNKS) < luminance(MapPalette.MOTORWAYS))
    }

    @Test
    fun `the amber route stands out from a street by four to one`() {
        val ratio = contrast(luminance(MapPalette.AMBER and 0xFFFFFF), luminance(MapPalette.ROADS))
        assertTrue(ratio >= 4.0, "amber against a street $ratio")
    }
}
