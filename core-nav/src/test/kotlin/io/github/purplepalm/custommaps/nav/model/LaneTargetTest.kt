package io.github.purplepalm.custommaps.nav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaneTargetTest {

    @Test
    fun `the lanes at the edge and a lane two or three in have words`() {
        for (edge in LaneEdge.entries) {
            // Right lane, 2 right, 3 right, 2nd from right, 3rd from right; and the same on the left.
            for ((count, offset) in listOf(1 to 0, 2 to 0, 3 to 0, 1 to 1, 1 to 2)) {
                assertTrue(LaneTarget(edge, count, offset).hasWords, "$edge $count lanes $offset in")
            }
        }
    }

    @Test
    fun `wider blocks and lanes farther in have no words`() {
        for (edge in LaneEdge.entries) {
            for ((count, offset) in listOf(4 to 0, 5 to 0, 1 to 3, 1 to 4, 2 to 1, 2 to 2, 3 to 1)) {
                assertFalse(LaneTarget(edge, count, offset).hasWords, "$edge $count lanes $offset in")
            }
        }
    }

    @Test
    fun `odd numbers have no words and are no error`() {
        for ((count, offset) in listOf(0 to 0, 0 to 1, -1 to 0, 1 to -1, -2 to -2)) {
            assertFalse(LaneTarget(LaneEdge.RIGHT, count, offset).hasWords, "$count lanes $offset in")
        }
    }

    @Test
    fun `targets are values`() {
        assertEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), LaneTarget(LaneEdge.RIGHT, 1, 0))
        assertNotEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), LaneTarget(LaneEdge.LEFT, 1, 0))
        assertNotEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), LaneTarget(LaneEdge.RIGHT, 1, 1))
        assertNotEquals(LaneTarget(LaneEdge.RIGHT, 1, 0), LaneTarget(LaneEdge.RIGHT, 2, 0))
    }
}
