package io.github.purplepalm.custommaps.ui.guidance

import org.junit.jupiter.api.Assertions.assertEquals
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
}
