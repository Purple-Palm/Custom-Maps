package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.road.model.RoadComponent
import com.mapbox.navigation.base.trip.model.eh.EHorizon
import com.mapbox.navigation.base.trip.model.eh.EHorizonEdge
import com.mapbox.navigation.base.trip.model.eh.EHorizonEdgeMetadata
import com.mapbox.navigation.base.trip.model.eh.EHorizonGraphPosition
import com.mapbox.navigation.base.trip.model.eh.EHorizonPosition
import com.mapbox.navigation.base.trip.model.eh.EHorizonResultType
import com.mapbox.navigation.base.trip.model.eh.RoadSurface
import com.mapbox.navigation.core.trip.session.eh.EHorizonObserver
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonLanesLine
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.NodeManeuver
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The horizon's reading ([HorizonReader]) with stand-ins for the navigator: a made-up route with stations only (no place),
 * the navigation SDK's own horizon objects made by reflection from invented edges, and edge ids no route ever had.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class HorizonReaderTest {

    /** Runs what is handed to it when asked, in order: the main thread and the worker, each its own. */
    private class Queue : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /** A navigator by name. */
    private data class Nav(val name: String)

    private class Lines : DebugLog {
        val lines = ArrayList<Pair<String, Map<String, Any?>>>()

        override fun log(type: String, fields: Map<String, Any?>) {
            lines.add(type to fields)
        }

        fun of(type: String) = lines.filter { it.first == type }.map { it.second }
    }

    private val main = Queue()
    private val worker = Queue()
    private val log = Lines()
    private val registered = ArrayList<Nav>()
    private val unregistered = ArrayList<Nav>()
    private val asked = ArrayList<Long>()
    private val results = ArrayList<HorizonResult>()
    private val positions = ArrayList<Boolean>()
    private var failures = 0

    /** What the stand-in navigator does at registration: the SDK hands a new observer its last position at once. */
    private var onRegister: (EHorizonObserver) -> Unit = {}

    /** The metadata of each edge; an edge not here has none. */
    private val metadata = HashMap<Long, HorizonEdge>()

    /** An edge whose read throws, with its id in the message. */
    private var throwsAt: Long? = null

    private var car: HorizonCar? = null
    private var clock = 0L

    private val reader = HorizonReader<Nav>(
        scope = CoroutineScope(main + Job()),
        worker = worker,
        debugLog = log,
        register = { nav, observer ->
            registered.add(nav)
            onRegister(observer)
        },
        unregister = { nav, _ -> unregistered.add(nav) },
        metadata = { _, id ->
            asked.add(id)
            if (id == throwsAt) throw IllegalStateException("no metadata for edge $id")
            metadata[id]
        },
        car = { car },
        listener = object : HorizonReader.Listener {
            override fun onPosition(available: Boolean) {
                positions.add(available)
            }

            override fun onResult(result: HorizonResult) {
                results.add(result)
            }

            override fun onFailure() {
                failures++
            }
        },
        nanos = { clock },
    )

    private fun runAll() {
        while (main.tasks.isNotEmpty() || worker.tasks.isNotEmpty()) {
            main.runAll()
            worker.runAll()
        }
    }

    // ------------------------------------------------------------------ a made-up route and its plan

    private fun node(at: Double, lanes: Int = 0, maneuver: NodeManeuver? = null, ramp: Boolean = false) = RouteNode(
        station = at,
        lanes = List(lanes) { RouteLane(setOf(TurnModifier.STRAIGHT), valid = true, active = true) },
        bearings = listOf(180, 0),
        entry = listOf(false, true),
        inIndex = 0,
        outIndex = 1,
        freeway = !ramp,
        ramp = ramp,
        maneuver = maneuver,
    )

    /** Depart at 0; five lanes at 1,000 and at 2,000; off to the right at 3,000; the arrival at 3,500. */
    private fun route(id: String): LaneRoute = LaneRoute(
        id,
        steps = 3,
        lengthMeters = 3_500.0,
        nodes = listOf(
            node(0.0, maneuver = NodeManeuver(ManeuverType.DEPART)),
            node(1_000.0, lanes = 5),
            node(2_000.0, lanes = 5),
            node(3_000.0, lanes = 5, maneuver = NodeManeuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, viewStaysUp = true), ramp = true),
            node(3_500.0, maneuver = NodeManeuver(ManeuverType.ARRIVE), ramp = true),
        ),
    )

    private fun plan(route: LaneRoute): RouteLanePlan {
        val keys = route.nodes.indices.filter { route.nodes[it].isLanePoint }.associateBy { "${route.routeId}/0/0/$it" }
        val input = LaneRouteInput(route, listOf(doubleArrayOf(0.0, 0.0, 0.0)), keys, emptyList())
        val evidence = LaneEvidence.of(route)
        val plan = LanePlanner.plan(LaneTopology.of(evidence), CardWindows.latched(route), emptyList())
        return RouteLanePlan(input, plan, plan.evidence(), 0.0, lazy { error("not read in these tests") })
    }

    // ------------------------------------------------------------------ the SDK's own horizon objects, made by reflection

    private fun edge(id: Long, onRoute: Boolean = true, out: List<EHorizonEdge> = emptyList(), level: Int = 0): EHorizonEdge =
        EHorizonEdge::class.java
            .getConstructor(Long::class.javaPrimitiveType, Byte::class.javaPrimitiveType, Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, List::class.java)
            .newInstance(id, level.toByte(), 1.0, onRoute, out)

    /** A chain of edges [ids] in route order, each the next one's only way in; the ones in [offRoute] are not on the route. */
    private fun chain(ids: List<Long>, offRoute: Set<Long> = emptySet()): EHorizonEdge {
        var next: EHorizonEdge? = null
        for (id in ids.asReversed()) next = edge(id, id !in offRoute, listOfNotNull(next))
        return checkNotNull(next)
    }

    private fun position(start: EHorizonEdge, edgeId: Long, percentAlong: Double, type: String = EHorizonResultType.UPDATE): EHorizonPosition {
        val graph = EHorizonGraphPosition::class.java
            .getConstructor(Long::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .newInstance(edgeId, percentAlong)
        val horizon = EHorizon::class.java.getConstructor(EHorizonEdge::class.java).newInstance(start)
        return EHorizonPosition::class.java
            .getConstructor(EHorizonGraphPosition::class.java, EHorizon::class.java, String::class.java)
            .newInstance(graph, horizon, type)
    }

    private fun freeway(length: Double, lanes: Int? = 5) =
        HorizonEdge(length, lanes, motorway = true, ramp = false, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    /** Edge ids as big as the navigator's, none of them any real edge's. */
    private val a = 9_876_543_210_101L
    private val b = 9_876_543_210_202L
    private val c = 9_876_543_210_303L
    private val d = 9_876_543_210_404L
    private val trailing = 9_876_543_210_009L

    private val navA = Nav("a")
    private val navB = Nav("b")

    private fun drive(routeId: String = "route-1", station: Double = 700.0) {
        car = HorizonCar(plan(route(routeId)), station)
        metadata[a] = freeway(400.0)
        metadata[b] = freeway(1_000.0)
        metadata[c] = freeway(1_000.0, lanes = 6)
        metadata[d] = freeway(500.0, lanes = 2)
    }

    /** The car a quarter of the way along edge a, behind which the tree begins with a trailing edge, and d off the route. */
    private fun update(type: String = EHorizonResultType.UPDATE, along: Double = 0.25) =
        position(chain(listOf(trailing, a, b, c, d), offRoute = setOf(d)), a, along, type)

    // ------------------------------------------------------------------ the tests

    @Test
    fun `it listens only where asked, once, and takes the observer back at the switch off, at the drive's end and at release`() {
        assertFalse(reader.listening)
        reader.listen(navA)
        reader.listen(navA)
        assertEquals(listOf(navA), registered)
        assertTrue(reader.listening)
        // The switch goes off.
        reader.listen(null)
        assertEquals(listOf(navA), unregistered)
        assertFalse(reader.listening)
        // On again, then the drive ends.
        reader.listen(navA)
        reader.endDrive()
        assertEquals(listOf(navA, navA), unregistered)
        // Another navigator: the observer moves.
        reader.listen(navA)
        reader.listen(navB)
        assertEquals(listOf(navA, navA, navA, navB), registered)
        assertEquals(listOf(navA, navA, navA), unregistered)
        // Released: the navigator goes.
        reader.listen(null)
        assertEquals(listOf(navA, navA, navA, navB), registered)
        assertEquals(navB, unregistered.last())
        // Never asked to, it never registers.
        val quiet = HorizonReader<Nav>(CoroutineScope(main + Job()), worker, log, { _, _ -> error("registered") }, { _, _ -> error("unregistered") }, { _, _ -> null }, { null }, object : HorizonReader.Listener {
            override fun onPosition(available: Boolean) = error("position")
            override fun onResult(result: HorizonResult) = error("result")
            override fun onFailure() = error("failure")
        })
        quiet.listen(null)
        quiet.endDrive()
        assertFalse(quiet.listening)
    }

    @Test
    fun `the route's edges are laid along the route from the car, the car's edge from where the car is on it`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()

        val ahead = results.single().ahead
        assertEquals(listOf(700.0 to 1_000.0, 1_000.0 to 2_000.0, 2_000.0 to 3_000.0), ahead.stretches.map { it.start to it.end })
        assertEquals(listOf(5, 5, 6), ahead.stretches.map { it.edge.laneCount })
        assertEquals("route-1", ahead.routeId)
        // The trailing edge behind the car and the edge off the route are never read.
        assertEquals(listOf(a, b, c), asked)
        assertEquals(3, results.single().reads)
        assertEquals(5, results.single().carEdgeLanes)
        assertEquals(listOf(true), positions)
    }

    @Test
    fun `each edge is read once in a drive, again after a new horizon and in the next drive`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        reader.onPosition(update(along = 0.5))
        runAll()
        assertEquals(listOf(a, b, c), asked)
        assertEquals(0, results.last().reads)
        reader.onPosition(update(EHorizonResultType.INITIAL))
        runAll()
        assertEquals(listOf(a, b, c, a, b, c), asked)
        reader.endDrive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        assertEquals(9, asked.size)
    }

    @Test
    fun `nothing of a drive is kept after its end, the edges, the lane points and their plan`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        // Three edges read, two lane points compared, one plan's points.
        assertEquals(6, reader.heldOnWorker)
        reader.endDrive()
        runAll()
        assertEquals(0, reader.heldOnWorker)
        // The next drive, its first update right after the end, keeps its own.
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        reader.endDrive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        assertEquals(6, reader.heldOnWorker)
    }

    @Test
    fun `each lane point is compared once, and a new route's points afresh`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        reader.onPosition(update(along = 0.5))
        runAll()
        val lines = log.of(HorizonLanesLine.TYPE)
        // The points at 1,000 and 2,000; the exit's at 3,000 is where the stretches end, not 20 m past it.
        assertEquals(listOf("route-1/0/0/1", "route-1/0/0/2"), lines.map { it["key"] })
        assertEquals(listOf(5, 5), lines.map { it["count"] })
        assertEquals(listOf("all", "all"), lines.map { it["convention"] })
        assertEquals(listOf(300L, 1_300L), lines.map { it["aheadM"] })
        // A reroute: the new route's plan, its points compared again.
        car = HorizonCar(plan(route("route-2")), 700.0)
        reader.onPosition(update())
        runAll()
        assertEquals(listOf("route-2/0/0/1", "route-2/0/0/2"), log.of(HorizonLanesLine.TYPE).drop(2).map { it["key"] })
        assertEquals(listOf(2, 0, 2), results.map { it.compared.size })
    }

    @Test
    fun `no edge id reaches any line, not even an error's`() {
        drive()
        throwsAt = c
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        throwsAt = null
        // The next drive reads them all, its lane points with them.
        reader.endDrive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        assertTrue(log.lines.isNotEmpty())
        val text = log.lines.toString()
        for (id in listOf(a, b, c, d, trailing)) {
            assertFalse(id.toString() in text, "$id")
            // Nor its last digits, as a shorter number would show them.
            assertFalse(id.toString().takeLast(6) in text, "$id")
        }
        val error = log.of("error").single()
        assertEquals(HorizonReader.ERROR_WHERE, error["where"])
        assertEquals(IllegalStateException::class.java.name, error["error"])
    }

    @Test
    fun `a failure costs the horizon for the rest of the drive and one error line, never the drive`() {
        drive()
        throwsAt = b
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        assertEquals(1, failures)
        assertFalse(reader.listening)
        assertEquals(listOf(navA), unregistered)
        assertEquals(emptyList<HorizonResult>(), results)
        // The switch on again in the same drive: no listening.
        reader.listen(navA)
        assertEquals(listOf(navA), registered)
        // The next drive listens again.
        reader.endDrive()
        reader.listen(navA)
        assertEquals(listOf(navA, navA), registered)
        assertEquals(1, log.of("error").size)
    }

    @Test
    fun `the old position the SDK hands a new observer at once is left alone, as one after the switch went off`() {
        drive()
        val old = update()
        onRegister = { observer -> observer.onPositionUpdated(old, emptyList()) }
        reader.listen(navA)
        runAll()
        assertEquals(emptyList<Long>(), asked)
        assertEquals(emptyList<Boolean>(), positions)
        reader.listen(null)
        reader.onPosition(old)
        runAll()
        assertEquals(emptyList<Long>(), asked)
    }

    @Test
    fun `an update without a horizon gives nothing and forgets the car's edge, one off the route lays nothing`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        runAll()
        assertEquals(mapOf("ehLanes" to 5), reader.ehLanesField())
        reader.onPosition(update(EHorizonResultType.NOT_AVAILABLE))
        runAll()
        assertEquals(listOf(true, false), positions)
        assertEquals(1, results.size)
        assertEquals(mapOf("ehLanes" to null), reader.ehLanesField())
        // The car on an edge off the route.
        reader.onPosition(position(chain(listOf(d, a), offRoute = setOf(d)), d, 0.5))
        runAll()
        assertEquals(1, results.size)
        // No plan of the update's route held: nothing to lay along.
        car = null
        reader.onPosition(update())
        runAll()
        assertEquals(1, results.size)
        reader.listen(null)
        assertEquals(emptyMap<String, Any?>(), reader.ehLanesField())
    }

    @Test
    fun `an update that comes while the worker reads waits, only the newest, and nothing comes back after the switch off`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update(along = 0.1))
        main.runAll()
        // The worker has the first; two more come meanwhile.
        reader.onPosition(update(along = 0.2))
        reader.onPosition(update(along = 0.3))
        runAll()
        assertEquals(listOf(700.0, 700.0), results.map { it.ahead.from })
        // The car's edge from a tenth and from three tenths of its 400 m: the second update waited, the third took its place.
        assertEquals(listOf(700.0 + 0.9 * 400.0, 700.0 + 0.7 * 400.0), results.map { it.ahead.stretches[0].end })
        // The switch goes off while the worker reads: its answer is dropped.
        reader.onPosition(update(along = 0.15))
        main.runAll()
        reader.listen(null)
        runAll()
        assertEquals(2, results.size)
    }

    @Test
    fun `the observer is timed only while measuring, the time taken once`() {
        drive()
        reader.listen(navA)
        reader.onPosition(update())
        assertNull(reader.takeObserverTime())
        var t = 0L
        val timed = HorizonReader<Nav>(CoroutineScope(main + Job()), worker, log, { _, _ -> }, { _, _ -> }, { _, _ -> null }, { null }, object : HorizonReader.Listener {
            override fun onPosition(available: Boolean) {
                // Each update takes 2 ms of the main thread, then 3.
                t += if (t == 0L) 2_000_000L else 3_000_000L
            }
            override fun onResult(result: HorizonResult) = Unit
            override fun onFailure() = Unit
        }, nanos = { t })
        timed.measuring = true
        timed.listen(navA)
        timed.onPosition(update())
        timed.onPosition(update())
        assertEquals(mapOf("updates" to 2, "ms" to 5.0, "maxMs" to 3.0), timed.takeObserverTime())
        assertNull(timed.takeObserverTime())
        timed.measuring = false
        timed.onPosition(update())
        assertNull(timed.takeObserverTime())
    }

    @Test
    fun `of the paths the horizon gives, the one that stays on the route the longest is read`() {
        // At a about as likely as the route: c1 off the route after a, the route's b and c after it.
        val c1 = 9_876_543_210_505L
        val split = edge(a, out = listOf(edge(c1, onRoute = false), edge(b, out = listOf(edge(c)))))
        assertEquals(listOf(a, b, c), routeEdges(position(split, a, 0.0)).toList())
        // The car's edge not in the tree: none, and nothing thrown.
        assertEquals(0, routeEdges(position(chain(listOf(a, b)), d, 0.0)).size)
    }

    @Test
    fun `an edge's metadata keeps its length, lanes, kind and class, and nothing that names it`() {
        val names = emptyList<RoadComponent>()
        val metadata = EHorizonEdgeMetadata::class.java.constructors.first { it.parameterCount == 23 }.newInstance(
            0.0, 412.5, "TRUNK", null, 19.0, null, null,
            true, false, false, false, false,
            names, 3.toByte(), null, 0.toByte(), null, null, null,
            true, true, RoadSurface.PAVED_SMOOTH, false,
        ) as EHorizonEdgeMetadata
        assertEquals(HorizonEdge(412.5, 3, motorway = false, ramp = true, oneWay = true, roadClass = HorizonRoadClass.TRUNK), metadata.toHorizonEdge())
    }
}
