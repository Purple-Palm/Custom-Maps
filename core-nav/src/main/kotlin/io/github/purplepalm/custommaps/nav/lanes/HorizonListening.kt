package io.github.purplepalm.custommaps.nav.lanes

/**
 * When the app listens to Mapbox's electronic horizon ([wanted]): during guidance, once the trip session the driver
 * started with Start runs, while the switch Lane counts between lane points is on, whoever draws the lanes on the map.
 * The horizon's callbacks fire only inside a running trip session; listening starts none and sends no request of its
 * own, since the navigator reads the horizon from the road data it already loads for the trip.
 */
object HorizonListening {

    /**
     * Whether to listen with the switch [switchOn] (null while the setting has not been read yet, which counts as off),
     * [guidanceActive] and [tripSessionRunning].
     */
    fun wanted(switchOn: Boolean?, guidanceActive: Boolean, tripSessionRunning: Boolean): Boolean =
        switchOn == true && guidanceActive && tripSessionRunning
}
