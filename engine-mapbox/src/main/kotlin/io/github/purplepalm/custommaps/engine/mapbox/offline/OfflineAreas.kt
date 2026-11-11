package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.navigation.base.options.PredictiveCacheLocationOptions
import com.mapbox.navigation.base.options.PredictiveCacheMapsOptions
import com.mapbox.navigation.base.options.PredictiveCacheNavigationOptions
import com.mapbox.navigation.base.options.PredictiveCacheOptions

/** How far data is kept around the car, along the route and around the destination, in meters. */
internal data class KeptAround(val carMeters: Int, val routeMeters: Int, val destinationMeters: Int)

/** Zoom levels of the map, from [min] to [max] with both included. */
internal data class ZoomBand(val min: Int, val max: Int)

/**
 * What is kept ahead of the car during guidance (docs/DRIVES.md, Offline). The numbers are the
 * app's own: Mapbox's defaults are 20 km around the car, 5 km along the route and 50 km around the
 * destination, for road data and for the map alike, and every kilometer is paid for in mobile
 * data. Only the route in use counts, not its alternatives.
 *
 * The map comes in tile packs of fixed zoom bands (0 to 5, 6 to 10, 11 to 14, 15 to 16), and a
 * range that cuts into a band is rounded out to all of it, so the two bands here are whole ones.
 */
internal object OfflineAreas {

    /** Road data: map matching, speed limits and reroutes without a connection. */
    val ROAD_DATA = KeptAround(carMeters = 10_000, routeMeters = 3_000, destinationMeters = 10_000)

    /** The streets close to the car, for the camera that follows it and the close view at junctions. */
    val STREET_ZOOMS = ZoomBand(min = 15, max = 16)
    val STREET_MAP = KeptAround(carMeters = 2_000, routeMeters = 1_000, destinationMeters = 2_000)

    /** The town around, for the horizon of a tilted camera and the view of the whole route. */
    val TOWN_ZOOMS = ZoomBand(min = 11, max = 14)
    val TOWN_MAP = KeptAround(carMeters = 10_000, routeMeters = 5_000, destinationMeters = 10_000)

    /**
     * The areas above as the SDK's predictive caching takes them: road data of the navigator's own
     * tiles (no dataset or version is named), the map of whatever style is on screen in the two
     * bands, and nothing for search.
     */
    fun predictiveCacheOptions(): PredictiveCacheOptions = PredictiveCacheOptions.Builder()
        .predictiveCacheNavigationOptions(
            PredictiveCacheNavigationOptions.Builder().predictiveCacheLocationOptions(ROAD_DATA.toOptions()).build(),
        )
        .predictiveCacheMapsOptionsList(listOf(mapOptions(STREET_ZOOMS, STREET_MAP), mapOptions(TOWN_ZOOMS, TOWN_MAP)))
        .build()

    private fun mapOptions(zooms: ZoomBand, around: KeptAround): PredictiveCacheMapsOptions =
        PredictiveCacheMapsOptions.Builder()
            .predictiveCacheLocationOptions(around.toOptions())
            .minZoom(zooms.min.toByte())
            .maxZoom(zooms.max.toByte())
            .build()

    private fun KeptAround.toOptions(): PredictiveCacheLocationOptions = PredictiveCacheLocationOptions.Builder()
        .currentLocationRadiusInMeters(carMeters)
        .routeBufferRadiusInMeters(routeMeters)
        .destinationLocationRadiusInMeters(destinationMeters)
        .build()
}
