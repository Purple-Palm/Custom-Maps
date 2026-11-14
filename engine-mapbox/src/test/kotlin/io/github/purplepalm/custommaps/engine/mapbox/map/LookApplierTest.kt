package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.engine.mapbox.map.LookApplier.Writes
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookApplierTest {

    private val day = LookOnMap(Look.DAY)
    private val night = LookOnMap(Look.NIGHT)

    /** A map made at night whose style has loaded. */
    private fun loadedAtNight(): LookApplier = LookApplier(night).apply {
        onStyleLoading()
        onStyleData()
        assertEquals(Writes.NONE, onStyleLoaded())
    }

    @Test
    fun `the style's data writes the whole look of the map's first request, with its theme`() {
        val applier = LookApplier(night)
        applier.onStyleLoading()
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT), applier.onStyleData())
        // The route line was made with the first request's colours.
        assertEquals(Writes.NONE, applier.onStyleLoaded())
    }

    @Test
    fun `a look asked for before the style's data is kept and written once, there`() {
        val applier = LookApplier(night)
        applier.onStyleLoading()
        assertEquals(Writes.NONE, applier.setLook(day))
        assertEquals(Writes(basemap = Look.DAY, theme = Look.DAY), applier.onStyleData())
        // The basemap has it already; the route line takes it with the style load.
        assertEquals(Writes(routeLine = Look.DAY), applier.onStyleLoaded())
    }

    @Test
    fun `a look asked for between the style's data and the style load reaches the basemap at once`() {
        val applier = LookApplier(night)
        applier.onStyleLoading()
        applier.onStyleData()
        assertEquals(Writes(basemap = Look.DAY, theme = Look.DAY), applier.setLook(day))
        assertEquals(Writes(routeLine = Look.DAY), applier.onStyleLoaded())
        assertEquals(Writes.NONE, applier.setLook(day))
    }

    @Test
    fun `a look asked for after the style load goes everywhere at once`() {
        val applier = loadedAtNight()
        assertEquals(Writes(basemap = Look.DAY, theme = Look.DAY, routeLine = Look.DAY), applier.setLook(day))
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT, routeLine = Look.NIGHT), applier.setLook(night))
    }

    @Test
    fun `the car's lighter map asked for before the style's data waits for it, and after it is written at once`() {
        val applier = LookApplier(night)
        applier.onStyleLoading()
        // No basemap yet: nothing now, and the style's data writes the whole look with it.
        assertNull(applier.setLight(true))
        assertTrue(applier.light)
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT), applier.onStyleData())
        // After it: the 3D objects of the look the basemap shows, at once, and once.
        assertEquals(Look.NIGHT, applier.setLight(false))
        assertNull(applier.setLight(false))
        assertFalse(applier.light)
        applier.onStyleLoaded()
        applier.setLook(day)
        assertEquals(Look.DAY, applier.setLight(true))
        assertTrue(applier.light)
    }

    @Test
    fun `a new style load keeps the car's lighter map for its whole look`() {
        val applier = loadedAtNight()
        assertEquals(Look.NIGHT, applier.setLight(true))
        applier.onStyleLoading()
        assertTrue(applier.light)
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT), applier.onStyleData())
        assertTrue(applier.light)
    }

    @Test
    fun `the same request twice writes nothing the second time`() {
        val applier = loadedAtNight()
        assertEquals(Writes.NONE, applier.setLook(night))
        applier.setLook(day)
        assertEquals(Writes.NONE, applier.setLook(day))
    }

    @Test
    fun `a theme left behind keeps the old theme, then goes alone and once`() {
        val applier = loadedAtNight()
        applier.setLook(day)
        // Back on screen at night near a turn: everything but the theme.
        assertEquals(Writes(basemap = Look.NIGHT, routeLine = Look.NIGHT), applier.setLook(LookOnMap(Look.NIGHT, theme = Look.DAY)))
        assertEquals(Look.DAY, applier.themeLook)
        assertEquals(Writes.NONE, applier.setLook(LookOnMap(Look.NIGHT, theme = Look.DAY)))
        // Let go at a quiet moment: the theme alone.
        assertEquals(Writes(theme = Look.NIGHT), applier.setLook(night))
        assertEquals(Writes.NONE, applier.setLook(night))
    }

    @Test
    fun `a map made while a theme waits takes the whole look, and the release later writes nothing`() {
        val applier = LookApplier(LookOnMap(Look.NIGHT, theme = Look.DAY))
        applier.onStyleLoading()
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT), applier.onStyleData())
        assertEquals(Writes.NONE, applier.onStyleLoaded())
        assertEquals(Writes.NONE, applier.setLook(night))
    }

    @Test
    fun `a style load while a theme waits writes the whole look, and the release later writes nothing`() {
        val applier = loadedAtNight()
        applier.setLook(day)
        applier.setLook(LookOnMap(Look.NIGHT, theme = Look.DAY))
        applier.onStyleLoading()
        assertEquals(Writes(basemap = Look.NIGHT, theme = Look.NIGHT), applier.onStyleData())
        // The route line has the night colours already: it keeps them across style loads.
        assertEquals(Writes.NONE, applier.onStyleLoaded())
        assertEquals(Writes.NONE, applier.setLook(night))
    }

    @Test
    fun `back to the look whose theme still shows, before the release, no theme is written`() {
        val applier = loadedAtNight()
        applier.setLook(day)
        applier.setLook(LookOnMap(Look.NIGHT, theme = Look.DAY))
        // The driver picks Day before the theme went: back to the day look, whose theme is still on.
        assertEquals(Writes(basemap = Look.DAY, routeLine = Look.DAY), applier.setLook(day))
        assertEquals(Look.DAY, applier.themeLook)
    }
}
