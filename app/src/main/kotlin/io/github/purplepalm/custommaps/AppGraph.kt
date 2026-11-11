package io.github.purplepalm.custommaps

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.purplepalm.custommaps.diagnostics.DeviceLog
import io.github.purplepalm.custommaps.diagnostics.ProcessHistory
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.nav.debug.CrashRecorder
import io.github.purplepalm.custommaps.nav.debug.FileDebugLog
import io.github.purplepalm.custommaps.nav.debug.StateSummary
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.network.NetworkMonitor
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.settings.DataStoreAppSettings
import io.github.purplepalm.custommaps.token.DataStoreTokenStore
import io.github.purplepalm.custommaps.token.TokenStore
import io.github.purplepalm.custommaps.trip.DataStoreTripLogStore
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val debugLog = FileDebugLog(
        directory = File(application.filesDir, DEBUG_LOG_DIRECTORY),
        exportDirectory = File(application.cacheDir, DEBUG_LOG_EXPORT_DIRECTORY),
        scope = applicationScope,
        io = Dispatchers.IO,
    )

    /**
     * Writes an uncaught exception into the debug log before the process dies. Installed right
     * after the log exists, so a crash while the stores and the navigator start up is caught too.
     */
    private val crashRecorder = CrashRecorder.install(debugLog)

    val tokenStore: TokenStore = DataStoreTokenStore(preferences(DataStoreTokenStore.FILE_NAME))

    val tripLogStore = DataStoreTripLogStore(preferences(DataStoreTripLogStore.FILE_NAME))

    /** Every change of a setting goes to the debug log, with whether a drive was running. */
    val settings: AppSettings = DataStoreAppSettings(preferences(DataStoreAppSettings.FILE_NAME)) { key, value ->
        debugLog.log("settings", mapOf("key" to key, "value" to value, "guidance" to engine.navigation.guidanceState.value))
    }

    /** How earlier processes ended and why this one started, as Android recorded it. */
    private val processHistory = ProcessHistory(application, debugLog)

    /** The phone's state: on screen, screen, power, heat, memory; a sample a minute during guidance. */
    private val deviceLog = DeviceLog(application, debugLog, applicationScope)

    /** This app's network as Android reports it: logged on every change. */
    val network = NetworkMonitor(application, debugLog, applicationScope)

    /** Sets up the navigator; starts no trip session. */
    val engine: MapboxEngine = MapboxEngine.create(application, tripLogStore, debugLog)

    private val _mapsAccess = MutableStateFlow<MapsAccess>(MapsAccess.Checking)
    val mapsAccess: StateFlow<MapsAccess> = _mapsAccess.asStateFlow()

    /**
     * When the app was installed on this phone, which is when its trip session log began: an
     * update keeps the time, a new installation starts it over. The start of time when Android
     * cannot say, which counts as a log that covers every month.
     */
    fun installedAt(): Instant = firstInstall

    private val firstInstall: Instant by lazy {
        try {
            val manager = application.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(application.packageName, 0)
            }
            Instant.ofEpochMilli(info.firstInstallTime)
        } catch (e: PackageManager.NameNotFoundException) {
            Instant.EPOCH
        }
    }

    /** Opens every exported log file, so each one says which build and phone it came from. */
    fun debugLogHeader(): Map<String, Any?> = mapOf(
        "app" to application.getString(R.string.app_version),
        "build" to application.getString(R.string.build_revision),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
    )

    /** A file that cannot be read any more starts over empty instead of failing every read and write. */
    private fun preferences(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            debugLog.log("settings_file_replaced", mapOf("file" to name, "error" to error.toString()))
            emptyPreferences()
        },
        produceFile = { application.preferencesDataStoreFile(name) },
    )

    fun start() {
        // The pid ties this start to the "process_exit" record Android writes when it ends.
        debugLog.log("app_start", debugLogHeader() + ("pid" to Process.myPid()))
        // Read on the crashing thread, which can be any thread: only StateFlow values.
        crashRecorder.context = {
            mapOf("guidance" to engine.navigation.guidanceState.value, "visible" to deviceLog.visible.value)
        }
        deviceLog.start()
        network.start()
        network.logPolicy("app start")
        // Back from system settings the driver may have changed what the app may use.
        deviceLog.onActivityResumed = { network.logPolicy("resume") }
        applicationScope.launch(Dispatchers.IO) { processHistory.logOnStart() }
        // Kept by Android with this process and handed back in its exit record.
        applicationScope.launch {
            combine(engine.navigation.guidanceState, deviceLog.visible) { guidance, visible -> guidance to visible }
                .distinctUntilChanged()
                .collect { (guidance, visible) ->
                    val summary = StateSummary.encode(
                        mapOf(
                            "guidance" to guidance,
                            "visible" to visible,
                            "since" to Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        ),
                    )
                    withContext(Dispatchers.IO) { processHistory.setStateSummary(summary) }
                }
        }
        // Battery, heat and memory once a minute while a drive runs; animations as it starts.
        applicationScope.launch {
            engine.navigation.guidanceState
                .map { it == GuidanceState.ACTIVE }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) deviceLog.logAnimations("guidance")
                    while (active) {
                        withContext(Dispatchers.IO) { deviceLog.logSample() }
                        delay(DEVICE_SAMPLE_MILLIS)
                    }
                }
        }
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
        /** Under the app's files directory; the backup rules never include it. */
        const val DEBUG_LOG_DIRECTORY = "debug-log"

        /** Under the app's cache directory. Must match res/xml/debug_log_paths.xml, which shares it. */
        const val DEBUG_LOG_EXPORT_DIRECTORY = "debug-log-export"

        private const val DEVICE_SAMPLE_MILLIS = 60_000L
    }
}
