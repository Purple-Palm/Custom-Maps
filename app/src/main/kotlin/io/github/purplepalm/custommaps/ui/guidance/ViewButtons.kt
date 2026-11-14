package io.github.purplepalm.custommaps.ui.guidance

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.DriveViewControl
import io.github.purplepalm.custommaps.nav.model.RecenterControl
import io.github.purplepalm.custommaps.ui.theme.AccentButton
import io.github.purplepalm.custommaps.ui.theme.LookTheme

/**
 * The Overview switch of the guidance bar (W21). It always says Overview, so its width never
 * changes: outlined while the drive view is Normal, filled while it is Overview ([toggleLook]), also
 * while the view is down for a turn and right after a tap that waits for one. It reads [control]
 * itself, so a change of drive view composes it alone. Material's own button, 40 dp high with a
 * 48 dp touch target; TalkBack reads it as a switch, with its state.
 */
@Composable
fun OverviewToggle(control: State<DriveViewControl>, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LookTheme.tokens
    val look = toggleLook(control.value)
    val said = stringResource(look.said)
    val described = modifier.semantics {
        role = Role.Switch
        contentDescription = said
    }
    val label: @Composable RowScope.() -> Unit = { Text(stringResource(R.string.guidance_overview)) }
    when (look) {
        ToggleLook.OUTLINED -> OutlinedButton(
            onClick = onToggle,
            modifier = described,
            // Material's own border, outlineVariant, is 1.28:1 on the bar at night, and its label muted.
            border = BorderStroke(1.dp, tokens.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.onCard),
            contentPadding = COMPACT_BUTTON_PADDING,
            content = label,
        )
        ToggleLook.FILLED -> FilledTonalButton(
            onClick = onToggle,
            modifier = described,
            // The tonal fill alone is hardly darker than the bar (1.20:1 by day, 1.32:1 at night) and
            // the word stays the same: the amber border says on (5.93:1 and 8.55:1).
            border = BorderStroke(2.dp, tokens.accentInk),
            contentPadding = COMPACT_BUTTON_PADDING,
            content = label,
        )
    }
}

/**
 * Recenter, while the driver holds the map: "Recenter 7" counting down to the map's return, or
 * "Recenter" while Recenter by itself is off ([recenterLabel]). The bright amber button in both
 * looks, 48 dp high and as wide as the place it is given; its label shrinks from [RECENTER_MAX_SIZE]
 * to [RECENTER_MIN_SIZE] to fit and never wraps. It reads [control] itself, so the count's tick
 * composes it alone, and it keeps the label it had while it fades out after the hold. TalkBack reads
 * the count when it comes to the button; no tick is announced.
 */
@Composable
fun RecenterButton(control: State<RecenterControl?>, onRecenter: () -> Unit, modifier: Modifier = Modifier) {
    val last = remember { LastLabel() }
    val label = recenterLabel(control.value) ?: last.label
    SideEffect { last.label = label }
    val text = when (label) {
        is RecenterLabel.Count -> stringResource(R.string.guidance_recenter_count, label.seconds)
        RecenterLabel.Plain -> stringResource(R.string.guidance_recenter)
    }
    val said = when (label) {
        is RecenterLabel.Count -> pluralStringResource(R.plurals.guidance_recenter_said, label.seconds, label.seconds)
        RecenterLabel.Plain -> text
    }
    AccentButton(
        onClick = onRecenter,
        modifier = modifier
            .height(RECENTER_HEIGHT)
            .semantics { contentDescription = said },
        contentPadding = RECENTER_PADDING,
    ) {
        Text(
            text = text,
            style = recenterStyle(),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = RECENTER_MIN_SIZE, maxFontSize = RECENTER_MAX_SIZE),
        )
    }
}

/** The label Recenter showed last: written after each composition and read only while there is no hold. */
private class LastLabel(var label: RecenterLabel = RecenterLabel.Plain)

/** How the Overview switch looks; its word is always Overview. [said] is what TalkBack reads. */
internal enum class ToggleLook(@StringRes val said: Int) {
    /** The drive view is Normal: a 1 dp border in the outline colour and the label in the card's ink. */
    OUTLINED(R.string.guidance_overview_off),

    /** The drive view is Overview: Material's filled tonal button with a 2 dp border in the amber ink. */
    FILLED(R.string.guidance_overview_on),
}

/**
 * How the Overview switch looks for [control]: filled while the drive view is Overview, with Zoom in
 * before turns on or off, and outlined while it is Normal. Whether the driver holds the map, and
 * where the camera is meanwhile, change nothing.
 */
internal fun toggleLook(control: DriveViewControl): ToggleLook = when (control.driveView) {
    DriveView.NORMAL -> ToggleLook.OUTLINED
    DriveView.OVERVIEW -> ToggleLook.FILLED
}

/** What Recenter says ([recenterLabel]). */
internal sealed interface RecenterLabel {
    /** "Recenter 7": the whole [seconds] until the map goes back by itself. */
    data class Count(val seconds: Int) : RecenterLabel

    /** "Recenter" alone: Recenter by itself is off, and the map goes back on a tap only. */
    data object Plain : RecenterLabel
}

/**
 * What Recenter says while the driver holds the map ([control]), and nothing while no one does: the
 * whole seconds left while Recenter by itself counts down, never fewer than none, and the word alone
 * while it is off. The button shows "Recenter 7"; TalkBack reads "Recenter. The map comes back in 7
 * seconds." when it comes to the button, and nothing at a tick, for the button is no live region.
 */
internal fun recenterLabel(control: RecenterControl?): RecenterLabel? =
    control?.let { held -> held.secondsLeft?.let { RecenterLabel.Count(it.coerceAtLeast(0)) } ?: RecenterLabel.Plain }

/**
 * The least width Recenter needs, in pixels: its label at the smallest size with the widest count
 * ([labelWidth]), the button's [padding] on each side, and never less than Material's least button
 * width ([minWidth]). The guidance bar keeps this much room in the numbers' place.
 */
internal fun recenterMinWidth(labelWidth: Int, padding: Int, minWidth: Int): Int = maxOf(labelWidth + 2 * padding, minWidth)

/** Recenter's label: Material's button label, with digits all as wide, so a tick never moves the word. */
@Composable
@ReadOnlyComposable
internal fun recenterStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_DIGITS)

/** A state that never changes: for a bar without the map's views, and for the previews. */
internal fun <T> fixedState(value: T): State<T> = FixedState(value)

private class FixedState<T>(override val value: T) : State<T>

/** How high Recenter is: a whole touch target, as tall as the buttons beside it with theirs. */
internal val RECENTER_HEIGHT = 48.dp

/** Recenter's label at its largest, Material's button label. */
internal val RECENTER_MAX_SIZE = 14.sp

/** The smallest Recenter's label shrinks to, at the phone's font scale, so that it never wraps. */
internal val RECENTER_MIN_SIZE = 11.sp

/** Recenter's padding on each side: it fills the numbers' place, so this counts only where that is narrow. */
internal val RECENTER_SIDE_PADDING = 4.dp

/** No padding above and below: the whole 48 dp holds the label, also with the largest font. */
private val RECENTER_PADDING = PaddingValues(horizontal = RECENTER_SIDE_PADDING, vertical = 0.dp)

/**
 * The longest wait Recenter by itself counts down from, 20 s. Its two digits are all as wide, so the
 * bar keeps room for every count Recenter shows.
 */
internal const val WIDE_RECENTER_COUNT = 20

/** How long the numbers and Recenter take to swap: a crossfade, at once with the system's animations off. */
internal const val SWAP_MILLIS = 150
