package io.github.purplepalm.custommaps.diagnostics

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The phone's side of a drive, for the debug log: whether the app is on screen, the screen itself,
 * charging, battery saver and Doze, heat, memory pressure, and whether Android's animations run.
 * Only changes are logged, plus one [logSample] a minute while guidance runs. The network is logged
 * by its own monitor.
 *
 * Every reading that asks the system (power, battery, standby) runs on [io], and its line is
 * written after it; the callbacks themselves only note what happened. Call [start] once, on the
 * main thread.
 */
internal class DeviceLog(
    private val application: Application,
    private val debugLog: DebugLog,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val powerManager: PowerManager? = application.getSystemService(PowerManager::class.java)
    private val activityManager: ActivityManager? = application.getSystemService(ActivityManager::class.java)
    private val connectivity: ConnectivityManager? = application.getSystemService(ConnectivityManager::class.java)

    /** One reading at a time, so the lines keep the order of the events. */
    private val reads = io.limitedParallelism(1)

    private val _visible = MutableStateFlow(false)

    /** True while one of the app's activities is started, that is, on screen. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /**
     * Called on the main thread whenever an activity resumes, for instance back from system
     * settings, where the driver may have changed what the app is allowed to do.
     */
    var onActivityResumed: () -> Unit = {}

    private var animationsLogged = false

    /** Held here because Android keeps only a weak reference to it. */
    private var scaleListener: Any? = null

    fun start() {
        logAfterReading("device_state") { stateFields() }
        application.registerActivityLifecycleCallbacks(activities)
        application.registerComponentCallbacks(memory)
        val systemEvents = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        // Protected system broadcasts, which no app can send; Android asks for an exported receiver
        // to be sure of getting every system broadcast.
        ContextCompat.registerReceiver(application, system, systemEvents, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listenToHeat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listenToAnimationScale()
    }

    /**
     * Whether Android's animations run, and why it was checked. With the animator duration scale at
     * 0 (Developer options, an accessibility switch, Power saving on some phones) the map's puck and
     * camera jump instead of gliding. Only logged: the app never changes the setting.
     */
    fun logAnimations(reason: String, newScale: Float? = null) {
        logAfterReading("animations") {
            mapOf(
                "reason" to reason,
                "enabled" to ValueAnimator.areAnimatorsEnabled(),
                "durationScale" to Settings.Global.getFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
                "newScale" to newScale,
                "powerSave" to powerManager?.isPowerSaveMode,
            ).filterValues { it != null }
        }
    }

    /**
     * Battery, heat, memory and the bytes the app has received; once a minute during guidance.
     * Asks the system: call it off the main thread.
     */
    fun logSample() {
        // The sticky battery broadcast: no receiver is registered.
        val battery = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val runtime = Runtime.getRuntime()
        debugLog.log(
            "device",
            mapOf(
                "batteryPercent" to if (level >= 0 && scale > 0) level * 100 / scale else null,
                "plugged" to pluggedName(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1),
                // Tenths of a degree Celsius in the broadcast.
                "batteryC" to if (temperature != Int.MIN_VALUE) temperature / 10.0 else null,
                "thermal" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalName(powerManager?.currentThermalStatus) else null,
                "headroom" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) headroom() else null,
                "powerSave" to powerManager?.isPowerSaveMode,
                "javaHeapMb" to (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB,
                "nativeHeapMb" to Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB,
                // Everything the app has received since the phone started, Wi-Fi and mobile data
                // alike. Predictive caching has no byte count of its own, so the growth from one
                // line to the next is the most it can have loaded meanwhile.
                "appRxBytes" to TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0 },
            ),
        )
    }

    /** Reads [fields] on the reading dispatcher, then writes them as a [type] line. */
    private fun logAfterReading(type: String, fields: () -> Map<String, Any?>) {
        scope.launch(reads) { debugLog.log(type, fields()) }
    }

    private fun stateFields(): Map<String, Any?> = mapOf(
        "powerSave" to powerManager?.isPowerSaveMode,
        "locationPowerSave" to locationPowerSave(),
        "ignoresBatteryOptimizations" to powerManager?.isIgnoringBatteryOptimizations(application.packageName),
        "backgroundRestricted" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) activityManager?.isBackgroundRestricted else null,
        "standbyBucket" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) SystemNames.standbyBucket(application) else null,
        "dataSaver" to SystemNames.dataSaverName(connectivity?.restrictBackgroundStatus),
        "thermal" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalName(powerManager?.currentThermalStatus) else null,
        // Without it, a low memory kill is reported as SIGNALED with signal 9.
        "lowMemoryKillReported" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ActivityManager.isLowMemoryKillReportSupported() else null,
    )

    private fun locationPowerSave(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationPowerSaveName(powerManager?.locationPowerSaveMode) else null

    private val activities = object : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            logActivity(activity, "created", "restored" to (savedInstanceState != null))

        override fun onActivityStarted(activity: Activity) {
            logActivity(activity, "started")
            started += 1
            _visible.value = started > 0
            // Android hands the scale to the process only once a window exists.
            if (!animationsLogged) {
                animationsLogged = true
                logAnimations("first activity")
            }
        }

        override fun onActivityResumed(activity: Activity) {
            logActivity(activity, "resumed")
            onActivityResumed()
        }

        override fun onActivityPaused(activity: Activity) = logActivity(activity, "paused")

        override fun onActivityStopped(activity: Activity) {
            logActivity(activity, "stopped")
            started -= 1
            _visible.value = started > 0
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) =
            logActivity(activity, "destroyed", "finishing" to activity.isFinishing, "recreating" to activity.isChangingConfigurations)
    }

    private fun logActivity(activity: Activity, event: String, vararg extra: Pair<String, Any?>) {
        debugLog.log("activity", mapOf("event" to event, "name" to activity.javaClass.simpleName) + extra)
    }

    private val system = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> debugLog.log("screen", mapOf("on" to true))
                Intent.ACTION_SCREEN_OFF -> debugLog.log("screen", mapOf("on" to false))
                Intent.ACTION_USER_PRESENT -> debugLog.log("screen", mapOf("unlocked" to true))
                Intent.ACTION_POWER_CONNECTED -> debugLog.log("power", mapOf("charging" to true))
                Intent.ACTION_POWER_DISCONNECTED -> debugLog.log("power", mapOf("charging" to false))
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> logAfterReading("power") {
                    mapOf("powerSave" to powerManager?.isPowerSaveMode, "locationPowerSave" to locationPowerSave())
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> logAfterReading("power") {
                    mapOf("doze" to powerManager?.isDeviceIdleMode)
                }
            }
        }
    }

    private val memory = object : ComponentCallbacks2 {
        private var lastLevel = -1

        override fun onTrimMemory(level: Int) {
            if (level == lastLevel) return
            lastLevel = level
            debugLog.log("memory_trim", mapOf("level" to trimLevelName(level)))
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Superseded by onTrimMemory")
        override fun onLowMemory() = Unit
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listenToHeat() {
        powerManager?.addThermalStatusListener(ContextCompat.getMainExecutor(application)) { status ->
            debugLog.log("thermal", mapOf("status" to thermalName(status)))
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun listenToAnimationScale() {
        val listener = ValueAnimator.DurationScaleChangeListener { scale -> logAnimations("scale changed", scale) }
        scaleListener = listener
        ValueAnimator.registerDurationScaleChangeListener(listener)
    }

    /** How close the phone is to throttling, 0 to 1 and beyond; NaN, so null, where unsupported. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun headroom(): Double? =
        powerManager?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)?.takeIf { !it.isNaN() }?.let { (it * 100).roundToInt() / 100.0 }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val HEADROOM_FORECAST_SECONDS = 10

        fun thermalName(status: Int?): String? = when (status) {
            null -> null
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "STATUS_$status"
        }

        fun locationPowerSaveName(mode: Int?): String? = when (mode) {
            null -> null
            PowerManager.LOCATION_MODE_NO_CHANGE -> "NO_CHANGE"
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF -> "GPS_OFF_WHEN_SCREEN_OFF"
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF -> "ALL_OFF_WHEN_SCREEN_OFF"
            PowerManager.LOCATION_MODE_FOREGROUND_ONLY -> "FOREGROUND_ONLY"
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF -> "THROTTLED_WHEN_SCREEN_OFF"
            else -> "MODE_$mode"
        }

        fun pluggedName(plugged: Int): String? = when (plugged) {
            -1 -> null
            0 -> "NO"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "PLUGGED_$plugged"
        }

        // Most levels are deprecated from Android 15, which sends only UI_HIDDEN and BACKGROUND.
        @Suppress("DEPRECATION")
        fun trimLevelName(level: Int): String = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }
    }
}
