package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import com.mapbox.common.Cancelable
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.SourceDataLoadedType
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import io.github.purplepalm.custommaps.nav.lanes.HorizonEvidence
import io.github.purplepalm.custommaps.nav.lanes.geometry.CarBar
import io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneSwap
import io.github.purplepalm.custommaps.nav.lanes.geometry.PuckProjection
import io.github.purplepalm.custommaps.nav.lanes.geometry.StationTable
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficRun
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * The route line drawn by the app itself (the design's 5.3), behind the Debug switch App's own route line, which is
 * off unless turned on, and with Lanes on the map's Drawn by the app. Otherwise nothing here touches the style or the
 * navigation SDK's line.
 *
 * Turned on, during guidance, the route's path is drawn from the app's own GeoJSON source: the route's own
 * line, moved into the lanes the route's lane plan names, as a line and its casing with the SDK's widths
 * ([RouteLineWidths], held from zoom 16.5 as with lane details on), the route line's colours of the look
 * ([MapPalette.routeLine]) and the SDK's traffic levels. The SDK's primary line is hidden meanwhile
 * ([Primary.hide]); it keeps receiving the routes and the progress as always, so its hidden line stays trimmed and
 * showing it again is right at once. The SDK's other routes and its maneuver arrow are drawn as before: the own layers
 * lie in the style's middle slot right above the SDK's route line and below the arrow ([LAYERS]). Before Start, in the
 * preview, nothing changes.
 *
 * The traveled part is trimmed with the SDK's own vanishing point, a fraction of the SDK's route measured as the map
 * measures lines, taken onto the path through the path's station table ([StationTable]) and only through the table of
 * that route. Where the SDK skips its point while the drive is tracked, the app's own projection of the puck
 * ([PuckProjection]) stands in.
 *
 * The path, its table and its traffic are built once for a route, off the main thread ([Worker]), as soon as the
 * route's lane plan is there or could not be made; a traffic refresh builds the traffic again and nothing else. Per
 * accepted puck update the main thread does one lookup in the table and sets the trim of the two layers.
 *
 * A watchdog gives the line back to the SDK at once ([Fallback]): where the own source reports a loading error
 * ([onSourceError]), where the style refuses a call or a path cannot be built (an exception, caught here), or where
 * the own line has no data while guidance runs ([check]). The SDK's line is shown, the own drawing is taken away, one
 * "route_line" line says why, and the route is not tried again: the next route is. A failure costs the own line and
 * at most one error line with it, never the drive.
 *
 * At every change of route (Start, a new route, a reroute, another route taken) and at every style load, such as on a
 * map made anew, the SDK's line is shown first, already trimmed, and the own layers hidden; the new path is built off
 * the main thread and sent to the source with a data id of its own, and only once the map says that data has loaded
 * ([onSourceLoaded]) are the path's table, colours and trim taken up together, the own layers shown and the SDK's line
 * hidden again. In between the driver sees the SDK's line on the new route, never the old path, and no trim of the new
 * route is applied through the old route's table.
 *
 * The debug log gets a "route_line" line at each change of who draws the line, with the swap's times where the own
 * line took over, and the camera line a "routeLine" object once a second ([diagnostics]): numbers and words only.
 *
 * With Lanes on the map's Drawn by the app ([setLanes]) the own line draws the app's own lanes with the route
 * ([OwnLanes]): their layers ([LaneLayers]) are in the style with the own line's, added after them, empty, at every
 * style load and when either is turned on, in the look's colours. Each path is built with its drawing, and the
 * drawing's source holds one window of it at a time ([DrawingWindows]): the first goes with the path, and the swap from
 * the SDK's line waits for both to load; the next ones are built off the main thread and sent while the car drives,
 * never inside a card window ([onProgress]), each with a "lane_drawing" line of its size and of how long it took. With
 * the lanes the route line lies in two pairs of layers, one over the stretches where a lane is lit ([LIT_LAYERS]) and
 * one over the rest, each with a gradient that is transparent where the other draws, so together they are the one line
 * it was. A tint's piece the car has passed is hidden, and a lit piece takes its stretch's traffic colour, by feature
 * state, set again after each window's load. At guidance end the drawing's source is cleared. Over the lit stretches the
 * route line and the lit lane swap on the camera's state ([LaneSwap]): the pair over them is drawn whole or not at all,
 * written only when the state flips ([onLatched], [onCameraZoom]).
 *
 * The lane counts of Mapbox's electronic horizon between lane points ([HorizonEvidence], OL9) come with the next window's
 * send, never sooner and never into a window already sent: where the horizon has an update newer than the one the drawing
 * took, the window that is due is built with a path and lanes built anew on the worker, the lane counts taken only past
 * the window on screen and past a card window there, the path kept point for point up to the window's end
 * ([PathPrefix]). The path and the window go to their sources together, each with its data id, while the line drawn
 * stays as it is (the SDK's line is not shown: the old path is right until the new one has loaded); once both have
 * loaded, the station table, the gradients and the trim switch to the new path together. With the switch off the next
 * send takes nothing new past the window on screen; what was sent stays until the next route. A failure costs the
 * horizon's counts for the route and one error line, never the own line. Main thread only.
 */
internal class OwnRouteLine(
    private val layers: Layers,
    private val primary: Primary,
    private val worker: Worker,
    private val debugLog: DebugLog,
    /** The layers of the app's own lanes ([LaneLayers]), in the style with the own line's while [lanes] is on; null for none. */
    private val lanePort: LaneLayers.Port? = null,
    /** The electronic horizon's newest update along the drive's route, for the lanes' next window; null for none. */
    private val horizon: HorizonSource? = null,
    /** The clock, in nanoseconds, for how long a path has waited to load. */
    private val nanos: () -> Long = System::nanoTime,
) {

    /** The own line's source and layers in the style of one map. Every call may throw where the style refuses. */
    interface Layers {
        /** False before the map can be asked and once it is gone. */
        val usable: Boolean

        /** Whether the style has the source and every layer of [LAYERS]. */
        fun exist(): Boolean

        /** Adds the source, with nothing in it, and [layers] in their order, in [look]'s colours, shown. */
        fun add(layers: List<Layer>, look: Look)

        /** Gives the source the path through [points], as its data [dataId]: the map says when that data has loaded. */
        fun setPath(points: List<GeoPoint>, dataId: String)

        /** Whether the map is to say when the source's data has loaded ([onSourceLoaded]); asked only while a path waits. */
        fun watchLoads(watch: Boolean)

        /** Gives the source nothing to draw. */
        fun clearPath()

        /** The line's colours along the path from each of [stops] on, and the casing's [casing]. */
        fun setColours(stops: List<ColourStop>, casing: Int)

        /**
         * With the app's lanes: each of [gradients]' layers its colours along the path, a step from each of its stops on;
         * where a stop's colour is [TRANSPARENT] the layer draws nothing, its casing too.
         */
        fun setGradients(gradients: List<Gradient>)

        /** Trims every layer added from the path's start to [fraction] of its projected length. */
        fun setTrim(fraction: Double)

        /** Shows or hides every layer added. */
        fun setVisible(visible: Boolean)

        /**
         * With the app's lanes: the pair over the lit stretches ([LIT_LAYERS]) at full opacity, or none, as a step with no
         * transition: the swap of the route line and the lit lane ([LaneSwap]).
         */
        fun setLitShown(shown: Boolean)

        /** Takes the layers and the source out of the style, whichever of them is there. */
        fun remove()
    }

    /** The navigation SDK's primary route line. Its routes and progress are fed elsewhere, as always. */
    interface Primary {
        fun show()

        fun hide()
    }

    /** Work off the main thread, one job at a time. */
    interface Worker {
        /** Runs [work] off the main thread and hands its result to [done] on the main thread, unless cancelled first. */
        fun <T> run(work: () -> T, done: (Result<T>) -> Unit): Task
    }

    /** A job of the [Worker]. */
    fun interface Task {
        fun cancel()
    }

    /**
     * Builds the drawing of one path of a route, off the main thread: [version] tells it from the route's earlier ones;
     * with [lanes], the app's own lanes along it too ([OwnLineDrawing.lanes]).
     */
    fun interface Build {
        fun drawing(version: Int, lanes: Boolean): OwnLineDrawing

        /**
         * The same with the lanes drawn with the electronic horizon's counts of [horizon], the path kept as [prefix] up to its
         * end; where a build cannot take them, as without.
         */
        fun drawing(version: Int, lanes: Boolean, horizon: HorizonEvidence, prefix: PathPrefix): OwnLineDrawing = drawing(version, lanes)
    }

    /** The electronic horizon's newest update along the route [routeId], as the engine holds it; null for none. */
    fun interface HorizonSource {
        fun aheadOf(routeId: String): HorizonAhead?
    }

    /** Gives the traffic of a route along a drawing's path, off the main thread, for a refresh. */
    fun interface Traffic {
        fun runs(drawing: OwnLineDrawing): List<TrafficRun>
    }

    /**
     * One of the own line's layers as it goes into the style: in [slot], right above the layer [above], with [widths];
     * [lit] for the pair over the stretches where a lane is lit, which draws nothing until its gradient is set.
     */
    data class Layer(
        val id: String,
        val casing: Boolean,
        val above: String,
        val slot: String,
        val widths: List<RouteLineWidths.Stop>,
        val emissiveStrength: Double,
        val lit: Boolean = false,
    )

    /** From [from], a fraction of the path's projected length, the line is [colour] up to the next stop. */
    data class ColourStop(val from: Double, val colour: Int)

    /** The colours of the layer [layerId] along the path. */
    data class Gradient(val layerId: String, val stops: List<ColourStop>)

    /** Why the own line gave the route line back to the SDK, as the "route_line" line names it. */
    enum class Fallback(val logName: String) {
        /** The map reported a loading error of the own source. */
        SOURCE("source"),

        /** The style refused a call, or the route's path could not be built: an exception, caught here. */
        EXCEPTION("exception"),

        /** Guidance runs and the own line has no data: its layers left the style, or a path sent did not load in time. */
        NO_DATA("no_data"),
    }

    /** Whether the own line is on: its Debug switch, or the lanes drawn by the app. */
    var enabled = false
        private set

    /**
     * Whether the app's own lanes are drawn with the own line (Lanes on the map's Drawn by the app): their layers are in
     * the style whenever the own line's are, added after them at every style load ([LaneLayers]).
     */
    var lanes = false
        private set

    /** The latitude the lanes' widths and minimum zooms are set for. */
    private var laneLatitude = 0.0

    /**
     * Whether the lanes show ([setLanesShown]): the measuring switch hides them around every other close view. Hidden, the
     * lit lane never takes the route line's place.
     */
    private var lanesShown = true

    /** Hears each window of the lanes that loaded: from set to loaded, in milliseconds, and its size in bytes. */
    var onWindowLoad: ((loadMs: Long, bytes: Int) -> Unit)? = null

    /**
     * Hears each window that loaded with lane counts from the electronic horizon in it, or sent with a path that took some,
     * for the trip summary: how many of the horizon's stretches that path took for the first time ([HorizonEvidence.taken],
     * 0 for a later window of the same path), and whether the window [drawn] draws any of the horizon's counts.
     */
    var onHorizonWindow: ((taken: Int, drawn: Boolean) -> Unit)? = null

    private var styleReady = false
    private var released = false
    private var look = Look.NIGHT

    /** The primary route of the drive while guidance runs; null outside guidance. */
    private var routeId: String? = null

    /** What builds that route's path; null while its lane plan is on its way. */
    private var build: Build? = null

    private var traffic: Traffic? = null

    /** Counts the paths built, for their versions. */
    private var versions = 0

    /** The route's path as built; null before it is, and outside guidance. */
    private var drawing: OwnLineDrawing? = null

    /** The build or the refresh under way on the worker. */
    private var task: Task? = null

    /** A refresh came while the path was being built from the traffic before it: the traffic is read again once it is. */
    private var refreshPending = false

    /** Whether the own line is drawn and the SDK's primary line hidden. */
    var drawn = false
        private set

    /** The lanes' window the drawing's source holds now; null for none. */
    private var window: LaneWindow? = null

    /** The window sent to the source that has not loaded yet, its data id and when it was sent; null while none waits. */
    private var pendingWindow: LaneWindow? = null
    private var windowSentId: String? = null
    private var windowSentAt = 0L

    /** Why that window was sent, and how long building it took, for its "lane_drawing" line. */
    private var windowReason = REASON_ROUTE
    private var windowBuildNanos = 0L

    /** The window being built on the worker while the car drives. */
    private var windowTask: Task? = null

    /**
     * The path built anew with the electronic horizon's newest counts, sent with its window: drawn once both have loaded;
     * null while none waits.
     */
    private var nextDrawing: OwnLineDrawing? = null

    /** The horizon's update the drawing last took its counts from: a send takes only a newer one. */
    private var horizonTaken: HorizonAhead? = null

    /** Building with the horizon's counts failed for the drive's route: its windows are built without them from now on. */
    private var horizonFailed = false

    /** How many of the horizon's stretches the window on its way took, for the trip summary. */
    private var windowTaken = 0

    /** How many of the held window's tint pieces, in the order they end, are hidden behind the car. */
    private var hiddenTints = 0

    /** The windows of the drive's route that loaded, and the windows sent, for their data ids. */
    private var windowsLoaded = 0
    private var windowIds = 0

    /** When the path sent with a first window loaded while that window had not; null while no path waits for one. */
    private var pathLoadedAt: Long? = null

    /** The swap of the route line over the lit stretches and the lit lane, on the camera's state. */
    private val swap = LaneSwap()

    /** The data id of the path sent to the source that has not loaded yet; null while none waits. */
    private var sentId: String? = null

    /** When that path was sent, in [nanos]. */
    private var sentAt = 0L

    /** The route whose own line failed: it is not tried again, the next route is. */
    private var failedRouteId: String? = null

    /** How many routes of the drive the own line was given, for the "route_line" line. */
    private var routes = 0

    /** When the swap under way began (a new route, a style load), and how long the worker took for its path, in [nanos]. */
    private var swapFrom = 0L
    private var buildNanos = 0L

    // For the camera line: the trims set, the largest distance along the route between the own trim and the SDK's
    // vanishing point, and the longest time a puck update took here, since the last line.
    private var trims = 0
    private var trimLagMeters = Double.NaN
    private var trimMaxNanos = 0L

    /** Counts the paths sent to the source, for their data ids. */
    private var sent = 0

    /** The trim the layers have, and the route station it stands for. */
    private var trim = 0.0
    private var trimStation = 0.0

    // The SDK's newest vanishing point it accepted, and the newest puck, kept for a path drawn between two updates.
    private var sdkRouteId: String? = null
    private var sdkOffset = 0.0
    private var puckLatitude = Double.NaN
    private var puckLongitude = Double.NaN
    private var tracking = false

    /** On, the own line is drawn during guidance from now on; off, the SDK's line is shown and the own layers leave the style. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            if (ready()) attempt { addLayers() }
            buildPath()
        } else {
            // The SDK's line again during a drive: the line says so.
            if (routeId != null) logLine(DRAWER_SDK, REASON_SWITCH)
            // The route too is forgotten: with the switch on again the map names the route the drive is on at that time.
            failedRouteId = null
            routeId = null
            build = null
            traffic = null
            task?.cancel()
            task = null
            refreshPending = false
            drawing = null
            forgetSent()
            forgetWindow()
            swap.reset()
            showSdkLine()
            if (ready()) attempt { removeLayers() }
        }
    }

    /**
     * Lanes on the map's Drawn by the app: on, the app's own lanes are drawn with the own line from now on; off, they
     * leave the style. Nothing of them is added while the own line is off. During a drive the route's own line is built
     * and drawn anew, with or without them, and the SDK's line shows until it is.
     */
    fun setLanes(on: Boolean) {
        if (on == lanes) return
        lanes = on
        // The swap starts afresh: the camera's next word and zoom say where it stands.
        swap.reset()
        if (!enabled) return
        val had = routeId != null && !failed()
        task?.cancel()
        task = null
        refreshPending = false
        drawing = null
        forgetSent()
        forgetWindow()
        showSdkLine()
        if (had) {
            logLine(DRAWER_SDK, REASON_SWITCH)
            swapFrom = nanos()
        }
        if (ready()) {
            attempt {
                // Off, they go first: the own layers are added again without the pair of the lit stretches.
                if (!on) lanePort?.remove()
                addLayers()
            }
        }
        buildPath()
    }

    /** Call from the callback of a style load, with [look] the route line's: the new style has no own layers. */
    fun onStyleLoaded(look: Look) {
        styleReady = true
        this.look = look
        if (!enabled) return
        // The SDK's line of the new style is shown until the own line is drawn on it: whether its layers keep a hidden
        // state across a style load is not known (the design's JE 1), so it is shown at every one.
        drawn = false
        sentId = null
        // The new style's source is empty: the lanes' window the map held, or the one on its way, goes again, with the path
        // it was built with: one built with the horizon's counts that had not loaded yet too.
        val keep = pendingWindow ?: window
        val taken = if (pendingWindow != null) windowTaken else 0
        nextDrawing?.let { drawing = it }
        nextDrawing = null
        windowTask?.cancel()
        windowTask = null
        windowSentId = null
        pendingWindow = null
        window = null
        hiddenTints = 0
        pathLoadedAt = null
        if (routeId != null && !failed()) {
            logLine(DRAWER_SDK, REASON_STYLE_LOAD)
            swapFrom = nanos()
        }
        attempt { primary.show() }
        attempt { addLayers() }
        drawing?.let { send(it, keep, REASON_STYLE_LOAD, taken) }
    }

    /**
     * The drive's primary route during guidance: [routeId], a [refresh] of its traffic or not. [build] builds its path,
     * null while its lane plan is on its way ([onPlan]); [traffic] gives its traffic for a refresh. A new route shows the
     * SDK's line until its own path is drawn.
     */
    fun onRoute(routeId: String, refresh: Boolean, build: Build?, traffic: Traffic) {
        if (routeId == this.routeId) {
            this.traffic = traffic
            if (build != null && this.build == null) {
                this.build = build
                buildPath()
            } else if (refresh) {
                refreshTraffic()
            }
            return
        }
        val first = this.routeId == null
        this.routeId = routeId
        this.build = build
        this.traffic = traffic
        routes++
        task?.cancel()
        task = null
        refreshPending = false
        drawing = null
        // The new route's own line is trimmed from its start until the SDK or the puck says where the car is on it.
        trimStation = 0.0
        windowsLoaded = 0
        if (!enabled) return
        // The old route's path may still be loading: its data is no longer wanted, nor its lanes' window, and its pieces'
        // feature states go with it: the new route's pieces may have the same ids.
        forgetSent()
        forgetWindow()
        if (lanes && ready()) quietly { lanePort?.resetStates() }
        showSdkLine()
        swapFrom = nanos()
        logLine(DRAWER_SDK, if (first) REASON_START else REASON_ROUTE_CHANGE)
        buildPath()
    }

    /** The lane plan of the route [routeId] is there, or could not be made: [build] builds its path. */
    fun onPlan(routeId: String, build: Build) {
        if (routeId != this.routeId || this.build != null) return
        this.build = build
        buildPath()
    }

    /**
     * A puck update the SDK's route line was given: [accepted] where the SDK moved its vanishing point, [offset] on the
     * route [sdkRouteId], or skipped it; [tracking] while the navigator follows the car on the route. Sets the own line's
     * trim: the SDK's point through the path's station table, or where the SDK skipped it while [tracking], the app's
     * own projection of the puck.
     */
    fun onPuck(latitude: Double, longitude: Double, accepted: Boolean, sdkRouteId: String?, offset: Double, tracking: Boolean) {
        if (!enabled) return
        puckLatitude = latitude
        puckLongitude = longitude
        this.tracking = tracking
        if (accepted && sdkRouteId != null) {
            this.sdkRouteId = sdkRouteId
            sdkOffset = offset
        }
        val drawing = drawing ?: return
        if (!drawn) return
        val start = nanos()
        trimStation(drawing, accepted)?.let { setTrim(drawing, it) }
        // How far the own line's trimmed end lies from the SDK's vanishing point, along the route, where it is this route's.
        if (this.sdkRouteId == drawing.table.routeId) {
            val lag = abs(trimStation - drawing.table.stationAt(sdkOffset))
            if (trimLagMeters.isNaN() || lag > trimLagMeters) trimLagMeters = lag
        }
        trimMaxNanos = max(trimMaxNanos, nanos() - start)
    }

    /**
     * The map loaded the source's data [dataId]: where it is the path sent last, its table, colours and trim are taken
     * up together, the own layers shown and the SDK's line hidden. Data sent before it, or by another route, changes
     * nothing.
     */
    fun onSourceLoaded(dataId: String) {
        val loadedAt = nanos()
        when (dataId) {
            windowSentId -> windowLoaded(loadedAt)
            sentId -> {
                forgetSent()
                pathLoadedAt = loadedAt
            }
            else -> return
        }
        // A path built with the horizon's counts and its window switch together, once both have loaded.
        if (nextDrawing != null) {
            if (sentId == null && windowSentId == null) swapToNext()
            return
        }
        val drawing = drawing ?: return
        val path = pathLoadedAt ?: return
        if (!enabled || !ready() || drawn) return
        // With the lanes the path waits for the window sent with it: both are drawn together.
        if (windowSentId != null) return
        pathLoadedAt = null
        show(drawing, max(path, loadedAt))
    }

    /**
     * Shows the app's lanes, or hides them ([lanesShown]): every layer of theirs draws nothing, and the route line stays
     * whole over the lit stretches, so the car is the dot. The windows, the trim and the states go on as ever, and shown
     * again the lanes are as they would have been. Written only where it changes, never per frame.
     */
    fun setLanesShown(shown: Boolean) {
        if (shown == lanesShown) return
        lanesShown = shown
        if (!lanes || !ready()) return
        attempt {
            showLanes()
            // Hidden, the pair over the lit stretches is whole; shown again, as the swap says.
            if (drawn) layers.setLitShown(!(shown && swap.swapped))
        }
    }

    /** Every layer of the lanes shown or hidden as [lanesShown] says, what is lit only on a line drawn for the route. */
    private fun showLanes() {
        val port = lanePort ?: return
        port.setVisible(lanesShown)
        port.setLitVisible(lanesShown && drawn)
    }

    /**
     * The camera's word, once per progress update, whether a close view or the junction view is latched: with the lanes,
     * the swap's state, the line's opacity written where it flips.
     */
    fun onLatched(latched: Boolean) {
        if (!enabled || !lanes) return
        if (swap.onLatched(latched)) applySwap()
    }

    /**
     * The camera's zoom at a change of the camera, its center at [latitude]: with the lanes, how wide a drawn lane is for
     * the swap; the line's opacity written only where its state flips, at most twice an approach.
     */
    fun onCameraZoom(zoom: Double, latitude: Double) {
        if (!enabled || !lanes) return
        if (swap.onZoom(zoom, latitude)) applySwap()
    }

    /**
     * Whether the lit lane shows in the route line's place now ([LaneSwap]): with the lanes, on a line drawn for the route
     * the drive is on. Only while it is does the car show as a bar across the lanes ([carBar]).
     */
    val swapped: Boolean get() = enabled && lanes && drawn && lanesShown && swap.swapped

    /**
     * The car's bar along the window of the lanes the map holds ([LaneWindow.carBar]), with the lanes drawn for the route the
     * drive is on; null otherwise. A lookup, read every frame.
     */
    val carBar: CarBar? get() = if (enabled && lanes && drawn) window?.carBar else null

    /** The swap's state on the drawn line; a line not drawn takes it when it is ([show]). */
    private fun applySwap() {
        // Hidden lanes keep the line whole whatever the camera says: it was written whole as they hid.
        if (!drawn || !ready() || !lanesShown) return
        attempt { layers.setLitShown(!swap.swapped) }
    }

    /**
     * The arrow of the maneuver that begins step [step] of leg [leg] of the route [routeId], laid along the path where
     * that maneuver lies at a gore of the app's lanes ([OwnLanes.arrows]); null unless the own line is drawn with its
     * lanes for that route, and for a maneuver at no gore of theirs, where the navigation SDK's own arrow is the right
     * one. A lookup, once per progress update.
     */
    fun goreArrow(routeId: String, leg: Int, step: Int): List<GeoPoint>? {
        if (!enabled || !lanes || !drawn) return null
        val drawing = drawing ?: return null
        if (drawing.routeId != routeId) return null
        return drawing.lanes?.arrows?.get(OwnLanes.StepAt(leg, step))
    }

    /**
     * Once per progress update, with the app's lanes drawn: the tint pieces the car has passed are hidden, and where the
     * next window is due ([DrawingWindows.due]) it is built off the main thread and sent, unless the car has meanwhile
     * entered a card window.
     */
    fun onProgress() {
        if (!enabled || !lanes || !drawn) return
        val drawing = drawing ?: return
        val own = drawing.lanes ?: return
        val car = trimStation
        hideTintsBehind(car)
        if (windowTask != null || windowSentId != null || nextDrawing != null) return
        if (!own.windows.due(car, (pendingWindow ?: window)?.window, changing = false)) return
        if (withHorizon(drawing, own, car)) return
        val wanted = own.windows.windowAt(car)
        windowTask = worker.run({
            val from = nanos()
            own.window(wanted, drawing.table, drawing.runs, drawing.horizon) to nanos() - from
        }) { result ->
            windowTask = null
            result.fold(
                onSuccess = { (built, took) -> takeWindow(drawing, own, built, took) },
                // The route costs its own line, with this one error line.
                onFailure = { error ->
                    debugLog.error(WHERE, error)
                    if (drawing === this.drawing) fallBack(Fallback.EXCEPTION)
                },
            )
        }
    }

    /**
     * The route line's colours of [look] from now on. Given with the switch off too: the look is only kept while it is
     * off, and the own layers are added and drawn in it once the switch is turned on.
     */
    fun setLook(look: Look) {
        if (look == this.look) return
        this.look = look
        if (!enabled) return
        // The lanes' layers take the look whether or not a route is drawn.
        if (lanes && lanePort != null && ready()) attempt { lanePort.setColours(MapPalette.lanes(look)) }
        if (!drawn) return
        val drawing = drawing ?: return
        attempt { setColours(drawing) }
    }

    /**
     * The map reported a loading error of the own source: the SDK's line again at once, and the own drawing taken
     * away, while a route is drawn or on its way.
     */
    fun onSourceError() {
        if (enabled && routeId != null) fallBack(Fallback.SOURCE)
    }

    /**
     * Once a second during guidance: the own line falls back where it should be drawn and has no data, as when its
     * layers have left the style, or a path sent has not loaded within [LOAD_WAIT_NANOS].
     */
    fun check() {
        if (!enabled || routeId == null || failed() || !ready()) return
        attempt {
            val lost = drawn && (!layers.exist() || (lanes && lanePort?.exist() == false))
            val now = nanos()
            val late = (sentId != null && now - sentAt > LOAD_WAIT_NANOS) || (windowSentId != null && now - windowSentAt > LOAD_WAIT_NANOS)
            if (lost || late) fallBack(Fallback.NO_DATA)
        }
    }

    /** Guidance ended: the SDK's line is shown, as it is in the preview, and the own source is cleared. */
    fun onGuidanceEnded() {
        val had = routeId != null || drawn || task != null
        if (enabled && had) logLine(DRAWER_SDK, REASON_GUIDANCE_END)
        failedRouteId = null
        routes = 0
        routeId = null
        build = null
        traffic = null
        task?.cancel()
        task = null
        refreshPending = false
        drawing = null
        sdkRouteId = null
        trimStation = 0.0
        windowsLoaded = 0
        forgetWindow()
        swap.reset()
        if (!enabled || !had) return
        forgetSent()
        showSdkLine()
        if (ready()) {
            attempt { layers.clearPath() }
            // The drawing's source too: the next drive begins with nothing of this one's lanes.
            if (lanes) attempt { lanePort?.clear() }
        }
    }

    /**
     * The camera line's "routeLine", once a second while the switch is on: [drawn] as "own", the "trims" set, "trimLagM",
     * the largest distance along the route between the own line's trimmed end and the SDK's vanishing point, and
     * "trimMaxMs", the longest a puck update took here on the main thread; the last two left out without a sample. Counted
     * afresh from here.
     */
    fun diagnostics(): Map<String, Any?> {
        val fields = mapOf(
            "own" to drawn,
            "trims" to trims,
            "trimLagM" to trimLagMeters.takeUnless { it.isNaN() }?.let { Motion.round(it, 1) },
            "trimMaxMs" to trimMaxNanos.takeIf { it > 0L }?.let { Motion.round(it / NANOS_PER_MILLI, 2) },
            // With the lanes: whether the lit lane shows in the line's place, and how often that flipped this second.
            "swapped" to swap.swapped.takeIf { lanes },
            "swaps" to swap.takeFlips().takeIf { lanes },
        ).filterValues { it != null }
        trims = 0
        trimLagMeters = Double.NaN
        trimMaxNanos = 0L
        return mapOf("routeLine" to fields)
    }

    /** The map is going away: nothing is built or set any more. */
    fun release() {
        task?.cancel()
        task = null
        forgetSent()
        forgetWindow()
        released = true
    }

    /** The own line's layers in their order, the bottom one first: the pair of the lit stretches too while [lanes] is on. */
    val layerIds: List<String> get() = routeLayers().map { it.id }

    private fun ready() = styleReady && !released && layers.usable

    /** The own line's layers: the pair of the lit stretches over the other pair while [lanes] is on. */
    private fun routeLayers(): List<Layer> = if (lanes) LAYERS + LIT_LAYERS else LAYERS

    /**
     * The own line's layers, and with [lanes] the lanes' after them: in the order they have to be added. The lanes' go
     * first where they are there, as the own line's source goes with its layers.
     */
    private fun addLayers() {
        if (lanes) lanePort?.remove()
        layers.add(routeLayers(), look)
        addLanes()
    }

    /**
     * The lanes' layers, empty, under the route line and over the own line ([LaneLayers.positions]), if [lanes] is on;
     * hidden again at once while the lanes are ([lanesShown]).
     */
    private fun addLanes() {
        if (!lanes) return
        lanePort?.add(LaneLayers.positions(LaneLayers.PLACEMENT, routeLayers().last().id), MapPalette.lanes(look), laneLatitude)
        if (!lanesShown) showLanes()
    }

    private fun removeLayers() {
        if (lanes) lanePort?.remove()
        layers.remove()
    }

    /** Whether the own line of the route the drive is on failed. */
    private fun failed() = routeId != null && routeId == failedRouteId

    /** Builds the path of the route the drive is on, if its plan is there, the switch is on and no path is built yet. */
    private fun buildPath() {
        val build = build ?: return
        val routeId = routeId ?: return
        if (!enabled || drawing != null || failed()) return
        val version = ++versions
        val withLanes = lanes
        // Where the car is, as the main thread knows it now: the lanes' first window is built around it.
        val car = CarNow(sdkRouteId, sdkOffset, puckLatitude, puckLongitude, trimStation)
        task?.cancel()
        task = worker.run({
            val from = nanos()
            val built = build.drawing(version, withLanes)
            val took = nanos() - from
            val first = built.lanes?.let { own ->
                val start = nanos()
                own.window(own.windows.windowAt(car.station(built)), built.table, built.runs) to nanos() - start
            }
            Triple(built, took, first)
        }) { result ->
            task = null
            result.fold(
                onSuccess = { (built, took, first) ->
                    // A path of a route the drive has left, or built after a switch changed, is dropped.
                    if (enabled && withLanes == lanes && built.routeId == routeId && routeId == this.routeId) {
                        buildNanos = took
                        windowBuildNanos = first?.second ?: 0L
                        drawing = built
                        send(built, first?.first, REASON_ROUTE)
                        if (refreshPending) {
                            refreshPending = false
                            refreshTraffic()
                        }
                    }
                },
                // The route costs its own line, with this one error line.
                onFailure = { error ->
                    debugLog.error(WHERE, error)
                    if (routeId == this.routeId) fallBack(Fallback.EXCEPTION)
                },
            )
        }
    }

    /** A refresh: the traffic again along the path drawn, and the line's colours with it. */
    private fun refreshTraffic() {
        if (!enabled) return
        val traffic = traffic ?: return
        val old = drawing
        if (old == null) {
            // A path under way was built from the traffic before this refresh.
            refreshPending = task != null
            return
        }
        // A path built with the horizon's counts waits to load: the refresh comes once it is drawn.
        if (nextDrawing != null) {
            refreshPending = true
            return
        }
        val held = window
        task?.cancel()
        task = worker.run({
            val runs = traffic.runs(old)
            // The lit pieces of the window the map holds take the new traffic too.
            old.withRuns(runs) to held?.let { w -> old.lanes?.levels(w, old.table, runs) }
        }) { result ->
            task = null
            result.fold(
                onSuccess = { (refreshed, levels) ->
                    if (enabled && drawing === old) {
                        drawing = refreshed
                        if (drawn) {
                            attempt {
                                setColours(refreshed)
                                if (lanes && levels != null && held === window) lanePort?.setStates(emptyList(), levels)
                            }
                        }
                    }
                },
                onFailure = { debugLog.error(WHERE, it) },
            )
        }
    }

    /**
     * Sends [drawing]'s path to the source as data of its own, and with the lanes the window [first] to theirs: both are
     * drawn once the map says both have loaded ([onSourceLoaded]). Until they have, the own layers stay hidden and the
     * SDK's line shown.
     */
    private fun send(drawing: OwnLineDrawing, first: LaneWindow?, reason: String, taken: Int = 0) {
        if (!ready() || failed()) return
        val dataId = "$DATA_ID${++sent}"
        sentId = dataId
        sentAt = nanos()
        pathLoadedAt = null
        attempt {
            layers.watchLoads(true)
            layers.setPath(drawing.points, dataId)
            if (lanes && drawing.lanes != null && first != null) sendWindow(first, reason, taken)
        }
    }

    /**
     * Sends [window] to the drawing's source as data of its own, the layers' widths first where its latitude asks; [taken]
     * of the horizon's stretches went into it, for the trip summary.
     */
    private fun sendWindow(window: LaneWindow, reason: String, taken: Int = 0) {
        val port = lanePort ?: return
        val dataId = "$WINDOW_ID${++windowIds}"
        pendingWindow = window
        windowSentId = dataId
        windowSentAt = nanos()
        windowReason = reason
        windowTaken = taken
        if (!window.latitude.isNaN() && LaneDrawing.stopsStale(laneLatitude, window.latitude)) {
            laneLatitude = window.latitude
            port.setLatitude(window.latitude)
        }
        port.watchLoads(true)
        port.setWindow(window.collection, dataId)
    }

    /**
     * The window [built] for [drawing], made off the main thread in [took]: sent while it is still the path drawn and
     * still due, so never inside a card window; a window with nothing to draw over one with nothing either is only kept.
     */
    private fun takeWindow(drawing: OwnLineDrawing, own: OwnLanes, built: LaneWindow, took: Long) {
        if (!enabled || !lanes || !drawn || drawing !== this.drawing || windowSentId != null) return
        if (!own.windows.due(trimStation, (pendingWindow ?: window)?.window, changing = false)) return
        windowBuildNanos = took
        if (built.features == 0 && (window?.features ?: 0) == 0) {
            window = built
            hiddenTints = 0
            return
        }
        attempt { sendWindow(built, REASON_AHEAD) }
    }

    /**
     * Where the electronic horizon has an update newer than the one [drawing] took, or the switch went off after it took
     * some, builds the window that is due with a path and lanes built anew on the worker (with [car] the car's station),
     * and returns true; false where the window is built as before. The lane counts are taken only past the window on
     * screen ([HorizonEvidence.next]), and the path is kept up to its end ([PathPrefix]). Nothing is built while nothing is
     * drawn at the car, nor after a failure on this route.
     */
    private fun withHorizon(drawing: OwnLineDrawing, own: OwnLanes, car: Double): Boolean {
        val held = drawing.horizon ?: return false
        val source = horizon ?: return false
        val shown = window ?: return false
        // Nothing drawn at the car, as after a seek of a simulated drive: the window there goes as before, without a path
        // anew, which would change the line where the car is; the horizon's counts come with the next window, past it.
        if (!shown.window.holds(car)) return false
        val build = build ?: return false
        if (horizonFailed || drawing.stations.size != drawing.points.size) return false
        val ahead = source.aheadOf(drawing.routeId)?.takeIf { it.routeId == drawing.routeId }
        if (ahead != null && ahead === horizonTaken) return false
        val windowEnd = shown.window.to
        // With nothing from the horizon now (the switch off), only a drawing that took counts past the window is built again.
        if (ahead == null && held.lanes.from(HorizonEvidence.startFor(windowEnd, own.windows.cards)).between.isEmpty()) return false
        val version = ++versions
        val prefix = PathPrefix(drawing.points, drawing.stations, windowEnd)
        val traffic = traffic
        windowTask = worker.run({
            val from = nanos()
            val next = held.next(ahead, windowEnd, own.windows.cards)
            val built = build.drawing(version, true, next, prefix)
            // The traffic of the route as it is now, a refresh's too.
            val rebuilt = traffic?.let { built.withRuns(it.runs(built)) } ?: built
            val lanes = checkNotNull(rebuilt.lanes) { "No lanes" }
            Triple(rebuilt, lanes.window(lanes.windows.windowAt(car), rebuilt.table, rebuilt.runs, rebuilt.horizon), nanos() - from)
        }) { result ->
            windowTask = null
            result.fold(
                onSuccess = { (rebuilt, built, took) -> takeHorizon(drawing, rebuilt, built, took, ahead) },
                // The horizon's counts cost only themselves: one error line, and the route's windows are built without them.
                onFailure = { error ->
                    debugLog.error(WHERE, error)
                    if (drawing === this.drawing) horizonFailed = true
                },
            )
        }
        return true
    }

    /**
     * The path [rebuilt] with the horizon's counts of [ahead] and its window [built], made off the main thread in [took] for
     * [old]: sent together while [old] is still the path drawn and the window still due, so never inside a card window
     * and never during a route change; the line stays as it is until both have loaded ([swapToNext]).
     */
    private fun takeHorizon(old: OwnLineDrawing, rebuilt: OwnLineDrawing, built: LaneWindow, took: Long, ahead: HorizonAhead?) {
        if (!enabled || !lanes || !drawn || old !== drawing || windowSentId != null || sentId != null || nextDrawing != null) return
        val own = old.lanes ?: return
        if (!own.windows.due(trimStation, window?.window, changing = false)) return
        horizonTaken = ahead
        windowBuildNanos = took
        nextDrawing = rebuilt
        val dataId = "$DATA_ID${++sent}"
        sentId = dataId
        sentAt = nanos()
        pathLoadedAt = null
        attempt {
            layers.watchLoads(true)
            layers.setPath(rebuilt.points, dataId)
            sendWindow(built, REASON_HORIZON, rebuilt.horizon?.taken ?: 0)
        }
    }

    /**
     * The path built with the horizon's counts and its window have both loaded: it is the path drawn from now on, its
     * station table, gradients and trim taken up together. Up to the end of the window it replaced it is the same, point
     * for point, so the line does not move where the driver sees it.
     */
    private fun swapToNext() {
        val next = nextDrawing ?: return
        nextDrawing = null
        pathLoadedAt = null
        if (!enabled || !ready() || !drawn) return
        drawing = next
        attempt {
            setColours(next)
            val station = trimStation(next, accepted = sdkRouteId == next.routeId) ?: trimStation
            trimStation = station
            trim = next.table.pathFraction(station)
            layers.setTrim(trim)
            if (lanes) lanePort?.setEraserTrim(trim)
        }
        if (refreshPending) {
            refreshPending = false
            refreshTraffic()
        }
    }

    /** The window sent last has loaded: the source holds it from now on, its line is written and its states set. */
    private fun windowLoaded(loadedAt: Long) {
        windowSentId = null
        quietly { lanePort?.watchLoads(false) }
        val loaded = pendingWindow ?: return
        pendingWindow = null
        window = loaded
        hiddenTints = 0
        windowsLoaded++
        val loadMs = Math.round((loadedAt - windowSentAt) / NANOS_PER_MILLI)
        debugLog.log(
            LANE_DRAWING,
            mapOf(
                "route" to routes,
                "window" to windowsLoaded,
                "reason" to windowReason,
                "lengthM" to Math.round(loaded.window.meters),
                "features" to loaded.features,
                "coordinates" to loaded.coordinates,
                "bytes" to loaded.bytes,
                "buildMs" to Math.round(windowBuildNanos / NANOS_PER_MILLI),
                "loadMs" to loadMs,
                "budget" to loaded.withinBudget,
                // What the electronic horizon's counts drew in it: metres of its bridges and zones, and its zones.
                "horizonM" to Math.round(loaded.horizonMeters),
                "horizonZones" to loaded.horizonZones,
            ),
        )
        onWindowLoad?.invoke(loadMs, loaded.bytes)
        // The stretches a path took are counted with its window, wherever in the path they lie.
        if (loaded.horizonMeters > 0.0 || windowTaken > 0) onHorizonWindow?.invoke(windowTaken, loaded.horizonMeters > 0.0)
        windowTaken = 0
        // Whether feature states outlive a new window's data is not known: they are set again for every window.
        if (drawn) applyStates(loaded)
    }

    /** The feature states of [window]: its tint pieces the car has passed hidden, its lit pieces' traffic. */
    private fun applyStates(window: LaneWindow) {
        val port = lanePort ?: return
        var k = 0
        while (k < window.tints.size && window.tints[k].to <= trimStation) k++
        hiddenTints = k
        val behind = window.tints.subList(0, k).map { it.id }
        if (behind.isNotEmpty() || window.levels.isNotEmpty()) attempt { port.setStates(behind, window.levels) }
    }

    /** Hides the held window's tint pieces that end at or behind [car] and are not hidden yet: a few calls a second at most. */
    private fun hideTintsBehind(car: Double) {
        val held = window ?: return
        var k = hiddenTints
        while (k < held.tints.size && held.tints[k].to <= car) k++
        if (k == hiddenTints) return
        val passed = held.tints.subList(hiddenTints, k).map { it.id }
        hiddenTints = k
        attempt { lanePort?.setStates(passed, emptyList()) }
    }

    /**
     * No window waits or is built any more, and none is held: a new route's, or none at all, comes next; nor does a path
     * built with the horizon's counts, nor anything the route took from the horizon.
     */
    private fun forgetWindow() {
        windowTask?.cancel()
        windowTask = null
        nextDrawing = null
        horizonTaken = null
        horizonFailed = false
        windowTaken = 0
        val waited = windowSentId != null
        windowSentId = null
        pendingWindow = null
        window = null
        hiddenTints = 0
        pathLoadedAt = null
        if (waited && !released) quietly { lanePort?.watchLoads(false) }
    }

    /**
     * The colours of [drawing]'s line: with the lanes in its two pairs of layers, and the eraser in the road's colour
     * over the lit stretches; else in the one pair.
     */
    private fun setColours(drawing: OwnLineDrawing) {
        if (lanes) {
            val lit = drawing.lanes?.lit.orEmpty()
            layers.setGradients(laneGradients(drawing.runs, lit, look))
            lanePort?.setEraser(eraserGradient(lit, MapPalette.lanes(look).asphalt))
        } else {
            layers.setColours(colourStops(drawing.runs, look), casingColour(look))
        }
    }

    /** No data sent waits any more: a load that comes after is not drawn, and the map is not asked about loads. */
    private fun forgetSent() {
        val waited = sentId != null
        sentId = null
        if (waited && !released) quietly { layers.watchLoads(false) }
    }

    /** Shows [drawing], whose path the source has loaded: its colours and trim, the own layers, and the SDK's line hidden. */
    private fun show(drawing: OwnLineDrawing, loadedAt: Long) {
        attempt {
            setColours(drawing)
            val station = trimStation(drawing, accepted = sdkRouteId == drawing.routeId) ?: 0.0
            trim = drawing.table.pathFraction(station)
            trimStation = station
            layers.setTrim(trim)
            // The eraser over the part behind the car, with the same fraction: the lit lane ends where the line does.
            if (lanes) lanePort?.setEraserTrim(trim)
            layers.setVisible(true)
            if (lanes) {
                layers.setLitShown(!(swap.swapped && lanesShown))
                lanePort?.setLitVisible(lanesShown)
                window?.let(::applyStates)
            }
            primary.hide()
            drawn = true
            logLine(
                DRAWER_OWN,
                REASON_LOADED,
                mapOf(
                    "swapMs" to Math.round((loadedAt - swapFrom) / NANOS_PER_MILLI),
                    "loadMs" to Math.round((loadedAt - sentAt) / NANOS_PER_MILLI),
                    "buildMs" to Math.round(buildNanos / NANOS_PER_MILLI),
                    "points" to drawing.points.size,
                    "version" to drawing.version,
                ),
            )
        }
    }

    /**
     * Where along the route the own line is trimmed: the SDK's vanishing point where it [accepted] one of this route,
     * else, while the drive is tracked, the app's own projection of the puck; null where neither says.
     */
    private fun trimStation(drawing: OwnLineDrawing, accepted: Boolean): Double? = when {
        // The SDK's point through this path's table only where the SDK's route is this one.
        accepted && sdkRouteId == drawing.table.routeId -> drawing.table.stationAt(sdkOffset)
        tracking && !puckLatitude.isNaN() -> drawing.projection.station(puckLatitude, puckLongitude, trimStation)
        else -> null
    }

    private fun setTrim(drawing: OwnLineDrawing, station: Double) {
        val fraction = drawing.table.pathFraction(station)
        trimStation = station
        if (fraction == trim) return
        trim = fraction
        trims++
        attempt {
            layers.setTrim(fraction)
            if (lanes) lanePort?.setEraserTrim(fraction)
        }
    }

    /** The SDK's line is shown and the own layers hidden. */
    private fun showSdkLine() {
        drawn = false
        quietly { primary.show() }
        if (ready()) {
            quietly { layers.setVisible(false) }
            // What is lit for the route the map holds goes with its line; its road stays until the next window.
            if (lanes) quietly { lanePort?.setLitVisible(false) }
        }
    }

    /**
     * Runs [change] on the style; where the style refuses, the error goes to the debug log and the own line falls back
     * to the SDK's for the route ([fallBack]); without a route, the SDK's line is shown all the same.
     */
    private inline fun attempt(change: () -> Unit) {
        try {
            change()
        } catch (e: RuntimeException) {
            debugLog.error(WHERE, e)
            if (enabled && routeId != null) {
                fallBack(Fallback.EXCEPTION)
            } else {
                drawn = false
                quietly { primary.show() }
                if (ready()) quietly { layers.setVisible(false) }
            }
        }
    }

    /**
     * The watchdog: the SDK's line at once, the own drawing taken away (its layers hidden, its source cleared, nothing
     * waiting to load, no path kept), one "route_line" line with [cause], and no other try for the route the drive is
     * on; the next route gets one. Once per route.
     */
    private fun fallBack(cause: Fallback) {
        if (failed()) return
        failedRouteId = routeId
        task?.cancel()
        task = null
        refreshPending = false
        drawing = null
        val waited = sentId != null
        sentId = null
        drawn = false
        quietly { primary.show() }
        forgetWindow()
        if (ready()) {
            if (waited) quietly { layers.watchLoads(false) }
            quietly { layers.setVisible(false) }
            quietly { layers.clearPath() }
            if (lanes) quietly { lanePort?.clear() }
        }
        logLine(DRAWER_SDK, REASON_FALLBACK, mapOf("ownLineFallback" to cause.logName))
    }

    /** A "route_line" line: who draws the route line from now on ([drawer]), why, the drive's route by its count, and [more]. */
    private fun logLine(drawer: String, reason: String, more: Map<String, Any?> = emptyMap()) {
        debugLog.log(ROUTE_LINE, mapOf("drawer" to drawer, "reason" to reason, "route" to routes) + more)
    }

    /**
     * Where the car is, as the main thread knew it when a path was sent to be built: the SDK's vanishing point
     * [sdkOffset] on [sdkRouteId], the puck, and the trim's station. Read on the worker against the built path.
     */
    private class CarNow(
        private val sdkRouteId: String?,
        private val sdkOffset: Double,
        private val latitude: Double,
        private val longitude: Double,
        private val trimStation: Double,
    ) {
        /** The car's station along [drawing]'s route: the SDK's point where it is this route's, else the puck's. */
        fun station(drawing: OwnLineDrawing): Double = when {
            sdkRouteId == drawing.table.routeId -> drawing.table.stationAt(sdkOffset)
            !latitude.isNaN() -> drawing.projection.station(latitude, longitude, trimStation) ?: trimStation
            else -> trimStation
        }
    }

    /** Runs [change] on the style while the own line falls back: what still fails is only written down. */
    private inline fun quietly(change: () -> Unit) {
        try {
            change()
        } catch (e: RuntimeException) {
            debugLog.error(WHERE, e)
        }
    }

    companion object {
        /** Where an error line says the own route line failed. */
        const val WHERE = "ownRouteLine"

        /** The debug log's line about who draws the route line, and its words. */
        const val ROUTE_LINE = "route_line"
        const val DRAWER_SDK = "sdk"
        const val DRAWER_OWN = "own"

        /** The own line begins with the drive's route: at Start, on a map made anew, or with the switch turned on during a drive. */
        const val REASON_START = "start"
        const val REASON_ROUTE_CHANGE = "route_change"
        const val REASON_LOADED = "loaded"
        const val REASON_FALLBACK = "fallback"
        const val REASON_SWITCH = "switch"
        const val REASON_STYLE_LOAD = "style_load"
        const val REASON_GUIDANCE_END = "guidance_end"

        private const val NANOS_PER_MILLI = 1_000_000.0

        /** A path sent to the source that has not loaded after this long, during guidance, counts as no data: 10 s. */
        const val LOAD_WAIT_NANOS = 10_000_000_000L

        /** What the data ids of the own source's paths begin with. */
        private const val DATA_ID = "own-"

        /** What the data ids of the lanes' windows begin with. */
        private const val WINDOW_ID = "lanes-"

        /** The debug log's line of each window of the app's own lanes that loaded, and why it was sent. */
        const val LANE_DRAWING = "lane_drawing"

        /** The first window of a path: at Start, a new route, or a switch turned on. */
        const val REASON_ROUTE = "route"

        /** The next window, while the car drives. */
        const val REASON_AHEAD = "ahead"

        /** The next window, with a path built anew with the electronic horizon's newest lane counts past the window on screen. */
        const val REASON_HORIZON = "horizon"

        /** A colour that draws nothing: a layer's gradient is this where the other pair of layers draws. */
        const val TRANSPARENT = 0

        const val SOURCE = "custommaps-own-route"
        const val CASING = "custommaps-own-route-casing"
        const val LINE = "custommaps-own-route-line"

        /** Mapbox Standard's slot above the roads, the navigation SDK's route line's. */
        const val SLOT = "middle"

        /**
         * The own line's layers, the bottom one first: the casing right above the SDK's route line's top layer, the line
         * right above the casing, both below the SDK's maneuver arrow, whose layers lie above the route line's top layer.
         * Their widths are the SDK's up to zoom 16.5 and those of 16.5 from there on in, the line never narrowed;
         * emissive strength 1, so the style's light does not dim them, as the SDK's line.
         */
        val LAYERS = listOf(
            Layer(
                CASING,
                casing = true,
                above = LayerOrder.ROUTE_LINE_TOP,
                slot = SLOT,
                widths = RouteLineWidths.heldFrom(RouteLineWidths.CASING, RouteLineWidths.HOLD_ZOOM),
                emissiveStrength = 1.0,
            ),
            Layer(
                LINE,
                casing = false,
                above = CASING,
                slot = SLOT,
                widths = RouteLineWidths.heldFrom(RouteLineWidths.LINE, RouteLineWidths.HOLD_ZOOM),
                emissiveStrength = 1.0,
            ),
        )

        const val LIT_CASING = "custommaps-own-route-lit-casing"
        const val LIT_LINE = "custommaps-own-route-lit-line"

        /**
         * With the app's lanes, the route line's second pair of layers, over [LAYERS]: the line and its casing over the
         * stretches where a lane is lit, at the same widths and in the same colours, transparent everywhere else, as the
         * first pair is transparent where these draw ([laneGradients]).
         */
        val LIT_LAYERS = listOf(
            Layer(
                LIT_CASING,
                casing = true,
                above = LINE,
                slot = SLOT,
                widths = RouteLineWidths.heldFrom(RouteLineWidths.CASING, RouteLineWidths.HOLD_ZOOM),
                emissiveStrength = 1.0,
                lit = true,
            ),
            Layer(
                LIT_LINE,
                casing = false,
                above = LIT_CASING,
                slot = SLOT,
                widths = RouteLineWidths.heldFrom(RouteLineWidths.LINE, RouteLineWidths.HOLD_ZOOM),
                emissiveStrength = 1.0,
                lit = true,
            ),
        )

        /** Every layer the own line may have in the style. */
        private val ALL_LAYERS = LAYERS + LIT_LAYERS

        // The source as the SDK makes its own: geometry tiles to zoom 16, its tolerance and buffer, and line metrics
        // for the gradient and the trim.
        private const val SOURCE_MAX_ZOOM = 16L
        private const val SOURCE_TOLERANCE = 0.375
        private const val SOURCE_BUFFER = 32L

        /**
         * The line's colours along the path: each run's level in the route line's colours of [look], a stop where the
         * colour changes; one stop at the path's start for a path without traffic.
         */
        fun colourStops(runs: List<TrafficRun>, look: Look): List<ColourStop> {
            val colours = MapPalette.routeLine(look)
            val out = ArrayList<ColourStop>()
            for (run in runs) {
                val colour = colourOf(run.level, colours)
                if (out.isEmpty() || out.last().colour != colour) out.add(ColourStop(if (out.isEmpty()) 0.0 else run.from, colour))
            }
            if (out.isEmpty()) out.add(ColourStop(0.0, colourOf(TrafficLevel.UNKNOWN, colours)))
            return out
        }

        /** The casing's colour in [look]. */
        fun casingColour(look: Look): Int = MapPalette.routeLine(look).getValue(RouteLineColor.ROUTE_CASING)

        /**
         * The colours of the route line's two pairs of layers along the path, with the app's lanes: each run of [runs] in
         * the route line's colours of [look], cut where a lane's [lit] stretch (fractions of the path) begins and ends;
         * the pair of [LIT_LAYERS] draws over the lit stretches only and the first pair over the rest only, so together
         * they draw the line the first pair alone would.
         */
        fun laneGradients(runs: List<TrafficRun>, lit: List<ClosedFloatingPointRange<Double>>, look: Look): List<Gradient> {
            val colours = MapPalette.routeLine(look)
            val casing = casingColour(look)
            val cuts = lit.flatMap { listOf(it.start, it.endInclusive) }.filter { it > 0.0 && it < 1.0 }.distinct().sorted()
            // Each piece of a run between two cuts: where it begins, its colour, and whether a lane is lit there.
            val pieces = ArrayList<Triple<Double, Int, Boolean>>()
            for (run in runs.ifEmpty { listOf(TrafficRun(0.0, 1.0, TrafficLevel.UNKNOWN)) }) {
                var from = run.from
                for (x in cuts.filter { it > run.from && it < run.to } + run.to) {
                    if (x > from) {
                        val middle = (from + x) / 2.0
                        pieces.add(Triple(from, colourOf(run.level, colours), lit.any { middle >= it.start && middle <= it.endInclusive }))
                    }
                    from = x
                }
            }
            fun stops(colour: (Int, Boolean) -> Int): List<ColourStop> {
                val out = ArrayList<ColourStop>()
                for ((from, line, isLit) in pieces) {
                    val value = colour(line, isLit)
                    if (out.isEmpty()) out.add(ColourStop(0.0, value)) else if (out.last().colour != value) out.add(ColourStop(from, value))
                }
                return out.ifEmpty { listOf(ColourStop(0.0, colour(colourOf(TrafficLevel.UNKNOWN, colours), false))) }
            }
            return listOf(
                Gradient(CASING, stops { _, isLit -> if (isLit) TRANSPARENT else casing }),
                Gradient(LINE, stops { line, isLit -> if (isLit) TRANSPARENT else line }),
                Gradient(LIT_CASING, stops { _, isLit -> if (isLit) casing else TRANSPARENT }),
                Gradient(LIT_LINE, stops { line, isLit -> if (isLit) line else TRANSPARENT }),
            )
        }

        /**
         * The eraser's colours along the path ([LaneLayers.ERASER]): [asphalt] over each of the [lit] stretches (fractions
         * of the path), [TRANSPARENT] elsewhere, so it covers nothing but the lit lane.
         */
        fun eraserGradient(lit: List<ClosedFloatingPointRange<Double>>, asphalt: Int): List<ColourStop> {
            val out = arrayListOf(ColourStop(0.0, TRANSPARENT))
            for (stretch in lit.sortedBy { it.start }) {
                if (stretch.endInclusive <= stretch.start) continue
                if (stretch.start <= 0.0) out[0] = ColourStop(0.0, asphalt) else out.add(ColourStop(stretch.start, asphalt))
                if (stretch.endInclusive < 1.0) out.add(ColourStop(stretch.endInclusive, TRANSPARENT))
            }
            return out
        }

        /** [stops] as a step gradient over the line's progress, as the navigation SDK builds its own. */
        internal fun stepGradient(stops: List<ColourStop>): Expression = Expression.ExpressionBuilder("step").apply {
            lineProgress()
            color(stops.first().colour)
            for (stop in stops) stop {
                literal(stop.from)
                color(stop.colour)
            }
        }.build()

        /** The colour of [level] among the route line's [colours], as the SDK colours its line. */
        fun colourOf(level: TrafficLevel, colours: Map<RouteLineColor, Int>): Int = colours.getValue(
            when (level) {
                TrafficLevel.UNKNOWN -> RouteLineColor.ROUTE_UNKNOWN
                TrafficLevel.LOW -> RouteLineColor.ROUTE_LOW
                TrafficLevel.MODERATE -> RouteLineColor.ROUTE_MODERATE
                TrafficLevel.HEAVY -> RouteLineColor.ROUTE_HEAVY
                TrafficLevel.SEVERE -> RouteLineColor.ROUTE_SEVERE
                TrafficLevel.CLOSED -> RouteLineColor.ROUTE_CLOSED
            },
        )

        /** The own line's source and layers on [mapboxMap]; [onLoaded] hears each data id of the source that loaded. */
        fun layersOn(mapboxMap: MapboxMap, onLoaded: (dataId: String) -> Unit): Layers = object : Layers {
            /** The source in the style now; its data is set through it. */
            private var source: GeoJsonSource? = null

            /** The layers added with it, the bottom one first. */
            private var added: List<Layer> = emptyList()

            /** The map's word on loaded data, while a path waits; the SDK's own route line waits for its data the same way. */
            private var loads: Cancelable? = null

            override val usable: Boolean get() = mapboxMap.isValid()

            override fun exist(): Boolean =
                source != null && mapboxMap.styleSourceExists(SOURCE) && added.isNotEmpty() && added.all { mapboxMap.styleLayerExists(it.id) }

            override fun add(layers: List<Layer>, look: Look) {
                // One of them may be left from an addition that failed half way.
                remove()
                val created = geoJsonSource(SOURCE) {
                    lineMetrics(true)
                    maxzoom(SOURCE_MAX_ZOOM)
                    tolerance(SOURCE_TOLERANCE)
                    buffer(SOURCE_BUFFER)
                }
                mapboxMap.addSource(created)
                source = created
                for (layer in layers) {
                    mapboxMap.addLayerAbove(
                        lineLayer(layer.id, SOURCE) {
                            slot(layer.slot)
                            lineCap(LineCap.ROUND)
                            lineJoin(LineJoin.ROUND)
                            lineWidth(RouteLineWidths.expression(layer.widths))
                            lineEmissiveStrength(layer.emissiveStrength)
                            // Not faded behind 3D buildings, as the SDK's line is not.
                            lineDepthOcclusionFactor(0.0)
                            lineColor(if (layer.casing) casingColour(look) else colourStops(emptyList(), look).first().colour)
                            // The pair of the lit stretches draws nothing until the path's gradients are set, and its
                            // opacity, 1 or 0, changes as a step.
                            if (layer.lit) {
                                lineGradient(stepGradient(listOf(ColourStop(0.0, TRANSPARENT))))
                                lineOpacityTransition {
                                    duration(0L)
                                    delay(0L)
                                }
                            }
                        },
                        layer.above,
                    )
                }
                added = layers
            }

            override fun setPath(points: List<GeoPoint>, dataId: String) {
                val source = checkNotNull(source) { "No source" }
                // The source hands its data to the map on a thread of its own, the one every GeoJSON source shares.
                source.geometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }), dataId)
            }

            override fun watchLoads(watch: Boolean) {
                if (!watch) {
                    loads?.cancel()
                    loads = null
                } else if (loads == null) {
                    loads = mapboxMap.subscribeSourceDataLoaded { loaded ->
                        if (loaded.sourceId == SOURCE && loaded.type == SourceDataLoadedType.METADATA) loaded.dataId?.let(onLoaded)
                    }
                }
            }

            override fun clearPath() {
                source?.featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>()))
            }

            override fun setColours(stops: List<ColourStop>, casing: Int) {
                val gradient = Expression.ExpressionBuilder("step").apply {
                    lineProgress()
                    color(stops.first().colour)
                    for (stop in stops) stop {
                        literal(stop.from)
                        color(stop.colour)
                    }
                }.build()
                set(LINE, "line-gradient", gradient)
                set(CASING, "line-color", Expression.color(casing))
            }

            override fun setGradients(gradients: List<Gradient>) {
                for (g in gradients) set(g.layerId, "line-gradient", stepGradient(g.stops))
            }

            override fun setTrim(fraction: Double) {
                val trim = Value.valueOf(listOf(Value.valueOf(0.0), Value.valueOf(fraction)))
                for (layer in added) set(layer.id, "line-trim-offset", trim)
            }

            override fun setVisible(visible: Boolean) {
                val visibility = Value.valueOf(if (visible) "visible" else "none")
                for (layer in added) set(layer.id, "visibility", visibility)
            }

            override fun setLitShown(shown: Boolean) {
                val opacity = Value.valueOf(if (shown) 1.0 else 0.0)
                for (layer in added) if (layer.lit) set(layer.id, "line-opacity", opacity)
            }

            override fun remove() {
                for (layer in ALL_LAYERS.asReversed()) if (mapboxMap.styleLayerExists(layer.id)) mapboxMap.removeStyleLayer(layer.id)
                if (mapboxMap.styleSourceExists(SOURCE)) mapboxMap.removeStyleSource(SOURCE)
                source = null
                added = emptyList()
            }

            /** One property of one layer, written as it is; the style's refusal is thrown, its words without the value. */
            private fun set(layerId: String, property: String, value: Value) {
                val error = mapboxMap.setStyleLayerProperty(layerId, property, value).error
                if (error != null) throw IllegalStateException("The style refused $property of $layerId")
            }
        }
    }
}
