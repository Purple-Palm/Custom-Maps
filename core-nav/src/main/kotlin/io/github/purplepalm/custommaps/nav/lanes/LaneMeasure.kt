package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * The Debug switch Measure the drawn lanes on one map (the own lanes' design 9 and 7; E10, T6, R10, R20): while it is on,
 * the app draws the lanes (Lanes on the map's Drawn by the app) and guidance runs, the drawing shows around every other
 * close view only, run-ups and junction views counted in turn, so one drive gives the frames of close views with the
 * drawing and without it, on the same phone, roads and light. With the switch off, or another choice, nothing here acts:
 * no statistics, no line, the drawing as ever.
 *
 * A close view is what the camera reports once per progress update ([onCloseView]). One view at a time is measured: from
 * the update it begins on, one statistics collection of the map runs ([Effects.startStats]), and it stops however the view
 * ends ([End]): the view lets go, guidance ends, the switch or Lanes on the map changes, the app goes to the background,
 * Settings covers the map, the phone's map is put away for the list, the camera style changes, or the map is made again.
 * Its frames are those of the camera line's seconds taken while it lasts ([onSecond]): its render p95 and longest frame
 * are worked out over every frame time of those seconds together, never from the seconds' own percentiles. Its
 * "lane_measure" line ([Effects.log]) says whether the drawing showed, its seconds and frames, how long it lasted, the
 * render p95 and longest frame, the display frames missed, the largest upload of a frame and the time the renderer spent
 * making graphics programs ([onSample]), the drawing windows that loaded in it ([onWindowLoaded]), the positions the car's
 * layer was handed, why it ended and whether the gate counts it ([LaneMeasureGate]), to which every view goes
 * ([Effects.report]).
 *
 * The drawing's state for the next view is set when a view ends, not when the next one begins: showing or hiding the
 * layers makes the map lay out and upload their tiles again, which must not fall into a measured view. The gate counts
 * whole views only: a view with frames, begun at least [SETTLE_NANOS] after such a change, that lasted at least
 * [MIN_VIEW_NANOS] and ended as its close view let go ([End.LET_GO]). Every other view is logged but not counted: one
 * without frames or too short says too little, one begun too soon after a change measures the change, and one that the
 * guidance's end, a switch, a setting, the background, Settings over the map, the list, a camera style or the map made
 * again cut short measured only part of its close view. After a view that ended while its close view held, the next one
 * waits for that close view to let go.
 *
 * Once per measuring drive Mapbox's lane details go on under the drawing, for the check of the own lanes' design 7 (T6),
 * with one "lane_measure" line that names it ([Effects.checkDetails]); they go back as Lanes on the map has them when the
 * measuring ends. Main thread only. Numbers, words and the drive's own count of views only.
 */
class LaneMeasure(private val effects: Effects, private val nanos: () -> Long) {

    /** What the map does for the measuring, and where its lines and views go. */
    interface Effects {
        /** Starts one statistics collection of the map, its samples to [onSample]. */
        fun startStats()

        /** Stops the collection under way. */
        fun stopStats()

        /** Shows the app's drawing, or hides it with its lit lane held off the route line's place. */
        fun showDrawing(shown: Boolean)

        /** Mapbox's lane details on under the drawing for this drive's check, or back as Lanes on the map has them. */
        fun checkDetails(on: Boolean)

        /** What the style says of Mapbox's lane details now; null where it says nothing. */
        fun detailsShown(): Boolean?

        /** One "lane_measure" line. */
        fun log(fields: Map<String, Any?>)

        /** A view that ended, for the trip's gate. */
        fun report(view: LaneMeasureGate.View)
    }

    /** How a measured view ended, as its line writes it in [logName]. */
    enum class End(val logName: String) {
        /** The close view let go. */
        LET_GO("let_go"),
        GUIDANCE_END("guidance_end"),

        /** The measuring switch was turned off. */
        SWITCH("switch"),

        /** Lanes on the map changed to another choice. */
        SETTING("setting"),

        /** The app went to the background. */
        BACKGROUND("background"),

        /** Settings covered the map. */
        COVERED("covered"),

        /** The phone's map was put away for the list of the turns. */
        AWAY("away"),
        CAMERA_STYLE("camera_style"),

        /** The map is made again; this one goes. */
        RELEASED("released"),
    }

    // What the map is told.
    private var switchOn = false
    private var appDraws = false
    private var guidance = false
    private var onScreen = false
    private var closeView = false

    /** Whether the measuring runs: the switch on, the app drawing the lanes, and guidance. */
    var measuring = false
        private set

    /** Whether the drawing shows now. */
    var drawingShown = true
        private set

    /** When the drawing last showed or hid, in [nanos]; [NEVER] before. */
    private var shownChangedAt = NEVER

    /** Views begun in this measuring drive. */
    private var begun = 0

    /** A view ended while its close view held: the next waits for that one to let go. */
    private var waitLetGo = false

    /** The view measured now; null between views. */
    private var view: Recorder? = null

    private var released = false

    /** Whether a view is measured now. */
    val viewOpen: Boolean get() = view != null

    /** The Debug switch Measure the drawn lanes. */
    fun setSwitch(on: Boolean) {
        if (on == switchOn) return
        switchOn = on
        reconcile(End.SWITCH)
    }

    /** Whether Lanes on the map is Drawn by the app. */
    fun setAppDraws(on: Boolean) {
        if (on == appDraws) return
        appDraws = on
        reconcile(End.SETTING)
    }

    /** Guidance began ([active]) or ended. */
    fun onGuidance(active: Boolean) {
        if (active == guidance) return
        guidance = active
        reconcile(End.GUIDANCE_END)
    }

    /** Whether the map is on screen, nothing over it; off screen, a view under way ends for [why]. */
    fun setOnScreen(onScreen: Boolean, why: End) {
        if (onScreen == this.onScreen) return
        this.onScreen = onScreen
        if (!onScreen) endView(why)
    }

    /**
     * The camera's close view at a progress update: [open], a run-up's close view ([runUp]) or a junction view, in
     * [look]. A view begins at the first update of a close view while measuring on screen, and ends when it lets go.
     */
    fun onCloseView(open: Boolean, runUp: Boolean, look: Look) {
        if (!open) {
            endView(End.LET_GO)
            closeView = false
            waitLetGo = false
            return
        }
        closeView = true
        if (measuring && onScreen && view == null && !waitLetGo && !released) beginView(runUp, look)
    }

    /** The camera style changed: the view under way ends, and the new camera's first update says whether one holds. */
    fun onCameraStyle() {
        endView(End.CAMERA_STYLE)
        closeView = false
        waitLetGo = false
    }

    /**
     * One second of the camera line: the render thread's [frameTimes] in milliseconds, the display frames it [missedVsync],
     * and the positions handed to the car's layer ([carPositions]). Counted only into a view under way.
     */
    fun onSecond(frameTimes: List<Double>, missedVsync: Long, carPositions: Int) {
        view?.addSecond(frameTimes, missedVsync, carPositions)
    }

    /**
     * One sample of the statistics collection: the longest upload of a frame in it ([uploadMaxMs]), the time the renderer
     * spent making graphics programs ([programsMs]) and how many it holds ([programs]), each null where it said nothing.
     */
    fun onSample(uploadMaxMs: Double?, programsMs: Double?, programs: Long?) {
        view?.addSample(uploadMaxMs, programsMs, programs)
    }

    /** A window of the drawing loaded: [loadMs] from set to loaded, [bytes] its size. */
    fun onWindowLoaded(loadMs: Long, bytes: Int) {
        view?.addWindow(loadMs, bytes)
    }

    /** The map goes: the view under way ends with its line, and nothing more is measured or set. */
    fun release() {
        released = true
        endView(End.RELEASED)
    }

    private fun reconcile(why: End) {
        val now = switchOn && appDraws && guidance && !released
        if (now == measuring) return
        if (!now) {
            // First: the view's end sets no drawing for a next view.
            measuring = false
            endView(why)
            waitLetGo = false
            show(true)
            effects.checkDetails(false)
            return
        }
        measuring = true
        begun = 0
        // A close view under way when the measuring begins is not one of its views.
        waitLetGo = closeView
        show(true)
        effects.checkDetails(true)
        effects.log(mapOf("check" to CHECK_DETAILS, "on" to true))
    }

    private fun beginView(runUp: Boolean, look: Look) {
        begun += 1
        val now = nanos()
        val settled = shownChangedAt == NEVER || now - shownChangedAt >= SETTLE_NANOS
        view = Recorder(begun, drawingShown, runUp, look, settled, effects.detailsShown(), now)
        effects.startStats()
    }

    private fun endView(why: End) {
        val ended = view ?: return
        view = null
        effects.stopStats()
        val lasted = nanos() - ended.beganAt
        effects.log(ended.fields(why, lasted))
        effects.report(ended.gateView(why, lasted))
        waitLetGo = closeView && why != End.LET_GO
        // The drawing for the next view, set now, between the views: the odd ones show it.
        if (measuring && !released) show((ended.index + 1) % 2 == 1)
    }

    private fun show(shown: Boolean) {
        if (shown == drawingShown) return
        drawingShown = shown
        shownChangedAt = nanos()
        effects.showDrawing(shown)
    }

    /** One view's numbers, added up as they come; it began at [beganAt], in [nanos]. */
    private class Recorder(
        val index: Int,
        val drawn: Boolean,
        val runUp: Boolean,
        val look: Look,
        val settled: Boolean,
        val hdRoads: Boolean?,
        val beganAt: Long,
    ) {
        private var frameTimes = DoubleArray(INITIAL_FRAMES)
        private var frames = 0
        private var seconds = 0
        private var missed = 0L
        private var positions = 0L
        private var uploadMaxMs = Double.NaN
        private var programsMs = Double.NaN
        private var programs: Long? = null
        private var windows = 0
        private var windowLoadMaxMs = 0L
        private var windowBytesMax = 0

        fun addSecond(times: List<Double>, missedVsync: Long, carPositions: Int) {
            seconds += 1
            missed += missedVsync
            positions += carPositions
            if (frames + times.size > frameTimes.size) frameTimes = frameTimes.copyOf(maxOf(frameTimes.size * 2, frames + times.size))
            for (time in times) frameTimes[frames++] = time
        }

        fun addSample(upload: Double?, creationMs: Double?, held: Long?) {
            if (upload != null && (uploadMaxMs.isNaN() || upload > uploadMaxMs)) uploadMaxMs = upload
            if (creationMs != null) programsMs = (if (programsMs.isNaN()) 0.0 else programsMs) + creationMs
            if (held != null) programs = maxOf(programs ?: held, held)
        }

        fun addWindow(loadMs: Long, bytes: Int) {
            windows += 1
            windowLoadMaxMs = maxOf(windowLoadMaxMs, loadMs)
            windowBytesMax = maxOf(windowBytesMax, bytes)
        }

        /**
         * Whether the gate counts the view, which ended for [end] after [lastedNanos]: a whole close view, with frames,
         * begun settled, of [MIN_VIEW_NANOS] at least, that its close view let go.
         */
        fun counts(end: End, lastedNanos: Long): Boolean = frames > 0 && settled && lastedNanos >= MIN_VIEW_NANOS && end == End.LET_GO

        /** Worked out once the view has ended, over every frame time of it; NaN without frames. */
        private val p95: Double by lazy {
            val sorted = frameTimes.copyOf(frames)
            sorted.sort()
            LaneMeasureGate.percentile(sorted, frames, P95)
        }

        private fun missedPerSecond(): Double = if (seconds > 0) missed.toDouble() / seconds else Double.NaN

        fun gateView(end: End, lastedNanos: Long): LaneMeasureGate.View =
            LaneMeasureGate.View(look, drawn, p95, missedPerSecond(), uploadMaxMs, counts(end, lastedNanos))

        fun fields(end: End, lastedNanos: Long): Map<String, Any?> = mapOf(
            "view" to index,
            "drawn" to drawn,
            "kind" to if (runUp) KIND_RUN_UP else KIND_JUNCTION,
            "look" to look,
            "seconds" to seconds,
            "ms" to lastedNanos.coerceAtLeast(0L) / NANOS_PER_MILLI,
            "frames" to frames,
            "p95Ms" to p95.takeUnless { it.isNaN() }?.let { Motion.round(it, 1) },
            "maxMs" to if (frames > 0) Motion.round(frameTimes.copyOf(frames).max(), 1) else null,
            "missedVsync" to missed,
            "missedPerS" to missedPerSecond().takeUnless { it.isNaN() }?.let { Motion.round(it, 1) },
            "uploadMaxMs" to uploadMaxMs.takeUnless { it.isNaN() }?.let { Motion.round(it, 1) },
            "programsMs" to programsMs.takeUnless { it.isNaN() }?.let { Motion.round(it, 1) },
            "programs" to programs,
            "windows" to windows,
            "windowLoadMaxMs" to windowLoadMaxMs.takeIf { windows > 0 },
            "windowBytesMax" to windowBytesMax.takeIf { windows > 0 },
            "carPositions" to positions,
            "hdRoads" to hdRoads,
            "end" to end.logName,
            "counts" to counts(end, lastedNanos),
        ).filterValues { it != null }
    }

    companion object {
        /**
         * How long after the drawing showed or hid a view must begin to count: the map lays out and uploads the layers'
         * tiles again then, and the work must not be measured as the drawing's.
         */
        const val SETTLE_NANOS = 5_000_000_000L

        /**
         * How long a view must last to count, from the progress update it began on to its end: shorter ones hold too few
         * frames for their percentile to say what the drawing costs.
         */
        const val MIN_VIEW_NANOS = 3_000_000_000L

        /** The percentile of a view's frame times its line gives. */
        const val P95 = 0.95

        /** The "check" of the line that says Mapbox's lane details are on under the drawing for the drive. */
        const val CHECK_DETAILS = "details_under_drawing"

        const val KIND_RUN_UP = "run_up"
        const val KIND_JUNCTION = "junction"

        private const val NEVER = Long.MIN_VALUE

        private const val NANOS_PER_MILLI = 1_000_000L

        /** Room for about two seconds of frames at 120 Hz before the first growth. */
        private const val INITIAL_FRAMES = 256
    }
}
