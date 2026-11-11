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
}
