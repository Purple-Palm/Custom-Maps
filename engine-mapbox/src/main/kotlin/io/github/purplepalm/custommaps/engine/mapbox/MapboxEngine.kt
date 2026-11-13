package io.github.purplepalm.custommaps.engine.mapbox

import android.app.Application
import android.content.Context
import androidx.car.app.CarContext
import androidx.lifecycle.LifecycleOwner
import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMapsOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.options.RoutingTilesOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.purplepalm.custommaps.engine.mapbox.car.CarNavigationMap
import io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.engine.mapbox.navigation.NavigatorHosts
import io.github.purplepalm.custommaps.engine.mapbox.offline.OfflineData
import io.github.purplepalm.custommaps.engine.mapbox.search.MapboxSearchProvider
import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.look.LookOnMap
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
     * True while a car's surface shows the map, as the car maps count their surfaces: during a drive
     * the phone then shows the list of the maneuvers ahead in place of its map
     * ([io.github.purplepalm.custommaps.nav.car.PhoneScreenRule]), and its map draws at most 60 frames
     * a second. Main thread.
     */
    val carMapShown: StateFlow<Boolean> get() = navigationEngine.carMapShown

    /** The screens that keep the navigator alive: the phone's and each car session's ([NavigatorHosts]). */
    private val hosts = NavigatorHosts(
        attach = { owner -> MapboxNavigationApp.attach(owner) },
        detach = { owner -> MapboxNavigationApp.detach(owner) },
        debugLog = debugLog,
        guidance = { navigationEngine.guidanceState.value },
    )

    /**
     * Keeps the navigator alive while [owner], the phone's screen, is at least created, until
     * [detachPhoneHost]. Attach it only after a valid access token has been applied.
     */
    fun attachPhoneHost(owner: LifecycleOwner) {
        hosts.attachPhone(owner)
    }

    /** The phone's screen [owner] no longer keeps the navigator alive. */
    fun detachPhoneHost(owner: LifecycleOwner) {
        hosts.detachPhone(owner)
    }

    /**
     * Keeps the navigator alive while [owner], a car session, is at least created. There is no detach:
     * the navigator lets the session go by itself when it is destroyed ([NavigatorHosts]). Attach it
     * only after a valid access token has been applied. Starts nothing that Mapbox bills.
     */
    fun attachCarHost(owner: LifecycleOwner) {
        hosts.attachCar(owner)
    }

    /**
     * A map for the phone screen, showing [lookOnMap] from its first tile on. Make it only where its
     * view goes in the window at once: a view never in the window is never stopped or destroyed
     * ([PhoneMap]). Call [PhoneMap.release] when its view leaves the screen.
     */
    fun createPhoneMap(context: Context, lookOnMap: LookOnMap): PhoneMap =
        PhoneMap(context, navigationEngine, debugLog, offlineData, lookOnMap)

    /**
     * The map for the car's screen of the session whose context is [carContext], showing the same scene
     * as the phone's map on the surface the car's host gives, in [lookOnMap] from its first tile on.
     * One per car session; call [CarMapRenderer.release] when the session ends. Make it once the
     * session is created, maps may be shown and the app's first look is decided. Nothing it does
     * starts a trip session, ends one or asks Mapbox for anything billed.
     */
    fun createCarMap(carContext: CarContext, lookOnMap: LookOnMap): CarMapRenderer =
        CarNavigationMap(carContext, navigationEngine, debugLog, lookOnMap)

    /**
     * What the maneuver card did with the words of a run-up's lane row: [shown] false when they did
     * not fit beside the lanes and were left out. [key] is the row's own key, as the progress gave
     * it. For the debug log only; it changes nothing on the screen or in the drive.
     */
    fun onRunUpWordsShown(key: String, shown: Boolean) {
        navigationEngine.onRunUpWordsShown(key, shown)
    }

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
