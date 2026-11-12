package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.ui.theme.LookPalette
import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingCardTest {

    @Test
    fun `a card with the navigator's instruction is fully opaque`() {
        for (look in Look.entries) {
            assertEquals(1f, cardContentAlpha(pending = false, dimmed = LookPalette.of(look).pendingCardAlpha), look.name)
        }
    }

    @Test
    fun `a card waiting for a new route's first instruction is dimmed but still readable`() {
        for (look in Look.entries) {
            val alpha = cardContentAlpha(pending = true, dimmed = LookPalette.of(look).pendingCardAlpha)
            assertTrue(alpha in 0.3f..0.6f, "${look.name}: alpha $alpha")
        }
    }

    @Test
    fun `the white day card is dimmed less than the night card`() {
        // By day the road name then reads about 4.7:1 on the white card; at the night's share it would be 2.9:1.
        assertEquals(0.60f, cardContentAlpha(pending = true, dimmed = LookPalette.DAY.pendingCardAlpha))
        assertEquals(0.45f, cardContentAlpha(pending = true, dimmed = LookPalette.NIGHT.pendingCardAlpha))
    }

    @Test
    fun `the card dims by the share of the look on screen`() {
        val card = Sources.functionBody(Sources.code("ui/guidance/GuidancePanels.kt"), "fun ManeuverCard(")
        assertTrue("cardContentAlpha(progress.pending, tokens.pendingCardAlpha)" in card, card)
    }
}
