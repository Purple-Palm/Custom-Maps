package io.github.purplepalm.custommaps.nav.debug

/**
 * Counts of a route for the debug log, from plain values the route carries: no position and no
 * name.
 */
object RouteCounts {

    /**
     * How many of a step's intersections have a sign, a signal or a crossing, from each
     * intersection's flag in [flags]: true, false, or null when the route says nothing of it.
     * Null when no intersection says anything, so that "the service sent none" is not read as
     * "there are none".
     */
    fun flagged(flags: List<Boolean?>): Int? = if (flags.all { it == null }) null else flags.count { it == true }

    /**
     * The runs of one traffic [level] along a route, in order: each the meters of its segments in a
     * row, from the route's [levels] and [meters], one of each for every segment. A segment of
     * another level, or without one, ends a run.
     */
    fun runs(levels: List<String?>, meters: List<Double>, level: String): List<Double> {
        val runs = ArrayList<Double>()
        var length = 0.0
        var inRun = false
        for (index in levels.indices) {
            if (levels[index] == level) {
                length += meters.getOrNull(index)?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                inRun = true
            } else if (inRun) {
                runs += length
                length = 0.0
                inRun = false
            }
        }
        if (inRun) runs += length
        return runs
    }

    /**
     * The "congestionRuns" of a route: for "heavy" and for "severe", how many runs there are and
     * the meters of each, rounded to the meter, from the levels and meters of each of its legs; a
     * run ends with its leg.
     */
    fun congestionRuns(legs: List<Pair<List<String?>, List<Double>>>): Map<String, Any?> =
        listOf(HEAVY, SEVERE).associateWith { level ->
            val runs = legs.flatMap { (levels, meters) -> runs(levels, meters, level) }.map { Motion.round(it, 0) }
            mapOf("count" to runs.size, "meters" to runs)
        }

    const val HEAVY = "heavy"
    const val SEVERE = "severe"
}
