package io.github.purplepalm.custommaps.engine.mapbox.offline

/**
 * The fields of the debug lines about data kept on the phone, put together from plain values, so
 * what a line says for each case can be tested without the SDK.
 */
internal object OfflineLines {

    /**
     * The "tile_store" line, written each time a navigator is made. [mapHasStore]: the Maps SDK
     * has a tile store that was set for it, and only this app sets one. [navigatorHasStore]: the
     * navigator's options hold that same store. "shared" is true only with both: the map and the
     * navigator then read and fill one store, which predictive caching needs. [usageMode] is how
     * the map uses the store; READ_ONLY reads tile packs from it and still loads any other tile by
     * itself.
     */
    fun tileStore(mapHasStore: Boolean, navigatorHasStore: Boolean, usageMode: String): Map<String, Any?> = mapOf(
        "shared" to (mapHasStore && navigatorHasStore),
        "map" to mapHasStore,
        "navigator" to navigatorHasStore,
        "usage" to usageMode,
    )

    /**
     * The "offline_cache" line, written when predictive caching is set up for a navigator: how far
     * road data and the two bands of the map are kept ([OfflineAreas]). Each "meters" list is
     * around the car, along the route and around the destination.
     */
    fun predictiveCache(): Map<String, Any?> = mapOf(
        "roadData" to mapOf("meters" to OfflineAreas.ROAD_DATA.meters()),
        "streetMap" to mapOf("zooms" to OfflineAreas.STREET_ZOOMS.ends(), "meters" to OfflineAreas.STREET_MAP.meters()),
        "townMap" to mapOf("zooms" to OfflineAreas.TOWN_ZOOMS.ends(), "meters" to OfflineAreas.TOWN_MAP.meters()),
    )

    private fun KeptAround.meters(): List<Int> = listOf(carMeters, routeMeters, destinationMeters)

    private fun ZoomBand.ends(): List<Int> = listOf(min, max)
}
