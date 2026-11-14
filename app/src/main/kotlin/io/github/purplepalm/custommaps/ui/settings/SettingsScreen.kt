package io.github.purplepalm.custommaps.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.purplepalm.custommaps.ui.theme.AppSwitch
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

            SectionTitle(R.string.token_title)
            TokenListSection(tokens, onAddToken, onRenameToken, onUseToken, onDeleteToken)
            SectionDivider()
            DayNightSection(look, onDayNightChange)
            SectionDivider()
            GuidanceSection(guidance, onCameraStyleChange, onLaneZoomChange, onLanesOnMapChange, onLaneCountsHorizonChange)
            SectionDivider()
            DebugSection(
                debug,
                onSimulateDriveChange,
                onOwnRouteLineChange,
                onMeasureOwnLanesChange,
                onCarMapFpsChange,
                onCarLightWhenHotChange,
                onShareLog,
                onClearLog,
                onCrashNow,
                temporaryDebugKey,
            )
            SectionDivider()

            SectionTitle(R.string.about_title)
            Text(
                text = stringResource(
                    R.string.about_version,
                    stringResource(R.string.app_version),
                    stringResource(R.string.build_revision),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LookTheme.tokens.onCardMuted,
            )
            Text(
                text = stringResource(R.string.about_map_data),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            PrivacyPolicyButton()
        }
    }
}

/** Opens the privacy policy in the browser; a phone without one gets a short note instead. */
@Composable
private fun PrivacyPolicyButton() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            if (!context.openWebPage(PRIVACY_POLICY_URL)) {
                Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_LONG).show()
            }
        },
    ) {
        Text(stringResource(R.string.about_privacy_policy))
    }
}

/**
 * The day look, the night look, or by the sun. A choice applies at once, to the screen and the map
 * together; under Auto the note says when the look changes next.
 */
@Composable
private fun DayNightSection(look: LookSettings, onDayNightChange: (LookChoice) -> Unit) {
    SectionTitle(R.string.day_night_title)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val choices = LookChoice.entries
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == look.choice,
                onClick = { onDayNightChange(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                contentPadding = THREE_SEGMENT_PADDING,
            ) {
                SegmentLabel(stringResource(choice.titleRes()))
            }
        }
    }
    if (look.choice == LookChoice.AUTO) {
        Text(
            text = stringResource(R.string.day_night_auto_note),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCardMuted,
        )
        look.next?.let { next ->
            Text(
                text = stringResource(next.look.nextRes(), NEXT_LOOK_TIME.format(next.at)),
                style = MaterialTheme.typography.bodyMedium,
                color = LookTheme.tokens.onCard,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A segment's word on one line, smaller only when a large font leaves it no room, never below
 * 12 sp. Material measures the word as wide as the segment and sets the selected segment's check
 * mark beside it, so every segment keeps room for the mark and its words stay one size.
 */
@Composable
private fun SegmentLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = LocalTextStyle.current.fontSize),
        modifier = Modifier.layout { measurable, constraints ->
            val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - CHECK_MARK_ROOM.roundToPx()).coerceAtLeast(0) else constraints.maxWidth
            val label = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
            layout(label.width, label.height) { label.place(0, 0) }
        },
    )
}

/** The selected segment's check mark and the space before its word, as Material 3 1.4.0 lays them out. */
private val CHECK_MARK_ROOM = SegmentedButtonDefaults.IconSize + 8.dp

/** Three segments on a narrow phone: less padding than Material's 12 dp a side, so a large font still fits. */
private val THREE_SEGMENT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/** How the app guides. Every choice here applies at once, also during a drive. */
@Composable
private fun GuidanceSection(
    guidance: GuidanceSettings,
    onCameraStyleChange: (CameraStyle) -> Unit,
    onLaneZoomChange: (LaneZoom) -> Unit,
    onLanesOnMapChange: (LanesOnMap) -> Unit,
    onLaneCountsHorizonChange: (Boolean) -> Unit,
) {
    SectionTitle(R.string.guidance_title)
    Text(
        text = stringResource(R.string.camera_style_title),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    Column(Modifier.selectableGroup()) {
        CameraStyle.entries.forEach { style ->
            val selected = style == guidance.cameraStyle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onCameraStyleChange(style) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The whole row is the button.
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(style.titleRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LookTheme.tokens.onCard,
                    )
                    Text(
                        text = stringResource(style.noteRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = LookTheme.tokens.onCardMuted,
                    )
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.camera_style_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
    if (guidance.lanesOnMap.appDraws) {
        // Where the app's lit lane does not take the line's place, the line stays in the lane to take.
        Text(
            text = stringResource(R.string.camera_style_app_lanes_note),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCard,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    LaneZoomBlock(guidance, onLaneZoomChange)

    Spacer(Modifier.height(16.dp))
    LanesOnMapBlock(guidance.lanesOnMap, onLanesOnMapChange)

    Spacer(Modifier.height(16.dp))
    LaneCountsHorizonRow(guidance.laneCountsHorizon, onLaneCountsHorizonChange)
}

/**
 * Whether guidance reads the lane counts between lane points from Mapbox's electronic horizon: a switch, its title with a
 * label beside it that names it a Mapbox beta, and one line that says whose it is and that Mapbox may end its free use.
 * On until the driver turns it off.
 */
@Composable
private fun LaneCountsHorizonRow(on: Boolean, onLaneCountsHorizonChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The title takes what room the label leaves and wraps there; the label stays beside it.
                Text(
                    text = stringResource(R.string.lane_counts_horizon_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = LookTheme.tokens.onCard,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                MapboxBetaLabel()
            }
            Text(
                text = stringResource(R.string.lane_counts_horizon_note),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        AppSwitch(checked = on, onCheckedChange = onLaneCountsHorizonChange)
    }
}

/** The words Mapbox beta in a thin frame, beside the title of a setting that uses a beta of Mapbox's: a word, not a colour, says it. */
@Composable
private fun MapboxBetaLabel() {
    Text(
        text = stringResource(R.string.mapbox_beta_label),
        style = MaterialTheme.typography.labelSmall,
        color = LookTheme.tokens.onCard,
        modifier = Modifier
            .border(1.dp, LookTheme.tokens.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Who draws the lanes on the map: one of three, each row the whole button, with a line that says what it draws. The
 * note under them says what none of them is: the car's lane.
 */
@Composable
private fun LanesOnMapBlock(choice: LanesOnMap, onLanesOnMapChange: (LanesOnMap) -> Unit) {
    Text(
        text = stringResource(R.string.lanes_on_map_title),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    Column(Modifier.selectableGroup()) {
        LanesOnMap.entries.forEach { option ->
            val selected = option == choice
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onLanesOnMapChange(option) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The whole row is the button.
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(option.titleRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LookTheme.tokens.onCard,
                    )
                    Text(
                        text = stringResource(option.noteRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = LookTheme.tokens.onCardMuted,
                    )
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.lanes_on_map_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
}

/** How close the camera goes before turns and lane choices: one of two, each with a line that says what it does. */
@Composable
private fun LaneZoomBlock(guidance: GuidanceSettings, onLaneZoomChange: (LaneZoom) -> Unit) {
    Text(
        text = stringResource(R.string.lane_zoom_title),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        val modes = LaneZoom.entries
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == guidance.laneZoom,
                onClick = { onLaneZoomChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(stringResource(mode.titleRes()))
            }
        }
    }
    LaneZoom.entries.forEach { mode ->
        Text(
            text = stringResource(mode.noteRes()),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCardMuted,
        )
    }
    if (guidance.cameraStyle == CameraStyle.MAPBOX_STANDARD) {
        // That style is the camera as it comes, for comparison: the choice above does not reach it.
        Text(
            text = stringResource(R.string.lane_zoom_mapbox_standard_note),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCard,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Trip sessions are what Mapbox bills for navigation, so their count is kept in sight. */
@Composable
private fun DebugSection(
    debug: DebugState,
    onSimulateDriveChange: (Boolean) -> Unit,
    onOwnRouteLineChange: (Boolean) -> Unit,
    onMeasureOwnLanesChange: (Boolean) -> Unit,
    onCarMapFpsChange: (Int) -> Unit,
    onCarLightWhenHotChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onCrashNow: (() -> Unit)?,
    temporaryDebugKey: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    SectionTitle(R.string.debug_title)
    val trips = debug.trips
    Text(
        text = when (val since = trips.partialSince) {
            null -> stringResource(R.string.debug_trips_this_month, trips.count)
            else -> stringResource(tripsSinceRes(trips.began), trips.count, TRIPS_SINCE_DAY.format(since))
        },
        style = MaterialTheme.typography.titleMedium,
        color = LookTheme.tokens.onCard,
    )
    // By the token each ran on, when there is more than one line.
    debug.tripsByToken.forEach { row ->
        Text(
            text = stringResource(R.string.debug_trips_token, tripsOfText(row.of), row.count),
            style = MaterialTheme.typography.bodyMedium,
            color = LookTheme.tokens.onCard,
        )
    }
    Text(
        text = stringResource(R.string.debug_trips_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
    TextButton(onClick = { uriHandler.openUri(MAPBOX_STATISTICS_URL) }) {
        Text(stringResource(R.string.debug_open_statistics))
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.debug_recent_title),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    if (debug.recentTrips.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_recent_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = LookTheme.tokens.onCardMuted,
        )
    }
    debug.recentTrips.forEach { (trip, token) ->
        Text(
            text = listOfNotNull(
                TRIP_TIME.format(trip.startedAt.atZone(ZoneId.systemDefault())),
                stringResource(trip.kind.labelRes()),
                if (trip.simulated) stringResource(R.string.debug_trip_simulated) else null,
                // A session from before tokens were named shows none.
                if (token == TripsOf.BeforeNames) null else tripsOfText(token),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = LookTheme.tokens.onCardMuted,
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_simulate_drive),
                style = MaterialTheme.typography.titleSmall,
                color = LookTheme.tokens.onCard,
            )
            Text(
                text = stringResource(R.string.debug_simulate_drive_note),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        AppSwitch(checked = debug.simulateDrive, onCheckedChange = onSimulateDriveChange)
    }

    // A test of the route line the app draws itself; the SDK's comes back by itself if it fails.
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_own_route_line),
                style = MaterialTheme.typography.titleSmall,
                color = LookTheme.tokens.onCard,
            )
            Text(
                text = stringResource(R.string.debug_own_route_line_note),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        AppSwitch(checked = debug.ownRouteLine, onCheckedChange = onOwnRouteLineChange)
    }

    // What the lanes drawn by the app cost the map's frames, measured on a drive.
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_measure_own_lanes),
                style = MaterialTheme.typography.titleSmall,
                color = LookTheme.tokens.onCard,
            )
            Text(
                text = stringResource(R.string.debug_measure_own_lanes_note),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        AppSwitch(checked = debug.measureOwnLanes, onCheckedChange = onMeasureOwnLanesChange)
    }

    // The car's map at 30 or 60 frames a second, to compare what the car shows of each.
    Spacer(Modifier.height(16.dp))
    CarMapFramesBlock(debug.carMapFps, onCarMapFpsChange)

    // The car's map without its 3D objects while the phone is hot.
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_car_light_when_hot),
                style = MaterialTheme.typography.titleSmall,
                color = LookTheme.tokens.onCard,
            )
            Text(
                text = stringResource(R.string.debug_car_light_when_hot_note),
                style = MaterialTheme.typography.bodySmall,
                color = LookTheme.tokens.onCardMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        AppSwitch(checked = debug.carLightWhenHot, onCheckedChange = onCarLightWhenHotChange)
    }

    Spacer(Modifier.height(16.dp))
    if (temporaryDebugKey) {
        Text(
            text = stringResource(R.string.debug_temporary_key),
            style = MaterialTheme.typography.bodyMedium,
            color = LookTheme.tokens.danger,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    DebugLogBlock(debug.logBytes, onShareLog, onClearLog)

    if (onCrashNow != null) {
        Spacer(Modifier.height(16.dp))
        CrashNowBlock(onCrashNow)
    }
}

/**
 * How many frames a second the map on the car's screen draws: one of two, applied at once, also
 * during a drive, with a line that says what the car shows of them.
 */
@Composable
private fun CarMapFramesBlock(fps: Int, onCarMapFpsChange: (Int) -> Unit) {
    Text(
        text = stringResource(R.string.debug_car_map_fps),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        val targets = CarFrameRate.TARGETS
        targets.forEachIndexed { index, target ->
            SegmentedButton(
                selected = target == fps,
                onClick = { onCarMapFpsChange(target) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = targets.size),
            ) {
                SegmentLabel(stringResource(R.string.debug_car_map_fps_value, target))
            }
        }
    }
    Text(
        text = stringResource(R.string.debug_car_map_fps_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
}

/** Debug builds only: a deliberate crash, to check the crash record on the phone. */
@Composable
private fun CrashNowBlock(onCrashNow: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.debug_crash_now_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
    TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.debug_crash_now)) }
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
    Text(
        text = stringResource(R.string.debug_log_title),
        style = MaterialTheme.typography.titleSmall,
        color = LookTheme.tokens.onCard,
    )
    Text(
        text = when {
            logBytes == null -> stringResource(R.string.debug_log_measuring)
            logBytes == 0L -> stringResource(R.string.debug_log_empty)
            else -> stringResource(R.string.debug_log_size, SizeFormat.bytes(logBytes))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = LookTheme.tokens.onCard,
    )
    Text(
        text = stringResource(R.string.debug_log_note),
        style = MaterialTheme.typography.bodySmall,
        color = LookTheme.tokens.onCardMuted,
    )
    val hasLog = (logBytes ?: 0L) > 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onShareLog, enabled = hasLog) { Text(stringResource(R.string.debug_log_share)) }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { confirmClear = true }, enabled = hasLog) { Text(stringResource(R.string.debug_log_clear)) }
    }
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

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = LookTheme.tokens.accentInk,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = LookTheme.tokens.divider)
    Spacer(Modifier.height(24.dp))
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
                SectionDivider()
                DayNightSection(LookSettings(LookChoice.AUTO, NextLook(Look.DAY, LocalTime.of(6, 20))), onDayNightChange = {})
                SectionDivider()
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
                SectionDivider()
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
