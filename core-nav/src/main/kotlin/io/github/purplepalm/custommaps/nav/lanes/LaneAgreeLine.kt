package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.CardLanes
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import kotlin.math.roundToLong

/**
 * The "lane_agree" line of the debug log: each lane row the maneuver card shows at a lane point, once,
 * against the lane plan at that point ([CardPlanAgreement]). Lanes as indices from the left, the app's own
 * keys, metres relative to the car and short names only: never a coordinate, a road's name or an
 * instruction.
 */
object LaneAgreeLine {

    const val TYPE = "lane_agree"

    /**
     * The line's fields for the card's row from [source] at the point [key], the comparison [result], and
     * the point [aheadM] metres ahead of the car: "source", "key", the card's lit lanes ("lit"), the plan's
     * lit lane ("planLit") and amber set ("amber"), "agree" and, where they do not, "why"; then
     * "opensAhead", "unknownAhead" and "exact", what the card's words depend on (the design's rule 3), and
     * "outline", the point's lanes a drawing would not show at the car yet, where there are any.
     */
    fun fields(source: CardLanes.Source, key: String?, result: CardPlanAgreement.Result, aheadM: Double?): Map<String, Any?> = linkedMapOf(
        "source" to source.name.lowercase(),
        "key" to key,
        "aheadM" to aheadM?.takeIf { it.isFinite() }?.roundToLong(),
        "lit" to result.lit,
        "planLit" to result.planLit,
        "amber" to result.amber,
        "agree" to result.agree,
        "why" to result.why?.logName,
        "opensAhead" to result.opensAhead,
        "unknownAhead" to result.unknownAhead,
        "exact" to result.exact,
        "outline" to result.outline.takeIf { it.isNotEmpty() },
    ).filterValues { it != null }

    /**
     * Which of the card's rows get a line: each row it shows at a lane point, at the first update that
     * shows it while the plan of its route is held; a row the card goes on showing gets none again, and one
     * shown before its plan came gets its line once the plan is there. One instance for a drive, on the
     * thread the engine publishes its updates on.
     */
    class Rows {
        private var last: Shown? = null
        private var lastPlan: LanePlan? = null

        /**
         * One update of the card: its row from [source] at the point [key] with [lanes], [words] or none,
         * with [plan], the plan of the update's route, or null while none is held. Whether to compare it now.
         */
        fun take(source: CardLanes.Source, key: String?, lanes: LaneInfo?, words: Boolean, plan: LanePlan?): Boolean {
            if (source == CardLanes.Source.NONE || key == null || lanes == null) {
                last = null
                lastPlan = null
                return false
            }
            if (plan == null) return false
            val shown = Shown(source, key, lanes, words)
            if (shown == last && plan === lastPlan) return false
            last = shown
            lastPlan = plan
            return true
        }

        /** A drive ends or a new one begins: its first row is compared whatever it is. */
        fun reset() {
            last = null
            lastPlan = null
        }

        private data class Shown(val source: CardLanes.Source, val key: String, val lanes: LaneInfo, val words: Boolean)
    }
}
