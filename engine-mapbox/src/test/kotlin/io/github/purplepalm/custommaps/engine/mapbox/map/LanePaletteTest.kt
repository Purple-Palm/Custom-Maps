package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.look.Look
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The colours of the app's own lanes in both looks (the own lanes' design 2.4), measured with the contrast formula of
 * the web's accessibility guidelines (relative luminance), as the design's table gives them to two decimals. What the
 * phone shows is for the eye; the steps between the colours are set here.
 */
class LanePaletteTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Int): Double =
        0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [fg] laid over [bg] at [share], each channel rounded, opaque. */
    private fun over(fg: Int, bg: Int, share: Double): Int {
        fun channel(shift: Int) = Math.round((fg shr shift and 0xFF) * share + (bg shr shift and 0xFF) * (1.0 - share)).toInt() shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private val night = MapPalette.lanes(Look.NIGHT)
    private val day = MapPalette.lanes(Look.DAY)

    private fun assertContrast(expected: Double, a: Int, b: Int, what: String) =
        assertEquals(expected, contrast(a, b), 0.005, what)

    @Test
    fun `the drawn road is the road of lane details in each look, and the lanes' colours are the route's`() {
        assertEquals(0xFF4A4F57.toInt(), night.asphalt)
        assertEquals(0xFF575C64.toInt(), day.asphalt)
        assertEquals(MapPalette.argbOf(MapPalette.HD_ROADS_NIGHT), night.asphalt)
        assertEquals(MapPalette.argbOf(MapPalette.HD_ROADS_DAY), day.asphalt)
        for (colours in listOf(night, day)) {
            assertEquals(MapPalette.AMBER, colours.lit)
            assertEquals(MapPalette.GRAPHITE, colours.arrowEdge)
            // Jams on the dark drawn road are the night's colours by day too.
            assertEquals(MapPalette.TRAFFIC_MODERATE, colours.moderate)
            assertEquals(MapPalette.TRAFFIC_HEAVY_NIGHT, colours.heavy)
            assertEquals(MapPalette.TRAFFIC_SEVERE_NIGHT, colours.severe)
            assertEquals(MapPalette.CLOSED_NIGHT, colours.closed)
        }
        assertEquals(MapPalette.CASING_NIGHT, night.litEdge)
        assertEquals(MapPalette.CASING_DAY, day.litEdge)
        // No pure white at night.
        assertEquals(MapPalette.PLATINUM, night.white)
        assertEquals(0xFFFFFFFF.toInt(), day.white)
        assertEquals(0xFFC9BD7C.toInt(), night.yellow)
        assertEquals(0xFFD6C886.toInt(), day.yellow)
    }

    @Test
    fun `a tinted lane is the amber over the drawn road, 30 percent at night and 38 by day`() {
        assertEquals(0xFF7D6948.toInt(), night.tint)
        assertEquals(0xFF93784C.toInt(), day.tint)
        assertEquals(over(MapPalette.AMBER, night.asphalt, MapPalette.TINT_SHARE_NIGHT), night.tint)
        assertEquals(over(MapPalette.AMBER, day.asphalt, MapPalette.TINT_SHARE_DAY), day.tint)
    }

    @Test
    fun `a kept lane is the drawn road a quarter darker`() {
        assertEquals(0.25, MapPalette.KEPT_LANE_DARKER)
        assertEquals(0xFF383B41.toInt(), night.keptLane)
        assertEquals(0xFF41454B.toInt(), day.keptLane)
        // Darker than the road beside it, and its white line stands out on it more than on the road.
        for (colours in listOf(night, day)) {
            assertTrue(luminance(colours.keptLane) < luminance(colours.asphalt))
            assertTrue(contrast(colours.white, colours.keptLane) > contrast(colours.white, colours.asphalt))
        }
    }

    @Test
    fun `the night table`() {
        val c = night
        assertContrast(6.49, c.white, c.asphalt, "white paint on the road")
        assertContrast(4.34, c.yellow, c.asphalt, "yellow paint on the road")
        assertContrast(4.04, c.lit, c.asphalt, "the lit lane on the road")
        assertContrast(1.56, c.tint, c.asphalt, "a tint on the road")
        assertContrast(2.58, c.lit, c.tint, "the lit lane against a tinted lane")
        assertContrast(6.94, c.litEdge, c.lit, "the lit lane's edge against it")
        assertContrast(1.61, c.white, c.lit, "white paint on the lit lane")
        assertContrast(9.34, c.arrowEdge, c.lit, "an arrow's graphite edge on the lit lane")
        assertContrast(3.16, c.moderate, c.asphalt, "moderate on the road")
        assertContrast(2.95, c.heavy, c.asphalt, "heavy on the road")
        assertContrast(2.86, c.severe, c.asphalt, "severe on the road")
        assertContrast(5.07, c.heavy, c.litEdge, "heavy against the lit lane's edge")
        assertContrast(4.91, c.severe, c.litEdge, "severe against the lit lane's edge")
    }

    @Test
    fun `the day table`() {
        val c = day
        assertContrast(6.73, c.white, c.asphalt, "white paint on the road")
        assertContrast(4.00, c.yellow, c.asphalt, "yellow paint on the road")
        assertContrast(3.30, c.lit, c.asphalt, "the lit lane on the road")
        assertContrast(1.61, c.tint, c.asphalt, "a tint on the road")
        assertContrast(2.05, c.lit, c.tint, "the lit lane against a tinted lane")
        assertContrast(4.95, c.litEdge, c.lit, "the lit lane's edge against it")
        assertContrast(2.04, c.white, c.lit, "white paint on the lit lane")
        assertContrast(9.34, c.arrowEdge, c.lit, "an arrow's graphite edge on the lit lane")
        assertContrast(2.58, c.moderate, c.asphalt, "moderate on the road")
        assertContrast(2.41, c.heavy, c.asphalt, "heavy on the road")
        assertContrast(2.33, c.severe, c.asphalt, "severe on the road")
        assertContrast(3.61, c.heavy, c.litEdge, "heavy against the lit lane's edge")
        assertContrast(3.50, c.severe, c.litEdge, "severe against the lit lane's edge")
        // The day's own reds would hardly show on the drawn road: why the night's are used on it.
        assertContrast(1.72, MapPalette.TRAFFIC_HEAVY_DAY, c.asphalt, "the day's heavy on the road")
        assertContrast(1.17, MapPalette.TRAFFIC_SEVERE_DAY, c.asphalt, "the day's severe on the road")
    }

    @Test
    fun `where two states differ by less than three to one a shape tells them apart`() {
        for (c in listOf(night, day)) {
            // The lit lane and a tinted lane are close in lightness: the lit lane's edges stand out from it.
            assertTrue(contrast(c.lit, c.tint) < 3.0)
            assertTrue(contrast(c.litEdge, c.lit) >= 3.0)
            // Heavy and severe traffic are close to the road: the lit lane keeps its edges, which stand out from them.
            for (jam in listOf(c.heavy, c.severe)) {
                assertTrue(contrast(jam, c.asphalt) < 3.0)
                assertTrue(contrast(jam, c.litEdge) >= 3.0)
            }
        }
    }
}
