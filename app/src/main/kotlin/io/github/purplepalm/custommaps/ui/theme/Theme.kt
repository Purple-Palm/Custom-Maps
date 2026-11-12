package io.github.purplepalm.custommaps.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.purplepalm.custommaps.nav.look.Look

private val Day = LookPalette.DAY
private val Night = LookPalette.NIGHT

/**
 * The day look's Material scheme, with every one of its 48 roles set: a role left out would take
 * Material's baseline lavender or purple wherever a component reads it. Roles that are a token
 * take the token; the others hold values of their own.
 */
internal val DayScheme: ColorScheme = lightColorScheme(
    primary = Color(Day.accentInk),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(Day.accentContainer),
    onPrimaryContainer = Color(Day.onAccentContainer),
    inversePrimary = Color(Day.inverseAccent),
    secondary = Color(Day.onCardMuted),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(Day.accentContainer),
    onSecondaryContainer = Color(Day.onAccentContainer),
    tertiary = Color(Day.success),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5F0DF),
    onTertiaryContainer = Color(0xFF0A3A21),
    background = Color(Day.screen),
    onBackground = Color(Day.onCard),
    surface = Color(Day.screen),
    onSurface = Color(Day.onCard),
    surfaceVariant = Color(Day.cardRaised),
    onSurfaceVariant = Color(Day.onCardMuted),
    // The page's own colour, so a tinted surface never takes an amber cast.
    surfaceTint = Color(Day.screen),
    inverseSurface = Color(Day.inverseCard),
    inverseOnSurface = Color(Day.onInverseCard),
    error = Color(Day.danger),
    onError = Color(Day.onDanger),
    errorContainer = Color(0xFFFDE4E1),
    onErrorContainer = Color(0xFF5A0F0A),
    outline = Color(Day.outline),
    outlineVariant = Color(Day.divider),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainer = Color(Day.card),
    surfaceContainerHigh = Color(Day.cardRaised),
    surfaceContainerHighest = Color(0xFFE4E6E9),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E6E9),
    primaryFixed = Color(0xFFFCE8C8),
    primaryFixedDim = Color(0xFFF5C47A),
    onPrimaryFixed = Color(0xFF2B1C00),
    onPrimaryFixedVariant = Color(0xFF6B4600),
    secondaryFixed = Color(0xFFE4E6E9),
    secondaryFixedDim = Color(0xFFC9CDD2),
    onSecondaryFixed = Color(0xFF15181C),
    onSecondaryFixedVariant = Color(0xFF4A5059),
    tertiaryFixed = Color(0xFFD5F0DF),
    tertiaryFixedDim = Color(0xFF9ED9B6),
    onTertiaryFixed = Color(0xFF0A3A21),
    onTertiaryFixedVariant = Color(0xFF15703F),
)

/** The night look's Material scheme, with every one of its 48 roles set, as [DayScheme]. */
internal val NightScheme: ColorScheme = darkColorScheme(
    primary = Color(Night.accentInk),
    onPrimary = Color(Night.onAccent),
    primaryContainer = Color(Night.accentContainer),
    onPrimaryContainer = Color(Night.onAccentContainer),
    inversePrimary = Color(Night.inverseAccent),
    secondary = Color(Night.onCardMuted),
    onSecondary = Color(0xFF0E1013),
    secondaryContainer = Color(Night.accentContainer),
    onSecondaryContainer = Color(Night.onAccentContainer),
    tertiary = Color(Night.success),
    onTertiary = Color(0xFF0E1013),
    tertiaryContainer = Color(0xFF17382A),
    onTertiaryContainer = Color(0xFFC2F0D4),
    background = Color(Night.screen),
    onBackground = Color(Night.onCard),
    surface = Color(Night.screen),
    onSurface = Color(Night.onCard),
    surfaceVariant = Color(Night.cardRaised),
    onSurfaceVariant = Color(Night.onCardMuted),
    surfaceTint = Color(Night.screen),
    inverseSurface = Color(Night.inverseCard),
    inverseOnSurface = Color(Night.onInverseCard),
    error = Color(Night.danger),
    onError = Color(Night.onDanger),
    errorContainer = Color(0xFF5C1C17),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(Night.outline),
    outlineVariant = Color(Night.divider),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3038),
    surfaceContainer = Color(Night.card),
    surfaceContainerHigh = Color(Night.cardRaised),
    surfaceContainerHighest = Color(0xFF2A2E34),
    surfaceContainerLow = Color(0xFF131619),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceDim = Color(0xFF0B0D10),
    primaryFixed = Color(0xFFFCE8C8),
    primaryFixedDim = Color(0xFFF5C47A),
    onPrimaryFixed = Color(0xFF2B1C00),
    onPrimaryFixedVariant = Color(0xFF6B4600),
    secondaryFixed = Color(0xFFE4E6E9),
    secondaryFixedDim = Color(0xFFC9CDD2),
    onSecondaryFixed = Color(0xFF15181C),
    onSecondaryFixedVariant = Color(0xFF4A5059),
    tertiaryFixed = Color(0xFFD5F0DF),
    tertiaryFixedDim = Color(0xFF9ED9B6),
    onTertiaryFixed = Color(0xFF0A3A21),
    onTertiaryFixedVariant = Color(0xFF15703F),
)

/** A look's colours, made once: its Material scheme and its tokens. */
@Immutable
private class LookColors(val scheme: ColorScheme, val tokens: LookTokens) {
    companion object {
        private val DAY = LookColors(DayScheme, LookTokens(LookPalette.DAY))
        private val NIGHT = LookColors(NightScheme, LookTokens(LookPalette.NIGHT))

        fun of(look: Look): LookColors = when (look) {
            Look.DAY -> DAY
            Look.NIGHT -> NIGHT
        }
    }
}

/**
 * The app's colours for [look], by day or at night. The look only picks one of two prebuilt sets;
 * [content] is composed once, from this one place, whatever the look. Everything under it stays
 * where it is at a change of look, the map's view included, and is composed again with the other
 * colours: two places that composed it, one a look, would make a change of look dispose the map
 * and load its style again.
 */
@Composable
fun CustomMapsTheme(look: Look, content: @Composable () -> Unit) {
    val colors = LookColors.of(look)
    MaterialTheme(colorScheme = colors.scheme) {
        CompositionLocalProvider(LocalLookTokens provides colors.tokens, content)
    }
}

/**
 * The bright amber button, [LookTokens.accent] with [LookTokens.onAccent] text in both looks, for
 * what the screen is for: Start, Directions, Done, the prompt's action, Save. Material's filled
 * button takes the scheme's primary, which by day is the dark amber of text and thin marks.
 */
@Composable
fun AccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LookTheme.tokens
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.onAccent),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A switch whose off track is the page's colour, so the off thumb, in the outline colour, stands
 * out from it (3.58:1 by day, 4.04:1 at night; on Material's own track it would be 2.89:1 at night).
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(uncheckedTrackColor = MaterialTheme.colorScheme.background),
    )
}
