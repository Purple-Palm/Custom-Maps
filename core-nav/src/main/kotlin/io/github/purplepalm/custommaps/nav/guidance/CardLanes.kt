package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot

/**
 * Which lane row the maneuver card draws, decided in one place: the card itself, the debug log's
 * line about the row and every later reader take it from here, so they never disagree.
 */
data class CardLanes(
    val source: Source,
    /** The lanes of the row, from the leftmost; null for [Source.NONE]. */
    val lanes: LaneInfo? = null,
    /** How far ahead a lane choice is, for its label; set for [Source.CHOICE] only. */
    val distance: Distance? = null,
    /** The lanes to use in a few words; set for [Source.RUN_UP] only, and only where they have words. */
    val target: LaneTarget? = null,
) {

    /** Where the row's lanes come from. */
    enum class Source {
        /** No row: no lanes, or a pending card. */
        NONE,

        /** The next maneuver's own lanes, as the navigation SDK reads them. */
        MANEUVER,

        /** A lane choice the car reaches before the next maneuver, with its distance. */
        CHOICE,

        /** The run-up to an exit or a fork: one lane row, and its words. */
        RUN_UP,
    }

    companion object {
        private val NO_ROW = CardLanes(Source.NONE)

        /**
         * The row for [snapshot]. A pending card shows no lanes. Otherwise a run-up's row comes
         * first, then a lane choice ahead, then the maneuver's own lanes.
         */
        fun of(snapshot: RouteProgressSnapshot): CardLanes {
            if (snapshot.pending) return NO_ROW
            val runUp = snapshot.runUpLanes
            if (runUp != null) return CardLanes(Source.RUN_UP, runUp.lanes, target = runUp.target)
            val ahead = snapshot.laneChoiceAhead
            if (ahead != null) return CardLanes(Source.CHOICE, ahead.lanes, distance = ahead.distance)
            val lanes = snapshot.lanes ?: return NO_ROW
            return CardLanes(Source.MANEUVER, lanes)
        }
    }
}
