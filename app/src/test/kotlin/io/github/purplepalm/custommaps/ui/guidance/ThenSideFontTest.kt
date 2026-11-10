package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThenSideFontTest {

    private fun floorAt(fontScale: Float) = Density(density = 2.6f, fontScale = fontScale).thenSideMinFont()

    @Test
    fun `at the usual font size and below the floor is 12 sp`() {
        assertEquals(12.sp, floorAt(1f))
        assertEquals(12.sp, floorAt(0.85f))
    }

    @Test
    fun `a large font setting cannot raise the floor above 12 dp on the screen`() {
        for (fontScale in listOf(1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
            val density = Density(density = 2.6f, fontScale = fontScale)
            val onScreen = with(density) { density.thenSideMinFont().toDp() }
            assertEquals(12f, onScreen.value, 0.01f, "font scale $fontScale")
        }
    }
}
