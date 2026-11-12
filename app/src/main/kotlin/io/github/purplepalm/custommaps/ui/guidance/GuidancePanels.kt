package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
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
                            fontWeight = FontWeight.Medium,
                            color = tokens.onCardMuted,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        text = maneuver.primaryText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
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
                            labelMinSize = LANE_WORDS_MIN_SIZE,
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
 * [LookTokens.laneOff], and so is a lane kept for car pools or buses. Every lane is shown: when
 * there are more than fit, all of them get narrower, since the lane to take is often the last one.
 * A lane to take ([isLaneToTake]) is marked by shape as well as colour: its arrow is heavier
 * ([laneStroke]) and a short bar lies under it, in a band the row has whether any lane is to take
 * or not.
 *
 * With a [label], for lanes that are not the next maneuver's, the label stands at the start of the
 * row in small type: a lane choice's distance, or on the run-up to an exit the lanes to use in
 * words. It takes at most [labelShare] of the row and shrinks to fit, not below [labelMinSize],
 * and shows whole or not at all: a label wider than its share at that size is left out, never cut.
 * The lanes stay in the middle of the row while there is room for that beside the label, and move
 * over, and get narrower, when there is not. The row is as high as its lanes are without a label,
 * and the band ([laneRowGeometry]), so a label that comes or goes never changes the card's height;
 * lanes made narrower by it stand in the middle of the lanes' height, with their bars right under
 * them. A [description] is what the screen reader says for the row. [onLabelShown] hears, each time
 * the row is placed with a label, whether the label was shown or left out.
 */
@Composable
fun LanesRow(
    lanes: LaneInfo,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    labelShare: Float = LANE_LABEL_MAX_WIDTH_SHARE,
    labelMinSize: TextUnit = LANE_DISTANCE_MIN_SIZE,
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
            if (label != null) LaneLabel(label, labelStyle, labelMinSize, Modifier.layoutId(LaneRowPart.LABEL))
            lanes.lanes.forEachIndexed { index, lane ->
                if (isLaneToTake(lane)) LaneBar(highlight, Modifier.layoutId(LaneBarOf(index)))
            }
        },
        modifier = modifier.fillMaxWidth().then(described),
    ) { measurables, constraints ->
        val fullSlot = LANE_SLOT.roundToPx()
        // The card gives the row a width. Without one, every lane is as wide as it likes to be.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fullSlot * (count + 2)
        val lanesHeight = laneRowHeight(width, count, fullSlot)
        // The label keeps to a share of the row and to the lanes' height; its text shrinks to fit,
        // and a text that does not fit at its smallest size is left out.
        val smallest = label?.let { measurer.measure(it, labelStyle.copy(fontSize = labelMinSize), maxLines = 1, softWrap = false).size }
        val fits = smallest != null && wordsFit(smallest.width, width, labelShare) && smallest.height <= lanesHeight
        val shown = if (fits) {
            measurables.firstOrNull { it.layoutId == LaneRowPart.LABEL }
                ?.measure(Constraints(maxWidth = (width * labelShare).roundToInt(), maxHeight = lanesHeight))
        } else {
            null
        }
        val reserved = if (shown != null) shown.width + LANE_LABEL_GAP.roundToPx() else 0
        val barGap = LANE_BAR_GAP.roundToPx()
        val barThickness = LANE_BAR_THICKNESS.roundToPx()
        val row = laneRowGeometry(width, count, fullSlot, reserved, band = barGap + barThickness)
        val slot = row.plan.slot
        val gap = (slot * LANE_GAP_SHARE).roundToInt()
        val arrows = (slot - 2 * gap).coerceAtLeast(0)
        val glyphs = List(count) { measurables[it].measure(Constraints.fixed(arrows, arrows)) }
        val bars = measurables.mapNotNull { measurable ->
            val lane = (measurable.layoutId as? LaneBarOf)?.lane ?: return@mapNotNull null
            val bounds = laneBarBounds(row, lane, barGap, barThickness)
            bounds to measurable.measure(Constraints.fixed(bounds.width, bounds.height))
        }
        layout(width, row.height) {
            shown?.placeRelative(0, (row.lanesHeight - shown.height) / 2)
            glyphs.forEachIndexed { index, glyph -> glyph.placeRelative(row.plan.start + index * slot + gap, row.lanesTop + gap) }
            bars.forEach { (bounds, bar) -> bar.placeRelative(bounds.left, bounds.top) }
            // Once placed, so whoever listens hears what is on the screen.
            if (label != null) onLabelShown?.invoke(shown != null)
        }
    }
}

/** The parts of a lane row other than its lanes, by which its layout finds them. */
private enum class LaneRowPart { LABEL }

/** The bar under the lane at [lane], counted from the left. */
private data class LaneBarOf(val lane: Int)

/** The bar under a lane to take: a short line with round ends. Nothing for a screen reader. */
@Composable
private fun LaneBar(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color, CircleShape))
}

/** Where the lanes of a row go: how wide each lane is, gap included, and where the first one starts. */
internal data class LaneRowPlan(val slot: Int, val start: Int)

/**
 * How high a row of [lanes] lanes [width] pixels wide is: as high as a lane is wide without a label,
 * [fullSlot] at most. A label beside the lanes never changes it.
 */
internal fun laneRowHeight(width: Int, lanes: Int, fullSlot: Int): Int = planLaneRow(width, lanes, fullSlot, reserved = 0).slot

/** Where everything of a lane row goes, in pixels: its height, the lanes' part of it, and the lanes across it. */
internal data class LaneRowGeometry(val height: Int, val lanesHeight: Int, val plan: LaneRowPlan) {
    /** Where the lanes' top is: lanes made narrower by a label stand in the middle of their part. */
    val lanesTop: Int get() = (lanesHeight - plan.slot) / 2
}

/**
 * Lays out a row of [lanes] lanes [width] wide, the first [reserved] of it the label's: the
 * lanes' part is one lane high ([laneRowHeight]) and the [band] for the bars of the lanes to take
 * lies under it. The row's height is the same with a label and without one, and with lanes to take
 * and without.
 */
internal fun laneRowGeometry(width: Int, lanes: Int, fullSlot: Int, reserved: Int, band: Int): LaneRowGeometry {
    val lanesHeight = laneRowHeight(width, lanes, fullSlot)
    return LaneRowGeometry(height = lanesHeight + band, lanesHeight = lanesHeight, plan = planLaneRow(width, lanes, fullSlot, reserved))
}

/**
 * Where the bar under lane [index] of [row] goes: [thickness] high and [gap] under the lane, as wide
 * as the middle [share] of the lane. Under lanes at full size it fills the band to the row's
 * bottom; under lanes made narrower by a label it stays right under them.
 */
internal fun laneBarBounds(row: LaneRowGeometry, index: Int, gap: Int, thickness: Int, share: Float = LANE_BAR_SHARE): IntRect {
    val slot = row.plan.slot
    val width = (slot * share).roundToInt()
    val left = row.plan.start + index * slot + (slot - width) / 2
    val top = row.lanesTop + slot + gap
    return IntRect(left, top, left + width, top + thickness)
}

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

/**
 * The label at the start of a lane row: how far ahead the lanes are, for example "0.4 mi", or the
 * lanes to use in words. Its text shrinks to fit, never below [minSize].
 */
@Composable
private fun LaneLabel(text: String, style: TextStyle, minSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = LookTheme.tokens.onCardMuted,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = style.fontSize),
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

/** Between a lane to take and the bar under it. */
private val LANE_BAR_GAP = 2.dp

/** How high the bar under a lane to take is. With [LANE_BAR_GAP] it makes the band under the lanes, 5 dp. */
private val LANE_BAR_THICKNESS = 3.dp

/** How much of its lane's width the bar takes, in the middle of it. */
internal const val LANE_BAR_SHARE = 0.6f

/** The most of the row the distance label may take, whatever the font size: the lanes are what the row is for. */
internal const val LANE_LABEL_MAX_WIDTH_SHARE = 0.3f

/**
 * The most of the row the lanes to use in words may take on the run-up to an exit: more than a
 * distance, since "2nd from right" is longer and is the instruction itself.
 */
internal const val LANE_WORDS_MAX_WIDTH_SHARE = 0.45f

/** The smallest a lane choice's distance shrinks to, at the phone's font scale: a number read at a glance. */
private val LANE_DISTANCE_MIN_SIZE = 12.sp

/**
 * The smallest the lanes to use in words shrink to on the run-up to an exit, at the phone's font
 * scale. Smaller than a distance's: the longest words still fit with the largest font, where at
 * 12 sp they would be left out.
 */
private val LANE_WORDS_MIN_SIZE = 10.sp

/** The smallest the time and the distance left in the guidance bar shrink to, at the phone's font scale. */
private val REMAINING_MIN_SIZE = 12.sp

/** The smallest the arrival time in the guidance bar shrinks to, at the phone's font scale. */
private val ARRIVAL_MIN_SIZE = 16.sp

/** Between the guidance bar's numbers and its buttons, between its two buttons, and between its two rows. */
private val BAR_GAP = 8.dp

/**
 * The widest clock times the guidance bar shows: a two-digit hour, in either half of the day. Its
 * digits are all as wide, so no arrival time is wider than these.
 */
internal val WIDEST_ARRIVALS = listOf("12:00 AM", "12:00 PM")

/**
 * A time left as wide as the time left of any drive under ten hours, with digits all as wide: the
 * guidance bar keeps room for it, so a drive that falls under an hour keeps the bar it started with.
 */
internal const val WIDE_TIME_LEFT = "9 hr 59 min"

/**
 * Arrival time, time and distance left, and the controls to change view or end guidance. The
 * numbers shrink if they have to, never below [ARRIVAL_MIN_SIZE] and [REMAINING_MIN_SIZE], and stand
 * beside the two buttons where they fit there at those sizes. Where they do not, as with the largest
 * font sizes on a narrow phone, they take a row of their own across the bar, above the buttons
 * ([barRows]), so that no number is ever cut. That depends on the phone's width and font, not on the
 * drive: the bar keeps room for the widest numbers a drive shows ([WIDEST_ARRIVALS], [WIDE_TIME_LEFT],
 * or the drive's own where they are wider) and for the wider of Overview and Recenter, so it keeps
 * one layout from the start of a drive to its end, and one height once the numbers show, and the
 * camera's padding with it.
 * Overview has a border of its own and a label in the card's ink in both looks: Material's outlined
 * button would draw a border the card hardly shows (1.28:1 at night) and a muted label.
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
    // The numbers read at a glance a step heavier than Material's, for sunlight.
    val arrivalStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR_DIGITS)
    val remainingStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    // What Material's buttons set their labels in.
    val labelStyle = MaterialTheme.typography.labelLarge
    val arrival = progress?.let { ImperialFormat.arrivalTime(ZonedDateTime.now(), it.durationRemaining) }
    val time = progress?.let { ImperialFormat.duration(it.durationRemaining) }
    val distance = progress?.let { ImperialFormat.distance(it.distanceRemaining).toString() }
    val toggleLabel = stringResource(if (following) R.string.guidance_overview else R.string.guidance_recenter)
    val otherLabel = stringResource(if (following) R.string.guidance_recenter else R.string.guidance_overview)
    // How wide the numbers are at their smallest, and how much wider the toggle's other label is
    // than the one it shows, in pixels; the measurer keeps what it has measured.
    val measurer = rememberTextMeasurer()
    val numbers = maxOf(
        (WIDEST_ARRIVALS + listOfNotNull(arrival)).maxOf { measurer.widthOf(it, arrivalStyle.copy(fontSize = ARRIVAL_MIN_SIZE)) },
        listOfNotNull(WIDE_TIME_LEFT, time).maxOf { measurer.widthOf(it, remainingStyle.copy(fontSize = REMAINING_MIN_SIZE)) },
    )
    val toggleSlack = (measurer.widthOf(otherLabel, labelStyle) - measurer.widthOf(toggleLabel, labelStyle)).coerceAtLeast(0)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Layout(
            content = {
                Column(Modifier.layoutId(BarPart.NUMBERS)) {
                    if (arrival != null && time != null && distance != null) {
                        Text(
                            text = arrival,
                            style = arrivalStyle,
                            color = tokens.glanceAccent,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = ARRIVAL_MIN_SIZE, maxFontSize = arrivalStyle.fontSize),
                        )
                        RemainingLine(time = time, distance = distance, style = remainingStyle, color = tokens.onCardMuted)
                    } else {
                        Text(
                            text = stringResource(R.string.guidance_starting),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.onCardMuted,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onToggleOverview,
                    modifier = Modifier.layoutId(BarPart.TOGGLE),
                    border = BorderStroke(1.dp, tokens.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.onCard),
                    contentPadding = COMPACT_BUTTON_PADDING,
                ) {
                    Text(toggleLabel)
                }
                Button(
                    onClick = onEnd,
                    modifier = Modifier.layoutId(BarPart.END),
                    contentPadding = COMPACT_BUTTON_PADDING,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.danger, contentColor = tokens.onDanger),
                ) {
                    Text(stringResource(R.string.guidance_end))
                }
            },
            modifier = Modifier.padding(16.dp),
        ) { measurables, constraints ->
            val gap = BAR_GAP.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val toggle = measurables.first { it.layoutId == BarPart.TOGGLE }.measure(loose)
            val end = measurables.first { it.layoutId == BarPart.END }.measure(loose)
            val numbersPart = measurables.first { it.layoutId == BarPart.NUMBERS }
            // The buttons and the gaps beside them, with room for the toggle's wider label.
            val buttons = gap + toggle.width + toggleSlack + gap + end.width
            val bounded = constraints.hasBoundedWidth
            if (!bounded || barRows(constraints.maxWidth, buttons, numbers) == BarRows.ONE) {
                val room = if (bounded) (constraints.maxWidth - buttons).coerceAtLeast(0) else Constraints.Infinity
                val column = numbersPart.measure(loose.copy(maxWidth = room))
                val width = if (bounded) constraints.maxWidth else column.width + buttons
                val height = constraints.constrainHeight(maxOf(column.height, toggle.height, end.height))
                layout(width, height) {
                    column.placeRelative(0, (height - column.height) / 2)
                    toggle.placeRelative(width - end.width - gap - toggle.width, (height - toggle.height) / 2)
                    end.placeRelative(width - end.width, (height - end.height) / 2)
                }
            } else {
                // The numbers across the whole bar, and the buttons under them where they were.
                val column = numbersPart.measure(loose)
                val width = constraints.maxWidth
                val buttonsTop = column.height + gap
                val buttonsHeight = maxOf(toggle.height, end.height)
                layout(width, constraints.constrainHeight(buttonsTop + buttonsHeight)) {
                    column.placeRelative(0, 0)
                    toggle.placeRelative(width - end.width - gap - toggle.width, buttonsTop + (buttonsHeight - toggle.height) / 2)
                    end.placeRelative(width - end.width, buttonsTop + (buttonsHeight - end.height) / 2)
                }
            }
        }
    }
}

/** The parts of the guidance bar, by which its layout finds them. */
private enum class BarPart { NUMBERS, TOGGLE, END }

/** Where the guidance bar's numbers stand: beside its two buttons, or in a row of their own above them. */
internal enum class BarRows { ONE, TWO }

/**
 * Where the numbers go in a guidance bar [width] wide inside its padding, all in pixels: beside its
 * buttons, [buttons] wide with the gaps beside them, when [numbers], the widest the arrival time and
 * the time left are at their smallest sizes, fit into what the buttons leave; otherwise in a row of
 * their own across the whole bar, above the buttons, so that no number is ever cut.
 */
internal fun barRows(width: Int, buttons: Int, numbers: Int): BarRows =
    if (numbers <= width - buttons) BarRows.ONE else BarRows.TWO

/** How the time and the distance left are shown ([remainingForm]). */
internal enum class RemainingForm {
    /** Both on one line, as in "18 min · 12.3 mi". */
    BOTH,

    /** The time alone; the screen reader says both. */
    TIME,

    /** The time, and the distance under it, each wrapping at its spaces if it must. */
    STACKED,
}

/**
 * How the time and the distance left are shown in a column [room] wide, from how wide [both] on one
 * line and the [time] alone are at their smallest size, all in pixels: both where they fit, else the
 * time alone where it fits, else the two on lines of their own, so that neither is ever cut.
 */
internal fun remainingForm(room: Int, both: Int, time: Int): RemainingForm = when {
    both <= room -> RemainingForm.BOTH
    time <= room -> RemainingForm.TIME
    else -> RemainingForm.STACKED
}

/** How wide [text] is on one line in [style], in pixels. */
private fun TextMeasurer.widthOf(text: String, style: TextStyle): Int = measure(text, style, maxLines = 1, softWrap = false).size.width

/**
 * The time and distance left on one line, shrinking to fit, never below [REMAINING_MIN_SIZE]. Where
 * even that leaves no room, the time alone where that fits, and otherwise the time and the distance
 * on lines of their own ([remainingForm]), so that nothing is cut off; the screen reader says both.
 * In the guidance bar the time alone always fits: the bar keeps room for it.
 */
@Composable
private fun RemainingLine(time: String, distance: String, style: TextStyle, color: Color) {
    val both = stringResource(R.string.guidance_remaining, time, distance)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val smallest = style.copy(fontSize = REMAINING_MIN_SIZE)
        val form = if (constraints.hasBoundedWidth) {
            remainingForm(constraints.maxWidth, both = measurer.widthOf(both, smallest), time = measurer.widthOf(time, smallest))
        } else {
            RemainingForm.BOTH
        }
        when (form) {
            RemainingForm.BOTH, RemainingForm.TIME -> Text(
                text = if (form == RemainingForm.BOTH) both else time,
                style = style,
                color = color,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = REMAINING_MIN_SIZE, maxFontSize = style.fontSize),
                modifier = if (form == RemainingForm.BOTH) Modifier else Modifier.clearAndSetSemantics { contentDescription = both },
            )
            RemainingForm.STACKED -> Text(
                text = "$time\n$distance",
                style = smallest,
                color = color,
                modifier = Modifier.clearAndSetSemantics { contentDescription = both },
            )
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
@Preview(name = "large font", widthDp = 360, fontScale = 1.3f)
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
 * The guidance bar while the camera follows the car and in the overview, on a long drive, and
 * before the first progress, on a narrow phone, also with a large and the largest font: Overview's
 * border and label read in both looks, and the time and distance left shrink to fit, never below
 * 12 sp, or show the time alone. With the largest font the numbers, and the words before the first
 * progress, take a row of their own above the buttons.
 */
@Preview(name = "guidance bar", widthDp = 360)
@Preview(name = "guidance bar, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "guidance bar, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun GuidanceBarPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        Column {
            GuidanceBar(previewExitProgress(), following = true, onToggleOverview = {}, onEnd = {})
            Spacer(Modifier.height(12.dp))
            GuidanceBar(previewExitProgress(), following = false, onToggleOverview = {}, onEnd = {})
            Spacer(Modifier.height(12.dp))
            // A long drive: with a large font only the time fits beside the buttons; with the
            // largest, both fit in the row of their own.
            val long = previewExitProgress().copy(distanceRemaining = Distance.miles(48.0), durationRemaining = 75.minutes)
            GuidanceBar(long, following = true, onToggleOverview = {}, onEnd = {})
            Spacer(Modifier.height(12.dp))
            GuidanceBar(progress = null, following = true, onToggleOverview = {}, onEnd = {})
        }
    }
}

@Preview(name = "arrived", widthDp = 360)
@Composable
private fun ArrivedCardPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) { ArrivedCard(Place("Office", "12 Oak Street", GeoPoint(0.0, 0.0)), onDone = {}) }
}

/**
 * Lanes rows on a card: two lanes with one to take, four with two to take (one of them with two
 * arrows), six with one to take, and a lane choice's row with its distance. A lane to take has the
 * heavier arrow and the bar under it; on a narrow phone, also with a large font.
 */
@Preview(name = "lanes to take", widthDp = 360)
@Preview(name = "lanes to take, large font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun LanesRowPreview(@PreviewParameter(LookParameter::class) look: Look) {
    val left = Lane(setOf(TurnModifier.LEFT), isRecommended = true, activeIndication = TurnModifier.LEFT)
    val straight = previewOn(false)
    val straightOrRight = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), true, TurnModifier.SLIGHT_RIGHT)
    PreviewOnMap(look) {
        Surface(shape = RoundedCornerShape(CARD_CORNER), color = LookTheme.tokens.card) {
            Column(Modifier.padding(16.dp)) {
                LanesRow(LaneInfo(listOf(left, straight)))
                Spacer(Modifier.height(12.dp))
                LanesRow(LaneInfo(listOf(straight, straight, straightOrRight, previewExit)))
                Spacer(Modifier.height(12.dp))
                LanesRow(LaneInfo(listOf(previewCarPool, straight, straight, straight, straight, previewExit)))
                Spacer(Modifier.height(12.dp))
                LanesRow(LaneInfo(listOf(previewOn(true), previewOn(true), previewLeaving, previewLeaving)), label = "0.4 mi")
            }
        }
    }
}

/** A lane for car pools: drawn dim, never lit while another lane can be used. */
private val previewCarPool = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true)

/** A general lane that goes on, lit or not. */
private fun previewOn(lit: Boolean) = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = lit, activeIndication = if (lit) TurnModifier.STRAIGHT else null)

/** A lane that leaves for an earlier exit. */
private val previewLeaving = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false)

/** A lane that goes on to the exit, lit. */
private val previewExit = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
