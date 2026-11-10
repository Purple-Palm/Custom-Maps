package io.github.purplepalm.custommaps.network

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import io.github.purplepalm.custommaps.diagnostics.SystemNames
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.network.offlineReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Follows this app's network for the debug log and the screen: the app's only network callback.
 * "network" is written whenever this app's default network changes, with every network with
 * internet the app can see ("available"); "network_policy" holds the phone settings that can keep
 * this app alone off the network, at start, on every activity resume and after a network change.
 *
 * How to read the lines: transport "none" with no "available" network and
 * sim "ABSENT" or mobileData false means the phone had no mobile data. "none" with "cellular"
 * available means mobile data existed but Android withheld it from this app (a per-app rule such
 * as Wi-Fi only). "blocked" means Data Saver, a background data or battery limit. "validated"
 * false means a network without internet.
 *
 * The callbacks run on the main thread and only note what changed; one update runs after each
 * burst of them, outside the callbacks, as ConnectivityManager asks. The policy settings are read
 * on [io], since each one asks the system.
 */
class NetworkMonitor(
    context: Context,
    private val debugLog: DebugLog,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** One policy reading at a time, so the lines keep their order. */
    private val reads = io.limitedParallelism(1)

    private val _status = MutableStateFlow(NetworkStatus.NOT_REPORTED)

    /** [NetworkStatus.NOT_REPORTED] until Android has answered, or has stayed silent for a moment. */
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    // Main thread only.
    private var defaultNetwork: Network? = null
    private var defaultCapabilities: NetworkCapabilities? = null
    private var defaultBlocked = false
    private val visible = HashMap<Network, String>()
    private var updatePosted = false
    private var lastLogged: NetworkStatus? = null

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A new default network; its capabilities follow at once.
            defaultNetwork = network
            defaultCapabilities = null
            defaultBlocked = false
            scheduleUpdate()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network != defaultNetwork) return
            defaultCapabilities = networkCapabilities
            scheduleUpdate()
        }

        // Android 10+: Data Saver, background data and battery limits arrive here as blocked.
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (network != defaultNetwork) return
            defaultBlocked = blocked
            scheduleUpdate()
        }

        // Only when no other network can take over as the default.
        override fun onLost(network: Network) {
            if (network != defaultNetwork) return
            defaultNetwork = null
            defaultCapabilities = null
            defaultBlocked = false
            scheduleUpdate()
        }
    }

    private val anyCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (visible.put(network, networkCapabilities.transportName()) == null) scheduleUpdate()
        }

        override fun onLost(network: Network) {
            if (visible.remove(network) != null) scheduleUpdate()
        }
    }

    /** Registers the callbacks for the life of the process. Call once, on the main thread. */
    fun start() {
        val manager = connectivity
        if (manager == null) {
            debugLog.log("network_monitor_failed", mapOf("error" to "no ConnectivityManager"))
            return
        }
        try {
            manager.registerDefaultNetworkCallback(defaultCallback, main)
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                anyCallback,
                main,
            )
        } catch (e: RuntimeException) {
            // SecurityException without ACCESS_NETWORK_STATE; TooManyRequestsException past 100 callbacks.
            debugLog.log("network_monitor_failed", mapOf("error" to e.toString()))
            return
        }
        // With no network at all Android never calls back; record that after a moment.
        main.postDelayed({ if (_status.value == NetworkStatus.NOT_REPORTED) update() }, FIRST_ANSWER_MS)
    }

    /** Writes the settings that can keep this app alone off the network, with why they were read. */
    fun logPolicy(reason: String) {
        scope.launch(reads) { debugLog.log("network_policy", mapOf("reason" to reason) + policy()) }
    }

    // One network change brings a burst of callbacks; this runs once after all of them.
    private fun scheduleUpdate() {
        if (updatePosted) return
        updatePosted = true
        main.post {
            updatePosted = false
            update()
        }
    }

    private fun update() {
        val capabilities = defaultCapabilities
        val available = visible.values.sorted()
        val status = if (defaultNetwork == null || capabilities == null) {
            NetworkStatus.noNetwork(available)
        } else {
            NetworkStatus(
                transport = capabilities.transportName(),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                blocked = defaultBlocked,
                metered = capabilities.isMetered(),
                roaming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
                suspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                available = available,
            )
        }
        _status.value = status
        // Bandwidth estimates change all the time; only a different state is worth a line.
        if (status == lastLogged) return
        lastLogged = status
        debugLog.log(
            "network",
            mapOf(
                "offline" to status.offlineReason().name,
                "transport" to status.transport,
                "validated" to status.validated,
                "blocked" to status.blocked,
                "metered" to status.metered,
                "roaming" to status.roaming,
                "suspended" to status.suspended,
                "available" to status.available,
                "downKbps" to capabilities?.linkDownstreamBandwidthKbps,
            ),
        )
        logPolicy("network change")
    }

    /** Each value asks the system: call it on [reads] only. */
    @Suppress("DEPRECATION")
    private fun policy(): Map<String, Any?> {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        val power = appContext.getSystemService(PowerManager::class.java)
        val activity = appContext.getSystemService(ActivityManager::class.java)
        return mapOf(
            "dataSaver" to SystemNames.dataSaverName(connectivity?.restrictBackgroundStatus),
            // The legacy view Cronet also reads: CONNECTED, BLOCKED, DISCONNECTED, or null for none.
            "activeNetworkState" to connectivity?.activeNetworkInfo?.detailedState?.name,
            "mobileData" to telephony?.let(::mobileDataSwitch),
            "sim" to runCatching { telephony?.simState?.let(::simName) }.getOrNull(),
            "backgroundRestricted" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) activity?.isBackgroundRestricted else null,
            "ignoresBatteryOptimizations" to power?.isIgnoringBatteryOptimizations(appContext.packageName),
            "powerSave" to power?.isPowerSaveMode,
            "standbyBucket" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) SystemNames.standbyBucket(appContext) else null,
        )
    }

    private fun NetworkCapabilities.transportName(): String = when {
        // A VPN is the default network of the apps it covers; its own transport is listed too.
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

    /** From Android 11 an unmetered plan can make a cellular network temporarily not metered. */
    private fun NetworkCapabilities.isMetered(): Boolean =
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                )

    /** The phone's Mobile data switch; ACCESS_NETWORK_STATE is enough to read it. */
    private fun mobileDataSwitch(telephony: TelephonyManager): Boolean? =
        try {
            telephony.isDataEnabled
        } catch (e: SecurityException) {
            null
        } catch (e: UnsupportedOperationException) {
            // A phone without telephony.
            null
        }

    private fun simName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "LOCKED"
        else -> "STATE_$state"
    }

    private companion object {
        const val FIRST_ANSWER_MS = 2_000L
    }
}
