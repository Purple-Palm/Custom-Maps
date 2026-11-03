package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.debug.error
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

    /** Measures the debug log again; called whenever Settings opens. */
    fun refreshLogSize() {
        viewModelScope.launch { logBytes.value = debugLog.sizeBytes() }
    }

    /** Copies the debug log into [target] for sharing. Empty when there is nothing to share or the copy failed. */
    suspend fun exportLog(target: File, header: Map<String, Any?>): List<File> =
        try {
            debugLog.export(target, header)
        } catch (e: IOException) {
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
