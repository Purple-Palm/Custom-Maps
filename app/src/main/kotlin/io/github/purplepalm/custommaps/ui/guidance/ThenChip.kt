package io.github.purplepalm.custommaps.ui.guidance

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The arrow of the maneuver after the next one. */
private val THEN_GLYPH = 40.dp

/** Above and below the chip's content, in the part that shows under the card. */
private val THEN_CHIP_PADDING = 8.dp

/**
 * How far the chip reaches below the card. During guidance the camera keeps a band of this height
 * under the card out of its frame, whether the chip shows or not.
 */
val THEN_CHIP_BAND = THEN_GLYPH + THEN_CHIP_PADDING * 2

/** Share of the card's width the chip may take, so the compass at the top right stays clear. */
private const val THEN_CHIP_MAX_WIDTH_SHARE = 0.7f

/** Square on top, where the chip is under the card; rounded where it hangs over the map. */
private val THEN_CHIP_SHAPE = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

/** The side of the destination gets this small before it is cut, on a narrow screen with a large font. */
private val THEN_SIDE_MIN_FONT = 12.sp

/**
 * And never larger than this on the screen. A large font setting would otherwise raise the floor
 * with everything else, until the side no longer fits beside the label and the arrow.
 */
private val THEN_SIDE_MIN_SIZE = 12.dp

private const val THEN_ENTER_MS = 280
private const val THEN_EXIT_MS = 200
private const val THEN_FADE_MS = 180

/**
 * The chip's bounds grow downward from under the card, and the chip keeps its lower edge on
 * theirs: it is pulled out from under the card, not unrolled in place. Nothing of it shows above
 * the bounds' upper edge, which lies behind the card.
 */
private val THEN_ENTER = expandVertically(tween(THEN_ENTER_MS, easing = EaseOutCubic), expandFrom = Alignment.Bottom)

/** The same way back up. */
private val THEN_EXIT = shrinkVertically(tween(THEN_EXIT_MS, easing = EaseInCubic), shrinkTowards = Alignment.Bottom)

/**
 * The maneuver card with the Then chip hanging under it. The panel is only as large as the card:
 * the chip lies over the map in [THEN_CHIP_BAND], which the camera keeps free during guidance, so
 * the chip never moves the camera and never covers what it frames.
 *
 * The chip is drawn outside the panel's bounds. Nothing above it in the layout may clip its
 * children, or the chip is cut off.
 */
@Composable
fun ManeuverPanel(progress: RouteProgressSnapshot, modifier: Modifier = Modifier) {
    Layout(
        content = {
            ManeuverCard(progress)
            ThenChipSlot(progress.thenManeuver?.maneuver)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val card = measurables[0].measure(constraints.copy(minHeight = 0))
        // Fully hidden, the slot lays out nothing at all.
        val chip = measurables.getOrNull(1)?.measure(Constraints(maxWidth = (card.width * THEN_CHIP_MAX_WIDTH_SHARE).roundToInt()))
        val tuck = CARD_CORNER.roundToPx()
        layout(card.width, card.height) {
            // Below the card in drawing order: it slides out from under the card, never over it.
            chip?.placeRelative(0, card.height - tuck, zIndex = 0f)
            card.placeRelative(0, 0, zIndex = 1f)
        }
    }
}

/**
 * Slides the chip out from under the card and back. With the system's animations switched off it
 * simply appears and disappears. A panel first shown with a chip due shows it at once.
 *
 * [then] is the same object from one progress update to the next while the maneuver is the same,
 * so those updates skip this slot.
 */
@Composable
private fun ThenChipSlot(then: Maneuver?) {
    val transition = updateTransition(targetState = then, label = "then")
    transition.AnimatedVisibility(visible = { it != null }, enter = THEN_ENTER, exit = THEN_EXIT) {
        // While it slides away the target is null: it leaves with the arrow it had.
        val shown = transition.targetState ?: transition.currentState
        if (shown != null) ThenChip(shown)
    }
}

@Composable
private fun ThenChip(maneuver: Maneuver) {
    val description = thenDescription(maneuver)
    Surface(
        // One sentence for a screen reader, in place of the label, the arrow and the road.
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        shape = THEN_CHIP_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            // The top of the chip stays under the card's rounded bottom edge.
            modifier = Modifier.padding(start = 16.dp, top = CARD_CORNER + THEN_CHIP_PADDING, end = 18.dp, bottom = THEN_CHIP_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.guidance_then),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            // A new maneuver after a step change fades in; the chip's background stays put.
            AnimatedContent(
                targetState = maneuver,
                transitionSpec = {
                    fadeIn(tween(THEN_FADE_MS, delayMillis = THEN_FADE_MS / 2)) togetherWith
                        fadeOut(tween(THEN_FADE_MS / 2)) using
                        SizeTransform(clip = false) { _, _ -> tween(THEN_FADE_MS) }
                },
                label = "then maneuver",
            ) { shown -> ThenManeuver(shown) }
        }
    }
}

@Composable
private fun ThenManeuver(maneuver: Maneuver) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Platinum, not amber: amber stays for what the driver does now.
        ManeuverGlyph(
            type = maneuver.type,
            turn = maneuver.modifier,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(THEN_GLYPH),
            roundaboutExitDegrees = maneuver.roundaboutExitDegrees,
        )
        val label = thenLabel(maneuver)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            val style = MaterialTheme.typography.titleMedium
            Text(
                text = label,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A long road name is cut. The side of the destination is the whole message, so it
                // shrinks to fit first.
                autoSize = if (maneuver.type == ManeuverType.ARRIVE) {
                    TextAutoSize.StepBased(minFontSize = LocalDensity.current.thenSideMinFont(), maxFontSize = style.fontSize)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * The smallest font for the side of the destination: [THEN_SIDE_MIN_FONT] at the usual font size
 * and below, and whatever comes out at [THEN_SIDE_MIN_SIZE] on the screen with a larger one.
 */
internal fun Density.thenSideMinFont(): TextUnit = minOf(THEN_SIDE_MIN_FONT.value, THEN_SIDE_MIN_SIZE.toSp().value).sp

/** The short text after the arrow: the road, or the side the destination is on. */
@Composable
private fun thenLabel(maneuver: Maneuver): String? =
    if (maneuver.type == ManeuverType.ARRIVE) {
        stringResource(arrivalSideRes(maneuver.modifier))
    } else {
        maneuver.primaryText.takeIf { it.isNotBlank() }
    }

/** What a screen reader says, for example "Then: right, Pine Street". */
@Composable
private fun thenDescription(maneuver: Maneuver): String {
    val parts = listOfNotNull(
        stringResource(thenDirectionRes(maneuver.type, maneuver.modifier)),
        maneuver.roundaboutExitNumber?.let { stringResource(R.string.guidance_roundabout_exit, it) },
        maneuver.primaryText.takeIf { it.isNotBlank() && maneuver.type != ManeuverType.ARRIVE },
    )
    return stringResource(R.string.guidance_then_description, parts.joinToString(", "))
}

/** The direction in words, for the screen reader. */
@StringRes
internal fun thenDirectionRes(type: ManeuverType, turn: TurnModifier?): Int = when {
    type == ManeuverType.ARRIVE -> when {
        turn.isLeft() -> R.string.then_destination_left
        turn.isRight() -> R.string.then_destination_right
        else -> R.string.then_destination
    }
    type in ROUNDABOUTS -> R.string.then_roundabout
    else -> when (turn) {
        TurnModifier.U_TURN -> R.string.then_u_turn
        TurnModifier.SHARP_RIGHT -> R.string.then_sharp_right
        TurnModifier.RIGHT -> R.string.then_right
        TurnModifier.SLIGHT_RIGHT -> R.string.then_slight_right
        TurnModifier.SLIGHT_LEFT -> R.string.then_slight_left
        TurnModifier.LEFT -> R.string.then_left
        TurnModifier.SHARP_LEFT -> R.string.then_sharp_left
        TurnModifier.STRAIGHT, null -> R.string.then_straight
    }
}

/** The side the destination is on, shown after the destination mark. */
@StringRes
internal fun arrivalSideRes(turn: TurnModifier?): Int = when {
    turn.isLeft() -> R.string.guidance_then_on_left
    turn.isRight() -> R.string.guidance_then_on_right
    else -> R.string.guidance_then_destination
}

private fun TurnModifier?.isLeft() = this == TurnModifier.SLIGHT_LEFT || this == TurnModifier.LEFT || this == TurnModifier.SHARP_LEFT

private fun TurnModifier?.isRight() = this == TurnModifier.SLIGHT_RIGHT || this == TurnModifier.RIGHT || this == TurnModifier.SHARP_RIGHT

@Preview(widthDp = 400)
@Composable
private fun ManeuverPanelPreview() {
    CustomMapsTheme {
        // A grey stand-in for the map, with room for the chip below the card.
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFF2B3036))
                .padding(12.dp),
        ) {
            ManeuverPanel(
                RouteProgressSnapshot(
                    nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Oak Street"),
                    distanceToNextManeuver = Distance.feet(350.0),
                    lanes = LaneInfo(
                        listOf(
                            Lane(setOf(TurnModifier.LEFT), isRecommended = true, activeIndication = TurnModifier.LEFT),
                            Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                            Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), isRecommended = false),
                        ),
                    ),
                    distanceRemaining = Distance.miles(2.1),
                    durationRemaining = 7.minutes,
                    following = FollowingManeuver(
                        Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street"),
                        distanceAfterNext = Distance.meters(120.0),
                        durationAfterNext = 10.seconds,
                    ),
                    showThen = true,
                ),
            )
        }
    }
}

/**
 * A narrow phone with a large font, and with the largest one: the side of the destination shrinks
 * to fit beside the label and the arrow.
 */
@Preview(name = "large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun ManeuverPanelDestinationPreview() {
    CustomMapsTheme {
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(Color(0xFF2B3036))
                .padding(12.dp),
        ) {
            ManeuverPanel(
                RouteProgressSnapshot(
                    nextManeuver = Maneuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT, "Elm Street", roundaboutExitNumber = 2, roundaboutExitDegrees = 180.0),
                    distanceToNextManeuver = Distance.feet(500.0),
                    lanes = null,
                    distanceRemaining = Distance.miles(0.2),
                    durationRemaining = 1.minutes,
                    following = FollowingManeuver(
                        Maneuver(ManeuverType.ARRIVE, TurnModifier.RIGHT, "Elm Street"),
                        distanceAfterNext = Distance.meters(90.0),
                        durationAfterNext = 12.seconds,
                    ),
                    showThen = true,
                ),
            )
        }
    }
}
