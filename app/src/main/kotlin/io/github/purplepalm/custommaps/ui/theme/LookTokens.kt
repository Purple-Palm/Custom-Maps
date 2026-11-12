package io.github.purplepalm.custommaps.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * The colours of the look on screen, for composables: [LookPalette]'s values as Compose types,
 * read from [LookTheme.tokens]. Each token is described in [LookPalette]. Two of these exist, one a
 * look, made once; a change of look gives the other, and composables reading a token are composed
 * again once, with nothing computed and nothing animated.
 */
@Immutable
class LookTokens(private val palette: LookPalette) {
    val look: Look get() = palette.look
    val screen: Color get() = Color(palette.screen)
    val card: Color get() = Color(palette.card)
    val cardRaised: Color get() = Color(palette.cardRaised)
    val mapPlate: Color get() = Color(palette.mapPlate)
    val plateEdge: Color get() = Color(palette.plateEdge)
    val plateEdgeWidth: Dp get() = palette.plateEdgeDp.dp
    val overMapElevation: Dp get() = palette.overMapElevationDp.dp
    val onCard: Color get() = Color(palette.onCard)
    val onCardMuted: Color get() = Color(palette.onCardMuted)
    val outline: Color get() = Color(palette.outline)
    val divider: Color get() = Color(palette.divider)
    val accent: Color get() = Color(palette.accent)
    val onAccent: Color get() = Color(palette.onAccent)
    val accentInk: Color get() = Color(palette.accentInk)
    val glanceAccent: Color get() = Color(palette.glanceAccent)
    val accentContainer: Color get() = Color(palette.accentContainer)
    val onAccentContainer: Color get() = Color(palette.onAccentContainer)
    val laneOn: Color get() = Color(palette.laneOn)
    val laneOff: Color get() = Color(palette.laneOff)
    val danger: Color get() = Color(palette.danger)
    val onDanger: Color get() = Color(palette.onDanger)
    val dangerStrong: Color get() = Color(palette.dangerStrong)
    val warning: Color get() = Color(palette.warning)
    val success: Color get() = Color(palette.success)
    val successStrong: Color get() = Color(palette.successStrong)
    val inverseCard: Color get() = Color(palette.inverseCard)
    val onInverseCard: Color get() = Color(palette.onInverseCard)
    val inverseAccent: Color get() = Color(palette.inverseAccent)
    val sign: Color get() = Color(palette.sign)
    val onSign: Color get() = Color(palette.onSign)
    val mapInk: Color get() = Color(palette.mapInk)
    val sliderRest: Color get() = Color(palette.sliderRest)
    val sliderDot: Color get() = Color(palette.sliderDot)
    val glowRestRim: Color get() = Color(palette.glowRestRim)
    val glowPeakRim: Color get() = Color(palette.glowPeakRim)
    val glowBand: Color get() = Color(palette.glowBand)
    val glowBandShare: Float get() = palette.glowBandShare
    val previewMapStandIn: Color get() = Color(palette.previewMapStandIn)
    val pendingCardAlpha: Float get() = palette.pendingCardAlpha
    val darkBarIcons: Boolean get() = palette.darkBarIcons
}

/**
 * The tokens [CustomMapsTheme] provides. Static: a change of look composes again whatever reads a
 * token, with no tracking of each read, and it happens twice a day.
 */
val LocalLookTokens = staticCompositionLocalOf<LookTokens> { error("Only CustomMapsTheme provides the look's tokens") }

/** Where composables read the look's colours. */
object LookTheme {
    /** The tokens of the look on screen. */
    val tokens: LookTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalLookTokens.current
}
