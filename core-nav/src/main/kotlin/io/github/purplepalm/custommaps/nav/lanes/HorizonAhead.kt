package io.github.purplepalm.custommaps.nav.lanes

/*
 * Mapbox's electronic horizon as the app's own lanes read it (the own lanes' design 2.1, 3.1, OL9): what the horizon says
 * of each edge of the road ahead on the route, placed along the route from where the car is. The map engine maps the
 * navigator's tree and each edge's metadata into these values; they live in memory with their route and are never stored.
 * They hold no edge id, shape, road name or country code: the engine keeps edge ids in memory for its reading only, and
 * none of them reaches a log line.
 */

/** An edge's functional class as the horizon names it (OpenStreetMap's highway classes); [OTHER] for a name it does not list. */
enum class HorizonRoadClass(
    /** How the debug log names it. */
    val logName: String,
) {
    MOTORWAY("motorway"),
    TRUNK("trunk"),
    PRIMARY("primary"),
    SECONDARY("secondary"),
    TERTIARY("tertiary"),
    UNCLASSIFIED("unclassified"),
    RESIDENTIAL("residential"),
    SERVICE_OTHER("service_other"),

    /** A class the horizon may name in a later version. */
    OTHER("other"),
    ;

    companion object {
        /** The class the horizon calls [name], as its own words have it (upper case); [OTHER] for any other. */
        fun of(name: String?): HorizonRoadClass = entries.firstOrNull { it != OTHER && it.name == name } ?: OTHER
    }
}

/** What the horizon says of one edge of the road: its length, its lanes and its kind. */
data class HorizonEdge(
    /** Along the edge, in metres. */
    val lengthM: Double,
    /**
     * The lanes on the edge, which "does not change mid-edge"; null where the horizon gives none. Whether it counts the
     * car's direction only, and whether it counts lanes kept for car pools, is not known (the design's 17, E17): the
     * "horizon_lanes" line compares it with the route's own lane lists, and nothing uses it where they do not agree.
     */
    val laneCount: Int?,
    val motorway: Boolean,
    val ramp: Boolean,
    val oneWay: Boolean,
    val roadClass: HorizonRoadClass,
)

/** One edge of the horizon along the route, from [start] to [end] metres along it, with what the horizon says of it. */
data class HorizonStretch(val start: Double, val end: Double, val edge: HorizonEdge) {
    val meters: Double get() = end - start
}

/**
 * The horizon of one update along the route [routeId] ([of]): its [stretches] in route order, the first from the car at
 * [from] to the end of the edge the car is on, each next one from where the one before ends, as far as the horizon gave
 * each edge's length. They are placed by the route's own stations from the car's: where the horizon's edges and the
 * route's steps measure the road a little differently, the far end drifts by that much, which the "horizon_lanes" line
 * shows as each lane point's offset from the nearest edge end. Pure and never changed once made.
 */
class HorizonAhead private constructor(
    /** The navigator's id of the route the stretches lie along. */
    val routeId: String,
    /** Where the car was along the route, in metres, at the update. */
    val from: Double,
    val stretches: List<HorizonStretch>,
) {

    /** Where the stretches end along the route; [from] where there are none. */
    val to: Double get() = stretches.lastOrNull()?.end ?: from

    /** The stretch that holds [station], from its start up to, not at, its end; null outside every stretch. */
    fun at(station: Double): HorizonStretch? {
        var low = 0
        var high = stretches.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val s = stretches[mid]
            when {
                station < s.start -> high = mid - 1
                station >= s.end -> low = mid + 1
                else -> return s
            }
        }
        return null
    }

    /** The stretch whose end lies nearest [station], before or after it; null without stretches. A tie goes to the earlier. */
    fun endNearest(station: Double): HorizonStretch? {
        if (stretches.isEmpty() || station.isNaN()) return null
        // The first stretch that ends at or past the station, and the one before it.
        var low = 0
        var high = stretches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (stretches[mid].end < station) low = mid + 1 else high = mid
        }
        val after = stretches.getOrNull(low)
        val before = stretches.getOrNull(low - 1)
        return when {
            after == null -> before
            before == null -> after
            station - before.end <= after.end - station -> before
            else -> after
        }
    }

    companion object {

        /**
         * How far ahead the horizon reaches, in metres, as its options ask for once at the navigator's setup: a drawing
         * window reaches at most [io.github.purplepalm.custommaps.nav.lanes.geometry.DrawingWindows.MAX_METERS] less its
         * part behind the car ahead when it is sent, so the counts must already cover that much road; the horizon's own
         * default of 500 m never would.
         */
        const val HORIZON_AHEAD_M = 3_500.0

        /**
         * The horizon is updated at most once in this many seconds, as its options ask: the navigation SDK maps the whole
         * tree into objects on the main thread at every update, which once a second keeps small.
         */
        const val HORIZON_UPDATE_SECONDS = 1.0

        /**
         * A progress update older than this, in seconds, gives no station for the car at a horizon update ([carStation]):
         * the drive's updates have stopped, and the car's place is not known well enough to lay the horizon along the route.
         */
        const val STATION_MAX_AGE_S = 2.0

        /**
         * The stretches of an update along the route [routeId] for the car at [carStation], [percentAlong] of the way
         * along the first of [edges], the edge it is on, the next ones the edges that follow it on the route, in route
         * order. An edge without metadata (null) ends the stretches there: the places of the edges after it are not known;
         * so does an edge whose length is no number or below zero. An edge of no length adds no stretch. A [percentAlong]
         * outside 0 to 1 is taken as the nearest end of that range, one that is no number as 0.
         */
        fun of(routeId: String, carStation: Double, percentAlong: Double, edges: List<HorizonEdge?>): HorizonAhead {
            val stretches = ArrayList<HorizonStretch>(edges.size)
            if (carStation.isFinite()) {
                val along = if (percentAlong.isNaN()) 0.0 else percentAlong.coerceIn(0.0, 1.0)
                var at = carStation
                for ((i, edge) in edges.withIndex()) {
                    if (edge == null || !edge.lengthM.isFinite() || edge.lengthM < 0.0) break
                    val meters = if (i == 0) (1.0 - along) * edge.lengthM else edge.lengthM
                    if (meters <= 0.0) continue
                    stretches.add(HorizonStretch(at, at + meters, edge))
                    at += meters
                }
            }
            return HorizonAhead(routeId, carStation, stretches)
        }

        /**
         * Where the car is at a horizon update: [station], its station at the latest progress update, [ageSeconds] before,
         * moved on by its [speedMps] then (none where the speed is not known). The horizon's update and the progress update
         * come from the navigator one after the other, in either order, about once a second each. Null where that progress
         * update is more than [STATION_MAX_AGE_S] old, or where the station is no number.
         */
        fun carStation(station: Double, speedMps: Double?, ageSeconds: Double): Double? {
            if (!station.isFinite() || ageSeconds.isNaN() || ageSeconds > STATION_MAX_AGE_S) return null
            val speed = speedMps?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            return station + speed * ageSeconds.coerceAtLeast(0.0)
        }
    }
}
