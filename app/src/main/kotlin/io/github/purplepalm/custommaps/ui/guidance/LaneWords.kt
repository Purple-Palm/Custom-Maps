package io.github.purplepalm.custommaps.ui.guidance

import androidx.annotation.StringRes
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneTarget

/**
 * The words for the lanes to use on the run-up to an exit: [short] stands in the card's lane row,
 * [long] is what the screen reader says for the row. Both count from the exit's side, among the
 * lanes every car may use; nothing here is spoken by the app.
 */
internal data class LaneWordsRes(@StringRes val short: Int, @StringRes val long: Int)

/**
 * The words for [target]: "Right lane", "2 right", "3 right", "2nd from right" and "3rd from right",
 * and the same with left. Null for lanes that have no words ([LaneTarget.hasWords]).
 */
internal fun laneWordsRes(target: LaneTarget): LaneWordsRes? {
    if (!target.hasWords) return null
    val right = target.edge == LaneEdge.RIGHT
    return when {
        target.offset == 1 -> if (right) RIGHT_2ND else LEFT_2ND
        target.offset == 2 -> if (right) RIGHT_3RD else LEFT_3RD
        target.count == 1 -> if (right) RIGHT_1 else LEFT_1
        target.count == 2 -> if (right) RIGHT_2 else LEFT_2
        else -> if (right) RIGHT_3 else LEFT_3
    }
}

private val RIGHT_1 = LaneWordsRes(R.string.lane_words_short_right_1, R.string.lane_words_long_right_1)
private val RIGHT_2 = LaneWordsRes(R.string.lane_words_short_right_2, R.string.lane_words_long_right_2)
private val RIGHT_3 = LaneWordsRes(R.string.lane_words_short_right_3, R.string.lane_words_long_right_3)
private val RIGHT_2ND = LaneWordsRes(R.string.lane_words_short_right_2nd, R.string.lane_words_long_right_2nd)
private val RIGHT_3RD = LaneWordsRes(R.string.lane_words_short_right_3rd, R.string.lane_words_long_right_3rd)
private val LEFT_1 = LaneWordsRes(R.string.lane_words_short_left_1, R.string.lane_words_long_left_1)
private val LEFT_2 = LaneWordsRes(R.string.lane_words_short_left_2, R.string.lane_words_long_left_2)
private val LEFT_3 = LaneWordsRes(R.string.lane_words_short_left_3, R.string.lane_words_long_left_3)
private val LEFT_2ND = LaneWordsRes(R.string.lane_words_short_left_2nd, R.string.lane_words_long_left_2nd)
private val LEFT_3RD = LaneWordsRes(R.string.lane_words_short_left_3rd, R.string.lane_words_long_left_3rd)
