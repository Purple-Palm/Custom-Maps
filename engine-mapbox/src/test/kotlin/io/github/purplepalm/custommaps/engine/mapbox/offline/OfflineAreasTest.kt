package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.options.PredictiveCacheLocationOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineAreasTest {

    /** Around the car, along the route and around the destination, in meters. */
    private fun PredictiveCacheLocationOptions.meters(): List<Int> =
        listOf(currentLocationRadiusInMeters, routeBufferRadiusInMeters, destinationLocationRadiusInMeters)

    private fun KeptAround.meters(): List<Int> = listOf(carMeters, routeMeters, destinationMeters)

    @Test
    fun `the areas are those of the drive notes`() {
        // docs/DRIVES.md, Offline: the table of what is kept during guidance.
        assertEquals(KeptAround(carMeters = 10_000, routeMeters = 3_000, destinationMeters = 10_000), OfflineAreas.ROAD_DATA)
        assertEquals(KeptAround(carMeters = 2_000, routeMeters = 1_000, destinationMeters = 2_000), OfflineAreas.STREET_MAP)
        assertEquals(KeptAround(carMeters = 10_000, routeMeters = 5_000, destinationMeters = 10_000), OfflineAreas.TOWN_MAP)
        assertEquals(ZoomBand(15, 16), OfflineAreas.STREET_ZOOMS)
        assertEquals(ZoomBand(11, 14), OfflineAreas.TOWN_ZOOMS)
    }

    @Test
    fun `the two bands of the map are whole tile pack bands that follow each other`() {
        // Mapbox packs map tiles in the bands 0 to 5, 6 to 10, 11 to 14 and 15 to 16.
        val packBands = listOf(ZoomBand(0, 5), ZoomBand(6, 10), ZoomBand(11, 14), ZoomBand(15, 16))

        assertTrue(OfflineAreas.STREET_ZOOMS in packBands)
        assertTrue(OfflineAreas.TOWN_ZOOMS in packBands)
        assertEquals(OfflineAreas.TOWN_ZOOMS.max + 1, OfflineAreas.STREET_ZOOMS.min)
    }

    @Test
    fun `no area reaches as far as the defaults of the SDK`() {
        val defaults = PredictiveCacheLocationOptions.Builder().build()
        // What the test is about: 20 km, 5 km and 50 km.
        assertEquals(listOf(20_000, 5_000, 50_000), defaults.meters())

        for (area in listOf(OfflineAreas.ROAD_DATA, OfflineAreas.STREET_MAP, OfflineAreas.TOWN_MAP)) {
            assertNotEquals(defaults.meters(), area.meters())
            area.meters().zip(defaults.meters()).forEach { (ours, theirs) -> assertTrue(ours <= theirs, "$area") }
            assertTrue(area.meters().all { it > 0 }, "$area")
        }
    }

    @Test
    fun `road data is asked for with its own area`() {
        val roads = OfflineAreas.predictiveCacheOptions().predictiveCacheNavigationOptions

        assertEquals(listOf(10_000, 3_000, 10_000), roads.predictiveCacheLocationOptions.meters())
    }

    @Test
    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    fun `road data is that of the navigator's own tiles`() {
        val roads = OfflineAreas.predictiveCacheOptions().predictiveCacheNavigationOptions

        // No other dataset or version is named, so the navigator keeps the tiles it reads itself.
        assertNull(roads.tilesDataset)
        assertNull(roads.tilesVersion)
        assertFalse(roads.includeAdas)
    }

    @Test
    fun `the map is asked for in two bands, each with its own area`() {
        val maps = OfflineAreas.predictiveCacheOptions().predictiveCacheMapsOptionsList

        assertEquals(2, maps.size)
        val (streets, town) = maps
        assertEquals(listOf(15, 16), listOf(streets.minZoom.toInt(), streets.maxZoom.toInt()))
        assertEquals(listOf(2_000, 1_000, 2_000), streets.predictiveCacheLocationOptions.meters())
        assertEquals(listOf(11, 14), listOf(town.minZoom.toInt(), town.maxZoom.toInt()))
        assertEquals(listOf(10_000, 5_000, 10_000), town.predictiveCacheLocationOptions.meters())
    }

    @Test
    fun `nothing is asked for alternative routes or for search`() {
        val options = OfflineAreas.predictiveCacheOptions()
        val areas = options.predictiveCacheMapsOptionsList.map { it.predictiveCacheLocationOptions } +
            options.predictiveCacheNavigationOptions.predictiveCacheLocationOptions

        assertTrue(areas.none { it.loadPredictiveCacheForAlternativeRoutes })
        assertNull(options.predictiveCacheSearchOptionsList)
    }
}
