package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.geometry.RoutePath
import kotlin.math.max
import kotlin.math.min

/**
 * The electronic horizon's lane counts as one route's drawing has taken them, drawing window by drawing window (the own
 * lanes' OL9): the evidence the drawing of the app's own lanes is built from, which the map's own line holds with the
 * route's path. The horizon may change the drawing only beyond the drawing window on screen: each window's send takes
 * the newest update's pairs of lane points ([HorizonLanes]) whose first point lies at or past [startFor], and keeps the
 * pairs earlier sends took before that, so what the map already draws is never changed for the horizon's sake and a
 * window already sent is never sent again for it. With the switch off, a send takes nothing new from there on
 * ([next] with no update): what was sent stays until the next route.
 *
 * Pure and never changed once made; built on a worker with the drawing, never stored. The plan is the one the route
 * already has: neither it nor the card change ([LanePlan.evidence]).
 */
class HorizonEvidence private constructor(
    /** The route's lane plan. */
    val plan: LanePlan,
    /** The pairs of lane points the drawing takes from the horizon, each from the send that first covered it. */
    val lanes: HorizonLanes,
    /** Where the pairs of the last send's update begin ([startFor]); before every station for none. */
    val start: Double = Double.NEGATIVE_INFINITY,
    /**
     * How many of the horizon's stretches the last send took for the first time: those of its pairs that agree with the
     * route's lists and that no earlier send of the route took agreeing, so the trip summary counts each pair once.
     */
    val taken: Int = 0,
) {

    /** The evidence the drawing is built from: the plan's second pass with the horizon's pairs ([LanePlan.evidence]). */
    val evidence: LaneEvidence by lazy { if (lanes.isEmpty) plan.evidence() else plan.evidence(lanes) }

    /** Whether the drawing takes nothing from the horizon. */
    val isEmpty: Boolean get() = lanes.isEmpty

    /**
     * The holder for the next window's send, with the window on screen ending at [windowEnd] and the route's merged card
     * windows [cards]: the pairs taken before [startFor] stay, and from there on the pairs of [ahead], the newest update of
     * the horizon along this route, replace those taken before; none where [ahead] is null (the switch is off, or the
     * horizon has nothing) or of another route.
     */
    fun next(ahead: HorizonAhead?, windowEnd: Double, cards: List<ClosedFloatingPointRange<Double>>): HorizonEvidence {
        val start = startFor(windowEnd, cards)
        val routeId = plan.topology.evidence.route.routeId
        val newest = if (ahead != null && ahead.routeId == routeId) HorizonLanes.of(plan, ahead).from(start) else HorizonLanes.none(routeId)
        val agreed = lanes.between.filter { it.convention != HorizonConvention.NONE }.map { it.before }.toSet()
        val fresh = newest.between.filter { it.convention != HorizonConvention.NONE && it.before !in agreed }.sumOf { it.stretches }
        return HorizonEvidence(plan, lanes.before(start) + newest, start, fresh)
    }

    /**
     * How many metres from [from] to [to] the horizon drew: its bridges and zones ([LaneEvidence.Stretch.horizon]) and the
     * zones of the gains it placed, for the "lane_drawing" line.
     */
    fun drawnMeters(from: Double, to: Double): Double {
        if (isEmpty) return 0.0
        val e = evidence
        var meters = 0.0
        for (s in e.stretches) if (s.horizon) meters += overlap(s.from, s.to, from, to)
        for (z in placedZones(e)) meters += overlap(z.from, z.to, from, to)
        return meters
    }

    /** How many of the horizon's zones reach into [from] to [to]: its own and those of the gains it placed. */
    fun zones(from: Double, to: Double): Int {
        if (isEmpty) return 0
        val e = evidence
        return e.zones.count { z -> (z.cause == LaneEvidence.Zone.Cause.HORIZON || z in placedZones(e)) && overlap(z.from, z.to, from, to) > 0.0 }
    }

    /** The zones of rule 5 that a gain the horizon placed moved to its edge end. */
    private fun placedZones(e: LaneEvidence): List<LaneEvidence.Zone> =
        e.zones.filter { it.cause == LaneEvidence.Zone.Cause.CHANGE && e.placedGains[it.after] == it.to }

    companion object {

        /**
         * Nothing from the horizon beyond this far past the end of the window on screen: a stretch the horizon draws anew
         * begins with an asphalt taper ([RoutePath.ASPHALT_TAPER_METERS]) before it, which must not reach onto the window.
         */
        const val START_PAST_WINDOW_M = RoutePath.ASPHALT_TAPER_METERS

        /** The holder of [plan]'s route before any window took anything from the horizon. */
        fun of(plan: LanePlan): HorizonEvidence = HorizonEvidence(plan, HorizonLanes.none(plan.topology.evidence.route.routeId))

        /**
         * Where the horizon may first change a drawing whose window on screen ends at [windowEnd]: [START_PAST_WINDOW_M] past
         * it, and past the end of a card window that holds that station ([cards], merged and in route order), since nothing
         * of a card window the map shows is changed: no wedge or tint of it moves.
         */
        fun startFor(windowEnd: Double, cards: List<ClosedFloatingPointRange<Double>>): Double {
            var start = windowEnd + START_PAST_WINDOW_M
            for (card in cards) if (start > card.start && start < card.endInclusive) start = card.endInclusive
            return start
        }

        private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double = max(0.0, min(a1, b1) - max(a0, b0))
    }
}
