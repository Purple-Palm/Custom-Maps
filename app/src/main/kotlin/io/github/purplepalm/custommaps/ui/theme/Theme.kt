package io.github.purplepalm.custommaps.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Premium dark palette: graphite surfaces, platinum text and a single amber accent. */
object Palette {
    val Graphite = Color(0xFF0E1013)
    val GraphiteRaised = Color(0xFF171A1E)
    val GraphiteHigh = Color(0xFF20242A)
    val Outline = Color(0xFF3A3F46)
    val OutlineSoft = Color(0xFF2A2E34)
    val Platinum = Color(0xFFE5E4E2)
    val PlatinumMuted = Color(0xFFA9ADB3)
    val Amber = Color(0xFFF5A524)
    val OnAmber = Color(0xFF1C1300)
    val Alert = Color(0xFFFF8A80)
}

private val DarkColors = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Palette.OnAmber,
    secondary = Palette.PlatinumMuted,
    onSecondary = Palette.Graphite,
    background = Palette.Graphite,
    onBackground = Palette.Platinum,
    surface = Palette.Graphite,
    onSurface = Palette.Platinum,
    surfaceVariant = Palette.GraphiteHigh,
    onSurfaceVariant = Palette.PlatinumMuted,
    surfaceContainer = Palette.GraphiteRaised,
    surfaceContainerHigh = Palette.GraphiteHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.OutlineSoft,
    error = Palette.Alert,
    onError = Palette.Graphite,
)

/** The app is dark only, whatever the system setting. */
@Composable
fun CustomMapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
