package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenTripCounts
import io.github.purplepalm.custommaps.token.TokenTrips
import io.github.purplepalm.custommaps.token.TripsOf
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The guidance section: how the app guides, as the driver chose it. */
data class GuidanceSettings(
    val cameraStyle: CameraStyle = CameraStyle.DEFAULT,
    val laneZoom: LaneZoom = LaneZoom.DEFAULT,
    val laneDetails: Boolean = false,
)

/** A recent trip session, and whose token it ran on. */
data class RecentTrip(val start: TripSessionStart, val token: TripsOf)

/** The debug section: trip sessions started on this phone, the simulated drive switch and the debug log. */
data class DebugState(
    /** The month's trip sessions, and since when the count runs when that is after the month began. */
    val trips: MonthCount = MonthCount(0),
    /** The month's trip sessions by token; empty when that would be one line, the same as [trips]. */
    val tripsByToken: List<TokenTrips> = emptyList(),
    /** Newest first. */
    val recentTrips: List<RecentTrip> = emptyList(),
    val simulateDrive: Boolean = false,
    /** Size of the debug log on the phone; null until measured. */
    val logBytes: Long? = null,
)

class SettingsViewModel(
    tripLog: TripLogStore,
    private val settings: AppSettings,
    private val debugLog: DebugLogArchive,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    /** When the app was installed on this phone, which is when its trip session log began. */
    private val installedAt: () -> Instant = { Instant.EPOCH },
    /** The saved tokens, for the names the trip sessions are counted under. Only names leave here, never a token. */
    tokens: Flow<TokenList> = flowOf(TokenList.EMPTY),
) : ViewModel() {

    private val logBytes = MutableStateFlow<Long?>(null)

    val guidance: StateFlow<GuidanceSettings> =
        combine(settings.cameraStyle, settings.laneZoom, settings.laneDetails) { style, laneZoom, laneDetails ->
            GuidanceSettings(cameraStyle = style, laneZoom = laneZoom, laneDetails = laneDetails)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidanceSettings())

    val debug: StateFlow<DebugState> =
        combine(tripLog.entries, settings.simulateDrive, logBytes, tokens) { entries, simulate, bytes, list ->
            val time = now()
            DebugState(
                trips = TripLog.monthCount(entries, time, installedAt()),
                tripsByToken = TokenTripCounts.rows(TripLog.countByToken(entries, time), list).takeIf { it.size > 1 }.orEmpty(),
                recentTrips = entries.takeLast(RECENT_TRIPS).asReversed().map { RecentTrip(it, TokenTripCounts.of(it.tokenId, list)) },
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
