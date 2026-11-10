package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.AppSettings
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The guidance section: how the app guides, as the driver chose it. */
data class GuidanceSettings(
    val cameraStyle: CameraStyle = CameraStyle.DEFAULT,
    val laneZoom: LaneZoom = LaneZoom.DEFAULT,
    val laneDetails: Boolean = false,
)

/** The debug section: trip sessions started on this phone, the simulated drive switch and the debug log. */
data class DebugState(
    val tripsThisMonth: Int = 0,
    /** Newest first. */
    val recentTrips: List<TripSessionStart> = emptyList(),
    val simulateDrive: Boolean = false,
    /** Size of the debug log on the phone; null until measured. */
    val logBytes: Long? = null,
)

class SettingsViewModel(
    tripLog: TripLogStore,
    private val settings: AppSettings,
    private val debugLog: DebugLogArchive,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) : ViewModel() {

    private val logBytes = MutableStateFlow<Long?>(null)

    val guidance: StateFlow<GuidanceSettings> =
        combine(settings.cameraStyle, settings.laneZoom, settings.laneDetails) { style, laneZoom, laneDetails ->
            GuidanceSettings(cameraStyle = style, laneZoom = laneZoom, laneDetails = laneDetails)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidanceSettings())

    val debug: StateFlow<DebugState> =
        combine(tripLog.entries, settings.simulateDrive, logBytes) { entries, simulate, bytes ->
            DebugState(
                tripsThisMonth = TripLog.countInMonth(entries, now()),
                recentTrips = entries.takeLast(RECENT_TRIPS).asReversed(),
                simulateDrive = simulate,
                logBytes = bytes,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugState())

    fun setSimulateDrive(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setSimulateDrive(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value.
                debugLog.error("settings.setSimulateDrive", e)
            }
        }
    }

    /** Applies at once, also during a drive: the map follows the stored setting. */
    fun setCameraStyle(style: CameraStyle) {
        viewModelScope.launch {
            try {
                settings.setCameraStyle(style)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setCameraStyle", e)
            }
        }
    }

    /** Applies at once, also during a drive: the view glides to the new zoom. */
    fun setLaneZoom(mode: LaneZoom) {
        viewModelScope.launch {
            try {
                settings.setLaneZoom(mode)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setLaneZoom", e)
            }
        }
    }

    /** Applies at once, also during a drive: the map fades the lane markings in or out. */
    fun setLaneDetails(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setLaneDetails(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setLaneDetails", e)
            }
        }
    }

    /** Measures the debug log again; called whenever Settings opens. */
    fun refreshLogSize() {
        viewModelScope.launch { logBytes.value = debugLog.sizeBytes() }
    }

    /** Copies the debug log for sharing. Empty when there is nothing to share or the copy failed. */
    suspend fun exportLog(header: Map<String, Any?>): List<File> =
        try {
            debugLog.export(header)
        } catch (e: IOException) {
            debugLog.error("debugLog.export", e)
            emptyList()
        }

    fun clearLog() {
        viewModelScope.launch {
            debugLog.clear()
            logBytes.value = debugLog.sizeBytes()
        }
    }

    private companion object {
        const val RECENT_TRIPS = 5
    }
}
