package io.github.purplepalm.custommaps.nav.lanes.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Which stretch of the app's own lanes the map holds at a time, and when it takes the next one (the own lanes' design 9,
 * E7): a drawing window, sent to the map as one update of its source, runs from [BEHIND_METERS] behind the car to the end
 * of the next card window and the drawing that follows it for [PAST_CARD_METERS], or [MAX_METERS] of the route where the
 * next card window lies farther than that. A card window the car is about to enter, within [ENTRY_METERS], or is in, is
 * always taken whole, even where that makes the window longer than [MAX_METERS]: inside a card window no window is sent.
 * Card windows that begin within [TOGETHER_METERS] of the end of the one before are taken together ([nextGroup]): the
 * gap between two that close may hold no progress update at all, or too little road to send and load a window on.
 *
 * The next window is due while the car drives, [SEND_AHEAD_METERS] before the current one's end, or once the window from
 * the car would take the next card windows whole where the current one does not: never inside a card window and never
 * while a new route's path and first window load (the route-change swap sends the first window with the path). Where
 * nothing is drawn at the car, as after a seek of a simulated drive, the next one is due at once.
 *
 * Stations are metres along the route. Pure; one per route, made off the main thread with its drawing; [due] is a lookup
 * per progress update.
 */
class DrawingWindows(
    /** The route's card windows, as their stations: the merged ones are the ones the window rule reads ([cards]). */
    cardWindows: List<ClosedFloatingPointRange<Double>>,
    /** The route's length: no window reaches past it. */
    private val routeLength: Double,
) {

    /** One drawing window: from route station [from] to [to]. */
    data class Window(val from: Double, val to: Double) {
        val meters: Double get() = to - from

        /** Whether the window holds [station]. */
        fun holds(station: Double): Boolean = station >= from && station <= to
    }

    /** The card windows merged where they meet or overlap, in route order. */
    val cards: List<ClosedFloatingPointRange<Double>> = run {
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        for (w in cardWindows.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && w.start <= last.endInclusive) {
                out[out.size - 1] = last.start..max(last.endInclusive, w.endInclusive)
            } else {
                out.add(w)
            }
        }
        out
    }

    /** Whether [car] is inside a card window. */
    fun inCard(car: Double): Boolean = cards.any { car >= it.start && car <= it.endInclusive }

    /** The first card window that has not ended at [car]: the one the car is in, or the next ahead; null past the last. */
    fun nextCard(car: Double): ClosedFloatingPointRange<Double>? = cards.firstOrNull { it.endInclusive > car }

    /**
     * The card windows a window takes together from [car]: [nextCard] and each card window after it that begins within
     * [TOGETHER_METERS] of the end of the one before, from the first's start to the last's end; null past the last.
     */
    fun nextGroup(car: Double): ClosedFloatingPointRange<Double>? {
        val first = cards.indexOfFirst { it.endInclusive > car }
        if (first < 0) return null
        var end = cards[first].endInclusive
        var k = first + 1
        while (k < cards.size && cards[k].start < end + TOGETHER_METERS) {
            end = max(end, cards[k].endInclusive)
            k++
        }
        return cards[first].start..end
    }

    /** The window for the car at [car]. */
    fun windowAt(car: Double): Window {
        val from = max(0.0, car - BEHIND_METERS)
        val cap = from + MAX_METERS
        val group = nextGroup(car)
        val to = when {
            group == null -> cap
            // The next card windows and the drawing past them fit.
            group.endInclusive + PAST_CARD_METERS <= cap -> group.endInclusive + PAST_CARD_METERS
            // About to enter them, or in them: all of them, whatever that makes the window's length.
            car >= group.start - ENTRY_METERS -> group.endInclusive + PAST_CARD_METERS
            // They lie farther: as much road as a window takes; a later one takes the card windows.
            else -> cap
        }
        return Window(from, max(from, min(to, routeLength)))
    }

    /**
     * Whether the next window is due with the car at [car], the map holding [current] (null for none yet), [changing]
     * while a new route's path and first window have not loaded.
     */
    fun due(car: Double, current: Window?, changing: Boolean): Boolean {
        if (changing) return false
        // Nothing drawn at the car: at once, wherever it is.
        if (current == null || !current.holds(car)) return true
        if (inCard(car)) return false
        val next = windowAt(car)
        if (next.to <= current.to + SAME_STATION) return false
        if (car >= current.to - SEND_AHEAD_METERS) return true
        val group = nextGroup(car) ?: return false
        val need = min(group.endInclusive + PAST_CARD_METERS, routeLength)
        return current.to < need - SAME_STATION && next.to >= need - SAME_STATION
    }

    companion object {
        /** A window begins this far behind the car. */
        const val BEHIND_METERS = 50.0

        /** A window is at most this long, unless a card window the car is about to enter needs more. */
        const val MAX_METERS = 3_000.0

        /** The next window is sent this far before the current one's end. */
        const val SEND_AHEAD_METERS = 1_000.0

        /**
         * A window takes the drawing this far past the end of its card window: the main road's stub and taper past a
         * gore, and the road the close view still shows there, until the next window has loaded.
         */
        const val PAST_CARD_METERS = 300.0

        /** A card window this close ahead of the car is taken whole by the next window, however long that makes it. */
        const val ENTRY_METERS = 200.0

        /**
         * A card window that begins this close past the end of the one before is taken with it ([nextGroup]): the window
         * held for the one before reaches [PAST_CARD_METERS] past its end, and the car would be [ENTRY_METERS] from the next
         * before it reaches that.
         */
        const val TOGETHER_METERS = PAST_CARD_METERS + ENTRY_METERS

        /** Two stations closer than this are one. */
        private const val SAME_STATION = 1e-6
    }
}
