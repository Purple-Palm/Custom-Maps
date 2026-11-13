package io.github.purplepalm.custommaps

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.purplepalm.custommaps.car.CarSession
import io.github.purplepalm.custommaps.diagnostics.DeviceLog
import io.github.purplepalm.custommaps.diagnostics.ProcessHistory
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.look.LastKnownPosition
import io.github.purplepalm.custommaps.look.LookController
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.look.SunPlaces
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.debug.CrashRecorder
import io.github.purplepalm.custommaps.nav.debug.FileDebugLog
import io.github.purplepalm.custommaps.nav.debug.JsonLines
import io.github.purplepalm.custommaps.nav.debug.StateSummary
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.network.NetworkMonitor
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.settings.DataStoreAppSettings
import io.github.purplepalm.custommaps.token.DataStoreTokenStore
import io.github.purplepalm.custommaps.token.TokenEntry
import io.github.purplepalm.custommaps.token.TokenHandOver
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenStore
import io.github.purplepalm.custommaps.trip.DataStoreTripLogStore
import io.github.purplepalm.custommaps.trip.TokenTaggedTripLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
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

    /**
     * Which saved token the SDKs have, and a change that waits for the trip to end. Given every
     * read of the list on the main thread; a token is never handed over during a trip.
     */
    private val tokenHandOver = TokenHandOver()

    /**
     * Sets up the navigator; starts no trip session. Each session it logs gets the id of the token
     * it ran on, never the token.
     */
    val engine: MapboxEngine =
        MapboxEngine.create(application, TokenTaggedTripLog(tripLogStore) { tokenHandOver.appliedTokenId }, debugLog, network.status)

    /** Where the sun is computed for: the newest position the app has, which never leaves memory. */
    private val sunPlaces = SunPlaces(
        fix = { engine.navigation.lastPosition },
        lastKnown = LastKnownPosition(application),
        zone = { ZoneId.systemDefault().rules },
        now = Instant::now,
    )

    /**
     * The day look or the night look, for the whole process: an activity created again shows the
     * look this one decided. It follows the choice in Settings, Auto by the sun at first.
     */
    val lookController = LookController(
        guidance = engine.navigation.guidanceState,
        progress = engine.navigation.routeProgress,
        places = sunPlaces::current,
        choices = settings.dayNight,
        debugLog = debugLog,
        scope = applicationScope,
        now = Instant::now,
        elapsedMillis = SystemClock::elapsedRealtime,
        // Behind the progress update, once the map's camera has taken it too.
        driveContext = Dispatchers.Main,
    )

    /**
     * When Auto would change [shown] next, for the note in Settings, which shows it on screen only;
     * none for a [choice] by hand. Read on the main thread when Settings opens or the look or the
     * choice changes.
     */
    fun nextLook(choice: LookChoice, shown: Look): NextLook? =
        NextLook.of(choice, shown, sunPlaces.current(), Instant.now(), ZoneId.systemDefault())

    private val _mapsAccess = MutableStateFlow<MapsAccess>(MapsAccess.Checking)
    val mapsAccess: StateFlow<MapsAccess> = _mapsAccess.asStateFlow()

    /**
     * The car's screen for a connection of the car's host, from the car app service. [info] says
     * which of the car's displays it is for; the app declares only the main one.
     */
    fun carSession(info: SessionInfo): Session = CarSession(debugLog, info.displayType)

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
                            "since" to JsonLines.time(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
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
        // Hand the active token to the SDKs before any map exists, and again whenever the driver
        // picks another one. A change while guidance runs waits for the trip to end, and one while
        // routes are previewed closes the preview first (TokenHandOver, TokenSwitch).
        applicationScope.launch {
            var generation = 0
            combine(tokenStore.list, engine.navigation.guidanceState, ::Pair).collect { (list, guidance) ->
                when (val effect = tokenHandOver.next(list, guidance)) {
                    TokenHandOver.Effect.Keep -> Unit
                    is TokenHandOver.Effect.Hold -> if (effect.log) {
                        debugLog.log("token_switch_held", entryFields(list, effect.entry) + ("guidance" to guidance))
                    }
                    is TokenHandOver.Effect.Apply -> {
                        if (effect.closePreview) engine.navigation.clearPreview()
                        val entry = effect.entry
                        if (entry != null) {
                            MapboxAccessTokens.apply(entry.token)
                            generation += 1
                            _mapsAccess.value = MapsAccess.Ready(generation)
                        } else {
                            _mapsAccess.value = MapsAccess.NeedsToken
                        }
                        debugLog.log(
                            "maps_access",
                            mapOf("access" to _mapsAccess.value.toString()) + entryFields(list, entry) + ("previewClosed" to effect.closePreview),
                        )
                    }
                }
            }
        }
    }

    /**
     * Which entry, by its place in the list counted from 1 and the name the driver gave it. The
     * token itself never goes into the log, nor anything read from it.
     */
    private fun entryFields(list: TokenList, entry: TokenEntry?): Map<String, Any?> =
        if (entry == null) emptyMap() else mapOf("slot" to list.slotOf(entry.id), "name" to entry.name)

    companion object {
        /** Under the app's files directory; the backup rules never include it. */
        const val DEBUG_LOG_DIRECTORY = "debug-log"

        /** Under the app's cache directory. Must match res/xml/debug_log_paths.xml, which shares it. */
        const val DEBUG_LOG_EXPORT_DIRECTORY = "debug-log-export"

        private const val DEVICE_SAMPLE_MILLIS = 60_000L
    }
}
