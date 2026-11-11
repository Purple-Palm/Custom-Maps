package io.github.purplepalm.custommaps.engine.mapbox.offline

import android.os.SystemClock
import com.mapbox.bindgen.Expected
import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionError
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.common.TileStore
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackError
import com.mapbox.maps.StylePackLoadProgress
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.utils.DecodeUtils.completeGeometryToLineString
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.ui.maps.PredictiveCacheController
import com.mapbox.navigation.ui.maps.PredictiveCacheControllerErrorHandler
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.RepeatFolder
import io.github.purplepalm.custommaps.nav.debug.error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Before guidance, [downloadPreview] puts the street map along the previewed routes on the phone:
 * one tile region that each new preview takes over, and the style's own files. These are map
 * tiles, as the map loads them itself when it shows a place; road data is not asked for outside a
 * trip. Starting guidance ends it ([stopPreviewDownload]), so that during a drive nothing loads
 * but predictive caching, within its areas.
 *
 * Every download costs the driver data. Each preview download, of the region and of the style
 * pack, says in the debug log how many bytes came over the network for it; the SDK gives no such
 * count for predictive caching.
 *
 * Main thread only, like the SDK calls it makes. The tile store and the offline manager answer on
 * threads of their own; what runs there remembers the latest numbers and writes to the debug log,
 * and touches nothing else.
 */
internal class OfflineData(
    private val tileStore: TileStore,
    /** The screen's pixels for each density-independent one, as the map draws its tiles. */
    private val pixelRatio: Float,
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

    /**
     * Makes tile descriptors and loads style packs. Made at the first preview, on the main thread,
     * long after the engine gave the Maps SDK the shared tile store.
     */
    private val offlineManager by lazy { OfflineManager() }

    /** Which tiles the preview's region is made of ([PreviewDownload.descriptorOptions]). */
    private val previewDescriptorOptions by lazy { PreviewDownload.descriptorOptions(pixelRatio) }

    private val previewDescriptor: TilesetDescriptor by lazy { offlineManager.createTilesetDescriptor(previewDescriptorOptions) }

    /** The shape of the newest preview's routes being put together off the main thread. */
    private var previewGeometry: Job? = null

    /** Counts the loads of the tile region in this run of the app. */
    private var regionLoads = 0

    /** The loads of the tile region, each new one in the place of the one before. */
    private val regionLoad = LatestDownload<TileRegionLoadProgress>()

    /** Whether the style's own files have been loaded in this run of the app. */
    private val stylePack = LoadedOnce()

    /** The load of the style pack that was started last; it may have finished. */
    private var stylePackLoad: Download<StylePackLoadProgress>? = null

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
     * Puts the street map along [routes], the routes of a preview that has just opened, on the
     * phone: the map's style from zoom 11 to 16 as one tile region, along the first
     * [PreviewDownload.MAX_ROUTE_METERS] of each route, loaded outward from [origin], where the
     * routes start. The region is the same one every time, so a new preview takes over from the
     * one before: a load still under way is ended, and tiles only the old routes needed are the
     * store's to drop. The first call of a run also loads the style's own files.
     *
     * Tiles the phone has are taken as they are, also past their date. Nothing is asked of the
     * network here beyond handing the store its work: without a connection the store waits and
     * goes on by itself, or reports that it failed.
     */
    fun downloadPreview(routes: List<NavigationRoute>, origin: Point) {
        if (routes.isEmpty()) return
        loadStylePack()
        // Only the newest preview counts.
        previewGeometry?.cancel()
        previewGeometry = scope.launch {
            // Reads every point of every route, so not here on the main thread.
            val shape = withContext(Dispatchers.Default) {
                try {
                    PreviewDownload.geometry(routes.map { it.directionsRoute.completeGeometryToLineString().coordinates() })
                } catch (e: RuntimeException) {
                    debugLog.error("offline_region.geometry", e)
                    null
                }
            }
            // Back on the main thread, and neither a newer preview nor guidance has come since.
            loadRegion(routes.size, shape, origin)
        }
    }

    /**
     * Ends what [downloadPreview] started and has not finished. Called when guidance starts: from
     * then on predictive caching keeps the route in use, and the routes not taken are not paid for
     * in data. What was loaded so far stays on the phone.
     */
    fun stopPreviewDownload() {
        previewGeometry?.cancel()
        previewGeometry = null
        guarded("offline.cancel") { regionLoad.stop(EndedBy.GUIDANCE) }
        end(stylePackLoad, EndedBy.GUIDANCE)
    }

    private fun loadRegion(routes: Int, shape: PreviewDownload.Shape?, origin: Point) {
        val number = ++regionLoads
        if (shape == null) {
            // A newer preview all the same: what the load before it still fetches is for routes
            // that are no longer shown.
            guarded("offline.cancel") { regionLoad.stop(EndedBy.NEWER_PREVIEW) }
            debugLog.log(
                "offline_region",
                OfflineLines.region(number, routes, OfflineLines.NO_GEOMETRY, message = null, endedBy = null, counts = null, seconds = 0.0),
            )
            return
        }
        val load = Download<TileRegionLoadProgress>(SystemClock.elapsedRealtimeNanos(), number, routes)
        // The store would end the load before by itself; ended here first, before this one is
        // asked for, so that its answer is known for what it is.
        guarded("offline.cancel") { regionLoad.replace(load) }
        try {
            val options = PreviewDownload.loadOptions(shape.geometry, listOf(previewDescriptor), origin)
            // What the store is really asked for, read back from the options it gets.
            val descriptor = previewDescriptorOptions
            debugLog.log(
                "offline_region_start",
                OfflineLines.regionStart(
                    load = number,
                    routes = routes,
                    points = shape.routes.sumOf { it.line.size },
                    zooms = ZoomBand(descriptor.minZoom.toInt(), descriptor.maxZoom.toInt()),
                    pixelRatio = descriptor.pixelRatio,
                    network = options.networkRestriction.name,
                    routeMeters = shape.routes.map { it.routeMeters },
                    keptMeters = shape.routes.map { it.keptMeters },
                    maxMeters = PreviewDownload.MAX_ROUTE_METERS,
                ),
            )
            // Both callbacks come on a thread of the store.
            load.cancelable = tileStore.loadTileRegion(
                PreviewDownload.REGION_ID,
                options,
                { progress -> load.progress = progress },
                { result -> onRegionLoaded(load, result) },
            )
        } catch (e: RuntimeException) {
            // Nothing was started, so no answer will come for it.
            load.finished = true
            debugLog.error("offline_region", e)
        }
    }

    /** On a thread of the store: writes the load's one line. */
    private fun onRegionLoaded(load: Download<TileRegionLoadProgress>, result: Expected<TileRegionError, TileRegion>) {
        load.finished = true
        try {
            val error = result.error
            // The region's own numbers; what came over the network is only in the progress.
            val counts = downloadCounts(result.value, load.progress)
            debugLog.log(
                "offline_region",
                OfflineLines.region(load.number, load.routes, error?.type?.name, error?.message, load.endedBy, counts, secondsSince(load.startedNanos)),
            )
        } catch (e: RuntimeException) {
            debugLog.error("offline_region", e)
        }
    }

    /**
     * Loads the style's own files (the style, its fonts and images), which the map needs to open
     * without a connection. Asked for with the first preview of a run and, until a load has
     * succeeded, with each one after it; then not again in this run. Files past their date are
     * loaded anew ([PreviewDownload.stylePackOptions]).
     */
    private fun loadStylePack() {
        if (!stylePack.begin()) return
        val load = Download<StylePackLoadProgress>(SystemClock.elapsedRealtimeNanos())
        stylePackLoad = load
        try {
            // Both callbacks come on a background thread.
            load.cancelable = offlineManager.loadStylePack(
                PreviewDownload.STYLE,
                PreviewDownload.stylePackOptions(),
                { progress -> load.progress = progress },
                { result -> onStylePackLoaded(load, result) },
            )
        } catch (e: RuntimeException) {
            load.finished = true
            stylePack.ended(loaded = false)
            debugLog.error("offline_style_pack", e)
        }
    }

    /** On a background thread: notes whether the pack is there now, and writes the load's one line. */
    private fun onStylePackLoaded(load: Download<StylePackLoadProgress>, result: Expected<StylePackError, StylePack>) {
        load.finished = true
        // A load that failed or was ended is asked for again with the next preview.
        stylePack.ended(loaded = result.isValue)
        try {
            val error = result.error
            val counts = downloadCounts(result.value, load.progress)
            debugLog.log(
                "offline_style_pack",
                OfflineLines.stylePack(error?.type?.name, error?.message, load.endedBy, counts, secondsSince(load.startedNanos)),
            )
        } catch (e: RuntimeException) {
            debugLog.error("offline_style_pack", e)
        }
    }

    /** Ends [load] if there is one and it is still under way; the line it writes then says who did. */
    private fun end(load: Download<*>?, by: EndedBy) {
        if (load == null) return
        guarded("offline.cancel") { load.end(by) }
    }

    private fun secondsSince(startedNanos: Long): Double = (SystemClock.elapsedRealtimeNanos() - startedNanos) / NANOS_PER_SECOND

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

    private companion object {
        const val NANOS_PER_SECOND = 1e9
    }
}
