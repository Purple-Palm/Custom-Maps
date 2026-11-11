package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LaneWordsTest {

    @Test
    fun `every lane target with words has its own short and long words on both sides`() {
        val words = mapOf(
            LaneTarget(LaneEdge.RIGHT, 1, 0) to LaneWordsRes(R.string.lane_words_short_right_1, R.string.lane_words_long_right_1),
            LaneTarget(LaneEdge.RIGHT, 2, 0) to LaneWordsRes(R.string.lane_words_short_right_2, R.string.lane_words_long_right_2),
            LaneTarget(LaneEdge.RIGHT, 3, 0) to LaneWordsRes(R.string.lane_words_short_right_3, R.string.lane_words_long_right_3),
            LaneTarget(LaneEdge.RIGHT, 1, 1) to LaneWordsRes(R.string.lane_words_short_right_2nd, R.string.lane_words_long_right_2nd),
            LaneTarget(LaneEdge.RIGHT, 1, 2) to LaneWordsRes(R.string.lane_words_short_right_3rd, R.string.lane_words_long_right_3rd),
            LaneTarget(LaneEdge.LEFT, 1, 0) to LaneWordsRes(R.string.lane_words_short_left_1, R.string.lane_words_long_left_1),
            LaneTarget(LaneEdge.LEFT, 2, 0) to LaneWordsRes(R.string.lane_words_short_left_2, R.string.lane_words_long_left_2),
            LaneTarget(LaneEdge.LEFT, 3, 0) to LaneWordsRes(R.string.lane_words_short_left_3, R.string.lane_words_long_left_3),
            LaneTarget(LaneEdge.LEFT, 1, 1) to LaneWordsRes(R.string.lane_words_short_left_2nd, R.string.lane_words_long_left_2nd),
            LaneTarget(LaneEdge.LEFT, 1, 2) to LaneWordsRes(R.string.lane_words_short_left_3rd, R.string.lane_words_long_left_3rd),
        )
        assertEquals(words, words.keys.associateWith { laneWordsRes(it) })
        // Twenty strings, none of them twice.
        assertEquals(20, words.values.flatMap { listOf(it.short, it.long) }.toSet().size)
    }

    @Test
    fun `lanes without words get none`() {
        for (edge in LaneEdge.entries) {
            for (count in -1..6) {
                for (offset in -1..6) {
                    val target = LaneTarget(edge, count, offset)
                    if (!target.hasWords) assertNull(laneWordsRes(target), "$target")
                }
            }
        }
        // Four lanes at the edge, a block farther in, the fourth lane in.
        assertNull(laneWordsRes(LaneTarget(LaneEdge.RIGHT, 4, 0)))
        assertNull(laneWordsRes(LaneTarget(LaneEdge.LEFT, 2, 1)))
        assertNull(laneWordsRes(LaneTarget(LaneEdge.RIGHT, 1, 3)))
    }
}
