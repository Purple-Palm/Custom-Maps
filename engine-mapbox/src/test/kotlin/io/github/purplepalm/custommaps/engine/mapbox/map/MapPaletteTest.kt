package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import io.github.purplepalm.custommaps.engine.mapbox.map.MapLook.toValue
import io.github.purplepalm.custommaps.nav.look.Look
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two looks' columns of the basemap's config and the route line's two sets of colours, and the
 * colors as configured by the contrast measure of the web's accessibility guidelines (relative
 * luminance). What the phone shows after the light and the theme is for the eye on the phone; the
 * steps between them are set here. Standard's own day colors are approximate, measured from
 * Mapbox's preview images: a major road about #A0A3C0, the ground about #F3F0EF.
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

    /** What the night map wrote before there were two looks: every key and value, in this order. */
    private val nightBefore: List<Pair<String, Value>> = listOf(
        "lightPreset" to Value.valueOf("night"),
        "theme" to Value.valueOf("monochrome"),
        "show3dObjects" to Value.valueOf(true),
        "colorLand" to Value.valueOf("#1A1D21"),
        "colorBuildings" to Value.valueOf("#282B31"),
        "colorMotorways" to Value.valueOf("#6A6F77"),
        "colorTrunks" to Value.valueOf("#5A5F67"),
        "colorRoads" to Value.valueOf("#4A4F57"),
        "colorHdRoads" to Value.valueOf("#4A4F57"),
        "colorWater" to Value.valueOf("#080B0E"),
        "colorGreenspace" to Value.valueOf("#090C0A"),
        "colorPlaceLabels" to Value.valueOf("#D6D7D9"),
        "colorRoadLabels" to Value.valueOf("#A9ADB3"),
        "colorModePointOfInterestLabels" to Value.valueOf("single"),
        "colorPointOfInterestLabels" to Value.valueOf("#8C9198"),
        "densityPointOfInterestLabels" to Value.valueOf(2L),
    )

    @Test
    fun `the night column writes what the night map wrote before, key by key and value by value`() {
        assertEquals(nightBefore, MapPalette.column(Look.NIGHT).map { (key, value) -> key to value.toValue() })
    }

    @Test
    fun `both columns name the same keys in the same order, and neither names lane details' switch`() {
        val night = MapPalette.column(Look.NIGHT).map { it.first }
        assertEquals(night, MapPalette.column(Look.DAY).map { it.first })
        assertEquals(night.toSet().size, night.size, "each key once")
        assertFalse("showHdRoads" in night)
    }

    @Test
    fun `by day every colour the night sets goes back to the style, but the road under lane details`() {
        for (key in MapPalette.keys) {
            if (key.night !is LookValue.Colour) continue
            if (key.name == "colorHdRoads") {
                assertEquals(LookValue.Colour(MapPalette.HD_ROADS_DAY), key.day)
            } else {
                assertEquals(LookValue.Restore, key.day, key.name)
            }
        }
    }

    @Test
    fun `by day Standard shows its day light, its colourful theme and its places in their own colours`() {
        val day = MapPalette.column(Look.DAY).toMap()
        assertEquals(LookValue.Text("day"), day["lightPreset"])
        assertEquals(LookValue.Text("default"), day[MapLook.THEME])
        assertEquals(LookValue.Flag(true), day["show3dObjects"])
        assertEquals(LookValue.Text("default"), day["colorModePointOfInterestLabels"])
        assertEquals(LookValue.Number(2L), day["densityPointOfInterestLabels"])
        assertEquals(LookValue.Colour("#575C64"), day["colorHdRoads"])
    }

    @Test
    fun `the car's lighter map changes the 3D objects alone, off in both looks`() {
        for (look in listOf(Look.NIGHT, Look.DAY)) {
            for (key in MapPalette.keys) {
                assertEquals(key.value(look), key.value(look, light = false), "${key.name} $look")
                val light = key.value(look, light = true)
                if (key.name == MapPalette.SHOW_3D_OBJECTS) {
                    assertEquals(LookValue.Flag(true), key.value(look), "$look shows them")
                    assertEquals(LookValue.Flag(false), light, "$look")
                } else {
                    assertEquals(key.value(look), light, "${key.name} $look")
                }
            }
        }
        assertEquals(1, MapPalette.keys.count { it.name == MapPalette.SHOW_3D_OBJECTS })
    }

    @Test
    fun `every colour is written as #RRGGBB`() {
        val colours = MapPalette.keys.flatMap { listOf(it.night, it.day) }.filterIsInstance<LookValue.Colour>()
        assertTrue(colours.isNotEmpty())
        for (colour in colours) assertTrue(Regex("#[0-9A-F]{6}").matches(colour.hex), colour.hex)
    }

    @Test
    fun `by day the asphalt under lane details carries the white markings and the amber line`() {
        val asphalt = luminance(MapPalette.HD_ROADS_DAY)
        assertTrue(contrast(luminance("#FFFFFF"), asphalt) >= 6.5, "white markings")
        assertTrue(contrast(luminance(MapPalette.AMBER and 0xFFFFFF), asphalt) >= 3.0, "amber line")
    }

    @Test
    fun `the two route line sets name the same colours and differ in casing, heavy, severe and closed only`() {
        val night = MapPalette.routeLine(Look.NIGHT)
        val day = MapPalette.routeLine(Look.DAY)
        assertEquals(RouteLineColor.entries.toSet(), night.keys)
        assertEquals(RouteLineColor.entries.toSet(), day.keys)
        assertEquals(
            setOf(RouteLineColor.ROUTE_CASING, RouteLineColor.ROUTE_HEAVY, RouteLineColor.ROUTE_SEVERE, RouteLineColor.ROUTE_CLOSED),
            RouteLineColor.entries.filter { night[it] != day[it] }.toSet(),
        )
    }

    private fun hex(argb: Int?) = "#%06X".format(argb!! and 0xFFFFFF)

    @Test
    fun `the route is amber with orange moderate traffic in both looks, and other routes grey at every level`() {
        for (look in Look.entries) {
            val line = MapPalette.routeLine(look)
            for (color in listOf(RouteLineColor.ROUTE_DEFAULT, RouteLineColor.ROUTE_UNKNOWN, RouteLineColor.ROUTE_LOW)) {
                assertEquals("#F5A524", hex(line[color]), "$look $color")
            }
            assertEquals("#FF7A1A", hex(line[RouteLineColor.ROUTE_MODERATE]))
            for (color in RouteLineColor.entries.filter { it.name.startsWith("OTHER_") && it != RouteLineColor.OTHER_CASING }) {
                assertEquals("#9AA1AB", hex(line[color]), "$look $color")
            }
            assertEquals("#2A2E34", hex(line[RouteLineColor.OTHER_CASING]))
        }
    }

    @Test
    fun `at night the jams are bright and the casing dark, by day the casing is bronze and the reds stay`() {
        val night = MapPalette.routeLine(Look.NIGHT)
        assertEquals(listOf("#3A2800", "#FF6B5E", "#FF5C9E", "#E5E4E2"), listOf(RouteLineColor.ROUTE_CASING, RouteLineColor.ROUTE_HEAVY, RouteLineColor.ROUTE_SEVERE, RouteLineColor.ROUTE_CLOSED).map { hex(night[it]) })
        val day = MapPalette.routeLine(Look.DAY)
        assertEquals(listOf("#5C3B00", "#E5484D", "#9E1B32", "#3A3F46"), listOf(RouteLineColor.ROUTE_CASING, RouteLineColor.ROUTE_HEAVY, RouteLineColor.ROUTE_SEVERE, RouteLineColor.ROUTE_CLOSED).map { hex(day[it]) })
    }

    @Test
    fun `by day the casing outlines the route against a major road and the ground by four to one`() {
        val casing = luminance(MapPalette.CASING_DAY and 0xFFFFFF)
        assertTrue(contrast(casing, luminance("#A0A3C0")) >= 4.0, "against a major road")
        assertTrue(contrast(casing, luminance("#F3F0EF")) >= 4.0, "against the ground")
    }

    @Test
    fun `at night the heavy and severe jams stand out from a street far more than the dark reds did`() {
        val street = luminance(MapPalette.ROADS)
        for ((now, before) in listOf(MapPalette.TRAFFIC_HEAVY_NIGHT to 0xFFE5484D.toInt(), MapPalette.TRAFFIC_SEVERE_NIGHT to 0xFF9E1B32.toInt())) {
            val ratio = contrast(luminance(now and 0xFFFFFF), street)
            assertTrue(ratio >= 2.8 && ratio > contrast(luminance(before and 0xFFFFFF), street) + 0.5, hex(now))
        }
    }
}
