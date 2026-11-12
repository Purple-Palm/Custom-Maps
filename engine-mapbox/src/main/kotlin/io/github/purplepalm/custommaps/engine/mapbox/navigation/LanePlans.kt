package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.lanes.CardWindow
import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidenceLine
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanSegment

/*
 * The lane plan of each primary route a drive uses (the app's own lanes, design 5): built once for the
 * route, off the main thread, from the route the navigator already holds, and held with the route's id
 * for as long as the drive is on it. No request; nothing of it is stored. Nothing on the screen reads it
 * yet: the debug log compares it with the card's lane row.
 */

/** One primary route's lane plan, as the engine holds it ([LanePlans]). */
internal class RouteLanePlan(
    /** The route as the own lanes read it, with the engine's index of its places. */
    val input: LaneRouteInput,
    /** The plan ([LanePlanner.plan]): the lanes that work at every station and, inside the card windows, the one it names. */
    val plan: LanePlan,
    /** The evidence's second pass for the plan ([LanePlan.evidence]): its zones and possible lanes, what a drawing would draw. */
    val evidence: LaneEvidence,
    /** How long reading the route and making the plan took on the route lines' worker, in milliseconds. */
    val buildMillis: Double,
) {
    /** The navigator's id of the route. */
    val routeId: String get() = input.route.routeId

    /** Where along the route a car [metersOnStep] into step [step] of leg [leg] is; null for a step the route does not have. */
    fun station(leg: Int, step: Int, metersOnStep: Double): Double? = input.station(leg, step, metersOnStep)

    /** The plan at [station]: the same object anywhere in a stretch where nothing changes; null outside every run of lanes. */
    fun at(station: Double): PlanSegment? = plan.segmentAt(station)

    /** The lane point of the plan's lanes that the lane choices' [key] names; null for a key of no lane point of this route. */
    fun pointOf(key: String?): LaneTopology.Point? {
        val node = input.nodeOf(key) ?: return null
        return pointsByNode[node]
    }

    private val pointsByNode: Map<Int, LaneTopology.Point> by lazy { plan.topology.points.associateBy { it.node } }
}

/**
 * Which lane plan the engine holds: the plan of the primary route the drive is on, once it is built. Each
 * primary route of a drive gets one plan, as it gets its "lane_evidence" line ([LaneEvidenceLine.Routes]):
 * the route the drive starts on once the navigator has it, and every new primary route after it, never a
 * refresh. A route's plan is asked for with a [Ticket], built on the route lines' worker ([planRoute]) and
 * handed back with it ([deliver]); a plan whose ticket a newer one, the drive's end or a new drive has
 * passed is dropped. A progress update of another route reads none ([planFor]). Main thread only.
 */
internal class LanePlans {

    /** The ask for the plan of the route [routeId], as one drive's [n]th. */
    class Ticket internal constructor(val routeId: String, internal val drive: Int, internal val n: Int)

    private val routes = LaneEvidenceLine.Routes()

    /** Counts drives, and their ends: a ticket of another drive is out of date. */
    private var drive = 0

    /** Counts the tickets of the drive. */
    private var asked = 0

    /** The ticket of the plan held, or of the newest plan that failed; 0 for none. */
    private var answered = 0

    /** The plan of the drive's newest primary route that has one; null before it, after a failure and outside a drive. */
    var held: RouteLanePlan? = null
        private set

    /** A drive begins: its routes are on their way to the navigator, and no plan of the drive before is kept. */
    fun begin() {
        drive++
        asked = 0
        answered = 0
        held = null
        routes.begin()
    }

    /** The navigator has the routes the drive starts on, [routeId] the first: the ticket for its plan, or null. */
    fun onCommitted(routeId: String?): Ticket? = if (routes.onCommitted(routeId)) ticket(routeId) else null

    /** The navigator's routes changed, [routeId] the first now, a [refresh] or not: the ticket for its plan, or null. */
    fun onRoutesChanged(routeId: String?, refresh: Boolean): Ticket? = if (routes.onRoutesChanged(routeId, refresh)) ticket(routeId) else null

    /**
     * The route lines' worker's answer for [ticket]: [plan], or null where it could not be made. Held when
     * the ticket is of this drive and newer than the plan held; a null answer leaves no plan held, as the
     * plan held is of a route the drive has left. Whether it was taken.
     */
    fun deliver(ticket: Ticket, plan: RouteLanePlan?): Boolean {
        if (ticket.drive != drive || ticket.n <= answered) return false
        if (plan != null && plan.routeId != ticket.routeId) return false
        answered = ticket.n
        held = plan
        return true
    }

    /** The plan of the route [routeId], the one a progress update is of; null for any other route and while none is held. */
    fun planFor(routeId: String?): RouteLanePlan? = held?.takeIf { routeId != null && it.routeId == routeId }

    /** The drive ended: no plan of it is held, and none still on its way is taken. */
    fun end() {
        drive++
        held = null
        routes.end()
    }

    private fun ticket(routeId: String?): Ticket? = routeId?.let { Ticket(it, drive, ++asked) }
}

/**
 * What the route lines' worker does for a new primary route: reads it ([read], with [laneRouteInput]),
 * writes its "lane_evidence" line, and makes its lane plan with the stations of the roads without lane
 * data that leave on an exit's side, as plain numbers. A route that cannot be read costs its line and its
 * plan; a plan that cannot be made costs one error line. Neither costs the drive. [active] tells whether
 * the work is still wanted, between its stages.
 *
 * @param planner the planner, [LanePlanner.plan]; tests give one that fails
 * @param nanos the clock the work is timed on
 * @return the plan to hold, or null
 */
internal fun planRoute(
    read: () -> LaneRouteInput,
    debugLog: DebugLog,
    active: () -> Boolean = { true },
    planner: (LaneTopology, List<CardWindow>, List<Double>) -> LanePlan = LanePlanner::plan,
    nanos: () -> Long = System::nanoTime,
): RouteLanePlan? {
    val start = nanos()
    val input: LaneRouteInput
    val first: LaneEvidence
    try {
        input = read()
        first = LaneEvidence.of(input.route)
        val fields = LaneEvidenceLine.fields(first)
        if (!active()) return null
        debugLog.log(LaneEvidenceLine.TYPE, fields)
    } catch (e: RuntimeException) {
        // A route that cannot be read costs its line, never the drive.
        debugLog.error(LaneEvidenceLine.TYPE, e)
        return null
    }
    return try {
        val topology = LaneTopology.of(first)
        if (!active()) return null
        val plan = planner(topology, CardWindows.latched(input.route), input.unknownExits)
        if (!active()) return null
        RouteLanePlan(input, plan, plan.evidence(), (nanos() - start) / NANOS_PER_MILLI)
    } catch (e: RuntimeException) {
        // A plan that cannot be made costs this line and the plan, never the drive.
        debugLog.error(LANE_PLAN_ERROR, e)
        null
    }
}

/** Where an error line says a lane plan could not be made. */
internal const val LANE_PLAN_ERROR = "lane_plan"

private const val NANOS_PER_MILLI = 1_000_000.0
