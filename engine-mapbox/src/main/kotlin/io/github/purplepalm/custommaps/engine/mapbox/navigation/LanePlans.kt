package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.lanes.CardWindow
import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.LaneCarry
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidenceLine
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LanePlanLine
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanSegment
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/*
 * The lane plan of each primary route a drive uses (the app's own lanes, design 5): built once for the
 * route, off the main thread, from the route the navigator already holds, and held with the route's id
 * for as long as the drive is on it. No request; nothing of it is stored. The debug log compares it with
 * the card's lane row, and the app's own route line, behind a Debug switch, runs through its lanes.
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
    /**
     * The route's line with its stations, for finding the car on it when the drive changes to another route
     * (the design's D8). Read on the route lines' worker only, where it is made the first time it is needed.
     */
    val line: Lazy<LaneCarry.Line>,
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

    /**
     * Where the plan holds the run-up to the exit the lane choices' [key] names through the ramp after its gore
     * ([LanePlan.rampAfter]): the same object for that exit every time; null where it holds through none, and for a key
     * of no lane point of this route.
     */
    fun rampAfter(key: String?): LanePlan.RampHold? {
        val node = input.nodeOf(key) ?: return null
        return plan.rampAfter(node)
    }
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

    /** The route whose plan was answered last, made or not; null before the first answer of a drive. */
    private var answeredRouteId: String? = null

    /** Told of each answer that is taken ([deliver]): the route's id and its plan, or null where none could be made. */
    private val listeners = ArrayList<(routeId: String, plan: RouteLanePlan?) -> Unit>(1)

    /** The worker's answer for a route's plan: [plan], or null where it could not be made. */
    class Answer(val plan: RouteLanePlan?)

    /** A drive begins: its routes are on their way to the navigator, and no plan of the drive before is kept. */
    fun begin() {
        drive++
        asked = 0
        answered = 0
        held = null
        answeredRouteId = null
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
        answeredRouteId = ticket.routeId
        for (listener in listeners.toList()) listener(ticket.routeId, plan)
        return true
    }

    /**
     * The answer for the plan of [routeId] once the worker has given it, made or not; null while it is on its way, for a
     * route an answer of which a newer route's has taken over, and outside a drive.
     */
    fun answerFor(routeId: String?): Answer? = if (routeId != null && routeId == answeredRouteId) Answer(held) else null

    /** [listener] hears every answer taken from now on ([deliver]), on the main thread, until [removeListener]. */
    fun addListener(listener: (routeId: String, plan: RouteLanePlan?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (routeId: String, plan: RouteLanePlan?) -> Unit) {
        listeners.remove(listener)
    }

    /** The plan of the route [routeId], the one a progress update is of; null for any other route and while none is held. */
    fun planFor(routeId: String?): RouteLanePlan? = held?.takeIf { routeId != null && it.routeId == routeId }

    /** The drive ended: no plan of it is held, and none still on its way is taken. */
    fun end() {
        drive++
        held = null
        answeredRouteId = null
        routes.end()
    }

    private fun ticket(routeId: String?): Ticket? = routeId?.let { Ticket(it, drive, ++asked) }
}

/**
 * Where the car was when the drive changed from the route of [old] to a new one, for the hand-over of the lit
 * lane (the design's D8): [car], its latest matched place, kept in memory only, and [oldNear], its station on
 * the route before at the last update there, where known.
 */
internal class RouteChange(val old: RouteLanePlan, val car: GeoPoint, val oldNear: Double?)

/**
 * What the route lines' worker does for a new primary route [routeId], whose [legs] and steps' points
 * [shapes] the navigator already holds: reads it ([laneRouteInput]), makes its lane plan with the stations of
 * the roads without lane data that leave on an exit's side, as plain numbers, and writes its "lane_evidence"
 * line, then its "lane_plan" line, with the hand-over of the lit lane after [change], a change of route. A
 * route that cannot be read costs its line and its plan; a plan that cannot be made, or that the memory left
 * cannot hold, costs one error line and the "lane_plan" line, and the "lane_evidence" line is the first pass's
 * alone. Neither costs the drive: the job runs where nothing else would catch an error. [active] tells whether
 * the work is still wanted, between its stages.
 *
 * @param planner the planner, [LanePlanner.plan]; tests give one that fails
 * @param nanos the clock the work is timed on
 * @return the plan to hold, or null
 */
internal fun planRoute(
    routeId: String,
    legs: () -> List<RouteLeg>,
    shapes: () -> List<List<List<Point>>>,
    debugLog: DebugLog,
    change: RouteChange? = null,
    active: () -> Boolean = { true },
    planner: (LaneTopology, List<CardWindow>, List<Double>) -> LanePlan = LanePlanner::plan,
    nanos: () -> Long = System::nanoTime,
): RouteLanePlan? {
    val start = nanos()
    val input: LaneRouteInput
    val first: LaneEvidence
    try {
        input = laneRouteInput(routeId, legs(), shapes())
        first = LaneEvidence.of(input.route)
    } catch (e: RuntimeException) {
        // A route that cannot be read costs its line, never the drive.
        debugLog.error(LaneEvidenceLine.TYPE, e)
        return null
    } catch (e: OutOfMemoryError) {
        debugLog.error(LaneEvidenceLine.TYPE, e)
        return null
    }
    if (!active()) return null
    var failure: Throwable? = null
    val held = try {
        val topology = LaneTopology.of(first)
        val plan = planner(topology, CardWindows.latched(input.route), input.unknownExits)
        val line = lazy { carryLine(shapes(), input) }
        RouteLanePlan(input, plan, plan.evidence(), (nanos() - start) / NANOS_PER_MILLI, line)
    } catch (e: RuntimeException) {
        failure = e
        null
    } catch (e: OutOfMemoryError) {
        // What the plan took is free again here: it costs the plan and its error line, never the drive.
        failure = e
        null
    }
    if (!active()) return null
    try {
        debugLog.log(LaneEvidenceLine.TYPE, LaneEvidenceLine.fields(first, held?.plan, held?.evidence))
    } catch (e: RuntimeException) {
        debugLog.error(LaneEvidenceLine.TYPE, e)
    }
    // A plan that cannot be made costs this line and the plan, never the drive.
    if (failure != null) debugLog.error(LANE_PLAN_ERROR, failure)
    if (held == null) return null
    try {
        val carry = change?.let { carryAt(it, held) }
        debugLog.log(LanePlanLine.TYPE, LanePlanLine.fields(held.plan, held.evidence, held.input::keyOf, held.buildMillis, carry))
    } catch (e: RuntimeException) {
        debugLog.error(LanePlanLine.TYPE, e)
    }
    return held
}

/**
 * The hand-over of the lit lane from the plan before [change] to [new] with the car where [change] found it
 * ([LaneCarry.atChange]); null where the car is not on the new route or no lane is lit there. On the route
 * lines' worker, which makes both routes' lines.
 */
private fun carryAt(change: RouteChange, new: RouteLanePlan): LaneCarry.Carry? =
    LaneCarry.atChange(change.old.plan, change.old.line.value, change.oldNear, new.plan, new.line.value, change.car)

/** The line of the route [input] reads, from its steps' points [shapes] ([LaneCarry.Line]); a coordinate off the globe leaves its point out. */
internal fun carryLine(shapes: List<List<List<Point>>>, input: LaneRouteInput): LaneCarry.Line {
    val steps = ArrayList<List<GeoPoint>>()
    val starts = ArrayList<Double>()
    for ((l, leg) in shapes.withIndex()) {
        for ((k, step) in leg.withIndex()) {
            val start = input.station(l, k, 0.0) ?: continue
            steps.add(step.mapNotNull { it.toGeoPoint() })
            starts.add(start)
        }
    }
    return LaneCarry.Line.of(steps, starts)
}

/** Where an error line says a lane plan could not be made. */
internal const val LANE_PLAN_ERROR = "lane_plan"

private const val NANOS_PER_MILLI = 1_000_000.0
