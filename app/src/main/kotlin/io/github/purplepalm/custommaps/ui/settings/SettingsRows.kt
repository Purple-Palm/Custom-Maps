package io.github.purplepalm.custommaps.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.ui.theme.AppSwitch
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme

/*
 * The rows every part of Settings is built from, one look for each kind: a switch, a choice, a line
 * that opens something, a line of information and an action, under a group's title. The whole row
 * is the target, never only its control. Every colour is a look token or a Material role, so a row
 * reads by day and at night alike: names onCard, explanations onCardMuted, group titles and actions
 * accentInk. In [compact] form (Settings during a trip) a row leaves its explanations out.
 */

/** How a line under a row's name reads. */
enum class LineTone {
    /** An explanation of the row: small and muted. */
    NOTE,

    /** An explanation that applies now, to what is chosen: small, in the names' colour. */
    NOTE_NOW,

    /** A value the row shows, as a count or the next change: in the names' colour. */
    VALUE,

    /** A value of less weight, as an entry of a list: muted. */
    VALUE_MUTED,

    /** Something the driver has to know before acting: in the danger colour. */
    WARNING,
}

/** One line under a row's name, its words already resolved. */
@Immutable
data class RowLine(val text: String, val tone: LineTone = LineTone.NOTE)

/** One option of a choice, its words already resolved: its name and what it does. */
@Immutable
data class ChoiceItem(val title: String, val note: String? = null)

/** The least height of a row with a switch: the whole row takes the tap. */
private val SWITCH_ROW_MIN_HEIGHT = 64.dp

/** The least height of any other row, and of each option of a list: a touch target at every font size. */
private val ROW_MIN_HEIGHT = 48.dp

/** The selected segment's check mark and the space before its word, as Material 3 1.4.0 lays them out. */
private val CHECK_MARK_ROOM = SegmentedButtonDefaults.IconSize + 8.dp

/** Three segments or more on a narrow phone: less padding than Material's 12 dp a side, so a large font still fits. */
private val NARROW_SEGMENT_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/** A group's title: what the rows under it are about. In [compact] form it sits closer to its rows. */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = LookTheme.tokens.accentInk,
        modifier = modifier.padding(bottom = if (compact) 4.dp else 8.dp),
    )
}

/** The line between two groups. */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    Column(modifier) {
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = LookTheme.tokens.divider)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A setting that is on or off. The whole row is the switch: a tap anywhere on it changes it, and the
 * switch only shows the state, its thumb on the page's colour when off ([AppSwitch]). [badge] is a word
 * in a thin frame beside the name, as "Mapbox beta".
 */
@Composable
fun SwitchRowItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    lines: List<RowLine> = emptyList(),
    badge: String? = null,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SWITCH_ROW_MIN_HEIGHT)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title, badge)
            if (!compact) RowLines(lines)
        }
        Spacer(Modifier.width(16.dp))
        // The row takes the tap; the switch shows the state.
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One of two to four options. A row of segments, the selected one filled with its check mark, or,
 * where the options need a line each ([folds]), a list with a radio button on each option, the whole
 * option the target. Under segments, the options' notes follow in their order.
 */
@Composable
fun ChoiceRowItem(
    title: String?,
    options: List<ChoiceItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    folds: Boolean = false,
    lines: List<RowLine> = emptyList(),
    compact: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) RowTitle(title, badge = null)
        if (folds) {
            Column(Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    val chosen = index == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ROW_MIN_HEIGHT)
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(index) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The whole option is the button.
                        RadioButton(selected = chosen, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(text = option.title, style = MaterialTheme.typography.bodyLarge, color = LookTheme.tokens.onCard)
                            if (!compact && option.note != null) {
                                Text(text = option.note, style = MaterialTheme.typography.bodySmall, color = LookTheme.tokens.onCardMuted)
                            }
                        }
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        contentPadding = if (options.size >= 3) NARROW_SEGMENT_PADDING else SegmentedButtonDefaults.ContentPadding,
                    ) {
                        SegmentLabel(option.title)
                    }
                }
            }
            if (!compact) RowLines(options.mapNotNull { option -> option.note?.let { RowLine(it) } })
        }
        if (!compact) RowLines(lines)
    }
}

/** A line that opens something, a page or a web page: its name in the actions' colour, and what it shows now. */
@Composable
fun LinkRowItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    lines: List<RowLine> = emptyList(),
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = LookTheme.tokens.accentInk)
        if (value != null) Text(text = value, style = MaterialTheme.typography.bodyMedium, color = LookTheme.tokens.onCard)
        if (!compact) RowLines(lines)
    }
}

/** A line of information: a name, when it has one, and the lines under it. Not a target. */
@Composable
fun InfoRowItem(
    title: String?,
    modifier: Modifier = Modifier,
    lines: List<RowLine> = emptyList(),
    compact: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (title != null) RowTitle(title, badge = null)
        // A warning or a value is shown in either form; only explanations wait for the parked form.
        RowLines(if (compact) lines.filter { it.tone != LineTone.NOTE } else lines)
    }
}

/**
 * Something Settings does at a tap, as sharing the debug log: its name in the actions' colour, the
 * whole row the target. Muted while it cannot be done ([enabled] false).
 */
@Composable
fun ActionRowItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lines: List<RowLine> = emptyList(),
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) LookTheme.tokens.accentInk else LookTheme.tokens.onCardMuted,
        )
        if (!compact) RowLines(lines)
    }
}

/** A row's name, and the word in a thin frame beside it when it has one. */
@Composable
private fun RowTitle(title: String, badge: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The name takes what room the badge leaves and wraps there; the badge stays beside it.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = LookTheme.tokens.onCard,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Badge(badge)
        }
    }
}

/** A word in a thin frame: a word, not a colour, says it. */
@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LookTheme.tokens.onCard,
        modifier = Modifier
            .border(1.dp, LookTheme.tokens.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The lines under a row's name, each in its tone. */
@Composable
private fun RowLines(lines: List<RowLine>) {
    for (line in lines) {
        val (style, color) = line.tone.look()
        Text(
            text = line.text,
            style = style,
            color = color,
            modifier = if (line.tone == LineTone.NOTE_NOW) Modifier.padding(top = 4.dp) else Modifier,
        )
    }
}

/** The text style and the colour of a line in [this] tone. */
@Composable
private fun LineTone.look(): Pair<TextStyle, Color> {
    val tokens = LookTheme.tokens
    val type = MaterialTheme.typography
    return when (this) {
        LineTone.NOTE -> type.bodySmall to tokens.onCardMuted
        LineTone.NOTE_NOW -> type.bodySmall to tokens.onCard
        LineTone.VALUE -> type.bodyMedium to tokens.onCard
        LineTone.VALUE_MUTED -> type.bodyMedium to tokens.onCardMuted
        LineTone.WARNING -> type.bodyMedium to tokens.danger
    }
}

/**
 * A segment's word on one line, smaller only when a large font leaves it no room, never below
 * 12 sp. Material measures the word as wide as the segment and sets the selected segment's check
 * mark beside it, so every segment keeps room for the mark and its words stay one size.
 */
@Composable
internal fun SegmentLabel(text: String) {
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

/**
 * Every kind of row, by day and at night, on a narrow phone and with a large font: a switch on and
 * one off with a badge, a row of three segments, a folded list, a link, a line of information with a
 * warning, and an action that can be done and one that cannot.
 */
@Preview(name = "rows", widthDp = 360)
@Preview(name = "rows, large font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun SettingsRowsPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface(color = LookTheme.tokens.screen) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                GroupTitle("A group")
                SwitchRowItem("A switch that is on", checked = true, onCheckedChange = {}, lines = listOf(RowLine("What it does, in one line.")))
                SwitchRowItem("A switch that is off", checked = false, onCheckedChange = {}, badge = "Mapbox beta", lines = listOf(RowLine("Whose it is.")))
                ChoiceRowItem(
                    title = "A choice of three",
                    options = listOf(ChoiceItem("Off"), ChoiceItem("A little"), ChoiceItem("More")),
                    selected = 1,
                    onSelect = {},
                    lines = listOf(RowLine("What each does."), RowLine("What applies now.", LineTone.NOTE_NOW)),
                )
                ChoiceRowItem(
                    title = "A list",
                    options = listOf(ChoiceItem("The first", "What the first does"), ChoiceItem("The second", "What the second does")),
                    selected = 0,
                    onSelect = {},
                    folds = true,
                )
                GroupDivider()
                GroupTitle("Another group")
                LinkRowItem("A link", onClick = {})
                InfoRowItem("Some information", lines = listOf(RowLine("A value", LineTone.VALUE), RowLine("A warning", LineTone.WARNING)))
                ActionRowItem("An action", onClick = {}, lines = listOf(RowLine("What it does.")))
                ActionRowItem("An action that cannot be done now", onClick = {}, enabled = false)
            }
        }
    }
}
