package io.github.purplepalm.custommaps.ui.theme

import io.github.purplepalm.custommaps.nav.look.Look

/**
 * The screen's colours of one look, as plain ARGB values (`0xAARRGGBB`) with no Compose type, so a
 * JVM test can check every pair against its target. [LookTokens] hands the same values to the
 * composables, and [DayScheme] and [NightScheme] put them into Material's roles.
 *
 * No composable names a colour of its own. Amber as a fill is [accent] in both looks; amber as a
 * letter, a line or a small arrow is [accentInk], since the bright amber on white is 2.04:1. A
 * number read at a glance is [onCard] by day, [glanceAccent] where it is amber at night, and
 * [successStrong] where it is green. A state never shows by a fill or a hue alone: the lanes to
 * take, for one, are a heavier arrow and a bar as well as [laneOn].
 */
class LookPalette private constructor(
    /** Which look these are. */
    val look: Look,
    /** Full pages (Settings, the token screen), and behind the map before it draws. */
    val screen: Long,
    /** The maneuver card, the Then chip, the guidance bar, sheets, chips, the search field. */
    val card: Long,
    /** Rows inside a card, such as the routes to choose from; dialogs. */
    val cardRaised: Long,
    /** Anything that floats on the map by itself: badges, the route plates, the lane sign. Opaque white by day. */
    val mapPlate: Long,
    /** The edge of a map plate: a hairline by day, where the shadow separates it; a grey edge at night. */
    val plateEdge: Long,
    /** How wide [plateEdge] is, in dp. */
    val plateEdgeDp: Float,
    /** The shadow under anything over the map, in dp: none at night, where it would not show. */
    val overMapElevationDp: Float,
    /** Text, numbers, the Then arrow. */
    val onCard: Long,
    /** Units, secondary lines, the word Then, notes. */
    val onCardMuted: Long,
    /** Borders that identify a control: a field, an off switch and its thumb, a segment, Overview. */
    val outline: Long,
    /** Hairlines between rows. */
    val divider: Long,
    /** Amber fills: Start, Directions, Done, the buttons that do what the screen is for. */
    val accent: Long,
    /** Text and icons on [accent]. */
    val onAccent: Long,
    /** Amber text, links, section titles, the card's arrow, thin rings, selected borders. */
    val accentInk: Long,
    /** Numbers that are amber at night: the arrival time, the selected route's minutes. Ink by day. */
    val glanceAccent: Long,
    /** A selected segment. */
    val accentContainer: Long,
    /** Text on [accentContainer]. */
    val onAccentContainer: Long,
    /** A lane to take, always with its shape cue. */
    val laneOn: Long,
    /** Every other lane. */
    val laneOff: Long,
    /** Jams, closures, End, errors, the offline icon. */
    val danger: Long,
    /** Text on [danger]. */
    val onDanger: Long,
    /** The speed above the limit, a number read at a glance. */
    val dangerStrong: Long,
    /** Moderate traffic on plates, as on the map, always with its sign. */
    val warning: Long,
    /** Sooner, the leaf: words and icons. */
    val success: Long,
    /** Sooner minutes read at a glance. */
    val successStrong: Long,
    /** The snackbar. */
    val inverseCard: Long,
    /** Text on [inverseCard]. */
    val onInverseCard: Long,
    /** The snackbar's action. */
    val inverseAccent: Long,
    /** The speed limit sign: white by day, dimmed at night. */
    val sign: Long,
    /** The number and border on [sign]. */
    val onSign: Long,
    /** Rings and casings of light marks drawn straight on the map, such as the replay slider's dot and track. */
    val mapInk: Long,
    /** The replay slider's track ahead of the dot, the part not yet played. */
    val sliderRest: Long,
    /** The replay slider's dot, white in both looks. */
    val sliderDot: Long,
    /** The Then plate's rim at rest. */
    val glowRestRim: Long,
    /** The Then plate's rim at the glow's peaks. */
    val glowPeakRim: Long,
    /** The Then plate's inner band, at [glowBandShare] of the rise. */
    val glowBand: Long,
    /** How opaque [glowBand] is at the top of the rise. */
    val glowBandShare: Float,
    /** The map in previews. */
    val previewMapStandIn: Long,
    /** How opaque a pending card's content is, while it waits for a new route's first instruction. */
    val pendingCardAlpha: Float,
    /** Dark icons in the system bars, over the light map and pages by day. */
    val darkBarIcons: Boolean,
) {
    companion object {
        /** The light look: white cards with a soft shadow over the bright map, graphite ink, dark amber marks. */
        val DAY = LookPalette(
            look = Look.DAY,
            screen = 0xFFF3F4F6,
            card = 0xFFFFFFFF,
            cardRaised = 0xFFEEF0F2,
            mapPlate = 0xFFFFFFFF,
            plateEdge = 0xFFC9CDD2,
            plateEdgeDp = 1f,
            overMapElevationDp = 3f,
            onCard = 0xFF15181C,
            onCardMuted = 0xFF4A5059,
            outline = 0xFF7A818A,
            divider = 0xFFE1E3E7,
            accent = 0xFFF5A524,
            onAccent = 0xFF1C1300,
            accentInk = 0xFF8A5A00,
            glanceAccent = 0xFF15181C,
            accentContainer = 0xFFFCE8C8,
            onAccentContainer = 0xFF2B1C00,
            laneOn = 0xFF8A5A00,
            laneOff = 0xFF8D939B,
            danger = 0xFFB42318,
            onDanger = 0xFFFFFFFF,
            dangerStrong = 0xFFA51C12,
            warning = 0xFF9C4A00,
            success = 0xFF15703F,
            successStrong = 0xFF0D5A33,
            inverseCard = 0xFF23272C,
            onInverseCard = 0xFFF1F1EF,
            inverseAccent = 0xFFF5A524,
            sign = 0xFFFFFFFF,
            onSign = 0xFF000000,
            mapInk = 0xFF0E1013,
            sliderRest = 0xFF9AA1AB,
            sliderDot = 0xFFFFFFFF,
            glowRestRim = 0xFFF5A524,
            glowPeakRim = 0xFF8A5A00,
            glowBand = 0xFFF5A524,
            glowBandShare = 0.30f,
            previewMapStandIn = 0xFFE9EAE6,
            // The road name of a dimmed card stays at 4.7:1 on white; at 45 % it would be 2.9:1.
            pendingCardAlpha = 0.60f,
            darkBarIcons = true,
        )

        /**
         * The dark look: graphite cards, platinum text and amber, as the app has looked so far, with
         * borders and lanes not to take that now read. Nothing is pure white but the replay slider's dot.
         */
        val NIGHT = LookPalette(
            look = Look.NIGHT,
            screen = 0xFF0E1013,
            card = 0xFF171A1E,
            cardRaised = 0xFF20242A,
            // Graphite at 94 %.
            mapPlate = 0xF00E1013,
            plateEdge = 0xFF9AA1AB,
            plateEdgeDp = 1.5f,
            overMapElevationDp = 0f,
            onCard = 0xFFE5E4E2,
            onCardMuted = 0xFFA9ADB3,
            outline = 0xFF6E747C,
            divider = 0xFF2A2E34,
            accent = 0xFFF5A524,
            onAccent = 0xFF1C1300,
            accentInk = 0xFFF5A524,
            glanceAccent = 0xFFF5A524,
            accentContainer = 0xFF3A2E17,
            onAccentContainer = 0xFFFFE3B8,
            laneOn = 0xFFF5A524,
            laneOff = 0xFF6E747C,
            danger = 0xFFFF8A80,
            onDanger = 0xFF0E1013,
            dangerStrong = 0xFFFF8A80,
            warning = 0xFFFFA45C,
            success = 0xFF4CC38A,
            successStrong = 0xFF4CC38A,
            inverseCard = 0xFF2B3038,
            onInverseCard = 0xFFE5E4E2,
            inverseAccent = 0xFFF5A524,
            sign = 0xFFE5E4E2,
            onSign = 0xFF0E1013,
            mapInk = 0xFF0E1013,
            sliderRest = 0xFF9AA1AB,
            sliderDot = 0xFFFFFFFF,
            // Amber at 40 %.
            glowRestRim = 0x66F5A524,
            glowPeakRim = 0xFFF5A524,
            glowBand = 0xFFF5A524,
            glowBandShare = 0.25f,
            previewMapStandIn = 0xFF2B3036,
            pendingCardAlpha = 0.45f,
            darkBarIcons = false,
        )

        /** The palette of [look]. */
        fun of(look: Look): LookPalette = when (look) {
            Look.DAY -> DAY
            Look.NIGHT -> NIGHT
        }
    }
}
