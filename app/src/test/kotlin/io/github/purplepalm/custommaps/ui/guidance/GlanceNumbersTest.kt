package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.ui.unit.sp
import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How small and how heavy the numbers read at a glance are, at the phone's font scale. */
class GlanceNumbersTest {

    private val code = Sources.code("ui/guidance/GuidancePanels.kt")

    @Test
    fun `the time and distance left and a lane choice's distance never shrink below 12 sp`() {
        assertTrue(REMAINING_MIN_SIZE >= 12.sp, "$REMAINING_MIN_SIZE")
        assertTrue(LANE_DISTANCE_MIN_SIZE >= 12.sp, "$LANE_DISTANCE_MIN_SIZE")
        // The arrival time keeps its 16 sp, for which the guidance bar keeps room.
        assertTrue(ARRIVAL_MIN_SIZE >= 16.sp, "$ARRIVAL_MIN_SIZE")
    }

    @Test
    fun `only the words of a run-up go down to 10 sp`() {
        // Words, not numbers: at 12 sp the longest would be left out with the largest font.
        assertEquals(10.sp, LANE_WORDS_MIN_SIZE)
        // Named where it is set and once more, in the run-up's row of the card.
        assertEquals(2, Regex("\\bLANE_WORDS_MIN_SIZE\\b").findAll(code).count())
        val runUp = code.substringAfter("CardLanes.Source.RUN_UP ->").substringBefore("CardLanes.Source.CHOICE ->")
        assertTrue("labelMinSize = LANE_WORDS_MIN_SIZE" in runUp, runUp)
        // Every other row, a lane choice's distance among them, keeps the floor of a number.
        val lanesRow = code.substringAfter("fun LanesRow(").substringBefore("\n) {")
        assertTrue("labelMinSize: TextUnit = LANE_DISTANCE_MIN_SIZE" in lanesRow, lanesRow)
    }

    @Test
    fun `the numbers read at a glance are a step heavier than Material's`() {
        val bar = Sources.functionBody(code, "fun GuidanceBar(")
        assertTrue("headlineMedium.copy(fontWeight = FontWeight.SemiBold" in bar, "the arrival time")
        assertTrue("bodyMedium.copy(fontWeight = FontWeight.Medium)" in bar, "the time and distance left")
        // In the card, the unit after the distance and the road's name.
        val card = Sources.functionBody(code, "fun ManeuverCard(")
        assertEquals(2, Regex("fontWeight = FontWeight\\.Medium").findAll(card).count())
        assertTrue("fontWeight = FontWeight.SemiBold" in card, "the distance")
    }
}
