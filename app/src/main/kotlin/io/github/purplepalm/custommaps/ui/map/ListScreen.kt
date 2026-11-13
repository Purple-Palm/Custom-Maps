package io.github.purplepalm.custommaps.ui.map

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.guidance.ManeuverList
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.nav.network.OfflineReason
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import io.github.purplepalm.custommaps.ui.guidance.GuidanceBar
import io.github.purplepalm.custommaps.ui.guidance.ManeuverGlyph
import io.github.purplepalm.custommaps.ui.guidance.ManeuverPanel
import io.github.purplepalm.custommaps.ui.guidance.ReplaySlider
import io.github.purplepalm.custommaps.ui.guidance.TABULAR_DIGITS
import io.github.purplepalm.custommaps.ui.guidance.THEN_CHIP_BAND
import io.github.purplepalm.custommaps.ui.guidance.arrivalSideRes
import io.github.purplepalm.custommaps.ui.guidance.thenDirectionRes
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The drive without the map: what the phone shows in place of its map during a drive while a car's
 * screen shows the map ([PhoneScreens]), the map one tap away with Map. On a black page in the night
 * look's colours whatever the look, the least power on the phone's own screen: the maneuver card at
 * the top as during guidance, without its Then chip, since the first row is that maneuver; then the
 * maneuvers ahead in this leg ([ManeuverList]), as many as fit, each with the app's own arrow, its
 * distance from the car, rounded as the card rounds its own, and its words, all dimmed while the card
 * waits for a new route or the car is off the route; the replay slider during a simulated drive; the
 * guidance bar with Map in place of Overview; and under it a small static line for the route data's
 * sources, where a map would have the Mapbox logo.
 *
 * Nothing on it moves by itself: it is drawn again when the progress changes, about once a second,
 * and the replay slider's dot goes to each position at once instead of gliding. The rows' maneuvers
 * are made once a step, as the progress brings the same list for the whole step; only their
 * distances change with each update.
 *
 * [progress], [replay] and [offline] are read here, by the parts that show them.
 */
@Composable
fun ListScreen(
    progress: State<RouteProgressSnapshot?>,
    replay: State<ReplayPosition?>,
    offline: State<OfflineReason>,
    onMap: () -> Unit,
    onEnd: () -> Unit,
    onSeek: (Double) -> Unit,
    onRunUpWords: (key: String, shown: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CustomMapsTheme(Look.NIGHT) {
        Column(
            modifier
                .fillMaxSize()
                .background(LookTheme.tokens.listPage)
                .systemBarsPadding()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        ) {
            ListCard(progress, offline, onRunUpWords)
            UpcomingRows(
                progress,
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            ListReplay(replay, onSeek)
            ListBar(progress, onMap, onEnd)
            Text(
                text = stringResource(R.string.data_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = LookTheme.tokens.onCardMuted,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * The maneuver card with nothing hanging under it but the offline tab, which the rows make room for
 * while it shows. Nothing before the drive's first progress, as during guidance on the map.
 */
@Composable
private fun ListCard(progress: State<RouteProgressSnapshot?>, offline: State<OfflineReason>, onRunUpWords: (String, Boolean) -> Unit) {
    val current = progress.value ?: return
    ManeuverPanel(current, onRunUpWords = onRunUpWords, endTab = { OfflinePill(offline) }, then = false)
    val tab by remember(offline) { derivedStateOf { offline.value != OfflineReason.NONE } }
    Spacer(Modifier.height(if (tab) THEN_CHIP_BAND else ROWS_GAP))
}

/** A maneuver ahead as its row shows it, made once a step: what its arrow is and what it says. */
private class ListRow(val item: FollowingManeuver, val words: String)

/**
 * The maneuvers ahead, from the top, as many as fit whole; the rest wait for room as the car goes on.
 * The rows are made when the list of the step comes and read their distances at each update.
 */
@Composable
private fun UpcomingRows(progress: State<RouteProgressSnapshot?>, modifier: Modifier) {
    val current = progress.value
    val upcoming = current?.upcoming.orEmpty()
    val resources = LocalResources.current
    // Made once a step: the progress brings the same list for the whole step.
    val rows = remember(upcoming, resources) { upcoming.take(MOST_ROWS).map { ListRow(it, rowWords(it.maneuver, resources)) } }
    val dimmed = current != null && ManeuverList.dimmed(current)
    RowsThatFit(modifier.alpha(if (dimmed) LookTheme.tokens.pendingCardAlpha else 1f)) {
        if (current != null) {
            rows.forEachIndexed { index, row ->
                // The distance from the car, rounded as the card rounds its own.
                val distance = ImperialFormat.distance(ManeuverList.distanceFromCar(current, row.item))
                UpcomingRow(row, distance.value, distance.unit, divider = index > 0)
            }
        }
    }
}

/**
 * One maneuver ahead: its arrow in the card's ink, its distance from the car large and a step heavier,
 * and its words as large as the card's road, all to be read at a glance from a phone mount.
 */
@Composable
private fun UpcomingRow(row: ListRow, value: String, unit: String, divider: Boolean) {
    val tokens = LookTheme.tokens
    val maneuver = row.item.maneuver
    Column {
        if (divider) HorizontalDivider(color = tokens.divider)
        Row(Modifier.padding(vertical = ROW_PADDING), verticalAlignment = Alignment.CenterVertically) {
            // In the card's ink, not amber: amber stays for what the driver does now.
            ManeuverGlyph(
                type = maneuver.type,
                turn = maneuver.modifier,
                color = tokens.onCard,
                modifier = Modifier.size(ROW_GLYPH),
                roundaboutExitDegrees = maneuver.roundaboutExitDegrees,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR_DIGITS),
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.onCard,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = tokens.onCardMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = row.words,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = tokens.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What a row says: the road, after the exit to take at a roundabout; at the destination, the side it
 * is on as well. A maneuver with no road named says its direction.
 */
private fun rowWords(maneuver: Maneuver, resources: Resources): String {
    val road = maneuver.primaryText.takeIf { it.isNotBlank() }
    val parts = when {
        maneuver.type == ManeuverType.ARRIVE -> listOfNotNull(road, resources.getString(arrivalSideRes(maneuver.modifier)))
        else -> listOfNotNull(
            maneuver.roundaboutExitNumber?.let { resources.getString(R.string.guidance_roundabout_exit, it) },
            road ?: resources.getString(thenDirectionRes(maneuver.type, maneuver.modifier)),
        )
    }
    return parts.joinToString(" · ")
}

/**
 * Places its children from the top, each across the whole width, as many as fit whole in the height
 * it has; the others are neither measured nor placed. No scrolling: the next rows come up as the car
 * passes the first.
 */
@Composable
private fun RowsThatFit(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val placed = ArrayList<Placeable>(measurables.size)
        var used = 0
        for (measurable in measurables) {
            val row = measurable.measure(Constraints(minWidth = width, maxWidth = width))
            if (used + row.height > room) break
            placed += row
            used += row.height
        }
        layout(width, if (constraints.hasBoundedHeight) constraints.maxHeight else used) {
            var y = 0
            for (row in placed) {
                row.placeRelative(0, y)
                y += row.height
            }
        }
    }
}

/** The replay slider while a simulated drive runs, its dot going to each position at once. */
@Composable
private fun ListReplay(replay: State<ReplayPosition?>, onSeek: (Double) -> Unit) {
    val simulated by remember(replay) { derivedStateOf { replay.value != null } }
    if (simulated) ReplaySlider(position = replay, onSeek = onSeek, modifier = Modifier.padding(bottom = 4.dp), glides = false)
}

/** The guidance bar, reading the progress itself, with Map in place of Overview. */
@Composable
private fun ListBar(progress: State<RouteProgressSnapshot?>, onMap: () -> Unit, onEnd: () -> Unit) {
    GuidanceBar(progress = progress.value, following = true, onToggleOverview = onMap, onEnd = onEnd, onMap = onMap)
}

/** More rows than any phone's screen holds: the rest of a long leg is not even made. */
private const val MOST_ROWS = 16

/** Between the card and the first row while no tab hangs under the card. */
private val ROWS_GAP = 12.dp

/** A row's arrow: smaller than the card's 72 dp, larger than the Then chip's 40 dp. */
private val ROW_GLYPH = 48.dp

/** Above and below a row's content. */
private val ROW_PADDING = 10.dp

/**
 * The list on a phone 360 dp wide and 760 dp high, in either look of the app, which the list does not
 * follow: the same page by day and at night. With the phone's own font as many rows as fit, fewer
 * with a large and the largest font.
 */
@Preview(name = "list", widthDp = 360, heightDp = 760)
@Preview(name = "list, large font", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Preview(name = "list, largest font", widthDp = 360, heightDp = 760, fontScale = 2f)
@Composable
private fun ListScreenPreview(@PreviewParameter(LookParameter::class) look: Look) {
    ListPreview(look, previewListProgress())
}

/** A simulated drive while the app is offline: the replay slider over the guidance bar, the offline tab under the card. */
@Preview(name = "list, simulated and offline", widthDp = 360, heightDp = 760)
@Composable
private fun ListScreenSimulatedPreview(@PreviewParameter(LookParameter::class) look: Look) {
    ListPreview(look, previewListProgress(), replay = ReplayPosition(seconds = 240.0, durationSeconds = 600.0), offline = OfflineReason.NO_NETWORK)
}

/** Off the route, until a new route comes: the rows dimmed, the card as it is. */
@Preview(name = "list, off the route", widthDp = 360, heightDp = 760)
@Composable
private fun ListScreenOffRoutePreview(@PreviewParameter(LookParameter::class) look: Look) {
    ListPreview(look, previewListProgress().copy(offRoute = true))
}

@Composable
private fun ListPreview(look: Look, progress: RouteProgressSnapshot, replay: ReplayPosition? = null, offline: OfflineReason = OfflineReason.NONE) {
    CustomMapsTheme(look) {
        ListScreen(
            progress = remember { mutableStateOf(progress) },
            replay = remember { mutableStateOf(replay) },
            offline = remember { mutableStateOf(offline) },
            onMap = {},
            onEnd = {},
            onSeek = {},
            onRunUpWords = { _, _ -> },
        )
    }
}

/** A drive on invented roads: a right turn 0.2 mi ahead, then left, a roundabout and more turns to the destination. */
private fun previewListProgress(): RouteProgressSnapshot {
    fun item(type: ManeuverType, turn: TurnModifier, road: String, meters: Double, exit: Int? = null, degrees: Double? = null) =
        FollowingManeuver(Maneuver(type, turn, road, roundaboutExitNumber = exit, roundaboutExitDegrees = degrees), Distance.meters(meters), (meters / 13.0).seconds)
    return RouteProgressSnapshot(
        nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Oak Street"),
        distanceToNextManeuver = Distance.miles(0.2),
        lanes = LaneInfo(
            listOf(
                Lane(setOf(TurnModifier.LEFT), isRecommended = false),
                Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                Lane(setOf(TurnModifier.RIGHT), isRecommended = true, activeIndication = TurnModifier.RIGHT),
            ),
        ),
        distanceRemaining = Distance.miles(6.4),
        durationRemaining = 14.minutes,
        upcoming = listOf(
            item(ManeuverType.TURN, TurnModifier.LEFT, "Pine Street", 350.0),
            item(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT, "Elm Street", 1_200.0, exit = 2, degrees = 180.0),
            item(ManeuverType.ON_RAMP, TurnModifier.SLIGHT_RIGHT, "Ridge Road", 2_100.0),
            item(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 12: Lake Avenue", 7_400.0),
            item(ManeuverType.TURN, TurnModifier.LEFT, "Maple Drive", 8_100.0),
            item(ManeuverType.TURN, TurnModifier.RIGHT, "Cedar Lane", 9_300.0),
            item(ManeuverType.ARRIVE, TurnModifier.RIGHT, "Office", 9_900.0),
        ),
    )
}
