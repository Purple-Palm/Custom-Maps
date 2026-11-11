package io.github.purplepalm.custommaps.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.nav.format.SizeFormat
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.token.TokenEditor
import io.github.purplepalm.custommaps.ui.token.TokenState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Usage of billable products per month, in the Mapbox console. */
private const val MAPBOX_STATISTICS_URL = "https://console.mapbox.com/account/statistics/"

private val TRIP_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** The day since which the month's trip count runs, when the log began after the month did. */
private val TRIPS_SINCE_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

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
    tokenState: TokenState,
    tokenEditable: Boolean,
    guidance: GuidanceSettings,
    debug: DebugState,
    onSaveToken: (String) -> AccessTokenCheck,
    onClearToken: () -> Unit,
    onCameraStyleChange: (CameraStyle) -> Unit,
    onLaneZoomChange: (LaneZoom) -> Unit,
    onLaneDetailsChange: (Boolean) -> Unit,
    onSimulateDriveChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides Crash now: release builds never offer it. */
    onCrashNow: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(24.dp))

            TokenSection(tokenState, tokenEditable, onSaveToken, onClearToken)
            SectionDivider()
            GuidanceSection(guidance, onCameraStyleChange, onLaneZoomChange, onLaneDetailsChange)
            SectionDivider()
            DebugSection(debug, onSimulateDriveChange, onShareLog, onClearLog, onCrashNow)
            SectionDivider()

            SectionTitle(R.string.about_title)
            Text(
                text = stringResource(
                    R.string.about_version,
                    stringResource(R.string.app_version),
                    stringResource(R.string.build_revision),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_map_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun TokenSection(
    tokenState: TokenState,
    editable: Boolean,
    onSaveToken: (String) -> AccessTokenCheck,
    onClearToken: () -> Unit,
) {
    SectionTitle(R.string.token_title)
    Text(
        text = when (tokenState) {
            is TokenState.Present -> stringResource(R.string.token_saved_as, tokenState.masked)
            else -> stringResource(R.string.token_not_set)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    if (editable) {
        TokenEditor(
            saveLabel = if (tokenState is TokenState.Present) R.string.token_replace else R.string.token_save,
            onSave = onSaveToken,
        )
        if (tokenState is TokenState.Present) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClearToken) {
                Text(stringResource(R.string.token_remove))
            }
        }
    } else {
        // Replacing the token restarts the map and the navigator, which would end the drive.
        Text(
            text = stringResource(R.string.token_locked_during_guidance),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.token_storage_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** How the app guides. Every choice here applies at once, also during a drive. */
@Composable
private fun GuidanceSection(
    guidance: GuidanceSettings,
    onCameraStyleChange: (CameraStyle) -> Unit,
    onLaneZoomChange: (LaneZoom) -> Unit,
    onLaneDetailsChange: (Boolean) -> Unit,
) {
    SectionTitle(R.string.guidance_title)
    Text(
        text = stringResource(R.string.camera_style_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
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
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(style.noteRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.camera_style_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    LaneZoomBlock(guidance, onLaneZoomChange)

    // Lane markings on the map. The text says what they are not: the car's lane.
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.lane_details_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.lane_details_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = guidance.laneDetails, onCheckedChange = onLaneDetailsChange)
    }
}

/** How close the camera goes before turns and lane choices: one of two, each with a line that says what it does. */
@Composable
private fun LaneZoomBlock(guidance: GuidanceSettings, onLaneZoomChange: (LaneZoom) -> Unit) {
    Text(
        text = stringResource(R.string.lane_zoom_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (guidance.cameraStyle == CameraStyle.MAPBOX_STANDARD) {
        // That style is the camera as it comes, for comparison: the choice above does not reach it.
        Text(
            text = stringResource(R.string.lane_zoom_mapbox_standard_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Trip sessions are what Mapbox bills for navigation, so their count is kept in sight. */
@Composable
private fun DebugSection(
    debug: DebugState,
    onSimulateDriveChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onCrashNow: (() -> Unit)?,
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
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.debug_trips_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { uriHandler.openUri(MAPBOX_STATISTICS_URL) }) {
        Text(stringResource(R.string.debug_open_statistics))
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.debug_recent_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (debug.recentTrips.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_recent_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    debug.recentTrips.forEach { trip ->
        Text(
            text = listOfNotNull(
                TRIP_TIME.format(trip.startedAt.atZone(ZoneId.systemDefault())),
                stringResource(trip.kind.labelRes()),
                if (trip.simulated) stringResource(R.string.debug_trip_simulated) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.debug_simulate_drive),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.debug_simulate_drive_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = debug.simulateDrive, onCheckedChange = onSimulateDriveChange)
    }

    Spacer(Modifier.height(16.dp))
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
    Text(
        text = stringResource(R.string.debug_crash_now_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = when {
            logBytes == null -> stringResource(R.string.debug_log_measuring)
            logBytes == 0L -> stringResource(R.string.debug_log_empty)
            else -> stringResource(R.string.debug_log_size, SizeFormat.bytes(logBytes))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.debug_log_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

@Preview
@Composable
private fun SettingsScreenPreview() {
    CustomMapsTheme {
        SettingsScreen(
            tokenState = TokenState.Present("pk.abcd…wxyz"),
            tokenEditable = true,
            guidance = GuidanceSettings(),
            debug = DebugState(
                trips = MonthCount(3, partialSince = LocalDate.of(2000, 1, 15), began = LogBegan.INSTALLATION),
                recentTrips = listOf(
                    TripSessionStart(Instant.parse("2026-11-03T17:05:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true),
                    TripSessionStart(Instant.parse("2026-11-01T02:40:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false),
                ),
                simulateDrive = true,
                logBytes = 2_400_000,
            ),
            onSaveToken = { AccessTokenCheck.VALID },
            onClearToken = {},
            onCameraStyleChange = {},
            onLaneZoomChange = {},
            onLaneDetailsChange = {},
            onSimulateDriveChange = {},
            onShareLog = {},
            onClearLog = {},
            onBack = {},
            onCrashNow = {},
        )
    }
}
