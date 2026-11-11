package io.github.purplepalm.custommaps.ui.guidance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingCardTest {

    @Test
    fun `a card with the navigator's instruction is fully opaque`() {
        assertEquals(1f, cardContentAlpha(pending = false))
    }

    @Test
    fun `a card waiting for a new route's first instruction is dimmed but still readable`() {
        val alpha = cardContentAlpha(pending = true)
        assertTrue(alpha in 0.3f..0.6f, "alpha $alpha")
    }
}
