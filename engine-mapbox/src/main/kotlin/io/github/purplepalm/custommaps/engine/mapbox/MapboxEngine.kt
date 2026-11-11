package io.github.purplepalm.custommaps.engine.mapbox

import android.app.Application
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.engine.mapbox.offline.OfflineData
import io.github.purplepalm.custommaps.engine.mapbox.search.MapboxSearchProvider
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point of the Mapbox implementation; create one per process in Application.onCreate.
 * Creating it starts no trip session: that happens only when the driver starts guidance.
 */
class MapboxEngine private constructor(
    application: Application,
    tripLog: TripLogStore,
    private val debugLog: DebugLog,
    tileStore: TileStore,
    network: StateFlow<NetworkStatus>,
) {

    /** What is kept on the phone for stretches without a connection, in the one tile store. */
    private val offlineData = OfflineData(tileStore, application.resources.displayMetrics.density, debugLog)

    private val navigationEngine = MapboxNavigationEngine(application, tripLog, debugLog, offlineData, network)

    val navigation: NavigationEngine get() = navigationEngine

    val search: SearchProvider = MapboxSearchProvider(debugLog)

    /**
     * Keeps the navigator alive while [owner] is at least created. Attach a screen only after a
     * valid access token has been applied.
     */
    fun attachHost(owner: LifecycleOwner) {
        MapboxNavigationApp.attach(owner)
    }

    fun detachHost(owner: LifecycleOwner) {
        MapboxNavigationApp.detach(owner)
    }

    /** A map for the phone screen. Call [PhoneMap.release] when its view leaves the screen. */
    fun createPhoneMap(context: Context): PhoneMap = PhoneMap(context, navigationEngine, debugLog, offlineData)

    companion object {

        /**
         * The order of the first lines matters. The Maps SDK reads its options when a map, a
         * snapshotter or an offline manager is made, and the navigator reads its own when it is
         * made; a later change reaches none of them. So the tile store is made first and given to
         * the Maps SDK, then to the options the navigator will be made from, and only then is the
         * engine made: every map and offline manager of the app comes from the engine, and the
         * navigator is made from these options when a screen or a drive first needs it.
         */
        fun create(
            application: Application,
            tripLog: TripLogStore,
            debugLog: DebugLog,
            /** This app's network as Android reports it; the summary of each trip counts its time offline. */
            network: StateFlow<NetworkStatus>,
        ): MapboxEngine {
            // One tile store for the map and the navigator. This is the store at the default
            // location, the one each of them used by itself before, so nothing saved so far is
            // lost. How the map uses it stays as the SDK has it: it reads tile packs from the
            // store and loads any other tile by itself.
            val tileStore = TileStore.create()
            MapboxMapsOptions.tileStore = tileStore
            if (!MapboxNavigationApp.isSetup()) {
                MapboxNavigationApp.setup(
                    NavigationOptions.Builder(application)
                        // Miles and feet in every text the SDK formats, whatever the phone's locale.
                        .distanceFormatterOptions(distanceFormatterOptions(application))
                        .routingTilesOptions(RoutingTilesOptions.Builder().tileStore(tileStore).build())
                        .build(),
                )
            }
            return MapboxEngine(application, tripLog, debugLog, tileStore, network)
        }

        internal fun distanceFormatterOptions(context: Context): DistanceFormatterOptions =
            DistanceFormatterOptions.Builder(context)
                .unitType(UnitType.IMPERIAL)
                .locale(Locale.US)
                .build()
    }
}
