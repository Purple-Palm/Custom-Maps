package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.AppSettings
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The debug section: how many trip sessions this phone started, and the simulated drive switch. */
data class DebugState(
    val tripsThisMonth: Int = 0,
    /** Newest first. */
    val recentTrips: List<TripSessionStart> = emptyList(),
    val simulateDrive: Boolean = false,
)

class SettingsViewModel(
    tripLog: TripLogStore,
    private val settings: AppSettings,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) : ViewModel() {

    val debug: StateFlow<DebugState> = combine(tripLog.entries, settings.simulateDrive) { entries, simulate ->
        DebugState(
            tripsThisMonth = TripLog.countInMonth(entries, now()),
            recentTrips = entries.takeLast(RECENT_TRIPS).asReversed(),
            simulateDrive = simulate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugState())

    fun setSimulateDrive(enabled: Boolean) {
        viewModelScope.launch { settings.setSimulateDrive(enabled) }
    }

    private companion object {
        const val RECENT_TRIPS = 5
    }
}
