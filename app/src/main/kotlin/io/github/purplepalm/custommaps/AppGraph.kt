package io.github.purplepalm.custommaps

import android.app.Application
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.nav.debug.FileDebugLog
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.settings.DataStoreAppSettings
import io.github.purplepalm.custommaps.token.DataStoreTokenStore
import io.github.purplepalm.custommaps.token.TokenStore
import io.github.purplepalm.custommaps.trip.DataStoreTripLogStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether maps can be shown: only once a valid token has been handed to the SDKs. */
sealed interface MapsAccess {
    data object Checking : MapsAccess

    data object NeedsToken : MapsAccess

    /** [generation] grows every time a different token is applied. */
    data class Ready(val generation: Int) : MapsAccess
}

/** Creates the app's long-lived objects once per process. Each DataStore file has exactly one instance. */
class AppGraph(private val application: Application) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Every route, road and second of guidance, for the owner to share from Settings. */
    val debugLog = FileDebugLog(File(application.filesDir, DEBUG_LOG_DIRECTORY), applicationScope, Dispatchers.IO)

    val tokenStore: TokenStore = DataStoreTokenStore(
        PreferenceDataStoreFactory.create(
            produceFile = { application.preferencesDataStoreFile(DataStoreTokenStore.FILE_NAME) },
        ),
    )

    val tripLogStore = DataStoreTripLogStore(
        PreferenceDataStoreFactory.create(
            produceFile = { application.preferencesDataStoreFile(DataStoreTripLogStore.FILE_NAME) },
        ),
    )

    val settings: AppSettings = DataStoreAppSettings(
        PreferenceDataStoreFactory.create(
            produceFile = { application.preferencesDataStoreFile(DataStoreAppSettings.FILE_NAME) },
        ),
    )

    /** Sets up the navigator; starts no trip session. */
    val engine: MapboxEngine = MapboxEngine.create(application, tripLogStore, debugLog)

    private val _mapsAccess = MutableStateFlow<MapsAccess>(MapsAccess.Checking)
    val mapsAccess: StateFlow<MapsAccess> = _mapsAccess.asStateFlow()

    /** Opens every exported log file, so each one says which build and phone it came from. */
    fun debugLogHeader(): Map<String, Any?> = mapOf(
        "app" to application.getString(R.string.app_version),
        "build" to application.getString(R.string.build_revision),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
    )

    fun start() {
        debugLog.log("app_start", debugLogHeader())
        // Hand the stored token to the SDKs before any map exists, and again whenever it changes.
        applicationScope.launch {
            var generation = 0
            tokenStore.token.collect { token ->
                if (token != null && MapboxAccessTokens.check(token) == AccessTokenCheck.VALID) {
                    MapboxAccessTokens.apply(token)
                    generation += 1
                    _mapsAccess.value = MapsAccess.Ready(generation)
                } else {
                    _mapsAccess.value = MapsAccess.NeedsToken
                }
                // Only whether a token is set: the token itself never goes into the log.
                debugLog.log("maps_access", mapOf("access" to _mapsAccess.value.toString()))
            }
        }
    }

    companion object {
        /** Under the app's files directory. Must match the paths excluded in res/xml backup rules. */
        const val DEBUG_LOG_DIRECTORY = "debug-log"
    }
}
