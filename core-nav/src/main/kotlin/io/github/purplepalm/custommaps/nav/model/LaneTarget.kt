package io.github.purplepalm.custommaps.nav.model

/** The edge of the road that lanes are counted from: the side an exit or a fork leaves on. */
enum class LaneEdge {
    LEFT,
    RIGHT,
}

/**
 * The lanes to use before an exit or a fork, as a few words name them: [count] lanes side by side,
 * the first of them [offset] lanes in from [edge]. The right lane is (RIGHT, 1, 0), the two right
 * lanes are (RIGHT, 2, 0) and the second lane from the right is (RIGHT, 1, 1).
 *
 * Lanes are counted among the lanes open to every car: a lane kept for car pools or buses is
 * never "the left lane". Odd numbers are no error here; they only have no words.
 */
data class LaneTarget(val edge: LaneEdge, val count: Int, val offset: Int) {

    /**
     * Whether a few words name these lanes: the one, two or three lanes at the edge, or a single
     * lane that is the second or the third from it. A wider block, or a lane farther in, takes
     * counting at a glance, which is no instruction to give while driving.
     */
    val hasWords: Boolean
        get() = (offset == 0 && count in 1..MAX_LANES_AT_EDGE) || (count == 1 && offset in 1..MAX_ONE_LANE_OFFSET)

    private companion object {
        /** "3 right" is the widest block named. */
        const val MAX_LANES_AT_EDGE = 3

        /** "3rd from right" is the farthest single lane named. */
        const val MAX_ONE_LANE_OFFSET = 2
    }
}
