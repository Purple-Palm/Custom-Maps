package io.github.purplepalm.custommaps.ui.settings

import android.text.format.Formatter
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.token.TokenEditor
import io.github.purplepalm.custommaps.ui.token.TokenState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Usage of billable products per month, in the Mapbox console. */
private const val MAPBOX_STATISTICS_URL = "https://console.mapbox.com/account/statistics/"

private val TRIP_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

@Composable
fun SettingsScreen(
    tokenState: TokenState,
    tokenEditable: Boolean,
    debug: DebugState,
    onSaveToken: (String) -> AccessTokenCheck,
    onClearToken: () -> Unit,
    onSimulateDriveChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            DebugSection(debug, onSimulateDriveChange, onShareLog, onClearLog)
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
        }
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

/** Trip sessions are what Mapbox bills for navigation, so their count is kept in sight. */
@Composable
private fun DebugSection(
    debug: DebugState,
    onSimulateDriveChange: (Boolean) -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    SectionTitle(R.string.debug_title)
    Text(
        text = stringResource(R.string.debug_trips_this_month, debug.tripsThisMonth),
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
}

@Composable
private fun DebugLogBlock(logBytes: Long?, onShareLog: () -> Unit, onClearLog: () -> Unit) {
    val context = LocalContext.current
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
            else -> stringResource(R.string.debug_log_size, Formatter.formatShortFileSize(context, logBytes))
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
            debug = DebugState(
                tripsThisMonth = 3,
                recentTrips = listOf(
                    TripSessionStart(Instant.parse("2026-11-03T17:05:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true),
                    TripSessionStart(Instant.parse("2026-11-01T02:40:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false),
                ),
                simulateDrive = true,
                logBytes = 2_400_000,
            ),
            onSaveToken = { AccessTokenCheck.VALID },
            onClearToken = {},
            onSimulateDriveChange = {},
            onShareLog = {},
            onClearLog = {},
            onBack = {},
        )
    }
}
