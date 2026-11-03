package io.github.purplepalm.custommaps.engine.mapbox

import android.app.Application
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap
import io.github.purplepalm.custommaps.engine.mapbox.navigation.MapboxNavigationEngine
import io.github.purplepalm.custommaps.engine.mapbox.search.MapboxSearchProvider
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import java.util.Locale

/**
 * Entry point of the Mapbox implementation; create one per process in Application.onCreate.
 * Creating it starts no trip session: that happens only when the driver starts guidance.
 */
class MapboxEngine private constructor(application: Application, tripLog: TripLogStore) {

    private val navigationEngine = MapboxNavigationEngine(application, tripLog)

    val navigation: NavigationEngine get() = navigationEngine

    val search: SearchProvider = MapboxSearchProvider()

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
    fun createPhoneMap(context: Context): PhoneMap = PhoneMap(context, navigationEngine)

    companion object {

        fun create(application: Application, tripLog: TripLogStore): MapboxEngine {
            if (!MapboxNavigationApp.isSetup()) {
                MapboxNavigationApp.setup(
                    NavigationOptions.Builder(application)
                        // Miles and feet in every text the SDK formats, whatever the phone's locale.
                        .distanceFormatterOptions(distanceFormatterOptions(application))
                        .build(),
                )
            }
            return MapboxEngine(application, tripLog)
        }

        internal fun distanceFormatterOptions(context: Context): DistanceFormatterOptions =
            DistanceFormatterOptions.Builder(context)
                .unitType(UnitType.IMPERIAL)
                .locale(Locale.US)
                .build()
    }
}
