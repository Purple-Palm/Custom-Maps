package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection
import io.github.purplepalm.custommaps.nav.lanes.geometry.RoutePath
import kotlin.math.max
import kotlin.math.min

/**
 * The electronic horizon's lane counts as one route's drawing has taken them, drawing window by drawing window (the own
 * lanes' OL9): the evidence the drawing of the app's own lanes is built from, which the map's own line holds with the
 * route's path. The horizon may change the drawing only beyond the drawing window on screen: each window's send takes
 * the newest update's pairs of lane points ([HorizonLanes]) whose second point lies past [startFor], from there on where
 * the first lies before it, and keeps the pairs earlier sends took from before that, so what the map already draws is
 * never changed for the horizon's sake and a window already sent is never sent again for it. A pair longer than what the
 * window on screen leaves of the horizon ahead is taken all the same, with the edges behind the car that the updates
 * before gave ([HorizonAhead.after]). With the switch off, a send takes nothing new from there on ([next] with no
 * update): what was sent stays until the next route.
 *
 * The same holds for the route's streets ([streets], the own lanes' design 3.3): where the route's own access field does not
 * tell whether a street is one-way or two-way, a send takes the horizon's one-way flag along it ([HorizonAhead.oneWayAlong])
 * for each street stretch the drawing would draw that begins past [startFor] ([CrossSection.StreetStretch]), and keeps what
 * the stretches before it had, an answer or none: so a street the map draws, or leaves to the map's own road, stays as it
 * is. A road sits as its first stretch told alike does ([CrossSection]): a stretch past the start told otherwise is not drawn.
 *
 * Pure and never changed once made; built on a worker with the drawing, never stored. The plan is the one the route
 * already has: neither it nor the card change ([LanePlan.evidence]).
 */
class HorizonEvidence private constructor(
    /** The route's lane plan. */
    val plan: LanePlan,
    /** The pairs of lane points the drawing takes from the horizon: the last send's from its start on, earlier sends' before it. */
    val lanes: HorizonLanes,
    /** Where the pairs of the last send's update begin ([startFor]); before every station for none. */
    val start: Double = Double.NEGATIVE_INFINITY,
    /**
     * How many of the horizon's stretches the last send took for the first time: those of its pairs that agree with the
     * route's lists and that no earlier send of the route took agreeing, so the trip summary counts each pair once.
     */
    val taken: Int = 0,
    /**
     * How the route's streets sit as the drawing takes it ([StreetWays]): the route's own access field where it tells, and
     * the horizon's one-way flag as the sends took it.
     */
    val streets: StreetWays = StreetWays.taken(plan.topology.evidence.route, emptyMap()),
    /** The street stretches the last send took the horizon's answers for, all past its [start]. */
    private val streetsTaken: List<CrossSection.StreetStretch> = emptyList(),
    /** The evidence, where the send that made this holder worked it out already. */
    private val made: LaneEvidence? = null,
) {

    /** The evidence the drawing is built from: the plan's second pass with the horizon's pairs ([LanePlan.evidence]). */
    val evidence: LaneEvidence by lazy { made ?: if (lanes.isEmpty) plan.evidence() else plan.evidence(lanes) }

    /** Whether the drawing takes nothing from the horizon: no pair of lane points and no street's way. */
    val isEmpty: Boolean get() = lanes.isEmpty && streets.known.getValue(StreetWays.Source.HORIZON) == 0

    /**
     * Whether the last send took the horizon's answers for a street stretch that begins at or past [start]: a send with the
     * switch off from there on drops them, and needs a drawing anew.
     */
    fun streetsFrom(start: Double): Boolean = streetsTaken.any { it.from >= start }

    /**
     * Whether a drawing built with this holder is the one built with [other]: the same plan, and the same stretches, zones
     * and placed gains, as where the horizon's counts lie over the lane points' own reaches or are those taken before. A
     * send then needs no path anew.
     */
    fun drawsAs(other: HorizonEvidence): Boolean {
        if (plan !== other.plan) return false
        if (!streets.sameAs(other.streets)) return false
        if (lanes.isEmpty && other.lanes.isEmpty) return true
        val mine = evidence
        val theirs = other.evidence
        return mine.stretches == theirs.stretches && mine.zones == theirs.zones && mine.placedGains == theirs.placedGains
    }

    /**
     * The holder for the next window's send, with the window on screen ending at [windowEnd] and the route's merged card
     * windows [cards]: the pairs taken from before [startFor] stay as they were taken, the map drawing them in part; from
     * there on the pairs of [ahead], the newest update of the horizon along this route with the stretches kept behind the
     * car, replace those taken before: each pair whose second point lies past the start, from the start on where its
     * first lies before it ([HorizonLanes.of]). A pair kept from before that reads under neither convention draws nothing
     * and may be taken anew. None new where [ahead] is null (the switch is off, or the horizon has nothing) or of another
     * route.
     */
    fun next(ahead: HorizonAhead?, windowEnd: Double, cards: List<ClosedFloatingPointRange<Double>>): HorizonEvidence {
        val start = startFor(windowEnd, cards)
        val routeId = plan.topology.evidence.route.routeId
        val kept = lanes.before(start).filter { it.pieces.isNotEmpty() }
        val keptPairs = kept.between.mapTo(HashSet()) { it.before }
        val newest = if (ahead != null && ahead.routeId == routeId) {
            HorizonLanes.of(plan, ahead, from = start).filter { it.before !in keptPairs }
        } else {
            HorizonLanes.none(routeId)
        }
        val agreed = lanes.between.filter { it.convention != HorizonConvention.NONE }.mapTo(HashSet()) { it.before }
        val fresh = newest.between.filter { it.convention != HorizonConvention.NONE && it.before !in agreed }.sumOf { it.stretches }
        val pairs = kept + newest
        val evidence = if (pairs.isEmpty) plan.evidence() else plan.evidence(pairs)
        val (ways, took) = streetsFor(ahead?.takeIf { it.routeId == routeId }, start, evidence)
        return HorizonEvidence(plan, pairs, start, fresh, ways, took, evidence)
    }

    /**
     * How the route's streets sit for a send from [start] on, with [ahead] the newest update of the horizon along this route
     * (null for none) and [evidence] the one the drawing is built from: each street stretch that begins before [start] keeps
     * the answers of this holder, an answer or none; each one from [start] on takes the horizon's one-way flag along each of
     * its streets the route's own access field does not tell. A road sits as its first stretch told alike does, and a later
     * one told otherwise is not drawn ([CrossSection]), so what the stretches from [start] on are told never changes whether
     * one before it is drawn: nothing the window on screen may hold changes. With the stretches whose answers it took.
     */
    private fun streetsFor(
        ahead: HorizonAhead?,
        start: Double,
        evidence: LaneEvidence,
    ): Pair<StreetWays, List<CrossSection.StreetStretch>> {
        val route = plan.topology.evidence.route
        val stretches = CrossSection.streetStretches(plan, evidence)
        val told = HashMap<Int, StreetWays.Way>()
        val kept = HashSet<Int>()
        for (stretch in stretches) {
            if (stretch.from >= start) continue
            for (n in stretch.nodes) {
                kept.add(n)
                streets.at(n)?.takeIf { it.source == StreetWays.Source.HORIZON }?.let { told[n] = it }
            }
        }
        val took = ArrayList<CrossSection.StreetStretch>()
        if (ahead == null) return StreetWays.taken(route, told) to took
        for (stretch in stretches) {
            if (stretch.from < start) continue
            val read = HashMap<Int, StreetWays.Way>()
            for (n in stretch.nodes) {
                if (n in kept || route.nodes[n].oneWay != null) continue
                val span = StreetWays.span(route, n) ?: continue
                val oneWay = ahead.oneWayAlong(span.start, span.endInclusive) ?: continue
                read[n] = StreetWays.Way(StreetWays.carriageway(oneWay), StreetWays.Source.HORIZON)
            }
            if (read.isEmpty()) continue
            told.putAll(read)
            took.add(stretch)
        }
        return StreetWays.taken(route, told) to took
    }

    /**
     * How many metres from [from] to [to] the horizon drew: its bridges and zones ([LaneEvidence.Stretch.horizon]) and the
     * zones of the gains it placed, for the "lane_drawing" line.
     */
    fun drawnMeters(from: Double, to: Double): Double {
        if (lanes.isEmpty) return 0.0
        val e = evidence
        var meters = 0.0
        for (s in e.stretches) if (s.horizon) meters += overlap(s.from, s.to, from, to)
        for (z in placedZones(e)) meters += overlap(z.from, z.to, from, to)
        return meters
    }

    /** How many of the horizon's zones reach into [from] to [to]: its own and those of the gains it placed. */
    fun zones(from: Double, to: Double): Int {
        if (lanes.isEmpty) return 0
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

        /**
         * The holder of [plan]'s route before any window took anything from the horizon: its streets as the route's own access
         * field tells. [evidence] is the plan's own second pass where the caller has it already.
         */
        fun of(plan: LanePlan, evidence: LaneEvidence? = null): HorizonEvidence =
            HorizonEvidence(plan, HorizonLanes.none(plan.topology.evidence.route.routeId), made = evidence)

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
