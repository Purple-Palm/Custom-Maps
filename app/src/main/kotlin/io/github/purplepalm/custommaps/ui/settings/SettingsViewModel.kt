package io.github.purplepalm.custommaps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.car.CarMapSize
import io.github.purplepalm.custommaps.nav.debug.DebugLogArchive
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.settings.CAR_LIGHT_WHEN_HOT_DEFAULT
import io.github.purplepalm.custommaps.settings.LANE_COUNTS_HORIZON_DEFAULT
import io.github.purplepalm.custommaps.settings.SoundSettings
import io.github.purplepalm.custommaps.sound.ToneResult
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The guidance section: how the app guides, as the driver chose it. */
data class GuidanceSettings(
    val cameraStyle: CameraStyle = CameraStyle.DEFAULT,
    val laneZoom: LaneZoom = LaneZoom.DEFAULT,
    /** Who draws the lanes on the map. */
    val lanesOnMap: LanesOnMap = LanesOnMap.DEFAULT,
    /** Whether guidance reads the lane counts between lane points from Mapbox's electronic horizon, a beta. */
    val laneCountsHorizon: Boolean = LANE_COUNTS_HORIZON_DEFAULT,
    /** The drive view, Zoom in before turns and Recenter by itself. */
    val driveCamera: DriveCameraSettings = DriveCameraSettings(),
)

/** The car's screen, as the driver chose it on the phone: how large its map draws. */
data class CarScreenSettings(
    /** The size of the car's map against the size the car asks for; x1.5 when never chosen. */
    val mapSize: CarMapSize = CarMapSize.DEFAULT,
)

/** The look section: Auto, Day or Night, and under Auto when the look changes next. */
data class LookSettings(
    val choice: LookChoice = LookChoice.DEFAULT,
    /** Null with Day or Night, under the time zone's guess, and when the sun does not cross within a day. */
    val next: NextLook? = null,
)

/** A recent trip session, and whose token it ran on. */
data class RecentTrip(val start: TripSessionStart, val token: TripsOf)

/**
 * The debug section: trip sessions started on this phone, the simulated drive, own route line and
 * measuring switches, the side roads' fields on routes, the car map's frames and its lighter map
 * when hot, and the debug log.
 */
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
    /** Whether a drive with the lanes drawn by the app measures what they cost the map's frames. */
    val measureOwnLanes: Boolean = false,
    /** Whether the routes ask for the side roads' access and form of way, to measure them. */
    val sideRoadFields: Boolean = false,
    /** The frames a second the car's map draws: 30, or 60 ([CarFrameRate.TARGETS]). */
    val carMapFps: Int = CarFrameRate.TARGET_FPS,
    /** Whether the car's map gets lighter while the phone is hot. */
    val carLightWhenHot: Boolean = CAR_LIGHT_WHEN_HOT_DEFAULT,
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
    /** What became of the last sound Play the tones or a level chosen asked for (the app's tones). */
    tonesHeard: Flow<ToneResult?> = flowOf(null),
    /** Play the tones: the one tone, then the two, as the tones would sound them. */
    private val playTones: () -> Unit = {},
    /** The one tone at a level just chosen, so the driver hears it at once. */
    private val playToneAt: (ToneLevel) -> Unit = {},
) : ViewModel() {

    private val logBytes = MutableStateFlow<Long?>(null)

    val guidance: StateFlow<GuidanceSettings> =
        combine(
            settings.cameraStyle,
            settings.laneZoom,
            settings.lanesOnMap,
            settings.laneCountsHorizon,
            settings.driveCamera,
        ) { style, laneZoom, lanesOnMap, horizon, driveCamera ->
            GuidanceSettings(cameraStyle = style, laneZoom = laneZoom, lanesOnMap = lanesOnMap, laneCountsHorizon = horizon, driveCamera = driveCamera)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuidanceSettings())

    /** The car's screen: the size of its map. */
    val carScreen: StateFlow<CarScreenSettings> =
        settings.carMapSize.map { CarScreenSettings(mapSize = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarScreenSettings())

    /**
     * The choice and its next change, worked out again whenever Settings opens, the choice changes
     * or the look on screen does. The time is shown on the screen only, never logged or stored.
     */
    val look: StateFlow<LookSettings> =
        combine(settings.dayNight, shownLook) { choice, shown -> LookSettings(choice, nextLook(choice, shown)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LookSettings())

    /** The car map's two Debug settings, in one value. */
    private data class CarSwitches(val carMapFps: Int, val carLight: Boolean)

    /** The Debug switches, in one value. */
    private data class Switches(
        val simulate: Boolean,
        val ownLine: Boolean,
        val measureLanes: Boolean,
        val sideRoads: Boolean,
        val car: CarSwitches,
    )

    private val switches = combine(
        settings.simulateDrive,
        settings.ownRouteLine,
        settings.measureOwnLanes,
        settings.sideRoadFields,
        combine(settings.carMapFps, settings.carLightWhenHot, ::CarSwitches),
    ) { simulate, ownLine, measure, sideRoads, car ->
        Switches(simulate, ownLine, measure, sideRoads, car)
    }

    val debug: StateFlow<DebugState> =
        combine(tripLog.entries, switches, logBytes, tokens) { entries, switches, bytes, list ->
            val time = now()
            DebugState(
                trips = TripLog.monthCount(entries, time, installedAt()),
                tripsByToken = TokenTripCounts.rows(TripLog.countByToken(entries, time), list).takeIf { it.size > 1 }.orEmpty(),
                recentTrips = entries.takeLast(RECENT_TRIPS).asReversed().map { RecentTrip(it, TokenTripCounts.of(it.tokenId, list)) },
                simulateDrive = switches.simulate,
                ownRouteLine = switches.ownLine,
                measureOwnLanes = switches.measureLanes,
                sideRoadFields = switches.sideRoads,
                carMapFps = switches.car.carMapFps,
                carLightWhenHot = switches.car.carLight,
                logBytes = bytes,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugState())

    /** What became of the last sound Settings played, with the car's screen: two parts of [values] in one. */
    private val heardAndCar = combine(tonesHeard, carScreen) { heard, car -> heard to car }

    /**
     * Everything Settings shows, in one value, for the catalogue ([SettingsCatalogue]): the guidance, the car's screen,
     * the look and the debug section as above, the tones' settings and what became of the last sound Settings played.
     */
    val values: StateFlow<SettingsValues> =
        combine(guidance, look, debug, settings.sound, heardAndCar) { guidance, look, debug, sound, (heard, car) ->
            SettingsValues(guidance = guidance, carScreen = car, look = look, debug = debug, sound = sound, heard = heard)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsValues())

    /**
     * What the driver did with a row: stored through the row's own setter, by the options the catalogue lists
     * for a choice. A row that stores nothing, as a line of information or an action the screen carries out
     * itself, changes nothing here. Every row is named, so a new one does not compile until it is handled.
     */
    fun onEvent(event: SettingsEvent) {
        when (event.id) {
            SettingId.DRIVE_VIEW -> event.chosen(SettingsCatalogue.driveViews) { view -> updateDriveCamera { it.copy(driveView = view) } }
            SettingId.OVERVIEW_TURN_ZOOM -> event.switched { on -> updateDriveCamera { it.copy(turnZoom = on) } }
            SettingId.RECENTER_SECONDS -> event.chosen(SettingsCatalogue.recenterSeconds) { seconds -> updateDriveCamera { it.copy(recenterSeconds = seconds) } }
            SettingId.CAMERA_STYLE -> event.chosen(SettingsCatalogue.cameraStyles, ::setCameraStyle)
            SettingId.LANE_ZOOM -> event.chosen(SettingsCatalogue.laneZooms, ::setLaneZoom)
            SettingId.LANES_ON_MAP -> event.chosen(SettingsCatalogue.lanesOnMap, ::setLanesOnMap)
            SettingId.LANE_COUNTS_HORIZON -> event.switched(::setLaneCountsHorizon)
            SettingId.CAR_MAP_SIZE -> event.chosen(SettingsCatalogue.carMapSizes, ::setCarMapSize)
            SettingId.TONES -> event.switched { on -> updateSound { it.copy(tones = on) } }
            SettingId.TONE_BEFORE_TURN -> event.switched { on -> updateSound { it.copy(beforeTurn = on) } }
            SettingId.TONE_AT_TURN -> event.switched { on -> updateSound { it.copy(atTurn = on) } }
            SettingId.TONE_DUCK -> event.chosen(SettingsCatalogue.duckModes) { duck -> updateSound { it.copy(duck = duck) } }
            SettingId.TONE_LEVEL -> event.chosen(SettingsCatalogue.toneLevels) { level ->
                updateSound { it.copy(level = level) }
                // Heard at once, at the level chosen, whether or not it could be stored.
                playToneAt(level)
            }
            SettingId.TONE_LEAD_IN -> event.chosen(SettingsCatalogue.toneLeadIns) { millis -> updateSound { it.copy(leadInMillis = millis) } }
            SettingId.PLAY_TONES -> if (event is SettingsEvent.Acted) playTones()
            SettingId.DAY_NIGHT -> event.chosen(SettingsCatalogue.lookChoices, ::setDayNight)
            SettingId.SIMULATE_DRIVE -> event.switched(::setSimulateDrive)
            SettingId.OWN_ROUTE_LINE -> event.switched(::setOwnRouteLine)
            SettingId.MEASURE_OWN_LANES -> event.switched(::setMeasureOwnLanes)
            SettingId.SIDE_ROAD_FIELDS -> event.switched(::setSideRoadFields)
            SettingId.CAR_MAP_FPS -> event.chosen(SettingsCatalogue.carMapFps, ::setCarMapFps)
            SettingId.CAR_LIGHT_WHEN_HOT -> event.switched(::setCarLightWhenHot)
            SettingId.CLEAR_LOG -> if (event is SettingsEvent.Acted) clearLog()
            // Shown only, or carried out by the screen: the token list, the web pages, sharing the log and the crash.
            SettingId.TOKENS, SettingId.TRIP_SESSIONS, SettingId.MAPBOX_STATISTICS, SettingId.RECENT_TRIPS,
            SettingId.TEMPORARY_KEY, SettingId.DEBUG_LOG, SettingId.SHARE_LOG, SettingId.CRASH_NOW,
            SettingId.ABOUT, SettingId.PRIVACY_POLICY,
            -> Unit
        }
    }

    /** The option this event chose, of [options] as the catalogue lists them; nothing for another kind of event. */
    private inline fun <T> SettingsEvent.chosen(options: List<T>, store: (T) -> Unit) {
        val option = (this as? SettingsEvent.Chosen)?.option ?: return
        options.getOrNull(option)?.let(store)
    }

    /** The state this event switched to; nothing for another kind of event. */
    private inline fun SettingsEvent.switched(store: (Boolean) -> Unit) {
        (this as? SettingsEvent.Switched)?.let { store(it.on) }
    }

    /**
     * Applies from the next tone on, also during a drive; a tone that sounds now plays to its end. One write of the keys
     * [change] changes.
     */
    private fun updateSound(change: (SoundSettings) -> SoundSettings) {
        viewModelScope.launch {
            try {
                settings.updateSound(change)
            } catch (e: IOException) {
                // Not stored, so the row keeps showing the stored value, which the tones keep too.
                debugLog.error("settings.updateSound", e)
            }
        }
    }

    /**
     * Applies at once, also during a drive, on the phone's map and the car's: another drive view ends a hold of the map and
     * shows the view it wants; Recenter by itself counts from the next touch. One write of the keys [change] changes.
     */
    private fun updateDriveCamera(change: (DriveCameraSettings) -> DriveCameraSettings) {
        viewModelScope.launch {
            try {
                settings.updateDriveCamera(change)
            } catch (e: IOException) {
                // Not stored, so the row keeps showing the stored value, which the maps keep too.
                debugLog.error("settings.updateDriveCamera", e)
            }
        }
    }

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

    /**
     * Applies at once, also during a drive: with the lanes drawn by the app the measuring begins, or ends with the view it
     * measured.
     */
    fun setMeasureOwnLanes(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setMeasureOwnLanes(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setMeasureOwnLanes", e)
            }
        }
    }

    /**
     * Applies from the next route request on, also during a drive (the next reroute): the routes ask for the side roads'
     * access and form of way, or no longer do. No request is sent for it.
     */
    fun setSideRoadFields(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setSideRoadFields(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the requests keep too.
                debugLog.error("settings.setSideRoadFields", e)
            }
        }
    }

    /** Applies at once, also during a drive: the car's map draws 30 or 60 frames a second from its next frames on. */
    fun setCarMapFps(fps: Int) {
        viewModelScope.launch {
            try {
                settings.setCarMapFps(fps)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the car's map keeps too.
                debugLog.error("settings.setCarMapFps", e)
            }
        }
    }

    /** Applies at once, also during a drive: the car's map leaves out its 3D objects while the phone is hot, or never. */
    fun setCarLightWhenHot(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setCarLightWhenHot(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which the car's map keeps too.
                debugLog.error("settings.setCarLightWhenHot", e)
            }
        }
    }

    /** Stored at once, also during a drive, for the car's map, which follows the stored size; the phone's map keeps its own. */
    fun setCarMapSize(size: CarMapSize) {
        viewModelScope.launch {
            try {
                settings.setCarMapSize(size)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the car's map keeps too.
                debugLog.error("settings.setCarMapSize", e)
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

    /**
     * Applies at once, also during a drive: the map fades Mapbox's lane markings in or out, and draws the app's own lanes
     * from the next route line it draws, or takes them away. The drive itself goes on as it was.
     */
    fun setLanesOnMap(choice: LanesOnMap) {
        viewModelScope.launch {
            try {
                settings.setLanesOnMap(choice)
            } catch (e: IOException) {
                // Not stored, so the choice keeps showing the stored value, which the map keeps too.
                debugLog.error("settings.setLanesOnMap", e)
            }
        }
    }

    /**
     * Applies at once, also during a drive: guidance listens to Mapbox's electronic horizon for the lane counts between lane
     * points from then on, or no longer. The drive itself goes on as it was.
     */
    fun setLaneCountsHorizon(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setLaneCountsHorizon(enabled)
            } catch (e: IOException) {
                // Not stored, so the switch keeps showing the stored value, which guidance keeps too.
                debugLog.error("settings.setLaneCountsHorizon", e)
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
