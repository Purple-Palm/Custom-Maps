package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
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

/** The look section: Auto, Day or Night, and under Auto when the look changes next. */
data class LookSettings(
    val choice: LookChoice = LookChoice.DEFAULT,
    /** Null with Day or Night, under the time zone's guess, and when the sun does not cross within a day. */
    val next: NextLook? = null,
)

/** A recent trip session, and whose token it ran on. */
data class RecentTrip(val start: TripSessionStart, val token: TripsOf)

/** The debug section: trip sessions started on this phone, the simulated drive, own route line and own lanes switches, and the debug log. */
data class DebugState(
    /** The month's trip sessions, and since when the count runs when that is after the month began. */
    val trips: MonthCount = MonthCount(0),
    /** The month's trip sessions by token; empty when that would be one line, the same as [trips]. */
    val tripsByToken: List<TokenTrips> = emptyList(),
    /** Newest first. */
    val recentTrips: List<RecentTrip> = emptyList(),
    val simulateDrive: Boolean = false,
    /** Whether the app draws the route line of a drive itself. */
    val ownRouteLine: Boolean = false,
    /** Whether the app draws the freeway lanes of a drive itself. */
    val drawOwnLanes: Boolean = false,
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
    /** The look on screen now. */
    shownLook: Flow<Look> = flowOf(Look.NIGHT),
    /** When Auto would change the shown look next ([NextLook.of]); worked out here, never in composition. */
    private val nextLook: (choice: LookChoice, shown: Look) -> NextLook? = { _, _ -> null },
) : ViewModel() {

    private val logBytes = MutableStateFlow<Long?>(null)

    val guidance: StateFlow<GuidanceSettings> =
        combine(settings.cameraStyle, settings.laneZoom, settings.laneDetails) { style, laneZoom, laneDetails ->
            GuidanceSettings(cameraStyle = style, laneZoom = laneZoom, laneDetails = laneDetails)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidanceSettings())

    /**
     * The choice and its next change, worked out again whenever Settings opens, the choice changes
     * or the look on screen does. The time is shown on the screen only, never logged or stored.
     */
    val look: StateFlow<LookSettings> =
        combine(settings.dayNight, shownLook) { choice, shown -> LookSettings(choice, nextLook(choice, shown)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LookSettings())

    /** The three Debug switches, in one value. */
    private val switches = combine(settings.simulateDrive, settings.ownRouteLine, settings.drawOwnLanes) { simulate, ownLine, ownLanes ->
        Triple(simulate, ownLine, ownLanes)
    }

    val debug: StateFlow<DebugState> =
        combine(tripLog.entries, switches, logBytes, tokens) { entries, (simulate, ownLine, ownLanes), bytes, list ->
            val time = now()
            DebugState(
                trips = TripLog.monthCount(entries, time, installedAt()),
                tripsByToken = TokenTripCounts.rows(TripLog.countByToken(entries, time), list).takeIf { it.size > 1 }.orEmpty(),
                recentTrips = entries.takeLast(RECENT_TRIPS).asReversed().map { RecentTrip(it, TokenTripCounts.of(it.tokenId, list)) },
                simulateDrive = simulate,
                ownRouteLine = ownLine,
                drawOwnLanes = ownLanes,
                logBytes = bytes,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugState())

    /** Applies at once, also during a drive: the screen and the map take the look in the same moment. */
    fun setDayNight(choice: LookChoice) {
        viewModelScope.launch {
            try {
                settings.setDayNight(choice)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the look keeps too.
                debugLog.error("settings.setDayNight", e)
            }
        }
    }

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

    /** Applies at once, also during a drive: the map draws the route line itself, or the SDK does again. */
    fun setOwnRouteLine(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setOwnRouteLine(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setOwnRouteLine", e)
            }
        }
    }

    /** Applies at once, also during a drive: the map draws the freeway lanes itself, or nothing of them. */
    fun setDrawOwnLanes(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setDrawOwnLanes(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setDrawOwnLanes", e)
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
