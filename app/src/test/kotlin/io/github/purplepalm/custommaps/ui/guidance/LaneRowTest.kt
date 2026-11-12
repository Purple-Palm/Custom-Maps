package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.ui.unit.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Where the lanes of the card's row go, in pixels: a row 300 wide and lanes 44 wide at full size. */
class LaneRowTest {

    @Test
    fun `lanes that fit stand in the middle of the row at full size`() {
        assertEquals(LaneRowPlan(slot = 44, start = 62), planLaneRow(width = 300, lanes = 4, fullSlot = 44, reserved = 0))
        assertEquals(LaneRowPlan(slot = 44, start = 128), planLaneRow(width = 300, lanes = 1, fullSlot = 44, reserved = 0))
    }

    @Test
    fun `more lanes than fit all get narrower`() {
        assertEquals(LaneRowPlan(slot = 37, start = 2), planLaneRow(width = 300, lanes = 8, fullSlot = 44, reserved = 0))
    }

    @Test
    fun `a label that leaves the middle free does not move the lanes`() {
        val without = planLaneRow(width = 300, lanes = 4, fullSlot = 44, reserved = 0)
        assertEquals(without, planLaneRow(width = 300, lanes = 4, fullSlot = 44, reserved = 50))
        assertEquals(without, planLaneRow(width = 300, lanes = 4, fullSlot = 44, reserved = 62))
    }

    @Test
    fun `lanes that would reach under the label start right after it`() {
        // Five lanes would start at 40 in the middle of the row; the label ends at 50.
        assertEquals(LaneRowPlan(slot = 44, start = 50), planLaneRow(width = 300, lanes = 5, fullSlot = 44, reserved = 50))
    }

    @Test
    fun `beside a label the lanes get narrower sooner`() {
        // Six lanes fit a row of 300 at full size, but not the 250 the label leaves.
        assertEquals(44, planLaneRow(width = 300, lanes = 6, fullSlot = 44, reserved = 0).slot)
        assertEquals(LaneRowPlan(slot = 41, start = 50), planLaneRow(width = 300, lanes = 6, fullSlot = 44, reserved = 50))
    }

    @Test
    fun `the lanes never reach under the label or past the end of the row`() {
        for (width in listOf(200, 304, 355, 720)) {
            for (lanes in 1..12) {
                for (reserved in listOf(0, 40, 90, width / 3)) {
                    val plan = planLaneRow(width, lanes, fullSlot = 44, reserved = reserved)
                    assertTrue(plan.slot in 1..44, "$width $lanes $reserved: $plan")
                    assertTrue(plan.start >= reserved, "$width $lanes $reserved: $plan")
                    assertTrue(plan.start + plan.slot * lanes <= width, "$width $lanes $reserved: $plan")
                }
            }
        }
    }

    @Test
    fun `a row with no room for lanes gives them none`() {
        assertEquals(0, planLaneRow(width = 40, lanes = 3, fullSlot = 44, reserved = 60).slot)
    }

    @Test
    fun `a label shows only where it fits whole at its smallest size`() {
        // The run-up's words may take 45 % of the row: 135 of 300.
        assertTrue(wordsFit(textWidthAtMinSize = 135, rowWidth = 300, share = LANE_WORDS_MAX_WIDTH_SHARE))
        assertFalse(wordsFit(textWidthAtMinSize = 136, rowWidth = 300, share = LANE_WORDS_MAX_WIDTH_SHARE))
        // A distance keeps its 30 %: 90 of 300.
        assertTrue(wordsFit(textWidthAtMinSize = 90, rowWidth = 300, share = LANE_LABEL_MAX_WIDTH_SHARE))
        assertFalse(wordsFit(textWidthAtMinSize = 91, rowWidth = 300, share = LANE_LABEL_MAX_WIDTH_SHARE))
        // Words that fit beside a distance's share fit beside their own.
        assertTrue(wordsFit(textWidthAtMinSize = 100, rowWidth = 300, share = LANE_WORDS_MAX_WIDTH_SHARE))
    }

    @Test
    fun `the row is as high with a label as without, whatever the label does to the lanes`() {
        for (lanes in 1..9) {
            val height = laneRowHeight(width = 304, lanes = lanes, fullSlot = 44)
            assertEquals(planLaneRow(width = 304, lanes = lanes, fullSlot = 44, reserved = 0).slot, height, "$lanes lanes")
            for (reserved in listOf(0, 60, 98, 144)) {
                // Narrower lanes beside a label fit within it.
                assertTrue(planLaneRow(width = 304, lanes = lanes, fullSlot = 44, reserved = reserved).slot <= height, "$lanes lanes, $reserved")
            }
        }
        // Six lanes at full size, and the run-up's words beside them: the row stays 44 high.
        assertEquals(44, laneRowHeight(width = 304, lanes = 6, fullSlot = 44))
        assertTrue(planLaneRow(width = 304, lanes = 6, fullSlot = 44, reserved = 98).slot < 44)
    }

    @Test
    fun `beside the run-up's words the lanes get narrower and never reach under them`() {
        // Six lanes and words as wide as their whole share, 45 % of a row of 304 plus the gap.
        val reserved = (304 * LANE_WORDS_MAX_WIDTH_SHARE).toInt() + 8
        val plan = planLaneRow(width = 304, lanes = 6, fullSlot = 44, reserved = reserved)
        assertEquals(LaneRowPlan(slot = 26, start = reserved), plan)
        assertTrue(plan.start + plan.slot * 6 <= 304)
        // Short words leave the lanes at full size where there is room.
        assertEquals(44, planLaneRow(width = 304, lanes = 4, fullSlot = 44, reserved = 60).slot)
    }

    @Test
    fun `the row is one lane plus the band high, with and without a label`() {
        // A band of 5 for the bars, as 5 dp is at one pixel a dp.
        assertEquals(49, laneRowGeometry(width = 304, lanes = 4, fullSlot = 44, reserved = 0, band = 5).height)
        assertEquals(49, laneRowGeometry(width = 304, lanes = 4, fullSlot = 44, reserved = 98, band = 5).height)
        for (width in listOf(200, 304, 355, 720)) {
            for (lanes in 1..12) {
                val lane = laneRowHeight(width, lanes, fullSlot = 44)
                for (reserved in listOf(0, 40, 90, width / 3)) {
                    val row = laneRowGeometry(width, lanes, fullSlot = 44, reserved = reserved, band = 5)
                    assertEquals(lane + 5, row.height, "$width $lanes $reserved")
                    assertEquals(lane, row.lanesHeight, "$width $lanes $reserved")
                }
            }
        }
    }

    @Test
    fun `a bar lies under a lane to take, in the middle of its lane and inside the row`() {
        for (width in listOf(200, 304, 355, 720)) {
            for (lanes in 1..12) {
                for (reserved in listOf(0, 40, 90, width / 3)) {
                    val row = laneRowGeometry(width, lanes, fullSlot = 44, reserved = reserved, band = 5)
                    val slot = row.plan.slot
                    for (index in 0 until lanes) {
                        val bar = laneBarBounds(row, index, gap = 2, thickness = 3)
                        val laneLeft = row.plan.start + index * slot
                        val at = "$width $lanes $reserved lane $index: $bar"
                        assertTrue(bar.left >= laneLeft && bar.right <= laneLeft + slot, at)
                        assertEquals(Math.round(slot * LANE_BAR_SHARE), bar.width, at)
                        assertTrue(bar.left - laneLeft - (laneLeft + slot - bar.right) in -1..1, at)
                        assertEquals(row.lanesTop + slot + 2, bar.top, at)
                        assertEquals(3, bar.height, at)
                        assertTrue(bar.bottom <= row.height, at)
                    }
                }
            }
        }
    }

    @Test
    fun `under lanes at full size the bar fills the band to the bottom of the row`() {
        val row = laneRowGeometry(width = 304, lanes = 4, fullSlot = 44, reserved = 0, band = 5)
        // Lanes from 64, 44 wide; the third one's bar is 26 wide, 9 in from each side of it.
        assertEquals(IntRect(left = 161, top = 46, right = 187, bottom = 49), laneBarBounds(row, index = 2, gap = 2, thickness = 3))
    }

    @Test
    fun `beside the run-up's words the bars stay right under their narrower lanes`() {
        val row = laneRowGeometry(width = 304, lanes = 6, fullSlot = 44, reserved = 144, band = 5)
        // Six lanes 26 wide from 144, in the middle of the lanes' 44: from 9 to 35, the bars 2 under them.
        assertEquals(LaneRowPlan(slot = 26, start = 144), row.plan)
        assertEquals(9, row.lanesTop)
        assertEquals(IntRect(left = 149, top = 37, right = 165, bottom = 40), laneBarBounds(row, index = 0, gap = 2, thickness = 3))
        assertEquals(49, row.height)
    }
}
