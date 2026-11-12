package io.github.purplepalm.custommaps.nav.lanes.geometry

/** How the navigation SDK's route line colours a stretch of the route: its traffic, or a closure. */
enum class TrafficLevel {
    /** The route gives no congestion here, or none in a range. */
    UNKNOWN,
    LOW,
    MODERATE,
    HEAVY,
    SEVERE,

    /** The route is closed here. */
    CLOSED,
}

/** From [from] to [to], fractions of the path's projected length, the traffic is [level]. */
data class TrafficRun(val from: Double, val to: Double, val level: TrafficLevel)

/**
 * One leg's traffic as the route gives it: [stations], the station along the route of each point of the leg's line (its
 * steps' shapes one after the other, each point once); [congestion], for each segment between two points, its numeric
 * congestion from 0 to 100 (null where unknown), which may be shorter than the segments or empty; [closures], the
 * segments the route is closed on, as index ranges, both ends included.
 */
class LegTraffic(val stations: DoubleArray, val congestion: List<Int?>, val closures: List<IntRange> = emptyList())

/**
 * The traffic along the app's own route line (the design's 5.3, "Traffic"; D7): the route's numeric congestion read
 * with the navigation SDK's own ranges ([LOW], [MODERATE], [HEAVY], [SEVERE]) and its closures, turned into runs along
 * the path, each run a stretch of one level as fractions of the path's projected length ([StationTable]), so a line
 * gradient's step stops lie where the SDK's own line changes colour. The runs cover the path exactly once, from 0 to 1;
 * where the route gives no congestion, or less than it has segments (a refresh may remove expired congestion), the
 * level is [TrafficLevel.UNKNOWN]. Colours are chosen by the map, not here. Built once for a route and again at each
 * refresh, off the main thread.
 */
object TrafficRuns {

    /** The SDK's low congestion: 0 to 39. */
    val LOW = 0..39

    /** Moderate: 40 to 59. */
    val MODERATE = 40..59

    /** Heavy: 60 to 79. */
    val HEAVY = 60..79

    /** Severe: 80 to 100. */
    val SEVERE = 80..100

    /** The level of a numeric [congestion], in the SDK's ranges; unknown outside them and where there is none. */
    fun level(congestion: Int?): TrafficLevel = when (congestion) {
        null -> TrafficLevel.UNKNOWN
        in LOW -> TrafficLevel.LOW
        in HEAVY -> TrafficLevel.HEAVY
        in SEVERE -> TrafficLevel.SEVERE
        in MODERATE -> TrafficLevel.MODERATE
        else -> TrafficLevel.UNKNOWN
    }

    /**
     * The runs of [legs] along the path [table] measures. A run begins at every change of level and at every one of
     * [seams] (fractions of the path's projected length where a run must begin whatever the level, such as where the
     * stretches drawn by two pairs of line layers meet), so a gradient's step stops lie there too.
     */
    fun of(table: StationTable, legs: List<LegTraffic>, seams: List<Double> = emptyList()): List<TrafficRun> {
        // Every segment of every leg as a stretch of path fractions with its level; unknown where no leg says.
        val pieces = ArrayList<TrafficRun>()
        for (leg in legs) {
            val st = leg.stations
            for (i in 0 until st.size - 1) {
                if (!(st[i + 1] > st[i])) continue
                val level = if (leg.closures.any { i in it }) TrafficLevel.CLOSED else level(leg.congestion.getOrNull(i))
                pieces.add(TrafficRun(table.pathFraction(st[i]), table.pathFraction(st[i + 1]), level))
            }
        }
        pieces.sortBy { it.from }
        val cuts = seams.filter { it > 0.0 && it < 1.0 }.distinct().sorted()
        val seamSet = cuts.toHashSet()
        val out = ArrayList<TrafficRun>()
        var at = 0.0
        // A run from [from] to [to] at [level]: joined to the last where the level is the same and no seam lies between.
        fun put(from: Double, to: Double, level: TrafficLevel) {
            if (to <= from) return
            val last = out.lastOrNull()
            if (last != null && from !in seamSet && last.level == level && last.to == from) {
                out[out.size - 1] = last.copy(to = to)
            } else {
                out.add(TrafficRun(from, to, level))
            }
        }
        fun add(to: Double, level: TrafficLevel) {
            var from = at
            for (seam in cuts) {
                if (seam <= from || seam >= to) continue
                put(from, seam, level)
                from = seam
            }
            put(from, to, level)
            at = to
        }
        for (piece in pieces) {
            val to = piece.to.coerceAtMost(1.0)
            if (to <= at) continue
            if (piece.from > at) add(piece.from, TrafficLevel.UNKNOWN)
            add(to, piece.level)
        }
        if (at < 1.0) add(1.0, TrafficLevel.UNKNOWN)
        return out
    }
}
