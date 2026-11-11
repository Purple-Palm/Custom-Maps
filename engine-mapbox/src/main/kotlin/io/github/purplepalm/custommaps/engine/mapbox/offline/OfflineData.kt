package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error

/**
 * The data kept on the phone for stretches without a connection. All of it is in one tile store,
 * [tileStore], which the map and the navigator share: the engine gives it to both before either
 * exists (see MapboxEngine.create). It is the store at the default location, which both used
 * before, each on its own, so what they had saved is still there.
 *
 * Main thread only, like the SDK calls it makes.
 */
internal class OfflineData(
    private val tileStore: TileStore,
    private val debugLog: DebugLog,
) : MapboxNavigationObserver {

    init {
        MapboxNavigationApp.registerObserver(this)
    }

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        logTileStore(mapboxNavigation)
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) = Unit

    /**
     * Says in the debug log whether this navigator and the map have the one store, read back from
     * the two SDKs and not from what the engine meant to set. The Maps SDK hands back only a store
     * that was set for it, and nothing but the engine sets one; the navigator's options hold the
     * very object the engine put there.
     */
    private fun logTileStore(mapboxNavigation: MapboxNavigation) {
        val fields = try {
            OfflineLines.tileStore(
                mapHasStore = MapboxMapsOptions.tileStore != null,
                navigatorHasStore = mapboxNavigation.navigationOptions.routingTilesOptions.tileStore === tileStore,
                usageMode = MapboxMapsOptions.tileStoreUsageMode.name,
            )
        } catch (e: RuntimeException) {
            // An SDK that cannot answer costs this line and nothing else.
            debugLog.error("tile_store", e)
            return
        }
        debugLog.log("tile_store", fields)
    }
}
