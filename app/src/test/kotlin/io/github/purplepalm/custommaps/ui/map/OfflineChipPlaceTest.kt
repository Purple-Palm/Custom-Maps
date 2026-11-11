package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where the offline chip may show as the whole sentence. Sizes are pixels from the top of the screen. */
class OfflineChipPlaceTest {

    @Test
    fun `the sentence shows under the search bar, before a drive and at the arrival`() {
        val shown = GuidanceState.entries.filter { showsOfflineSentence(it, suggestionsShown = false) }

        assertEquals(listOf(GuidanceState.IDLE, GuidanceState.ARRIVED), shown)
    }

    @Test
    fun `never over open suggestions`() {
        assertEquals(emptyList<GuidanceState>(), GuidanceState.entries.filter { showsOfflineSentence(it, suggestionsShown = true) })
    }

    @Test
    fun `the sentence fits when it ends above the strip of the logo`() {
        // Under a search bar ending at 300, on a screen whose logo strip begins at 2_000.
        assertTrue(offlineChipFits(top = 324, height = 192, maxBottom = 2_000))
        // Ending right where the strip begins is still clear of it.
        assertTrue(offlineChipFits(top = 324, height = 1_676, maxBottom = 2_000))
    }

    @Test
    fun `one pixel into the strip is too much`() {
        assertFalse(offlineChipFits(top = 324, height = 1_677, maxBottom = 2_000))
    }

    @Test
    fun `a short window above the keyboard leaves the sentence out`() {
        // The keyboard lifts the strip to 520; a large font makes the sentence 306 tall.
        assertFalse(offlineChipFits(top = 324, height = 306, maxBottom = 520))
    }

    @Test
    fun `before the screen is measured nothing fits`() {
        // Height 0, less the inset under the logo and the strip itself.
        assertFalse(offlineChipFits(top = 24, height = 192, maxBottom = 0 - 48 - 99))
    }
}
