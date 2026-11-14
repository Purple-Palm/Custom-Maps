package io.github.purplepalm.custommaps.ui.settings

import androidx.annotation.StringRes
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.format.SizeFormat
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.DuckPlan
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.settings.SoundSettings
import io.github.purplepalm.custommaps.sound.ToneResult
import io.github.purplepalm.custommaps.token.TripsOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Every row of Settings as data: which rows exist, where each is at home, in which order, what it says
 * and what it shows now. The screen draws them ([SettingsRows]) and sends what the driver does as a
 * [SettingsEvent]; the view model stores it. A new setting is one entry in [SettingId], its row here,
 * one branch in SettingsViewModel.onEvent and its strings. No Compose here, and no Android type but
 * resource ids.
 */

/** The tabs of Settings, in their order. Until the tabs are built, one page shows every tab's groups in this order. */
enum class SettingsTab { MAP, ROUTE, SOUND, TRIPS, APP }

/** A page that a row of a tab opens once the tabs are built; until then its rows are groups of the one page. */
enum class SettingsPage(val tab: SettingsTab) {
    TOKEN(SettingsTab.APP),
    DEBUG(SettingsTab.APP),
}

/**
 * One row of Settings: the stored [key] it writes (null for a row that stores nothing), the [tab] it is
 * at home on, and the [page] of that tab it goes on once the tabs are built (null: on the tab itself).
 */
enum class SettingId(val key: String?, val tab: SettingsTab, val page: SettingsPage? = null) {
    DRIVE_VIEW("drive_view", SettingsTab.MAP),
    OVERVIEW_TURN_ZOOM("overview_turn_zoom", SettingsTab.MAP),
    RECENTER_SECONDS("recenter_seconds", SettingsTab.MAP),
    CAMERA_STYLE("camera_style", SettingsTab.MAP),
    LANE_ZOOM("lane_zoom", SettingsTab.MAP),
    LANES_ON_MAP("lanes_on_map", SettingsTab.MAP),
    LANE_COUNTS_HORIZON("lane_counts_horizon", SettingsTab.MAP),
    TONES("tones", SettingsTab.SOUND),
    TONE_BEFORE_TURN("tone_before_turn", SettingsTab.SOUND),
    TONE_AT_TURN("tone_at_turn", SettingsTab.SOUND),
    TONE_DUCK("tone_duck", SettingsTab.SOUND),
    TONE_LEVEL("tone_level", SettingsTab.SOUND),
    PLAY_TONES(null, SettingsTab.SOUND),
    DAY_NIGHT("day_night", SettingsTab.APP),
    TOKENS(null, SettingsTab.APP, SettingsPage.TOKEN),
    TRIP_SESSIONS(null, SettingsTab.APP),
    MAPBOX_STATISTICS(null, SettingsTab.APP, SettingsPage.DEBUG),
    RECENT_TRIPS(null, SettingsTab.APP, SettingsPage.DEBUG),
    SIMULATE_DRIVE("simulate_drive", SettingsTab.APP, SettingsPage.DEBUG),
    OWN_ROUTE_LINE("own_route_line", SettingsTab.APP, SettingsPage.DEBUG),
    MEASURE_OWN_LANES("measure_own_lanes", SettingsTab.APP, SettingsPage.DEBUG),
    SIDE_ROAD_FIELDS("side_road_fields", SettingsTab.APP, SettingsPage.DEBUG),
    CAR_MAP_FPS("car_map_fps", SettingsTab.APP, SettingsPage.DEBUG),
    CAR_LIGHT_WHEN_HOT("car_light_when_hot", SettingsTab.APP, SettingsPage.DEBUG),
    TONE_LEAD_IN("tone_lead_in_ms", SettingsTab.APP, SettingsPage.DEBUG),
    TEMPORARY_KEY(null, SettingsTab.APP, SettingsPage.DEBUG),
    DEBUG_LOG(null, SettingsTab.APP, SettingsPage.DEBUG),
    SHARE_LOG(null, SettingsTab.APP, SettingsPage.DEBUG),
    CLEAR_LOG(null, SettingsTab.APP, SettingsPage.DEBUG),
    CRASH_NOW(null, SettingsTab.APP, SettingsPage.DEBUG),
    ABOUT(null, SettingsTab.APP),
    PRIVACY_POLICY(null, SettingsTab.APP),
}

/** Words of a row, put together on screen: a string with its arguments, which may be words of their own, or words joined. */
sealed interface RowText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : RowText

    /** Words that are not translated: a name the driver gave, a time. */
    data class Plain(val text: String) : RowText

    data class Joined(val parts: List<RowText>, val separator: String) : RowText
}

/** A line under a row's name and the tone it reads in. */
data class RowNote(val text: RowText, val tone: LineTone = LineTone.NOTE)

/** One option of a choice: its name and what it does. */
data class ChoiceOption(val title: RowText, val note: RowText? = null)

/** The question an action asks before it is done: its title, its message and the word of the action. */
data class Confirm(@StringRes val title: Int, @StringRes val message: Int, @StringRes val action: Int)

/** One row, of the kinds [SettingsRows] draws. */
sealed interface SettingRow {
    val id: SettingId
    val lines: List<RowNote>
}

data class SwitchRow(
    override val id: SettingId,
    val title: RowText,
    val on: Boolean,
    override val lines: List<RowNote> = emptyList(),
    /** A word in a thin frame beside the name, as "Mapbox beta". */
    val badge: RowText? = null,
) : SettingRow

data class ChoiceRow(
    override val id: SettingId,
    val title: RowText?,
    val options: List<ChoiceOption>,
    val selected: Int,
    /** A list with a line for each option, for options that need one; else a row of segments. */
    val folds: Boolean = false,
    override val lines: List<RowNote> = emptyList(),
) : SettingRow

/** A line that opens a web page ([url]); a page of Settings once the tabs are built. */
data class LinkRow(
    override val id: SettingId,
    val title: RowText,
    val url: String,
    override val lines: List<RowNote> = emptyList(),
) : SettingRow

data class InfoRow(
    override val id: SettingId,
    val title: RowText?,
    override val lines: List<RowNote> = emptyList(),
) : SettingRow

data class ActionRow(
    override val id: SettingId,
    val title: RowText,
    override val lines: List<RowNote> = emptyList(),
    val enabled: Boolean = true,
    /** Asked first, for an action that cannot be undone. */
    val confirm: Confirm? = null,
) : SettingRow

/** A row the screen draws with a section of its own, as the list of tokens with its dialogs. */
data class BlockRow(override val id: SettingId) : SettingRow {
    override val lines: List<RowNote> get() = emptyList()
}

/** The rows under one title, all of one tab. */
data class SettingsGroup(val tab: SettingsTab, @StringRes val title: Int?, val rows: List<SettingRow>)

/** Everything Settings shows, in one value: the stored settings and what the app knows of itself. */
data class SettingsValues(
    val guidance: GuidanceSettings = GuidanceSettings(),
    val look: LookSettings = LookSettings(),
    val debug: DebugState = DebugState(),
    /** The guidance tones' settings. */
    val sound: SoundSettings = SoundSettings(),
    /** What became of the last sound Play the tones or a level chosen asked for; null before any, and while one is on its way. */
    val heard: ToneResult? = null,
    /** Debug builds only: Crash now is offered. */
    val crashNow: Boolean = false,
    /** A debug build signed with the default Android debug key, which the next CI build cannot be installed over. */
    val temporaryDebugKey: Boolean = false,
)

/** What the driver did with a row. */
sealed interface SettingsEvent {
    val id: SettingId

    data class Switched(override val id: SettingId, val on: Boolean) : SettingsEvent

    /** The option at [option] of the row's options, in the order the catalogue lists them. */
    data class Chosen(override val id: SettingId, val option: Int) : SettingsEvent

    data class Acted(override val id: SettingId) : SettingsEvent
}

/** The rows of every tab and what they show for a set of [SettingsValues]. */
object SettingsCatalogue {

    /** The options of each choice, in the order its row lists them; the view model stores by the same lists. */
    val driveViews: List<DriveView> = DriveView.entries
    val recenterSeconds: List<Int> = DriveCameraSettings.RECENTER_CHOICES
    val cameraStyles: List<CameraStyle> = CameraStyle.entries
    val laneZooms: List<LaneZoom> = LaneZoom.entries
    val lanesOnMap: List<LanesOnMap> = LanesOnMap.entries
    val lookChoices: List<LookChoice> = LookChoice.entries
    val carMapFps: List<Int> = CarFrameRate.TARGETS
    val duckModes: List<DuckMode> = DuckMode.entries
    val toneLevels: List<ToneLevel> = ToneLevel.entries
    val toneLeadIns: List<Int> = DuckPlan.WAKE_LEAD_INS

    /** Usage of billable products per month, in the Mapbox console. */
    const val MAPBOX_STATISTICS_URL = "https://console.mapbox.com/account/statistics/"

    /** The groups of [tab] for [values], in their order; the times of recent trips in [zone]. */
    fun groups(tab: SettingsTab, values: SettingsValues, zone: ZoneId = ZoneId.systemDefault()): List<SettingsGroup> = when (tab) {
        SettingsTab.MAP -> listOf(SettingsGroup(tab, R.string.guidance_title, guidanceRows(values.guidance)))
        SettingsTab.SOUND -> listOf(SettingsGroup(tab, R.string.sound_title, soundRows(values)))
        SettingsTab.ROUTE, SettingsTab.TRIPS -> emptyList()
        SettingsTab.APP -> listOf(
            SettingsGroup(tab, R.string.day_night_title, listOf(dayNightRow(values.look))),
            SettingsGroup(tab, R.string.token_title, listOf(BlockRow(SettingId.TOKENS))),
            SettingsGroup(tab, R.string.trip_sessions_title, tripRows(values.debug, zone)),
            SettingsGroup(tab, R.string.debug_title, debugRows(values)),
            SettingsGroup(tab, R.string.about_title, aboutRows()),
        )
    }

    /** The one page Settings is until the tabs are built: the groups of every tab, in the tabs' order. */
    fun page(values: SettingsValues, zone: ZoneId = ZoneId.systemDefault()): List<SettingsGroup> =
        SettingsTab.entries.flatMap { groups(it, values, zone) }

    /** The stored keys at home on [tab], on the tab itself or on one of its pages. */
    fun keysOn(tab: SettingsTab): Set<String> = SettingId.entries.filter { it.tab == tab }.mapNotNullTo(LinkedHashSet()) { it.key }

    /** The stored keys whose rows go on [page] once the tabs are built. */
    fun keysOn(page: SettingsPage): Set<String> = SettingId.entries.filter { it.page == page }.mapNotNullTo(LinkedHashSet()) { it.key }

    /**
     * The guidance group: first the drive views (the drive view, which the guidance bar's Overview button switches and
     * stores too, Zoom in before turns right under it, and Recenter by itself), then the camera style, the lane zoom and
     * the lanes on the map.
     */
    private fun guidanceRows(guidance: GuidanceSettings): List<SettingRow> = listOf(
        ChoiceRow(
            id = SettingId.DRIVE_VIEW,
            title = text(R.string.drive_view_title),
            options = driveViews.map { ChoiceOption(text(it.titleRes())) },
            selected = driveViews.indexOf(guidance.driveCamera.driveView),
            lines = buildList {
                add(RowNote(text(R.string.drive_view_note)))
                // The Mapbox styles carry the drive view's moves with flights of their own, less smooth than the glides.
                if (guidance.cameraStyle != CameraStyle.SMOOTH_FOLLOW) add(RowNote(text(R.string.drive_view_mapbox_styles_note), LineTone.NOTE_NOW))
            },
        ),
        // Read in the Overview only: off, the Overview stays up all the way.
        SwitchRow(
            id = SettingId.OVERVIEW_TURN_ZOOM,
            title = text(R.string.overview_turn_zoom_title),
            on = guidance.driveCamera.turnZoom,
            lines = listOf(RowNote(text(R.string.overview_turn_zoom_note))),
        ),
        ChoiceRow(
            id = SettingId.RECENTER_SECONDS,
            title = text(R.string.recenter_seconds_title),
            options = recenterSeconds.map { seconds ->
                ChoiceOption(if (seconds == 0) text(R.string.recenter_seconds_off) else text(R.string.recenter_seconds_value, seconds))
            },
            selected = recenterSeconds.indexOf(guidance.driveCamera.recenterSeconds),
            lines = listOf(RowNote(text(R.string.recenter_seconds_note))),
        ),
        ChoiceRow(
            id = SettingId.CAMERA_STYLE,
            title = text(R.string.camera_style_title),
            options = cameraStyles.map { ChoiceOption(text(it.titleRes()), text(it.noteRes())) },
            selected = cameraStyles.indexOf(guidance.cameraStyle),
            folds = true,
            lines = buildList {
                add(RowNote(text(R.string.camera_style_note)))
                // Where the app's lit lane does not take the line's place, the line stays in the lane to take.
                if (guidance.lanesOnMap.appDraws) add(RowNote(text(R.string.camera_style_app_lanes_note), LineTone.NOTE_NOW))
            },
        ),
        ChoiceRow(
            id = SettingId.LANE_ZOOM,
            title = text(R.string.lane_zoom_title),
            options = laneZooms.map { ChoiceOption(text(it.titleRes()), text(it.noteRes())) },
            selected = laneZooms.indexOf(guidance.laneZoom),
            // That style is the camera as it comes, for comparison: the choice does not reach it.
            lines = if (guidance.cameraStyle == CameraStyle.MAPBOX_STANDARD) {
                listOf(RowNote(text(R.string.lane_zoom_mapbox_standard_note), LineTone.NOTE_NOW))
            } else {
                emptyList()
            },
        ),
        // Who draws the lanes on the map; the note says what none of them is: the car's lane.
        ChoiceRow(
            id = SettingId.LANES_ON_MAP,
            title = text(R.string.lanes_on_map_title),
            options = lanesOnMap.map { ChoiceOption(text(it.titleRes()), text(it.noteRes())) },
            selected = lanesOnMap.indexOf(guidance.lanesOnMap),
            folds = true,
            lines = listOf(RowNote(text(R.string.lanes_on_map_note))),
        ),
        // A beta of Mapbox's, named one beside its title; on until the driver turns it off.
        SwitchRow(
            id = SettingId.LANE_COUNTS_HORIZON,
            title = text(R.string.lane_counts_horizon_title),
            on = guidance.laneCountsHorizon,
            lines = listOf(RowNote(text(R.string.lane_counts_horizon_note))),
            badge = text(R.string.mapbox_beta_label),
        ),
    )

    /**
     * The guidance tones: the master switch, and under it each part with its own switch, the dip of the music, the
     * level and Play the tones. With Tones off the switch is the only row, as nothing under it would sound. Under
     * Play the tones, why the last sound it played was not heard, where the app knows.
     */
    private fun soundRows(values: SettingsValues): List<SettingRow> {
        val sound = values.sound
        val tones = switch(SettingId.TONES, R.string.tones_title, sound.tones, R.string.tones_note)
        if (!sound.tones) return listOf(tones)
        return listOf(
            tones,
            switch(SettingId.TONE_BEFORE_TURN, R.string.tone_before_turn_title, sound.beforeTurn, R.string.tone_before_turn_note),
            switch(SettingId.TONE_AT_TURN, R.string.tone_at_turn_title, sound.atTurn, R.string.tone_at_turn_note),
            ChoiceRow(
                id = SettingId.TONE_DUCK,
                title = text(R.string.tone_duck_title),
                options = duckModes.map { ChoiceOption(text(it.titleRes())) },
                selected = duckModes.indexOf(sound.duck),
                lines = listOf(RowNote(text(R.string.tone_duck_note))),
            ),
            // Choosing a level plays the one tone at it (the view model's).
            ChoiceRow(
                id = SettingId.TONE_LEVEL,
                title = text(R.string.tone_level_title),
                options = toneLevels.map { ChoiceOption(text(it.titleRes())) },
                selected = toneLevels.indexOf(sound.level),
                lines = listOf(RowNote(text(R.string.tone_level_note))),
            ),
            ActionRow(
                id = SettingId.PLAY_TONES,
                title = text(R.string.play_tones),
                lines = listOfNotNull(
                    RowNote(text(R.string.play_tones_note)),
                    values.heard?.reasonRes()?.let { RowNote(text(it)) },
                ),
            ),
        )
    }

    /**
     * Auto, Day or Night. Under Auto the note says what it does, and when the look changes next: on the
     * screen only, never logged or stored.
     */
    private fun dayNightRow(look: LookSettings): SettingRow = ChoiceRow(
        id = SettingId.DAY_NIGHT,
        title = null,
        options = lookChoices.map { ChoiceOption(text(it.titleRes())) },
        selected = lookChoices.indexOf(look.choice),
        lines = buildList {
            if (look.choice == LookChoice.AUTO) {
                add(RowNote(text(R.string.day_night_auto_note)))
                look.next?.let { next -> add(RowNote(text(next.look.nextRes(), RowText.Plain(NEXT_LOOK_TIME.format(next.at))), LineTone.VALUE)) }
            }
        },
    )

    /** Trip sessions are what Mapbox bills for navigation, so their count is kept in sight. */
    private fun tripRows(debug: DebugState, zone: ZoneId): List<SettingRow> {
        val trips = debug.trips
        val count = when (val since = trips.partialSince) {
            null -> text(R.string.debug_trips_this_month, trips.count)
            else -> text(tripsSinceRes(trips.began), trips.count, RowText.Plain(TRIPS_SINCE_DAY.format(since)))
        }
        return listOf(
            InfoRow(
                id = SettingId.TRIP_SESSIONS,
                title = count,
                // By the token each ran on, when there is more than one line.
                lines = debug.tripsByToken.map { row -> RowNote(text(R.string.debug_trips_token, tripsOf(row.of), row.count), LineTone.VALUE) } +
                    RowNote(text(R.string.debug_trips_note)),
            ),
            LinkRow(SettingId.MAPBOX_STATISTICS, text(R.string.debug_open_statistics), MAPBOX_STATISTICS_URL),
            InfoRow(
                id = SettingId.RECENT_TRIPS,
                title = text(R.string.debug_recent_title),
                lines = if (debug.recentTrips.isEmpty()) {
                    listOf(RowNote(text(R.string.debug_recent_empty), LineTone.VALUE_MUTED))
                } else {
                    debug.recentTrips.map { (trip, token) ->
                        val parts = listOfNotNull(
                            RowText.Plain(TRIP_TIME.format(trip.startedAt.atZone(zone))),
                            text(trip.kind.labelRes()),
                            if (trip.simulated) text(R.string.debug_trip_simulated) else null,
                            // A session from before tokens were named shows none.
                            if (token == TripsOf.BeforeNames) null else tripsOf(token),
                        )
                        RowNote(RowText.Joined(parts, " · "), LineTone.VALUE_MUTED)
                    }
                },
            ),
        )
    }

    private fun debugRows(values: SettingsValues): List<SettingRow> {
        val debug = values.debug
        val hasLog = (debug.logBytes ?: 0L) > 0L
        return buildList {
            add(switch(SettingId.SIMULATE_DRIVE, R.string.debug_simulate_drive, debug.simulateDrive, R.string.debug_simulate_drive_note))
            // A test of the route line the app draws itself; the SDK's comes back by itself if it fails.
            add(switch(SettingId.OWN_ROUTE_LINE, R.string.debug_own_route_line, debug.ownRouteLine, R.string.debug_own_route_line_note))
            // What the lanes drawn by the app cost the map's frames, measured on a drive.
            add(switch(SettingId.MEASURE_OWN_LANES, R.string.debug_measure_own_lanes, debug.measureOwnLanes, R.string.debug_measure_own_lanes_note))
            // What Mapbox sends of the side roads at each intersection of a route, measured before the street lanes use it.
            add(switch(SettingId.SIDE_ROAD_FIELDS, R.string.debug_side_road_fields, debug.sideRoadFields, R.string.debug_side_road_fields_note))
            // The car's map at 30 or 60 frames a second, to compare what the car shows of each.
            add(
                ChoiceRow(
                    id = SettingId.CAR_MAP_FPS,
                    title = text(R.string.debug_car_map_fps),
                    options = carMapFps.map { ChoiceOption(text(R.string.debug_car_map_fps_value, it)) },
                    selected = carMapFps.indexOf(debug.carMapFps),
                    lines = listOf(RowNote(text(R.string.debug_car_map_fps_note))),
                ),
            )
            // The car's map without its 3D objects while the phone is hot.
            add(switch(SettingId.CAR_LIGHT_WHEN_HOT, R.string.debug_car_light_when_hot, debug.carLightWhenHot, R.string.debug_car_light_when_hot_note))
            // The silence in front of a tone on a Bluetooth output that may be asleep, to find the shortest that wakes it.
            add(
                ChoiceRow(
                    id = SettingId.TONE_LEAD_IN,
                    title = text(R.string.debug_tone_lead_in),
                    options = toneLeadIns.map { ChoiceOption(RowText.Plain(it.toString())) },
                    selected = toneLeadIns.indexOf(values.sound.leadInMillis),
                    lines = listOf(RowNote(text(R.string.debug_tone_lead_in_note))),
                ),
            )
            if (values.temporaryDebugKey) add(InfoRow(SettingId.TEMPORARY_KEY, null, listOf(RowNote(text(R.string.debug_temporary_key), LineTone.WARNING))))
            add(
                InfoRow(
                    id = SettingId.DEBUG_LOG,
                    title = text(R.string.debug_log_title),
                    lines = listOf(
                        RowNote(
                            when (val bytes = debug.logBytes) {
                                null -> text(R.string.debug_log_measuring)
                                0L -> text(R.string.debug_log_empty)
                                else -> text(R.string.debug_log_size, RowText.Plain(SizeFormat.bytes(bytes)))
                            },
                            LineTone.VALUE,
                        ),
                        RowNote(text(R.string.debug_log_note)),
                    ),
                ),
            )
            add(ActionRow(SettingId.SHARE_LOG, text(R.string.debug_log_share), enabled = hasLog))
            add(
                ActionRow(
                    id = SettingId.CLEAR_LOG,
                    title = text(R.string.debug_log_clear),
                    enabled = hasLog,
                    confirm = Confirm(R.string.debug_log_clear_title, R.string.debug_log_clear_message, R.string.debug_log_clear),
                ),
            )
            // Debug builds only: a deliberate crash, to check the crash record on the phone.
            if (values.crashNow) {
                add(
                    ActionRow(
                        id = SettingId.CRASH_NOW,
                        title = text(R.string.debug_crash_now),
                        lines = listOf(RowNote(text(R.string.debug_crash_now_note))),
                        confirm = Confirm(R.string.debug_crash_now_title, R.string.debug_crash_now_message, R.string.debug_crash_now),
                    ),
                )
            }
        }
    }

    /** The version and the map data's attribution; the privacy policy, which opens in the browser. */
    private fun aboutRows(): List<SettingRow> = listOf(
        InfoRow(
            id = SettingId.ABOUT,
            title = null,
            lines = listOf(
                RowNote(text(R.string.about_version, text(R.string.app_version), text(R.string.build_revision)), LineTone.VALUE_MUTED),
                RowNote(text(R.string.about_map_data)),
            ),
        ),
        LinkRow(SettingId.PRIVACY_POLICY, text(R.string.about_privacy_policy), PRIVACY_POLICY_URL),
    )

    private fun switch(id: SettingId, @StringRes title: Int, on: Boolean, @StringRes note: Int): SettingRow =
        SwitchRow(id, text(title), on, listOf(RowNote(text(note))))

    /** The name a count or a trip goes under: the token's own, or what stands for a token that is gone or had none. */
    private fun tripsOf(of: TripsOf): RowText = when (of) {
        is TripsOf.Token -> RowText.Plain(of.name)
        TripsOf.Deleted -> text(R.string.debug_trips_deleted_token)
        TripsOf.BeforeNames -> text(R.string.debug_trips_before_names)
    }

    private fun text(@StringRes id: Int, vararg args: Any): RowText = RowText.Res(id, args.toList())

    private val TRIP_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    /** The day since which the month's trip count runs, when the log began after the month did. */
    private val TRIPS_SINCE_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** When the look changes next under Auto, written as the guidance bar writes the arrival time. */
    private val NEXT_LOOK_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
}

/**
 * The words for a month's trip count that does not cover the whole month: since the installation,
 * or since the oldest trip the log kept ([LogBegan]).
 */
@StringRes
internal fun tripsSinceRes(began: LogBegan?): Int = when (began) {
    LogBegan.OLDEST_KEPT -> R.string.debug_trips_since_oldest_kept
    LogBegan.INSTALLATION, null -> R.string.debug_trips_since_installation
}

@StringRes
private fun DriveView.titleRes(): Int = when (this) {
    DriveView.NORMAL -> R.string.drive_view_normal
    DriveView.OVERVIEW -> R.string.drive_view_overview
}

@StringRes
private fun CameraStyle.titleRes(): Int = when (this) {
    CameraStyle.MAPBOX_STANDARD -> R.string.camera_style_mapbox_standard
    CameraStyle.MAPBOX_TUNED -> R.string.camera_style_mapbox_tuned
    CameraStyle.SMOOTH_FOLLOW -> R.string.camera_style_smooth_follow
}

@StringRes
private fun CameraStyle.noteRes(): Int = when (this) {
    CameraStyle.MAPBOX_STANDARD -> R.string.camera_style_mapbox_standard_note
    CameraStyle.MAPBOX_TUNED -> R.string.camera_style_mapbox_tuned_note
    CameraStyle.SMOOTH_FOLLOW -> R.string.camera_style_smooth_follow_note
}

@StringRes
private fun LanesOnMap.titleRes(): Int = when (this) {
    LanesOnMap.OFF -> R.string.lanes_on_map_off
    LanesOnMap.MAPBOX -> R.string.lanes_on_map_mapbox
    LanesOnMap.APP -> R.string.lanes_on_map_app
}

@StringRes
private fun LanesOnMap.noteRes(): Int = when (this) {
    LanesOnMap.OFF -> R.string.lanes_on_map_off_note
    LanesOnMap.MAPBOX -> R.string.lanes_on_map_mapbox_note
    LanesOnMap.APP -> R.string.lanes_on_map_app_note
}

@StringRes
private fun DuckMode.titleRes(): Int = when (this) {
    DuckMode.OFF -> R.string.tone_duck_off
    DuckMode.LITTLE -> R.string.tone_duck_little
    DuckMode.MORE -> R.string.tone_duck_more
}

@StringRes
private fun ToneLevel.titleRes(): Int = when (this) {
    ToneLevel.SOFT -> R.string.tone_level_soft
    ToneLevel.NORMAL -> R.string.tone_level_normal
    ToneLevel.LOUD -> R.string.tone_level_loud
}

/** Why a sound was not heard, where the app knows it; none for one played, nor for a failure it cannot name. */
@StringRes
private fun ToneResult.reasonRes(): Int? = when (this) {
    ToneResult.VOLUME_ZERO -> R.string.play_tones_volume_zero
    ToneResult.IN_CALL -> R.string.play_tones_in_call
    ToneResult.NO_FOCUS -> R.string.play_tones_no_focus
    ToneResult.PLAYED, ToneResult.FAILED -> null
}

@StringRes
private fun LookChoice.titleRes(): Int = when (this) {
    LookChoice.AUTO -> R.string.day_night_auto
    LookChoice.DAY -> R.string.day_night_day
    LookChoice.NIGHT -> R.string.day_night_night
}

/** The line that says when Auto brings [this] look next. */
@StringRes
private fun Look.nextRes(): Int = when (this) {
    Look.NIGHT -> R.string.day_night_next_night
    Look.DAY -> R.string.day_night_next_day
}

@StringRes
private fun LaneZoom.titleRes(): Int = when (this) {
    LaneZoom.DEFAULT -> R.string.lane_zoom_default
    LaneZoom.LARGE -> R.string.lane_zoom_large
}

@StringRes
private fun LaneZoom.noteRes(): Int = when (this) {
    LaneZoom.DEFAULT -> R.string.lane_zoom_default_note
    LaneZoom.LARGE -> R.string.lane_zoom_large_note
}

@StringRes
private fun TripKind.labelRes(): Int = when (this) {
    TripKind.ACTIVE_GUIDANCE -> R.string.debug_trip_guidance
    TripKind.FREE_DRIVE -> R.string.debug_trip_free_drive
}
