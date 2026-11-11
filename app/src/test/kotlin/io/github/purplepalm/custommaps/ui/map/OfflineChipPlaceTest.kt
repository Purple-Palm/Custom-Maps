package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where the offline chip may show as the whole sentence. */
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
}
