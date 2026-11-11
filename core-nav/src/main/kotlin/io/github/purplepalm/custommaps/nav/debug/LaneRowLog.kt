package io.github.purplepalm.custommaps.nav.debug

import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/**
 * The "lane_row" line of the debug log: the lane row the maneuver card draws ([CardLanes]), lane by
 * lane, with what the run-up to an exit adds to it and why such a row has no words. A line is
 * written when what the card draws changes: where its row comes from, its lanes, which of them are
 * lit, or the words beside them; never for a distance alone. Keys, indices, numbers and short
 * tokens only: no place, no road name and no instruction text. Not thread-safe: use it from one
 * thread, the one the engine publishes its updates on.
 *
 * The card measures the words itself and leaves out those that do not fit (the app's lane row).
 * It says so through [onWordsShown]; the line that follows has no words and the reason [ODD_NO_FIT].
 */
class LaneRowLog {

    /** The row of the latest update, with what the card said of its words. */
    private var row: Row? = null

    /** What the last line said the card draws; null before the first line. */
    private var written: Drawn? = null

    /**
     * One update of the card: [snapshot], as the engine publishes it. Returns the fields of a
     * "lane_row" line when what the card draws changed since the last line, and null otherwise.
     *
     * @param choiceKey the key of the lane choice whose lanes the card shows, for a lane choice's row
     * @param runUpMeters how far ahead the point is whose lanes a run-up's row shows, in meters
     * @param runUp the exit of the run-up on now, whatever row the card shows; null outside one
     * @param runUpStart what began that run-up: a point's key, `edge` or `resume`
     */
    fun onSnapshot(
        snapshot: RouteProgressSnapshot,
        choiceKey: String?,
        runUpMeters: Double?,
        runUp: String?,
        runUpStart: String?,
    ): Map<String, Any?>? {
        val card = CardLanes.of(snapshot)
        val runUpRow = if (card.source == CardLanes.Source.RUN_UP) snapshot.runUpLanes else null
        val key = when (card.source) {
            CardLanes.Source.RUN_UP -> runUpRow?.key
            CardLanes.Source.CHOICE -> choiceKey
            CardLanes.Source.MANEUVER -> "${snapshot.stepKey}/end"
            CardLanes.Source.NONE -> null
        }
        val metersAhead = when (card.source) {
            CardLanes.Source.RUN_UP -> runUpMeters
            CardLanes.Source.CHOICE -> card.distance?.meters
            CardLanes.Source.MANEUVER -> snapshot.distanceToNextManeuver.meters
            CardLanes.Source.NONE -> null
        }
        // The words show until the card says they did not fit; a new row's words show again.
        val last = row
        val wordsShown = last == null || last.key != key || last.card.target != card.target || last.wordsShown
        row = Row(
            card = card,
            key = key,
            need = runUpRow?.need,
            odd = runUpRow?.odd,
            metersAhead = metersAhead,
            runUp = runUp,
            runUpStart = runUpStart,
            pending = snapshot.pending,
            offRoute = snapshot.offRoute,
            wordsShown = wordsShown,
        )
        return lineIfChanged()
    }

    /**
     * What the card did with the words of the run-up's row whose key is [key]: [shown] false when
     * they did not fit beside the lanes and were left out. Returns the fields of a "lane_row" line
     * when that changes what the card draws, and null otherwise: for a row the card no longer
     * shows, a row without words, and an answer the last line already has.
     */
    fun onWordsShown(key: String, shown: Boolean): Map<String, Any?>? {
        val last = row ?: return null
        if (last.key != key || last.card.target == null || last.wordsShown == shown) return null
        row = last.copy(wordsShown = shown)
        return lineIfChanged()
    }

    /** A drive ends or a new one begins: its first row is written whatever it is. */
    fun reset() {
        row = null
        written = null
    }

    private fun lineIfChanged(): Map<String, Any?>? {
        val current = row ?: return null
        val drawn = Drawn(current.card.source, current.card.lanes, current.shownWords)
        if (drawn == written) return null
        written = drawn
        return fields(current)
    }

    private fun fields(row: Row): Map<String, Any?> {
        val lanes = row.card.lanes?.lanes
        val shown = row.shownWords
        val odd = if (row.card.target != null && shown == null) ODD_NO_FIT else row.odd
        val label = row.card.distance?.takeIf { row.card.source == CardLanes.Source.CHOICE }?.let { ImperialFormat.distance(it).toString() }
        return mapOf(
            "source" to row.card.source.name.lowercase(),
            "key" to row.key,
            "row" to lanes?.map(::token),
            "lit" to lanes?.indices?.filter { lanes[it].isRecommended },
            "des" to lanes?.indices?.filter { lanes[it].designated }?.takeIf { it.isNotEmpty() },
            "label" to label,
            "words" to shown?.let(::words),
            "need" to row.need,
            "metersAhead" to row.metersAhead?.takeIf { it.isFinite() }?.let { Math.round(it) },
            "runUp" to row.runUp,
            "start" to row.runUpStart,
            "odd" to odd,
            "pending" to row.pending,
            "offRoute" to row.offRoute,
        ).filterValues { it != null }
    }

    /** The row of one update, and whether the card showed its words. */
    private data class Row(
        val card: CardLanes,
        val key: String?,
        val need: Int?,
        val odd: String?,
        val metersAhead: Double?,
        val runUp: String?,
        val runUpStart: String?,
        val pending: Boolean,
        val offRoute: Boolean,
        val wordsShown: Boolean,
    ) {
        /** The words beside the lanes; null where the row has none or the card left them out. */
        val shownWords: LaneTarget? get() = card.target?.takeIf { wordsShown }
    }

    /** What the card draws: a line is written when this changes. */
    private data class Drawn(val source: CardLanes.Source, val lanes: LaneInfo?, val words: LaneTarget?)

    companion object {
        /** Why a run-up's row shows no words although it has some: they did not fit beside the lanes, and the card left them out. */
        const val ODD_NO_FIT = "no_fit"

        /** The arrows of a lane in the order its token lists them. */
        private val ARROWS = listOf(
            TurnModifier.STRAIGHT,
            TurnModifier.SLIGHT_LEFT,
            TurnModifier.LEFT,
            TurnModifier.SHARP_LEFT,
            TurnModifier.SLIGHT_RIGHT,
            TurnModifier.RIGHT,
            TurnModifier.SHARP_RIGHT,
            TurnModifier.U_TURN,
        )

        /**
         * One lane of a row as the line writes it: its arrows joined by `+` (`s` straight, `sl`,
         * `l` and `shl` slight, normal and sharp left, `sr`, `r` and `shr` the same to the right,
         * `u` a U-turn), `-` for a lane without arrows, then `*` when it is lit and `>` with the
         * arrow lit on it where that is not simply its one arrow: `s+sr*>sr`.
         */
        fun token(lane: Lane): String {
            val out = StringBuilder()
            var arrows = 0
            for (arrow in ARROWS) {
                if (arrow !in lane.indications) continue
                if (arrows > 0) out.append('+')
                out.append(arrowToken(arrow))
                arrows++
            }
            if (arrows == 0) out.append('-')
            if (lane.isRecommended) {
                out.append('*')
                val lit = lane.activeIndication
                if (lit != null && !(arrows == 1 && lit in lane.indications)) out.append('>').append(arrowToken(lit))
            }
            return out.toString()
        }

        /**
         * The lanes to use as the line writes them: the side, then how many lanes, then how many
         * lanes in from that side where it is not at it. `R1` the right lane, `R2` the two right
         * lanes, `R1+1` the second lane from the right, `L1+2` the third from the left.
         */
        fun words(target: LaneTarget): String {
            val side = if (target.edge == LaneEdge.RIGHT) "R" else "L"
            val inward = if (target.offset > 0) "+${target.offset}" else ""
            return "$side${target.count}$inward"
        }

        private fun arrowToken(arrow: TurnModifier): String = when (arrow) {
            TurnModifier.STRAIGHT -> "s"
            TurnModifier.SLIGHT_LEFT -> "sl"
            TurnModifier.LEFT -> "l"
            TurnModifier.SHARP_LEFT -> "shl"
            TurnModifier.SLIGHT_RIGHT -> "sr"
            TurnModifier.RIGHT -> "r"
            TurnModifier.SHARP_RIGHT -> "shr"
            TurnModifier.U_TURN -> "u"
        }
    }
}
