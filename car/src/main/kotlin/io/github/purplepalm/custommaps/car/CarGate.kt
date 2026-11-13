package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.MapsAccess

/**
 * What the car's screen may show, by the access to maps the phone has and the location it may use.
 * The car waits for the token as the phone's screen does: the process may have been started by the
 * car's host with the phone locked, before the token store's first read.
 */
enum class CarGate {
    /** The token store has not been read yet: a loading message. */
    LOADING,

    /** No Mapbox token is saved: a message that it is added on the phone. */
    NO_TOKEN,

    /** Precise location is not allowed: a message that it is allowed on the phone. Routes and guidance need it (DECISIONS 23). */
    NO_PRECISE_LOCATION,

    /** The map. */
    READY,
    ;

    companion object {
        /** The gate for [access] with precise location allowed or not. */
        fun of(access: MapsAccess, preciseLocation: Boolean): CarGate = when (access) {
            MapsAccess.Checking -> LOADING
            MapsAccess.NeedsToken -> NO_TOKEN
            is MapsAccess.Ready -> if (preciseLocation) READY else NO_PRECISE_LOCATION
        }
    }
}

/**
 * What the car's session does with its map when the gate or the token changes. A map keeps the token
 * it was made with, so a new token builds it again, as the phone's activity is made again for one
 * (DECISIONS 16). The map exists only while the gate is [CarGate.READY].
 */
sealed interface CarMapStep {
    /** Nothing to do. */
    data object Keep : CarMapStep

    /** Make the map, for the token of [generation]. */
    data class Build(val generation: Int) : CarMapStep

    /** Let go of the map and make a new one, for the token of [generation]. */
    data class Rebuild(val generation: Int) : CarMapStep

    /** Let go of the map: there is no token to draw it with. */
    data object Release : CarMapStep

    companion object {
        /**
         * The step from a map made for the token of [built], or none when null, to what [access] and
         * [gate] allow now.
         */
        fun next(built: Int?, access: MapsAccess, gate: CarGate): CarMapStep {
            val ready = access as? MapsAccess.Ready
            return when {
                gate != CarGate.READY || ready == null -> if (built != null) Release else Keep
                built == null -> Build(ready.generation)
                built != ready.generation -> Rebuild(ready.generation)
                else -> Keep
            }
        }
    }
}
