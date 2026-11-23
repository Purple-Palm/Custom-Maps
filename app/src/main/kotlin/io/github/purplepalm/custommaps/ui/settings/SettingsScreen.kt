package io.github.purplepalm.custommaps.ui.settings

import android.widget.Toast
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.look.NextLook
import io.github.purplepalm.custommaps.nav.car.CarMapSize
import io.github.purplepalm.custommaps.nav.lanes.LanesOnMap
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.sound.DuckMode
import io.github.purplepalm.custommaps.nav.sound.ToneLevel
import io.github.purplepalm.custommaps.nav.trip.LogBegan
import io.github.purplepalm.custommaps.nav.trip.MonthCount
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.settings.SoundSettings
import io.github.purplepalm.custommaps.sound.ToneResult
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
import java.time.ZoneOffset

/**
 * Settings: the [groups] the catalogue made ([SettingsCatalogue]), one under the other, each under
 * its title. What the driver does with a row goes to [onEvent], but for the two actions that need the
 * activity, sharing the log ([onShareLog]) and the crash of debug builds ([onCrashNow]). The screen
 * itself opens web pages and asks before an action that cannot be undone. The token list is the token
 * view model's ([TokenListSection]).
 */
@Composable
fun SettingsScreen(
    groups: List<SettingsGroup>,
    tokens: TokenState,
    onAddToken: (name: String?, token: String) -> TokenProblem?,
    onRenameToken: (id: String, name: String) -> TokenProblem?,
    onUseToken: (id: String) -> Unit,
    onDeleteToken: (id: String) -> Unit,
    onEvent: (SettingsEvent) -> Unit,
    onShareLog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Debug builds only: the catalogue offers the row only when the app says it may. */
    onCrashNow: () -> Unit = {},
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
            SettingsGroups(
                groups = groups,
                onEvent = onEvent,
                onAction = { id ->
                    when (id) {
                        SettingId.SHARE_LOG -> onShareLog()
                        SettingId.CRASH_NOW -> onCrashNow()
                        else -> onEvent(SettingsEvent.Acted(id))
                    }
                },
            ) { block ->
                when (block.id) {
                    SettingId.TOKENS -> TokenListSection(tokens, onAddToken, onRenameToken, onUseToken, onDeleteToken)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * [groups], each under its title, a line between two groups. A switch or a choice goes to [onEvent],
 * an action to [onAction] once asked when it asks; [block] draws the rows that are a section of their
 * own ([BlockRow]).
 */
@Composable
internal fun SettingsGroups(
    groups: List<SettingsGroup>,
    onEvent: (SettingsEvent) -> Unit,
    onAction: (SettingId) -> Unit,
    block: @Composable (BlockRow) -> Unit = {},
) {
    groups.forEachIndexed { index, group ->
        if (index > 0) GroupDivider()
        group.title?.let { GroupTitle(stringResource(it)) }
        group.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(8.dp))
            SettingRowView(row, onEvent, onAction, block)
        }
    }
}

/** One row of the catalogue, drawn with its kind's composable. */
@Composable
private fun SettingRowView(
    row: SettingRow,
    onEvent: (SettingsEvent) -> Unit,
    onAction: (SettingId) -> Unit,
    block: @Composable (BlockRow) -> Unit,
) {
    val lines = row.lines.map { RowLine(it.text.resolve(), it.tone) }
    when (row) {
        is SwitchRow -> SwitchRowItem(
            title = row.title.resolve(),
            checked = row.on,
            onCheckedChange = { onEvent(SettingsEvent.Switched(row.id, it)) },
            lines = lines,
            badge = row.badge?.resolve(),
        )
        is ChoiceRow -> ChoiceRowItem(
            title = row.title?.resolve(),
            options = row.options.map { ChoiceItem(it.title.resolve(), it.note?.resolve()) },
            selected = row.selected,
            onSelect = { onEvent(SettingsEvent.Chosen(row.id, it)) },
            folds = row.folds,
            lines = lines,
        )
        is LinkRow -> {
            val context = LocalContext.current
            LinkRowItem(
                title = row.title.resolve(),
                onClick = {
                    // A phone without a browser gets a short note instead.
                    if (!context.openWebPage(row.url)) Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_LONG).show()
                },
                lines = lines,
            )
        }
        is InfoRow -> InfoRowItem(title = row.title?.resolve(), lines = lines)
        is ActionRow -> ConfirmedActionRow(row, lines) { onAction(row.id) }
        is BlockRow -> block(row)
    }
}

/** An action row; one with a question asks it first, and acts only on the question's own action. */
@Composable
private fun ConfirmedActionRow(row: ActionRow, lines: List<RowLine>, onAct: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val confirm = row.confirm
    ActionRowItem(
        title = row.title.resolve(),
        onClick = { if (confirm == null) onAct() else asking = true },
        lines = lines,
        enabled = row.enabled,
    )
    if (asking && confirm != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(confirm.title)) },
            text = { Text(stringResource(confirm.message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onAct()
                    },
                ) { Text(stringResource(confirm.action)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The words of [this], put together: a string with its arguments resolved first. */
@Composable
internal fun RowText.resolve(): String = when (this) {
    is RowText.Res -> if (args.isEmpty()) {
        stringResource(id)
    } else {
        stringResource(id, *args.map { arg -> if (arg is RowText) arg.resolve() else arg }.toTypedArray())
    }
    is RowText.Plain -> text
    is RowText.Joined -> parts.map { it.resolve() }.joinToString(separator)
}

/** The values the previews show: invented names, counts, sizes and times, dates from 2000-01-01. */
private val PREVIEW_VALUES = SettingsValues(
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
    crashNow = true,
    temporaryDebugKey = true,
)

/** A group of the catalogue alone, as the page shows it. */
@Composable
private fun GroupPreview(look: Look, groups: List<SettingsGroup>) {
    CustomMapsTheme(look) {
        Surface(color = LookTheme.tokens.screen) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                SettingsGroups(groups, onEvent = {}, onAction = {})
            }
        }
    }
}

/** The whole page, its switches one on and one off, on a narrow phone and with a large font. */
@Preview(name = "settings", widthDp = 360, heightDp = 4000)
@Preview(name = "settings, large font", widthDp = 360, heightDp = 5100, fontScale = 1.3f)
@Composable
private fun SettingsScreenPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        SettingsScreen(
            groups = SettingsCatalogue.page(PREVIEW_VALUES, ZoneOffset.UTC),
            tokens = TokenState.Ready(
                rows = listOf(
                    TokenRow("t1", "Main", "pk.dGVz…2ln1", active = true, mayUse = false, mayDelete = true),
                    TokenRow("t2", "Test", "pk.dGVz…2ln2", active = false, mayUse = true, mayDelete = true),
                ),
                locked = false,
                full = false,
                defaultName = "Token 2",
            ),
            onAddToken = { _, _ -> null },
            onRenameToken = { _, _ -> null },
            onUseToken = {},
            onDeleteToken = {},
            onEvent = {},
            onShareLog = {},
            onBack = {},
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
private fun DayNightGroupPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val looks = listOf(
        LookSettings(LookChoice.AUTO, NextLook(Look.NIGHT, LocalTime.of(18, 5))),
        LookSettings(LookChoice.AUTO, NextLook(Look.DAY, LocalTime.of(6, 20))),
        LookSettings(LookChoice.NIGHT),
    )
    GroupPreview(look, looks.map { day -> SettingsCatalogue.groups(SettingsTab.APP, SettingsValues(look = day)).first() })
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
private fun GuidanceGroupPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val guidance = listOf(
        GuidanceSettings(lanesOnMap = LanesOnMap.APP, laneCountsHorizon = true),
        GuidanceSettings(lanesOnMap = LanesOnMap.MAPBOX, laneCountsHorizon = false),
    )
    GroupPreview(look, guidance.map { SettingsCatalogue.groups(SettingsTab.MAP, SettingsValues(guidance = it)).first() })
}

/**
 * The Car screen group on a narrow phone with every font size, by day and at night: x1.5, as when never chosen, and x2,
 * the last of the list.
 */
@Preview(name = "car screen", widthDp = 360)
@Preview(name = "car screen, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "car screen, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun CarScreenGroupPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val sizes = listOf(CarScreenSettings(), CarScreenSettings(mapSize = CarMapSize.X2))
    GroupPreview(look, sizes.map { car -> SettingsCatalogue.groups(SettingsTab.MAP, SettingsValues(carScreen = car)).last() })
}

/**
 * The drive camera's three rows at the head of the Guidance group on a narrow phone with every font size, by day and at
 * night: the Overview with Zoom in before turns on and Recenter by itself after 10 s, under Smooth follow; and Normal with
 * Zoom in before turns off and Recenter by itself Off, under Mapbox tuned, with the line that Smooth follow is smoothest.
 */
@Preview(name = "drive views", widthDp = 360)
@Preview(name = "drive views, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "drive views, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun DriveViewRowsPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val guidance = listOf(
        GuidanceSettings(driveCamera = DriveCameraSettings(driveView = DriveView.OVERVIEW)),
        GuidanceSettings(cameraStyle = CameraStyle.MAPBOX_TUNED, driveCamera = DriveCameraSettings(recenterSeconds = 0, turnZoom = false)),
    )
    val driveRows = setOf(SettingId.DRIVE_VIEW, SettingId.OVERVIEW_TURN_ZOOM, SettingId.RECENTER_SECONDS)
    GroupPreview(
        look,
        guidance.map { values ->
            val group = SettingsCatalogue.groups(SettingsTab.MAP, SettingsValues(guidance = values)).first()
            group.copy(rows = group.rows.filter { it.id in driveRows })
        },
    )
}

/**
 * The Sound group on a narrow phone with every font size, by day and at night: every tone on, with the line under Play the
 * tones when the media volume was at zero; More and Loud, the widest words of their rows; and Tones off, its one row.
 */
@Preview(name = "sound", widthDp = 360)
@Preview(name = "sound, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "sound, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun SoundGroupPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val sounds = listOf(
        SettingsValues(heard = ToneResult.VOLUME_ZERO),
        SettingsValues(sound = SoundSettings(duck = DuckMode.MORE, level = ToneLevel.LOUD)),
        SettingsValues(sound = SoundSettings(tones = false)),
    )
    GroupPreview(look, sounds.flatMap { SettingsCatalogue.groups(SettingsTab.SOUND, it) })
}
