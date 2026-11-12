package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.ui.theme.AccentButton
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.theme.LookTokens
import io.github.purplepalm.custommaps.ui.theme.PreviewOnMap
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

private const val TABULAR_DIGITS = "tnum"

/** Corner radius of the maneuver card; the Then chip tucks this far under its bottom edge. */
internal val CARD_CORNER = 24.dp

/**
 * Next maneuver: the arrow, a large distance and the road, with lane guidance below. Right after a
 * new route, until the navigator's first instruction for it ([RouteProgressSnapshot.pending]), the
 * card shows the new route's maneuver dimmed, with no distance and no lanes. Which lane row it
 * shows is [CardLanes]' decision: the run-up's to an exit with its words, a lane choice's ahead
 * with its distance, or the maneuver's own.
 *
 * [onRunUpWords] hears, for the debug log, whether the words of a run-up's row fitted beside its
 * lanes, with the row's key ([RunUpLanes.key]): false when they were left out.
 */
@Composable
fun ManeuverCard(
    progress: RouteProgressSnapshot,
    modifier: Modifier = Modifier,
    onRunUpWords: (key: String, shown: Boolean) -> Unit = NO_WORDS_REPORT,
) {
    val maneuver = progress.nextManeuver
    val distance = ImperialFormat.distance(progress.distanceToNextManeuver)
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CARD_CORNER),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Column(Modifier.padding(16.dp).alpha(cardContentAlpha(progress.pending, tokens.pendingCardAlpha))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverGlyph(
                    type = maneuver.type,
                    turn = maneuver.modifier,
                    color = tokens.accentInk,
                    modifier = Modifier.size(72.dp),
                    roundaboutExitDegrees = maneuver.roundaboutExitDegrees,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // A pending card keeps the row's place, but shows and says no distance.
                    val distanceRow = if (progress.pending) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
                    Row(modifier = distanceRow, verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = distance.value,
                            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TABULAR_DIGITS),
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.onCard,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = distance.unit,
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.onCardMuted,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        text = maneuver.primaryText,
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.onCard,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        maneuver.roundaboutExitNumber?.let { stringResource(R.string.guidance_roundabout_exit, it) },
                        maneuver.secondaryText,
                    ).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.onCardMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // On the run-up to an exit its one row, with the lanes to use in words where the counts
            // allow them. Elsewhere a lane choice the car reaches before the maneuver takes the row,
            // with its distance: those lanes come first on the road. After it the row is the
            // maneuver's again.
            val row = CardLanes.of(progress)
            row.lanes?.let { lanes ->
                Spacer(Modifier.height(12.dp))
                when (row.source) {
                    CardLanes.Source.RUN_UP -> {
                        val words = row.target?.let(::laneWordsRes)
                        val key = progress.runUpLanes?.key
                        LanesRow(
                            lanes,
                            label = words?.let { stringResource(it.short) },
                            description = words?.let { stringResource(it.long) },
                            labelShare = LANE_WORDS_MAX_WIDTH_SHARE,
                            onLabelShown = if (words != null && key != null) { shown -> onRunUpWords(key, shown) } else null,
                        )
                    }
                    CardLanes.Source.CHOICE -> LanesRow(lanes, label = row.distance?.let { ImperialFormat.distance(it).toString() })
                    else -> LanesRow(lanes)
                }
            }
        }
    }
}

/** Tells nobody whether a run-up's words fitted: for the previews. */
private val NO_WORDS_REPORT: (String, Boolean) -> Unit = { _, _ -> }

/**
 * How opaque the card's content is: [dimmed], the look's own share ([LookTokens.pendingCardAlpha]),
 * while the card waits for a new route's first instruction, and fully opaque otherwise.
 */
internal fun cardContentAlpha(pending: Boolean, dimmed: Float): Float = if (pending) dimmed else 1f

/**
 * Lanes from left to right; the ones to use in [LookTokens.laneOn], the others in
 * [LookTokens.laneOff], and so is a lane kept for car pools or buses. Every lane is shown: when there are more than fit, all of them get narrower,
 * since the lane to take is often the last one.
 *
 * With a [label], for lanes that are not the next maneuver's, the label stands at the start of the
 * row in small type: a lane choice's distance, or on the run-up to an exit the lanes to use in
 * words. It takes at most [labelShare] of the row and shrinks to fit, and shows whole or not at
 * all: a label wider than its share at the smallest size is left out, never cut. The lanes stay in
 * the middle of the row while there is room for that beside the label, and move over, and get
 * narrower, when there is not. The row is as high as its lanes are without a label
 * ([laneRowHeight]), so a label that comes or goes never changes the card's height; lanes made
 * narrower by it stand in the middle of that height. A [description] is what the screen reader
 * says for the row. [onLabelShown] hears, each time the row is placed with a label, whether the
 * label was shown or left out.
 */
@Composable
fun LanesRow(
    lanes: LaneInfo,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    labelShare: Float = LANE_LABEL_MAX_WIDTH_SHARE,
    onLabelShown: ((Boolean) -> Unit)? = null,
) {
    val tokens = LookTheme.tokens
    val highlight = tokens.laneOn
    val dim = tokens.laneOff
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_DIGITS)
    val measurer = rememberTextMeasurer()
    val count = lanes.lanes.size
    val described = if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier
    Layout(
        content = {
            lanes.lanes.forEach { lane -> LaneGlyph(lane = lane, highlight = highlight, dim = dim) }
            if (label != null) LaneLabel(label, labelStyle)
        },
        modifier = modifier.fillMaxWidth().then(described),
    ) { measurables, constraints ->
        val fullSlot = LANE_SLOT.roundToPx()
        // The card gives the row a width. Without one, every lane is as wide as it likes to be.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fullSlot * (count + 2)
        val height = laneRowHeight(width, count, fullSlot)
        // The label keeps to a share of the row and to its height; its text shrinks to fit, and a
        // text that does not fit at its smallest size is left out.
        val smallest = label?.let { measurer.measure(it, labelStyle.copy(fontSize = LANE_LABEL_MIN_SIZE), maxLines = 1, softWrap = false).size }
        val fits = smallest != null && wordsFit(smallest.width, width, labelShare) && smallest.height <= height
        val shown = if (fits) {
            measurables.getOrNull(count)?.measure(Constraints(maxWidth = (width * labelShare).roundToInt(), maxHeight = height))
        } else {
            null
        }
        val reserved = if (shown != null) shown.width + LANE_LABEL_GAP.roundToPx() else 0
        val row = planLaneRow(width, count, fullSlot, reserved)
        val gap = (row.slot * LANE_GAP_SHARE).roundToInt()
        val arrows = (row.slot - 2 * gap).coerceAtLeast(0)
        val glyphs = List(count) { measurables[it].measure(Constraints.fixed(arrows, arrows)) }
        val top = (height - row.slot) / 2
        layout(width, height) {
            shown?.placeRelative(0, (height - shown.height) / 2)
            glyphs.forEachIndexed { index, glyph -> glyph.placeRelative(row.start + index * row.slot + gap, top + gap) }
            // Once placed, so whoever listens hears what is on the screen.
            if (label != null) onLabelShown?.invoke(shown != null)
        }
    }
}

/** Where the lanes of a row go: how wide each lane is, gap included, and where the first one starts. */
internal data class LaneRowPlan(val slot: Int, val start: Int)

/**
 * How high a row of [lanes] lanes [width] pixels wide is: as high as a lane is wide without a label,
 * [fullSlot] at most. A label beside the lanes never changes it.
 */
internal fun laneRowHeight(width: Int, lanes: Int, fullSlot: Int): Int = planLaneRow(width, lanes, fullSlot, reserved = 0).slot

/**
 * Lays [lanes] lanes into a row [width] wide, of which the first [reserved] belong to the distance
 * label, all in pixels. A lane is [fullSlot] wide, or narrower when that many do not fit beside
 * the label. The lanes stand in the middle of the whole row, where they are without a label, as
 * long as that keeps them clear of the label; otherwise they start right after it.
 */
internal fun planLaneRow(width: Int, lanes: Int, fullSlot: Int, reserved: Int): LaneRowPlan {
    val slot = minOf(fullSlot, (width - reserved).coerceAtLeast(0) / lanes)
    return LaneRowPlan(slot, start = maxOf(reserved, (width - slot * lanes) / 2))
}

/**
 * Whether a label [textWidthAtMinSize] wide at its smallest size, in pixels, fits whole into its
 * [share] of a row [rowWidth] pixels wide. A label that does not is left out of the row, never cut:
 * on the run-up to an exit the lanes then stand without their words, which the screen reader still
 * says.
 */
internal fun wordsFit(textWidthAtMinSize: Int, rowWidth: Int, share: Float): Boolean =
    textWidthAtMinSize <= (rowWidth * share).roundToInt()

/** The label at the start of a lane row: how far ahead the lanes are, for example "0.4 mi", or the lanes to use in words. */
@Composable
private fun LaneLabel(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style,
        color = LookTheme.tokens.onCardMuted,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = LANE_LABEL_MIN_SIZE, maxFontSize = style.fontSize),
    )
}

/** Leaves the arrival time more room than the default button padding would. */
private val COMPACT_BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** A lane's width at full size, including the gap around its arrows. */
private val LANE_SLOT = 44.dp

/** Share of a lane's width left empty on each side. */
private const val LANE_GAP_SHARE = 4f / 44f

/** Between the distance label and the first lane. */
private val LANE_LABEL_GAP = 8.dp

/** The most of the row the distance label may take, whatever the font size: the lanes are what the row is for. */
internal const val LANE_LABEL_MAX_WIDTH_SHARE = 0.3f

/**
 * The most of the row the lanes to use in words may take on the run-up to an exit: more than a
 * distance, since "2nd from right" is longer and is the instruction itself.
 */
internal const val LANE_WORDS_MAX_WIDTH_SHARE = 0.45f

/** The smallest the label's text shrinks to; at the phone's font scale. */
private val LANE_LABEL_MIN_SIZE = 10.sp

/**
 * Arrival time, time and distance left, and the controls to change view or end guidance. The texts
 * stay on one line and shrink if they have to, so the arrival time is never split. Overview has a
 * border of its own and a label in the card's ink in both looks: Material's outlined button would
 * draw a border the card hardly shows (1.28:1 at night) and a muted label.
 */
@Composable
fun GuidanceBar(
    progress: RouteProgressSnapshot?,
    following: Boolean,
    onToggleOverview: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (progress != null) {
                    val arrivalStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR_DIGITS)
                    Text(
                        text = ImperialFormat.arrivalTime(ZonedDateTime.now(), progress.durationRemaining),
                        style = arrivalStyle,
                        color = tokens.glanceAccent,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = arrivalStyle.fontSize),
                    )
                    val remainingStyle = MaterialTheme.typography.bodyMedium
                    Text(
                        text = stringResource(
                            R.string.guidance_remaining,
                            ImperialFormat.duration(progress.durationRemaining),
                            ImperialFormat.distance(progress.distanceRemaining).toString(),
                        ),
                        style = remainingStyle,
                        color = tokens.onCardMuted,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = remainingStyle.fontSize),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.guidance_starting),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.onCardMuted,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onToggleOverview,
                border = BorderStroke(1.dp, tokens.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.onCard),
                contentPadding = COMPACT_BUTTON_PADDING,
            ) {
                Text(stringResource(if (following) R.string.guidance_overview else R.string.guidance_recenter))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onEnd,
                contentPadding = COMPACT_BUTTON_PADDING,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.danger, contentColor = tokens.onDanger),
            ) {
                Text(stringResource(R.string.guidance_end))
            }
        }
    }
}

@Composable
fun ArrivedCard(destination: Place?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverGlyph(
                type = ManeuverType.ARRIVE,
                turn = null,
                color = tokens.accentInk,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.guidance_arrived),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.onCard,
                )
                destination?.let {
                    Text(
                        text = it.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.onCardMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AccentButton(onClick = onDone) {
                Text(stringResource(R.string.guidance_done))
            }
        }
    }
}

/**
 * A lane choice before the exit: the row shows the lanes of the split 0.4 miles ahead, not the
 * exit's own. On a narrow phone, and with the largest font, where the label takes more of the row.
 */
@Preview(name = "lane choice ahead", widthDp = 360)
@Preview(name = "lane choice ahead, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun ManeuverCardLaneChoicePreview(@PreviewParameter(LookParameter::class) look: Look) {
    val on = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, activeIndication = TurnModifier.STRAIGHT)
    val off = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false)
    val exit = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
    PreviewOnMap(look) {
        ManeuverCard(
            RouteProgressSnapshot(
                nextManeuver = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 12: Oak Street"),
                distanceToNextManeuver = Distance.miles(2.3),
                lanes = LaneInfo(listOf(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false), exit)),
                distanceRemaining = Distance.miles(12.3),
                durationRemaining = 18.minutes,
                laneChoiceAhead = LanesAhead(Distance.miles(0.4), LaneInfo(listOf(on, on, on, off, off))),
            ),
        )
    }
}

@Preview(widthDp = 360)
@Composable
private fun ManeuverCardPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) { ManeuverCard(previewExitProgress()) }
}

/**
 * Right after a new route, until the navigator's first instruction for it: the new route's
 * maneuver dimmed, less by day where the card is white, with no distance and no lanes.
 */
@Preview(name = "pending card", widthDp = 360)
@Composable
private fun ManeuverCardPendingPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) { ManeuverCard(previewExitProgress().copy(lanes = null, pending = true)) }
}

/** The exit of the previews: two lanes go on, two take the exit. */
private fun previewExitProgress() = RouteProgressSnapshot(
    nextManeuver = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street", "Office"),
    distanceToNextManeuver = Distance.miles(0.4),
    lanes = LaneInfo(
        listOf(
            Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
            Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
            Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), true, TurnModifier.SLIGHT_RIGHT),
            Lane(setOf(TurnModifier.SLIGHT_RIGHT), true, TurnModifier.SLIGHT_RIGHT),
        ),
    ),
    distanceRemaining = Distance.miles(12.3),
    durationRemaining = 18.minutes,
)

/**
 * The run-up to an exit, on a narrow phone at three font sizes: the one row with the lanes to use
 * in words, which shrink with the font and show whole or not at all, while the row keeps its
 * height. A lane for car pools stands at the left edge, dim.
 */
@Preview(name = "run-up, right lane", widthDp = 360)
@Preview(name = "run-up, right lane, larger font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "run-up, right lane, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun RunUpRightLanePreview(@PreviewParameter(LookParameter::class) look: Look) {
    RunUpPreview(
        look,
        Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street"),
        RunUpLanes(
            LaneInfo(listOf(previewCarPool, previewOn(false), previewOn(false), previewOn(false), previewOn(false), previewExit)),
            target = LaneTarget(LaneEdge.RIGHT, 1, 0),
            key = "route/0/3/end",
            odd = null,
        ),
    )
}

@Preview(name = "run-up, 2 right", widthDp = 360)
@Preview(name = "run-up, 2 right, larger font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "run-up, 2 right, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun RunUpTwoRightPreview(@PreviewParameter(LookParameter::class) look: Look) {
    RunUpPreview(
        look,
        Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street"),
        RunUpLanes(
            LaneInfo(listOf(previewCarPool, previewOn(false), previewOn(false), previewOn(false), previewExit, previewExit)),
            target = LaneTarget(LaneEdge.RIGHT, 2, 0),
            key = "route/0/3/end",
            odd = null,
        ),
    )
}

/** Before the exit, where the right lane leaves for an earlier one: the second lane from the right goes on to it. */
@Preview(name = "run-up, 2nd from right", widthDp = 360)
@Preview(name = "run-up, 2nd from right, larger font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "run-up, 2nd from right, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun RunUpSecondFromRightPreview(@PreviewParameter(LookParameter::class) look: Look) {
    RunUpPreview(
        look,
        Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street"),
        RunUpLanes(
            LaneInfo(listOf(previewCarPool, previewOn(false), previewOn(false), previewOn(true), previewLeaving)),
            target = LaneTarget(LaneEdge.RIGHT, 1, 1),
            key = "route/0/3/2",
            odd = null,
        ),
    )
}

/** A left exit: the lane for car pools at the left edge is never the left lane, and stays dim. */
@Preview(name = "run-up, left lane past a car pool lane", widthDp = 360)
@Preview(name = "run-up, left lane past a car pool lane, larger font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "run-up, left lane past a car pool lane, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun RunUpLeftLanePreview(@PreviewParameter(LookParameter::class) look: Look) {
    val left = Lane(setOf(TurnModifier.SLIGHT_LEFT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_LEFT)
    RunUpPreview(
        look,
        Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_LEFT, "Exit 31: Elm Street"),
        RunUpLanes(
            LaneInfo(listOf(previewCarPool, left, previewOn(false), previewOn(false), previewOn(false))),
            target = LaneTarget(LaneEdge.LEFT, 1, 0),
            key = "route/0/5/end",
            odd = null,
        ),
    )
}

@Composable
private fun RunUpPreview(look: Look, maneuver: Maneuver, row: RunUpLanes) {
    PreviewOnMap(look) {
        ManeuverCard(
            RouteProgressSnapshot(
                nextManeuver = maneuver,
                distanceToNextManeuver = Distance.miles(1.2),
                lanes = null,
                distanceRemaining = Distance.miles(12.3),
                durationRemaining = 18.minutes,
                runUpLanes = row,
            ),
        )
    }
}

/**
 * The guidance bar while the camera follows the car and in the overview, on a narrow phone, also
 * with a large font: Overview's border and label read in both looks.
 */
@Preview(name = "guidance bar", widthDp = 360)
@Preview(name = "guidance bar, large font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun GuidanceBarPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        Column {
            GuidanceBar(previewExitProgress(), following = true, onToggleOverview = {}, onEnd = {})
            Spacer(Modifier.height(12.dp))
            GuidanceBar(previewExitProgress(), following = false, onToggleOverview = {}, onEnd = {})
        }
    }
}

@Preview(name = "arrived", widthDp = 360)
@Composable
private fun ArrivedCardPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) { ArrivedCard(Place("Office", "12 Oak Street", GeoPoint(0.0, 0.0)), onDone = {}) }
}

/** A lane for car pools: drawn dim, never lit while another lane can be used. */
private val previewCarPool = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true)

/** A general lane that goes on, lit or not. */
private fun previewOn(lit: Boolean) = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = lit, activeIndication = if (lit) TurnModifier.STRAIGHT else null)

/** A lane that leaves for an earlier exit. */
private val previewLeaving = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false)

/** A lane that goes on to the exit, lit. */
private val previewExit = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
