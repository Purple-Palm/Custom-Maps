package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.look.Look
import kotlin.math.ceil

/**
 * The frame gate of the app's lanes (the own lanes' design 9, R10): whether the close views with the drawing cost the
 * phone's map no more than the close views without it, on one measuring drive ([LaneMeasure]). In one look, with at least
 * [MIN_VIEWS] counted views of each kind: the drawing views' median render p95 at most [RENDER_P95_SLACK_MS] above the
 * other views', their median of missed display frames a second at most [MISSED_VSYNC_SLACK] more, and no upload in a
 * drawing view over [UPLOAD_MAX_MS]. The trip summary carries the verdict ([summary]); the default of the lanes waits for
 * a pass.
 */
object LaneMeasureGate {

    /** The fewest counted views of each kind, with the drawing and without it, in one look. */
    const val MIN_VIEWS = 6

    /** How much higher the drawing views' median render p95 may be than the other views', in milliseconds. */
    const val RENDER_P95_SLACK_MS = 1.0

    /** How many more missed display frames a second the drawing views may have, as a share of the other views' median. */
    const val MISSED_VSYNC_SLACK = 0.10

    /** The longest upload of a frame in a drawing view, in milliseconds: one frame at 120 Hz. */
    const val UPLOAD_MAX_MS = 8.0

    /** What the gate says, as the trip summary writes it in [logName]. */
    enum class Verdict(val logName: String) {
        PASS("pass"),
        FAIL("fail"),
        TOO_FEW_VIEWS("too_few_views"),
    }

    /**
     * One measured close view as the gate reads it: its [look], whether the drawing showed ([drawn]), its render p95 over
     * every frame time of it, its missed display frames a second, its longest upload (NaN without a statistics sample),
     * and whether it [counts] at all ([LaneMeasure]).
     */
    data class View(
        val look: Look,
        val drawn: Boolean,
        val renderP95Ms: Double,
        val missedPerSecond: Double,
        val uploadMaxMs: Double,
        val counts: Boolean,
    )

    /** The verdict on [views] of one look; only the counted ones are read. */
    fun verdict(views: List<View>): Verdict {
        val counted = views.filter { it.counts }
        val drawn = counted.filter { it.drawn }
        val plain = counted.filter { !it.drawn }
        if (drawn.size < MIN_VIEWS || plain.size < MIN_VIEWS) return Verdict.TOO_FEW_VIEWS
        val render = median(drawn.map { it.renderP95Ms }) <= median(plain.map { it.renderP95Ms }) + RENDER_P95_SLACK_MS
        val missed = median(drawn.map { it.missedPerSecond }) <= median(plain.map { it.missedPerSecond }) * (1.0 + MISSED_VSYNC_SLACK)
        // A view without a statistics sample has no upload to speak of.
        val uploads = drawn.none { it.uploadMaxMs > UPLOAD_MAX_MS }
        return if (render && missed && uploads) Verdict.PASS else Verdict.FAIL
    }

    /**
     * The verdict of a drive: [Verdict.FAIL] where the gate fails in a look, else [Verdict.PASS] where it passes in one,
     * else [Verdict.TOO_FEW_VIEWS].
     */
    fun overall(views: List<View>): Verdict {
        val verdicts = Look.entries.filter { look -> views.any { it.look == look } }.map { look -> verdict(views.filter { it.look == look }) }
        return when {
            Verdict.FAIL in verdicts -> Verdict.FAIL
            Verdict.PASS in verdicts -> Verdict.PASS
            else -> Verdict.TOO_FEW_VIEWS
        }
    }

    /**
     * The trip summary's "laneGate": "gate", the [overall] verdict; "views" and "counted"; and for each look that had a
     * view, under its name, its "gate", the counted views of each kind ("drawn", "notDrawn") and their medians of render
     * p95 ("drawnP95Ms", "notDrawnP95Ms") and of missed display frames a second ("drawnMissedPerS", "notDrawnMissedPerS"),
     * and the longest upload of a drawing view ("uploadMaxMs"); a median of no view is left out.
     */
    fun summary(views: List<View>): Map<String, Any?> {
        val looks = Look.entries.filter { look -> views.any { it.look == look } }.associate { look ->
            val ofLook = views.filter { it.look == look }
            val counted = ofLook.filter { it.counts }
            val drawn = counted.filter { it.drawn }
            val plain = counted.filter { !it.drawn }
            look.name to mapOf(
                "gate" to verdict(ofLook).logName,
                "drawn" to drawn.size,
                "notDrawn" to plain.size,
                "drawnP95Ms" to rounded(median(drawn.map { it.renderP95Ms })),
                "notDrawnP95Ms" to rounded(median(plain.map { it.renderP95Ms })),
                "drawnMissedPerS" to rounded(median(drawn.map { it.missedPerSecond })),
                "notDrawnMissedPerS" to rounded(median(plain.map { it.missedPerSecond })),
                "uploadMaxMs" to rounded(drawn.map { it.uploadMaxMs }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN),
            ).filterValues { it != null }
        }
        return mapOf(
            "gate" to overall(views).logName,
            "views" to views.size,
            "counted" to views.count { it.counts },
        ) + looks
    }

    /** The median of [values]: the middle one, or the mean of the two middle ones; NaN for none. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /**
     * The [share] percentile of the first [count] values of [sorted], in ascending order, by the nearest rank: the
     * smallest value at least [share] of them do not exceed; NaN for none.
     */
    fun percentile(sorted: DoubleArray, count: Int, share: Double): Double {
        if (count <= 0) return Double.NaN
        val rank = ceil(share * count).toInt().coerceIn(1, count)
        return sorted[rank - 1]
    }

    private fun rounded(value: Double): Double? = if (value.isNaN()) null else Motion.round(value, 1)
}
