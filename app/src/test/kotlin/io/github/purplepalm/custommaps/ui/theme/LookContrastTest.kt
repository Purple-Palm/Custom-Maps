package io.github.purplepalm.custommaps.ui.theme

import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Every pair of the looks' colours that carries something reaches its target, by day and at night:
 * 7:1 for the numbers read at a glance, 4.5:1 for text, 3:1 for graphics and the borders that
 * identify a control (WCAG 2.2). Decorative pairs (dividers, the plate's hairline by day, the
 * glow's rest and band) have none.
 */
class LookContrastTest {

    private class Pair(val what: String, val fg: (LookPalette) -> Long, val bg: (LookPalette) -> Long, val target: Double)

    /** Opaque pairs, the same in both looks. */
    private val pairs = listOf(
        Pair("text on a page", { it.onCard }, { it.screen }, TEXT),
        Pair("numbers on a card", { it.onCard }, { it.card }, GLANCE),
        Pair("route minutes on a raised row", { it.onCard }, { it.cardRaised }, GLANCE),
        Pair("notes on a page", { it.onCardMuted }, { it.screen }, TEXT),
        // The guidance bar's time and miles left are read at a glance.
        Pair("time and miles left on a card", { it.onCardMuted }, { it.card }, GLANCE),
        Pair("secondary lines on a raised row and in dialogs", { it.onCardMuted }, { it.cardRaised }, TEXT),
        Pair("a control's border on a card", { it.outline }, { it.card }, GRAPHIC),
        Pair("a control's border on a page", { it.outline }, { it.screen }, GRAPHIC),
        Pair("a field's border in a dialog", { it.outline }, { it.cardRaised }, GRAPHIC),
        Pair("amber text on a card", { it.accentInk }, { it.card }, TEXT),
        Pair("section titles on a page", { it.accentInk }, { it.screen }, TEXT),
        Pair("dialog buttons and the selected row's border on a raised row", { it.accentInk }, { it.cardRaised }, TEXT),
        Pair("text on an amber button", { it.onAccent }, { it.accent }, TEXT),
        Pair("the arrival time on the guidance bar", { it.glanceAccent }, { it.card }, GLANCE),
        Pair("the selected route's minutes on its row", { it.glanceAccent }, { it.cardRaised }, GLANCE),
        Pair("a selected segment's label", { it.onAccentContainer }, { it.accentContainer }, TEXT),
        Pair("a lane to take on the card", { it.laneOn }, { it.card }, GRAPHIC),
        Pair("a lane not to take on the card", { it.laneOff }, { it.card }, GRAPHIC),
        Pair("danger on a card", { it.danger }, { it.card }, TEXT),
        Pair("an error line on a page", { it.danger }, { it.screen }, TEXT),
        Pair("an error line in a dialog", { it.danger }, { it.cardRaised }, TEXT),
        Pair("End's label", { it.onDanger }, { it.danger }, TEXT),
        Pair("the speed above the limit on a card", { it.dangerStrong }, { it.card }, GLANCE),
        Pair("warning words on a card", { it.warning }, { it.card }, TEXT),
        Pair("sooner words on a card", { it.success }, { it.card }, TEXT),
        Pair("sooner minutes on a card", { it.successStrong }, { it.card }, GLANCE),
        Pair("sooner minutes on a raised row", { it.successStrong }, { it.cardRaised }, GLANCE),
        Pair("the snackbar's text", { it.onInverseCard }, { it.inverseCard }, TEXT),
        Pair("the snackbar's action", { it.inverseAccent }, { it.inverseCard }, TEXT),
        Pair("the speed limit's number", { it.onSign }, { it.sign }, GLANCE),
        Pair("the Then plate's rim at the glow's peak", { it.glowPeakRim }, { it.card }, GRAPHIC),
        // The replay slider draws its own edges over the map, the same in both looks.
        Pair("the slider's track ahead against its casing", { it.sliderRest }, { it.mapInk }, GRAPHIC),
        Pair("the slider's played part against its casing", { it.accent }, { it.mapInk }, GRAPHIC),
        Pair("the slider's dot against its ring", { it.sliderDot }, { it.mapInk }, GRAPHIC),
    )

    /** What is drawn on a map plate, and the targets it has there. */
    private val onPlate = listOf(
        Pair("numbers on a plate", { it.onCard }, { it.mapPlate }, GLANCE),
        Pair("secondary lines on a plate", { it.onCardMuted }, { it.mapPlate }, TEXT),
        Pair("the speed above the limit on a plate", { it.dangerStrong }, { it.mapPlate }, GLANCE),
        Pair("sooner minutes on a plate", { it.successStrong }, { it.mapPlate }, GLANCE),
        Pair("sooner words on a plate", { it.success }, { it.mapPlate }, TEXT),
        Pair("warning words on a plate", { it.warning }, { it.mapPlate }, TEXT),
        Pair("a lit lane on the lane sign", { it.accentInk }, { it.mapPlate }, GRAPHIC),
        Pair("an unlit lane on the lane sign", { it.laneOff }, { it.mapPlate }, GRAPHIC),
    )

    @ParameterizedTest
    @EnumSource(Look::class)
    fun `every pair reaches its target`(look: Look) {
        val palette = LookPalette.of(look)
        assertAll(pairs.map { pair -> Executable { assertReaches(pair, palette, pair.bg(palette)) } })
    }

    @Test
    fun `the day plate is opaque white, so every pair on it is the card's`() {
        val day = LookPalette.DAY
        assertEquals(0xFF, (day.mapPlate shr 24).toInt() and 0xFF)
        assertEquals(day.card, day.mapPlate)
        assertAll(onPlate.map { pair -> Executable { assertReaches(pair, day, day.mapPlate) } })
    }

    @Test
    fun `the night plate reads over the darkest and the lightest map under it`() {
        val night = LookPalette.NIGHT
        for (map in listOf(BLACK, WHITE)) {
            val plate = Contrast.over(night.mapPlate, map)
            assertAll(onPlate.map { pair -> Executable { assertReaches(pair, night, plate) } })
        }
    }

    @Test
    fun `by day the slider's ink outlines its light marks on every part of the map`() {
        // Standard's day colours as measured from Mapbox's preview images: approximate.
        val dayMap = listOf(0xFFF3F0EF, 0xFFE0D9D3, 0xFFFFFFFF, 0xFFA0A3C0, 0xFFB4BDCA, 0xFF98DCFE, 0xFFCFF3BD)
        val ink = LookPalette.DAY.mapInk
        assertAll(dayMap.map { ground -> Executable { assertTrue(Contrast.ratio(ink, ground) >= GRAPHIC, "ink on %08X".format(ground)) } })
    }

    @Test
    fun `cards cast a shadow over the light map, and none over the night map`() {
        assertEquals(3f, LookPalette.DAY.overMapElevationDp)
        assertEquals(0f, LookPalette.NIGHT.overMapElevationDp)
    }

    @Test
    fun `a pending card stays readable, more so by day where the card is white`() {
        // The road name of a card that waits for a new route's first instruction.
        val day = LookPalette.DAY
        val night = LookPalette.NIGHT
        assertTrue(Contrast.ratio(Contrast.over(day.onCard, day.card, day.pendingCardAlpha.toDouble()), day.card) >= TEXT)
        assertTrue(Contrast.ratio(Contrast.over(night.onCard, night.card, night.pendingCardAlpha.toDouble()), night.card) >= GRAPHIC)
    }

    @Test
    fun `the list's black page carries the night look's colours at their targets, whatever the look`() {
        // The same page in both looks; the list on it always takes the night look's colours.
        assertEquals(BLACK, LookPalette.DAY.listPage)
        assertEquals(BLACK, LookPalette.NIGHT.listPage)
        val onList = listOf(
            Pair("a row's distance and words on the list's page", { it.onCard }, { it.listPage }, GLANCE),
            Pair("a row's unit and the attribution line on the list's page", { it.onCardMuted }, { it.listPage }, TEXT),
            Pair("a row's arrow on the list's page", { it.onCard }, { it.listPage }, GRAPHIC),
        )
        val night = LookPalette.NIGHT
        assertAll(onList.map { pair -> Executable { assertReaches(pair, night, pair.bg(night)) } })
    }

    private fun assertReaches(pair: Pair, palette: LookPalette, background: Long) {
        val ratio = Contrast.ratio(pair.fg(palette), background)
        assertTrue(ratio >= pair.target, "${palette.look}, ${pair.what}: %.2f, needs %.1f".format(ratio, pair.target))
    }

    private companion object {
        const val GLANCE = 7.0
        const val TEXT = 4.5
        const val GRAPHIC = 3.0
        const val BLACK = 0xFF000000
        const val WHITE = 0xFFFFFFFF
    }
}
