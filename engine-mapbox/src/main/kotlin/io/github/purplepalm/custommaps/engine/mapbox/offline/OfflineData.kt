package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.ui.maps.PredictiveCacheController
import com.mapbox.navigation.ui.maps.PredictiveCacheControllerErrorHandler
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.RepeatFolder
import io.github.purplepalm.custommaps.nav.debug.error
import kotlinx.coroutines.MainScope

/**
 * The data kept on the phone for stretches without a connection. All of it is in one tile store,
 * [tileStore], which the map and the navigator share: the engine gives it to both before either
 * exists (see MapboxEngine.create). It is the store at the default location, which both used
 * before, each on its own, so what they had saved is still there.
 *
 * During guidance the SDK's predictive caching keeps road data (map matching, speed limits,
 * reroutes without a connection) and the map on screen around the car, along the route in use and
 * around the destination, as far as [OfflineAreas] says. Its controller is made with each
 * navigator and goes with it, but it has something to load only during guidance: the navigator
 * learns where the car is, and which route it drives, only in a trip session. It loads over
 * whatever network the app has then, mobile data included, and nothing here starts a session or
 * sends a request of its own.
 *
 * Main thread only, like the SDK calls it makes.
 */
internal class OfflineData(
    private val tileStore: TileStore,
    private val debugLog: DebugLog,
) : MapboxNavigationObserver {

    private val scope = MainScope()

    /** Keeps data ahead of the car for the navigator there is now; null while there is none. */
    private var predictiveCache: PredictiveCacheController? = null

    /** The maps that exist, in the order they came; each joins every controller made while it lives. */
    private val maps = LinkedHashSet<MapboxMap>()

    /** The same error can come with every map and every style load, so repeats are only counted. */
    private val cacheErrors = RepeatFolder(debugLog, scope)

    private val cacheErrorHandler = PredictiveCacheControllerErrorHandler { message ->
        cacheErrors.record("offline_cache_error", RepeatFolder.keyOf(message), mapOf("message" to message))
    }

    init {
        MapboxNavigationApp.registerObserver(this)
    }

    override fun onAttached(mapboxNavigation: MapboxNavigation) {
        logTileStore(mapboxNavigation)
        startPredictiveCache(mapboxNavigation)
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
        // The controller holds the navigator, so it goes first; the navigator is destroyed after this.
        val controller = predictiveCache ?: return
        predictiveCache = null
        guarded("predictiveCache.onDestroy") { controller.onDestroy() }
    }

    /**
     * Called by a map once it exists. From then on the style it shows is kept ahead during
     * guidance, also after the style is loaded again. Call [detachMap] when the map goes away.
     */
    fun attachMap(map: MapboxMap) {
        if (!maps.add(map)) return
        predictiveCache?.let { addMap(it, map) }
    }

    /** Called when a map goes away, so that nothing holds it or loads for it any more. */
    fun detachMap(map: MapboxMap) {
        if (!maps.remove(map)) return
        val controller = predictiveCache ?: return
        guarded("predictiveCache.removeMap") { controller.removeMapControllers(map) }
    }

    /**
     * Sets up predictive caching for a new navigator. Road data starts with the constructor; each
     * map is added after it. The constructor reports two things only to the system log, before an
     * error handler can be set: that no search caching is configured, which is as wanted, and a
     * missing tile store, which would come again here for every map. Failing to set this up costs
     * the data kept ahead, never the drive.
     */
    private fun startPredictiveCache(mapboxNavigation: MapboxNavigation) {
        val controller = try {
            PredictiveCacheController(mapboxNavigation, OfflineAreas.predictiveCacheOptions())
        } catch (e: RuntimeException) {
            debugLog.error("predictiveCache", e)
            return
        }
        controller.predictiveCacheControllerErrorHandler = cacheErrorHandler
        predictiveCache = controller
        debugLog.log("offline_cache", OfflineLines.predictiveCache())
        maps.forEach { addMap(controller, it) }
    }

    /** Has [controller] keep the style of [map]: the one it shows now, and each it loads later. */
    private fun addMap(controller: PredictiveCacheController, map: MapboxMap) {
        guarded("predictiveCache.addMap") { controller.createStyleMapControllers(map) }
    }

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

    /** Runs an SDK call that must not take the navigator or a map down with it. */
    private inline fun guarded(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            debugLog.error(where, e)
        }
    }
}
