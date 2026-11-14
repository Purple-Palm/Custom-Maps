package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.Motion

/**
 * Adds up what a trip read from the electronic horizon, for the "horizon" object of its "trip_summary" line: numbers and
 * the switch's state only, so drives with the horizon and without it can be compared, their data use above all (the
 * summary's "receivedBytes"). Begun with each trip session ([begin]); main thread only, like the trip's own tally.
 */
class HorizonCounts {

    /** True from [begin] to [end]. */
    var running = false
        private set

    /** The switch as the app last heard of it, kept between trips. */
    private var switchOn = false

    private var onAtStart = false
    private var switched = 0
    private var listening = false
    private var listeningSince = 0L
    private var listenedMillis = 0L
    private var updates = 0
    private var notAvailable = 0
    private var edges = 0
    private var points = 0
    private var agreeAll = 0
    private var agreeGeneral = 0
    private var disagree = 0
    private var noCount = 0
    private var stretchesUsed = 0
    private var windows = 0
    private var failed = false

    /** A trip session starts at [nowMillis]: everything starts over, with the switch as it is now. */
    fun begin(nowMillis: Long) {
        running = true
        onAtStart = switchOn
        switched = 0
        listening = false
        listeningSince = nowMillis
        listenedMillis = 0L
        updates = 0
        notAvailable = 0
        edges = 0
        points = 0
        agreeAll = 0
        agreeGeneral = 0
        disagree = 0
        noCount = 0
        stretchesUsed = 0
        windows = 0
        failed = false
    }

    /** The switch is [on] now: a change during a trip is counted. */
    fun onSwitch(on: Boolean) {
        if (running && on != switchOn) switched++
        switchOn = on
    }

    /** The app listens to the horizon, or no longer does, from [nowMillis] on. */
    fun onListening(listening: Boolean, nowMillis: Long) {
        if (!running || listening == this.listening) return
        if (this.listening) listenedMillis += nowMillis - listeningSince
        this.listening = listening
        listeningSince = nowMillis
    }

    /** A position update of the horizon came: [available] false for one that says it has none (off the road, uncertain). */
    fun onPosition(available: Boolean) {
        if (!running) return
        updates++
        if (!available) notAvailable++
    }

    /** [count] more edges' metadata were read. */
    fun onRead(count: Int) {
        if (running) edges += count
    }

    /** Lane points were compared with the horizon ([HorizonLanesLine.compare]), each in one "horizon_lanes" line. */
    fun onCompared(comparisons: List<HorizonLanesLine.Comparison>) {
        if (!running) return
        for (c in comparisons) {
            points++
            when {
                c.count == null -> noCount++
                c.convention == HorizonConvention.ALL -> agreeAll++
                c.convention == HorizonConvention.GENERAL -> agreeGeneral++
                else -> disagree++
            }
        }
    }

    /** [count] of the horizon's stretches went into a drawing of the app's own lanes. */
    fun onStretchesUsed(count: Int) {
        if (running) stretchesUsed += count
    }

    /** A drawing window went to the map with lane counts from the horizon in it. */
    fun onWindow() {
        if (running) windows++
    }

    /** Reading the horizon failed: the rest of the trip goes on without it. */
    fun onFailure() {
        if (running) failed = true
    }

    /**
     * The "horizon" object at [nowMillis]: "on", the switch when the trip began; "switched", how often it changed in the
     * trip; "listenedS", how long the app listened to the horizon; "updates" of its position and of those "notAvailable";
     * "edges" whose metadata was read; the lane "points" compared, of which the counts agreed with every listed lane
     * ("agreeAll"), with the lanes open to every car ("agreeGeneral"), with neither ("disagree"), or had none to compare
     * ("noCount"); "stretchesUsed" by the drawing and drawing "windows" with counts from the horizon; and whether reading
     * it "failed".
     */
    fun fields(nowMillis: Long): Map<String, Any?> {
        val listened = listenedMillis + if (listening) nowMillis - listeningSince else 0L
        return linkedMapOf(
            "on" to onAtStart,
            "switched" to switched,
            "listenedS" to Motion.round(listened / MILLIS_PER_SECOND, 1),
            "updates" to updates,
            "notAvailable" to notAvailable,
            "edges" to edges,
            "points" to points,
            "agreeAll" to agreeAll,
            "agreeGeneral" to agreeGeneral,
            "disagree" to disagree,
            "noCount" to noCount,
            "stretchesUsed" to stretchesUsed,
            "windows" to windows,
            "failed" to failed,
        )
    }

    /** The trip session stopped: nothing more is counted. */
    fun end() {
        running = false
        listening = false
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
