package io.github.purplepalm.custommaps.engine.mapbox.search

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/**
 * Chooses where a car should stop for a place, from the routable points of its search result.
 *
 * A search result has a point of its own, which for a building is often the middle of its roof. A
 * route asked to go there ends on whichever road is nearest to that point, and for a large
 * building that can be the service road behind it. The search service may also give routable
 * points: where the place is reached from the road. A route asked to go to one of those ends where
 * the service says a car should stop.
 *
 * The names of routable points are free text from the server, and they differ between its
 * services, so no list of good names is kept. Only one name is passed over: the door of a
 * building, which is for someone on foot.
 */
internal object StopPoints {

    /** Farther than this from the place, a routable point is more likely bad data than its parking. */
    const val MAX_METERS = 1_000.0

    /** The door of a building, in any letter case. */
    private const val ENTRANCE = "entrance"

    /** A routable point as the search service gives it; the numbers are taken as they come. */
    class Candidate(val name: String, val latitude: Double, val longitude: Double)

    /** The routable point taken as the stop point: which one of the list, and how far it is from the place. */
    class Choice(val index: Int, val name: String, val point: GeoPoint, val metersFromPlace: Double)

    /**
     * The first of [candidates] that is not a door and lies within [MAX_METERS] of [place]; null
     * when there is none, and the route then goes to the place itself. A candidate whose numbers
     * are no coordinate is passed over like one that is too far.
     */
    fun choose(candidates: List<Candidate>?, place: GeoPoint): Choice? {
        if (candidates == null) return null
        for (index in candidates.indices) {
            val candidate = candidates[index]
            if (candidate.name.trim().equals(ENTRANCE, ignoreCase = true)) continue
            // Also false for a number that is none.
            if (candidate.latitude !in -90.0..90.0 || candidate.longitude !in -180.0..180.0) continue
            val meters = Geo.meters(place.latitude, place.longitude, candidate.latitude, candidate.longitude)
            if (meters <= MAX_METERS) {
                return Choice(index, candidate.name, GeoPoint(candidate.latitude, candidate.longitude), meters)
            }
        }
        return null
    }
}
