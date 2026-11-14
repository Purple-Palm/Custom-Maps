package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.trip.model.eh.EHorizonEdge
import com.mapbox.navigation.base.trip.model.eh.EHorizonEdgeMetadata
import com.mapbox.navigation.base.trip.model.eh.EHorizonPosition
import com.mapbox.navigation.base.trip.model.eh.EHorizonResultType
import com.mapbox.navigation.base.trip.model.roadobject.RoadObjectEnterExitInfo
import com.mapbox.navigation.base.trip.model.roadobject.RoadObjectPassInfo
import com.mapbox.navigation.base.trip.model.roadobject.distanceinfo.RoadObjectDistanceInfo
import com.mapbox.navigation.core.trip.session.eh.EHorizonObserver
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdge
import io.github.purplepalm.custommaps.nav.lanes.HorizonEdges
import io.github.purplepalm.custommaps.nav.lanes.HorizonLanesLine
import io.github.purplepalm.custommaps.nav.lanes.HorizonPoint
import io.github.purplepalm.custommaps.nav.lanes.HorizonRoadClass
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Mapbox's electronic horizon, read for the app's own lanes (the own lanes' design OL9; the owner's answer 4: Settings'
 * switch Lane counts between lane points, on by default). The navigator works the horizon out from the road data it
 * already loads for the trip the driver started with Start: listening sends no request of its own, starts no trip
 * session, and changes none of the navigator's options, which are set once at its setup
 * ([io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine]). The horizon's position updates come on the main thread,
 * at most one a second; the reader there only walks the route's edges of the most probable path from the car's and hands
 * them to the route lines' worker, which reads each new edge's metadata once, lays the edges along the route from the
 * car's station, and compares them with the route's lane points ("horizon_lanes"). Edge ids, shapes, names and country
 * codes stay in memory, in this file and in [HorizonEdges]: no line holds one, and an error line names the kind of
 * error only, never its message.
 */

/** Where the car is along the route of the lane plan held, [station] metres, as a horizon update lays the horizon out from it. */
internal class HorizonCar(val plan: RouteLanePlan, val station: Double)

/** What one position update of the horizon gave, back on the main thread. */
internal class HorizonResult(
    /** The route's edges ahead, laid along the route. */
    val ahead: HorizonAhead,
    /** How many edges' metadata were read for it. */
    val reads: Int,
    /** The lane points it covered for the first time, each in one "horizon_lanes" line. */
    val compared: List<HorizonLanesLine.Comparison>,
    /** The horizon's count of lanes on the edge the car was on; null where it gives none. */
    val carEdgeLanes: Int?,
)

/**
 * Listens to the electronic horizon of navigator [N] while the engine asks it to ([listen]) and reads it, as the file's
 * notes say. [N] is the navigator itself in the app and a stand-in in tests.
 *
 * @param scope the main thread's scope: every call and every result is on the main thread
 * @param worker where the edges are read and laid out: the engine's route lines' worker, one job at a time
 * @param register registers the observer with a navigator, which activates the navigator's horizon
 * @param unregister takes it back; with no observer the horizon does not run
 * @param metadata what the horizon says of an edge, read through the navigator's graph accessor; on [worker] only
 * @param car where the car is along the route of the lane plan held, at this moment; null where no plan is held, or where
 *   the progress update the car's place comes from is one the screen leaves alone
 * @param listener hears what each update gave, on the main thread
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal class HorizonReader<N : Any>(
    private val scope: CoroutineScope,
    private val worker: CoroutineContext,
    private val debugLog: DebugLog,
    private val register: (N, EHorizonObserver) -> Unit,
    private val unregister: (N, EHorizonObserver) -> Unit,
    private val metadata: (N, Long) -> HorizonEdge?,
    private val car: () -> HorizonCar?,
    private val listener: Listener,
    private val nanos: () -> Long = System::nanoTime,
) {

    /** What the reader tells the engine, on the main thread. */
    interface Listener {
        /** A position update came while listening: [available] false for one that says the horizon has none. */
        fun onPosition(available: Boolean)

        /** An update's edges were read and laid out ([HorizonResult]). */
        fun onResult(result: HorizonResult)

        /** Reading the horizon failed: the reader stopped listening for the rest of the drive. */
        fun onFailure()
    }

    /** The navigator the observer is registered with; null while the app does not listen. */
    private var registeredOn: N? = null

    /** Counts listening spells and drives: an answer of the worker for an older one is dropped. */
    private var generation = 0

    /** Counts drives: the worker starts its edges and lane points over with a new one. */
    private var drive = 0

    /** Reading failed in this drive: no more listening until the next ([endDrive]). */
    private var failed = false

    /** The worker's job under way; null while none is. */
    private var running: Job? = null

    /** The newest update that came while a job was under way: the next job's. Older ones are dropped. */
    private var waiting: Work? = null

    /** Whether the app listens to the horizon now. */
    val listening: Boolean get() = registeredOn != null

    /** The horizon's count of lanes on the edge the car was on at the newest update taken; null where none was taken or it gave none. */
    var carEdgeLanes: Int? = null
        private set

    /** Whether the observer's time on the main thread is measured ([takeObserverTime]): with the switch Measure the drawn lanes. */
    var measuring = false
        set(value) {
            field = value
            measured = Measured()
        }

    private var measured = Measured()

    // The worker's own: read and changed inside the worker's jobs only, one at a time.
    private val edges = HorizonEdges()
    private var edgesDrive = -1
    private var pointsOf: RouteLanePlan? = null
    private var points: List<HorizonPoint> = emptyList()
    private val logged = HashSet<Int>()

    private val observer = object : EHorizonObserver {
        override fun onPositionUpdated(position: EHorizonPosition, distances: List<RoadObjectDistanceInfo>) = onPosition(position)

        // Road objects along the horizon: the app reads none of them.
        override fun onRoadObjectEnter(objectEnterExitInfo: RoadObjectEnterExitInfo) = Unit

        override fun onRoadObjectExit(objectEnterExitInfo: RoadObjectEnterExitInfo) = Unit

        override fun onRoadObjectPassed(objectPassInfo: RoadObjectPassInfo) = Unit

        override fun onRoadObjectAdded(roadObjectId: String) = Unit

        override fun onRoadObjectUpdated(roadObjectId: String) = Unit

        override fun onRoadObjectRemoved(roadObjectId: String) = Unit
    }

    /**
     * Listens on [navigator], or on none with null: registers the observer there, taking it back from a navigator it was
     * on before, or only takes it back. At once, either way; nothing still on its way from before is taken. After a failure
     * in this drive it listens nowhere.
     */
    fun listen(navigator: N?) {
        val target = navigator.takeIf { !failed }
        if (target === registeredOn) return
        registeredOn?.let(::stopOn)
        if (target != null) startOn(target)
    }

    /** A drive ended: the reader stops listening, drops whatever is on its way, and the next drive reads the horizon afresh. */
    fun endDrive() {
        listen(null)
        drive++
        generation++
        failed = false
        carEdgeLanes = null
        measured = Measured()
    }

    /**
     * The observer's time on the main thread since the last call, while [measuring]: "updates", "ms" in all and "maxMs" for
     * the longest; null where no update came.
     */
    fun takeObserverTime(): Map<String, Any?>? {
        val window = measured
        if (window.updates == 0) return null
        measured = Measured()
        return linkedMapOf(
            "updates" to window.updates,
            "ms" to Motion.round(window.nanos / NANOS_PER_MILLI, 2),
            "maxMs" to Motion.round(window.maxNanos / NANOS_PER_MILLI, 2),
        )
    }

    /** "ehLanes" for the motion object while listening: the horizon's count of lanes on the car's edge, null where none is known. */
    fun ehLanesField(): Map<String, Any?> = if (listening) mapOf("ehLanes" to carEdgeLanes) else emptyMap()

    private fun startOn(navigator: N) {
        generation++
        try {
            // The SDK hands a new observer its last position at once, from before: that position finds no navigator
            // registered yet and is left alone ([onPosition]).
            register(navigator, observer)
            registeredOn = navigator
        } catch (e: RuntimeException) {
            fail(e)
        }
    }

    private fun stopOn(navigator: N) {
        registeredOn = null
        generation++
        running?.cancel()
        running = null
        waiting = null
        carEdgeLanes = null
        try {
            unregister(navigator, observer)
        } catch (e: RuntimeException) {
            logFailure(e)
        }
    }

    /** A position update, on the main thread, as the SDK hands it on. */
    internal fun onPosition(position: EHorizonPosition) {
        // The old position the SDK hands a new observer at once, and one that comes after the app stopped listening.
        if (registeredOn == null) return
        val start = if (measuring) nanos() else 0L
        try {
            take(position)
        } catch (e: RuntimeException) {
            fail(e)
        }
        if (measuring) measured.add(nanos() - start)
    }

    private fun take(position: EHorizonPosition) {
        val navigator = registeredOn ?: return
        val available = position.eHorizonResultType != EHorizonResultType.NOT_AVAILABLE
        listener.onPosition(available)
        if (!available) {
            // Off the road or uncertain: the car's edge is not known.
            carEdgeLanes = null
            return
        }
        val car = car() ?: return
        val ids = routeEdges(position)
        if (ids.isEmpty()) {
            // Not on the route: nothing to lay along it.
            carEdgeLanes = null
            return
        }
        val work = Work(
            generation = generation,
            drive = drive,
            navigator = navigator,
            car = car,
            ids = ids,
            percentAlong = position.eHorizonGraphPosition.percentAlong,
            initial = position.eHorizonResultType == EHorizonResultType.INITIAL,
        )
        if (running != null) waiting = work else launch(work)
    }

    private fun launch(work: Work) {
        running = scope.launch {
            val result = try {
                withContext(worker) { read(work) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                if (work.generation == generation) fail(e)
                null
            } catch (e: OutOfMemoryError) {
                // What the reading took is free again here: it costs the horizon, never the drive.
                if (work.generation == generation) fail(e)
                null
            }
            if (work.generation != generation) return@launch
            running = null
            if (result != null) {
                carEdgeLanes = result.carEdgeLanes
                listener.onResult(result)
            }
            val next = waiting ?: return@launch
            waiting = null
            if (next.generation == generation) launch(next)
        }
    }

    /** One update's work, on the worker: the new edges read, the edges laid along the route, the lane points they cover compared. */
    private suspend fun read(work: Work): HorizonResult {
        if (work.drive != edgesDrive) {
            edgesDrive = work.drive
            edges.clear()
            pointsOf = null
            logged.clear()
        } else if (work.initial) {
            // A new horizon, perhaps of another version of the road data, whose ids may name other edges.
            edges.clear()
        }
        val plan = work.car.plan
        if (plan !== pointsOf) {
            pointsOf = plan
            points = HorizonLanesLine.points(plan.input.route, plan.input::keyOf)
            logged.clear()
        }
        val readsBefore = edges.reads
        val read = edges.edges(work.ids) { id -> metadata(work.navigator, id) }
        val ahead = HorizonAhead.of(plan.routeId, work.car.station, work.percentAlong, read)
        currentCoroutineContext().ensureActive()
        val compared = HorizonLanesLine.compare(points, ahead, logged)
        for (c in compared) {
            if (!currentCoroutineContext().isActive) break
            debugLog.log(HorizonLanesLine.TYPE, HorizonLanesLine.fields(c))
        }
        return HorizonResult(ahead, edges.reads - readsBefore, compared, read.firstOrNull()?.laneCount)
    }

    /** One error line, then no more listening in this drive. */
    private fun fail(e: Throwable) {
        logFailure(e)
        failed = true
        registeredOn?.let(::stopOn)
        listener.onFailure()
    }

    /** An "error" line for the horizon: the kind of error and where it was thrown, never its message, which may name an edge. */
    private fun logFailure(e: Throwable) {
        debugLog.log(
            "error",
            mapOf(
                "where" to ERROR_WHERE,
                "error" to e.javaClass.name,
                "stack" to e.stackTrace.take(STACK_FRAMES).map { it.toString() },
            ),
        )
    }

    /** One update for the worker: the route's edges of [ids] from the car's, the car [percentAlong] the first of them. */
    private inner class Work(
        val generation: Int,
        val drive: Int,
        val navigator: N,
        val car: HorizonCar,
        val ids: LongArray,
        val percentAlong: Double,
        val initial: Boolean,
    )

    /** The observer's time on the main thread over some updates. */
    private class Measured {
        var updates = 0
        var nanos = 0L
        var maxNanos = 0L

        fun add(spent: Long) {
            updates++
            nanos += spent
            maxNanos = max(maxNanos, spent)
        }
    }

    companion object {
        /** Where an error line of the horizon says it failed. */
        const val ERROR_WHERE = "horizon"

        private const val STACK_FRAMES = 12
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/**
 * The ids of the route's edges of [position]'s most probable path, from the car's edge on, while they are on the route:
 * of the paths the horizon gives (more than one where two ways are about as likely), the one that stays on the route the
 * longest. None where the car's edge is not on the route, or not in the tree.
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal fun routeEdges(position: EHorizonPosition): LongArray {
    val paths = try {
        position.eHorizon.mpp(position)
    } catch (e: IllegalArgumentException) {
        // The car's edge is not in the tree; the message names the edge, so it is not logged.
        return LongArray(0)
    }
    var best: List<EHorizonEdge> = emptyList()
    var bestCount = 0
    for (path in paths) {
        val count = path.indexOfFirst { !it.isOnRoute }.let { if (it < 0) path.size else it }
        if (count > bestCount) {
            best = path
            bestCount = count
        }
    }
    return LongArray(bestCount) { best[it].id }
}

/** What the horizon says of an edge, as the app's own lanes read it: its length, lane count, kind and class, nothing else. */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal fun EHorizonEdgeMetadata.toHorizonEdge(): HorizonEdge = HorizonEdge(
    lengthM = length,
    laneCount = laneCount?.toInt(),
    motorway = motorway,
    ramp = ramp,
    oneWay = isOneWay,
    roadClass = HorizonRoadClass.of(functionRoadClass),
)
