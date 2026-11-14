package io.github.purplepalm.custommaps.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.car.CarFrameRate
import io.github.purplepalm.custommaps.nav.format.SizeFormat
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.token.TokenProblem
import io.github.purplepalm.custommaps.token.TokenTrips
import io.github.purplepalm.custommaps.token.TripsOf
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.token.TokenListSection
import io.github.purplepalm.custommaps.ui.token.TokenRow
import io.github.purplepalm.custommaps.ui.token.TokenState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Usage of billable products per month, in the Mapbox console. */
private const val MAPBOX_STATISTICS_URL = "https://console.mapbox.com/account/statistics/"

private val TRIP_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** The day since which the month's trip count runs, when the log began after the month did. */
private val TRIPS_SINCE_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** When the look changes next under Auto, written as the guidance bar writes the arrival time. */
private val NEXT_LOOK_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * The words for a month's trip count that does not cover the whole month: since the installation,
 * or since the oldest trip the log kept ([LogBegan]).
 */
@StringRes
internal fun tripsSinceRes(began: LogBegan?): Int = when (began) {
    LogBegan.OLDEST_KEPT -> R.string.debug_trips_since_oldest_kept
    LogBegan.INSTALLATION, null -> R.string.debug_trips_since_installation
}

@Composable
fun SettingsScreen(
    tokens: TokenState,
    look: LookSettings,
    guidance: GuidanceSettings,
    debug: DebugState,
    onAddToken: (name: String?, token: String) -> TokenProblem?,
    onRenameToken: (id: String, name: String) -> TokenProblem?,
    onUseToken: (id: String) -> Unit,
    onDeleteToken: (id: String) -> Unit,
    onDayNightChange: (LookChoice) -> Unit,
    onCameraStyleChange: (CameraStyle) -> Unit,
    onLaneZoomChange: (LaneZoom) -> Unit,
    onLanesOnMapChange: (LanesOnMap) -> Unit,
    onLaneCountsHorizonChange: (Boolean) -> Unit,
    onSimulateDriveChange: (Boolean) -> Unit,
    onOwnRouteLineChange: (Boolean) -> Unit,
    onMeasureOwnLanesChange: (Boolean) -> Unit,
    onSideRoadFieldsChange: (Boolean) -> Unit,
    onCarMapFpsChange: (Int) -> Unit,
    onCarLightWhenHotChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides Crash now: release builds never offer it. */
    onCrashNow: (() -> Unit)? = null,
    /**
     * True for a debug build signed with the default Android debug key, which the next CI build
     * cannot be installed over; the Play build never says so.
     */
    temporaryDebugKey: Boolean = false,
) {
    Surface(modifier = modifier.fillMaxSize(), color = LookTheme.tokens.screen) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.navigate_back),
                        tint = LookTheme.tokens.onCard,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LookTheme.tokens.onCard,
                )
            }
            Spacer(Modifier.height(24.dp))

            GroupTitle(stringResource(R.string.token_title))
            TokenListSection(tokens, onAddToken, onRenameToken, onUseToken, onDeleteToken)
            GroupDivider()
            DayNightSection(look, onDayNightChange)
            GroupDivider()
            GuidanceSection(guidance, onCameraStyleChange, onLaneZoomChange, onLanesOnMapChange, onLaneCountsHorizonChange)
            GroupDivider()
            DebugSection(
                debug,
                onSimulateDriveChange,
                onOwnRouteLineChange,
                onMeasureOwnLanesChange,
                onSideRoadFieldsChange,
                onCarMapFpsChange,
                onCarLightWhenHotChange,
                onShareLog,
                onClearLog,
                onCrashNow,
                temporaryDebugKey,
            )
            GroupDivider()
            AboutSection()
        }
    }
}

/** The version and the map data's attribution, and the privacy policy, which opens in the browser. */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    GroupTitle(stringResource(R.string.about_title))
    InfoRowItem(
        title = null,
        lines = listOf(
            RowLine(
                stringResource(R.string.about_version, stringResource(R.string.app_version), stringResource(R.string.build_revision)),
                LineTone.VALUE_MUTED,
            ),
            RowLine(stringResource(R.string.about_map_data)),
        ),
    )
    // A phone without a browser gets a short note instead.
    LinkRowItem(
        title = stringResource(R.string.about_privacy_policy),
        onClick = {
            if (!context.openWebPage(PRIVACY_POLICY_URL)) {
                Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_LONG).show()
            }
        },
    )
}

/**
 * The day look, the night look, or by the sun. A choice applies at once, to the screen and the map
 * together; under Auto the note says when the look changes next.
 */
@Composable
private fun DayNightSection(look: LookSettings, onDayNightChange: (LookChoice) -> Unit) {
    GroupTitle(stringResource(R.string.day_night_title))
    val choices = LookChoice.entries
    val lines = buildList {
        if (look.choice == LookChoice.AUTO) {
            add(RowLine(stringResource(R.string.day_night_auto_note)))
            look.next?.let { next -> add(RowLine(stringResource(next.look.nextRes(), NEXT_LOOK_TIME.format(next.at)), LineTone.VALUE)) }
        }
    }
    ChoiceRowItem(
        title = null,
        options = choices.map { ChoiceItem(stringResource(it.titleRes())) },
        selected = choices.indexOf(look.choice),
        onSelect = { onDayNightChange(choices[it]) },
        lines = lines,
    )
}

/** How the app guides. Every choice here applies at once, also during a drive. */
@Composable
private fun GuidanceSection(
    guidance: GuidanceSettings,
    onCameraStyleChange: (CameraStyle) -> Unit,
    onLaneZoomChange: (LaneZoom) -> Unit,
    onLanesOnMapChange: (LanesOnMap) -> Unit,
    onLaneCountsHorizonChange: (Boolean) -> Unit,
) {
    GroupTitle(stringResource(R.string.guidance_title))
    val styles = CameraStyle.entries
    ChoiceRowItem(
        title = stringResource(R.string.camera_style_title),
        options = styles.map { ChoiceItem(stringResource(it.titleRes()), stringResource(it.noteRes())) },
        selected = styles.indexOf(guidance.cameraStyle),
        onSelect = { onCameraStyleChange(styles[it]) },
        folds = true,
        lines = buildList {
            add(RowLine(stringResource(R.string.camera_style_note)))
            // Where the app's lit lane does not take the line's place, the line stays in the lane to take.
            if (guidance.lanesOnMap.appDraws) add(RowLine(stringResource(R.string.camera_style_app_lanes_note), LineTone.NOTE_NOW))
        },
    )

    Spacer(Modifier.height(16.dp))
    val zooms = LaneZoom.entries
    ChoiceRowItem(
        title = stringResource(R.string.lane_zoom_title),
        options = zooms.map { ChoiceItem(stringResource(it.titleRes()), stringResource(it.noteRes())) },
        selected = zooms.indexOf(guidance.laneZoom),
        onSelect = { onLaneZoomChange(zooms[it]) },
        // That style is the camera as it comes, for comparison: the choice above does not reach it.
        lines = if (guidance.cameraStyle == CameraStyle.MAPBOX_STANDARD) {
            listOf(RowLine(stringResource(R.string.lane_zoom_mapbox_standard_note), LineTone.NOTE_NOW))
        } else {
            emptyList()
        },
    )

    // Who draws the lanes on the map; the note under them says what none of them is: the car's lane.
    Spacer(Modifier.height(16.dp))
    val lanes = LanesOnMap.entries
    ChoiceRowItem(
        title = stringResource(R.string.lanes_on_map_title),
        options = lanes.map { ChoiceItem(stringResource(it.titleRes()), stringResource(it.noteRes())) },
        selected = lanes.indexOf(guidance.lanesOnMap),
        onSelect = { onLanesOnMapChange(lanes[it]) },
        folds = true,
        lines = listOf(RowLine(stringResource(R.string.lanes_on_map_note))),
    )

    // A Mapbox beta, labelled as one beside its name; on until the driver turns it off.
    Spacer(Modifier.height(8.dp))
    SwitchRowItem(
        title = stringResource(R.string.lane_counts_horizon_title),
        checked = guidance.laneCountsHorizon,
        onCheckedChange = onLaneCountsHorizonChange,
        badge = stringResource(R.string.mapbox_beta_label),
        lines = listOf(RowLine(stringResource(R.string.lane_counts_horizon_note))),
    )
}

/** Trip sessions are what Mapbox bills for navigation, so their count is kept in sight. */
@Composable
private fun DebugSection(
    debug: DebugState,
    onSimulateDriveChange: (Boolean) -> Unit,
    onOwnRouteLineChange: (Boolean) -> Unit,
    onMeasureOwnLanesChange: (Boolean) -> Unit,
    onSideRoadFieldsChange: (Boolean) -> Unit,
    onCarMapFpsChange: (Int) -> Unit,
    onCarLightWhenHotChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onCrashNow: (() -> Unit)?,
    temporaryDebugKey: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    GroupTitle(stringResource(R.string.debug_title))
    val trips = debug.trips
    InfoRowItem(
        title = when (val since = trips.partialSince) {
            null -> stringResource(R.string.debug_trips_this_month, trips.count)
            else -> stringResource(tripsSinceRes(trips.began), trips.count, TRIPS_SINCE_DAY.format(since))
        },
        // By the token each ran on, when there is more than one line.
        lines = debug.tripsByToken.map { row -> RowLine(stringResource(R.string.debug_trips_token, tripsOfText(row.of), row.count), LineTone.VALUE) } +
            RowLine(stringResource(R.string.debug_trips_note)),
    )
    LinkRowItem(title = stringResource(R.string.debug_open_statistics), onClick = { uriHandler.openUri(MAPBOX_STATISTICS_URL) })

    Spacer(Modifier.height(8.dp))
    InfoRowItem(
        title = stringResource(R.string.debug_recent_title),
        lines = if (debug.recentTrips.isEmpty()) {
            listOf(RowLine(stringResource(R.string.debug_recent_empty), LineTone.VALUE_MUTED))
        } else {
            debug.recentTrips.map { (trip, token) ->
                RowLine(
                    listOfNotNull(
                        TRIP_TIME.format(trip.startedAt.atZone(ZoneId.systemDefault())),
                        stringResource(trip.kind.labelRes()),
                        if (trip.simulated) stringResource(R.string.debug_trip_simulated) else null,
                        // A session from before tokens were named shows none.
                        if (token == TripsOf.BeforeNames) null else tripsOfText(token),
                    ).joinToString(" · "),
                    LineTone.VALUE_MUTED,
                )
            }
        },
    )

    Spacer(Modifier.height(8.dp))
    SwitchRowItem(
        title = stringResource(R.string.debug_simulate_drive),
        checked = debug.simulateDrive,
        onCheckedChange = onSimulateDriveChange,
        lines = listOf(RowLine(stringResource(R.string.debug_simulate_drive_note))),
    )
    // A test of the route line the app draws itself; the SDK's comes back by itself if it fails.
    SwitchRowItem(
        title = stringResource(R.string.debug_own_route_line),
        checked = debug.ownRouteLine,
        onCheckedChange = onOwnRouteLineChange,
        lines = listOf(RowLine(stringResource(R.string.debug_own_route_line_note))),
    )
    // What the lanes drawn by the app cost the map's frames, measured on a drive.
    SwitchRowItem(
        title = stringResource(R.string.debug_measure_own_lanes),
        checked = debug.measureOwnLanes,
        onCheckedChange = onMeasureOwnLanesChange,
        lines = listOf(RowLine(stringResource(R.string.debug_measure_own_lanes_note))),
    )
    // What Mapbox sends of the side roads at each intersection of a route, measured before the street lanes use it.
    SwitchRowItem(
        title = stringResource(R.string.debug_side_road_fields),
        checked = debug.sideRoadFields,
        onCheckedChange = onSideRoadFieldsChange,
        lines = listOf(RowLine(stringResource(R.string.debug_side_road_fields_note))),
    )

    // The car's map at 30 or 60 frames a second, to compare what the car shows of each.
    Spacer(Modifier.height(8.dp))
    val targets = CarFrameRate.TARGETS
    ChoiceRowItem(
        title = stringResource(R.string.debug_car_map_fps),
        options = targets.map { ChoiceItem(stringResource(R.string.debug_car_map_fps_value, it)) },
        selected = targets.indexOf(debug.carMapFps),
        onSelect = { onCarMapFpsChange(targets[it]) },
        lines = listOf(RowLine(stringResource(R.string.debug_car_map_fps_note))),
    )

    // The car's map without its 3D objects while the phone is hot.
    Spacer(Modifier.height(8.dp))
    SwitchRowItem(
        title = stringResource(R.string.debug_car_light_when_hot),
        checked = debug.carLightWhenHot,
        onCheckedChange = onCarLightWhenHotChange,
        lines = listOf(RowLine(stringResource(R.string.debug_car_light_when_hot_note))),
    )

    Spacer(Modifier.height(16.dp))
    if (temporaryDebugKey) {
        InfoRowItem(title = null, lines = listOf(RowLine(stringResource(R.string.debug_temporary_key), LineTone.WARNING)))
        Spacer(Modifier.height(8.dp))
    }
    DebugLogBlock(debug.logBytes, onShareLog, onClearLog)

    if (onCrashNow != null) {
        Spacer(Modifier.height(16.dp))
        CrashNowBlock(onCrashNow)
    }
}

/** Debug builds only: a deliberate crash, to check the crash record on the phone. */
@Composable
private fun CrashNowBlock(onCrashNow: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ActionRowItem(
        title = stringResource(R.string.debug_crash_now),
        onClick = { confirm = true },
        lines = listOf(RowLine(stringResource(R.string.debug_crash_now_note))),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.debug_crash_now_title)) },
            text = { Text(stringResource(R.string.debug_crash_now_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onCrashNow()
                    },
                ) { Text(stringResource(R.string.debug_crash_now)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun DebugLogBlock(logBytes: Long?, onShareLog: () -> Unit, onClearLog: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    InfoRowItem(
        title = stringResource(R.string.debug_log_title),
        lines = listOf(
            RowLine(
                when {
                    logBytes == null -> stringResource(R.string.debug_log_measuring)
                    logBytes == 0L -> stringResource(R.string.debug_log_empty)
                    else -> stringResource(R.string.debug_log_size, SizeFormat.bytes(logBytes))
                },
                LineTone.VALUE,
            ),
            RowLine(stringResource(R.string.debug_log_note)),
        ),
    )
    val hasLog = (logBytes ?: 0L) > 0L
    ActionRowItem(title = stringResource(R.string.debug_log_share), onClick = onShareLog, enabled = hasLog)
    ActionRowItem(title = stringResource(R.string.debug_log_clear), onClick = { confirmClear = true }, enabled = hasLog)
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.debug_log_clear_title)) },
            text = { Text(stringResource(R.string.debug_log_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearLog()
                    },
                ) { Text(stringResource(R.string.debug_log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
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

/** The name a count or a trip goes under: the token's own, or what stands for a token that is gone or had none. */
@Composable
private fun tripsOfText(of: TripsOf): String = when (of) {
    is TripsOf.Token -> of.name
    TripsOf.Deleted -> stringResource(R.string.debug_trips_deleted_token)
    TripsOf.BeforeNames -> stringResource(R.string.debug_trips_before_names)
}

@StringRes
private fun TripKind.labelRes(): Int = when (this) {
    TripKind.ACTIVE_GUIDANCE -> R.string.debug_trip_guidance
    TripKind.FREE_DRIVE -> R.string.debug_trip_free_drive
}

/** The whole page, its switches one on and one off, on a narrow phone and with a large font. */
@Preview(name = "settings", widthDp = 360, heightDp = 1600)
@Preview(name = "settings, large font", widthDp = 360, heightDp = 2000, fontScale = 1.3f)
@Composable
private fun SettingsScreenPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        SettingsScreen(
            tokens = TokenState.Ready(
                rows = listOf(
                    TokenRow("t1", "Main", "pk.dGVz…2ln1", active = true, mayUse = false, mayDelete = true),
                    TokenRow("t2", "Test", "pk.dGVz…2ln2", active = false, mayUse = true, mayDelete = true),
                ),
                locked = false,
                full = false,
                defaultName = "Token 2",
            ),
            // An invented time.
            look = LookSettings(LookChoice.AUTO, NextLook(Look.NIGHT, LocalTime.of(18, 5))),
            guidance = GuidanceSettings(lanesOnMap = LanesOnMap.APP),
            debug = DebugState(
                trips = MonthCount(3, partialSince = LocalDate.of(2000, 1, 15), began = LogBegan.INSTALLATION),
                tripsByToken = listOf(
                    TokenTrips(TripsOf.Token("Main"), 1),
                    TokenTrips(TripsOf.Token("Test"), 1),
                    TokenTrips(TripsOf.BeforeNames, 1),
                ),
                recentTrips = listOf(
                    RecentTrip(TripSessionStart(Instant.parse("2000-01-19T17:05:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true, tokenId = "t2"), TripsOf.Token("Test")),
                    RecentTrip(TripSessionStart(Instant.parse("2000-01-18T02:40:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false, tokenId = "t1"), TripsOf.Token("Main")),
                    RecentTrip(TripSessionStart(Instant.parse("2000-01-16T12:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false), TripsOf.BeforeNames),
                ),
                simulateDrive = true,
                logBytes = 2_400_000,
            ),
            onAddToken = { _, _ -> null },
            onRenameToken = { _, _ -> null },
            onUseToken = {},
            onDeleteToken = {},
            onDayNightChange = {},
            onCameraStyleChange = {},
            onLaneZoomChange = {},
            onLanesOnMapChange = {},
            onLaneCountsHorizonChange = {},
            onSimulateDriveChange = {},
            onOwnRouteLineChange = {},
            onMeasureOwnLanesChange = {},
            onSideRoadFieldsChange = {},
            onCarMapFpsChange = {},
            onCarLightWhenHotChange = {},
            onShareLog = {},
            onClearLog = {},
            onBack = {},
            onCrashNow = {},
            temporaryDebugKey = true,
        )
    }
}

/**
 * The Day and night group on a narrow phone with every font size: Auto by day and at night, each
 * with the time it changes next (invented times), and Night by hand, its segment the widest.
 */
@Preview(name = "day and night", widthDp = 360)
@Preview(name = "day and night, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "day and night, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun DayNightSectionPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface(color = LookTheme.tokens.screen) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                DayNightSection(LookSettings(LookChoice.AUTO, NextLook(Look.NIGHT, LocalTime.of(18, 5))), onDayNightChange = {})
                GroupDivider()
                DayNightSection(LookSettings(LookChoice.AUTO, NextLook(Look.DAY, LocalTime.of(6, 20))), onDayNightChange = {})
                GroupDivider()
                DayNightSection(LookSettings(LookChoice.NIGHT), onDayNightChange = {})
            }
        }
    }
}

/**
 * The Guidance group on a narrow phone with every font size: Drawn by the app, with the note under the camera style it
 * brings, and the lane counts from the electronic horizon on; and Mapbox's lane details, without the note, the lane counts
 * from the horizon off.
 */
@Preview(name = "guidance, lanes drawn by the app", widthDp = 360)
@Preview(name = "guidance, lanes drawn by the app, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "guidance, lanes drawn by the app, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun GuidanceSectionPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface(color = LookTheme.tokens.screen) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                GuidanceSection(
                    GuidanceSettings(lanesOnMap = LanesOnMap.APP, laneCountsHorizon = true),
                    onCameraStyleChange = {},
                    onLaneZoomChange = {},
                    onLanesOnMapChange = {},
                    onLaneCountsHorizonChange = {},
                )
                GroupDivider()
                GuidanceSection(
                    GuidanceSettings(lanesOnMap = LanesOnMap.MAPBOX, laneCountsHorizon = false),
                    onCameraStyleChange = {},
                    onLaneZoomChange = {},
                    onLanesOnMapChange = {},
                    onLaneCountsHorizonChange = {},
                )
            }
        }
    }
}
