package io.github.purplepalm.custommaps.nav.camera

/**
 * Decides when the camera follows the car on the browsing map: outside guidance, with no route on
 * the map.
 *
 * The camera follows from the car's first position until the driver moves the map, and again once
 * the driver asks for My location. A route on the map, in the preview and during guidance, has a
 * camera of its own. When the route goes, the camera follows the car again if the route's camera
 * still had the map, and stays where it is if the driver had taken the map from it.
 *
 * Following takes no trip session and no request: the positions are the ones the map shows the car
 * at anyway. This class only decides; the map engine moves the camera. Not safe for more than one
 * thread.
 */
class IdleFollow {

    /** The car has a position on the map. */
    private var located = false

    /** No route is on the map. */
    private var browsing = true

    /** The driver has not moved the map since it last went to the car. */
    private var wanted = true

    /** True while the camera is to stay on the car. */
    var following = false
        private set

    /**
     * The map shows the car, at its first position or a later one.
     *
     * @return true when the camera has to go to the car and follow it from now on
     */
    fun onCarLocated(): Boolean {
        located = true
        return settle()
    }

    /**
     * The driver asked for My location.
     *
     * @return true when the camera has to go to the car and follow it. That is so also while it
     *   follows already. Without a position yet the camera follows from the first one.
     */
    fun onMyLocation(): Boolean {
        // The route's camera has the map, and its screen offers no My location.
        if (!browsing) return false
        wanted = true
        following = located
        return located
    }

    /** The driver moved the map, or the camera was told to leave it alone: it stays where it is. */
    fun onMapMoved() {
        wanted = false
        following = false
    }

    /** A route is on the map, and its camera takes over. */
    fun onRouteShown() {
        browsing = false
        following = false
    }

    /**
     * No route is on the map. Called again while that stays so, it changes nothing.
     *
     * @param routeCameraHadMap whether the route's camera still moved the map when the route went;
     *   false when the driver had taken the map from it
     * @return true when the camera has to go to the car and follow it from now on
     */
    fun onRouteGone(routeCameraHadMap: Boolean): Boolean {
        if (browsing) return false
        browsing = true
        wanted = routeCameraHadMap
        return settle()
    }

    /** Works out whether the camera follows now; true when it starts to. */
    private fun settle(): Boolean {
        val follows = located && browsing && wanted
        val starts = follows && !following
        following = follows
        return starts
    }

    companion object {
        /** The view the camera keeps on the car: the zoom level, and the tilt in degrees. */
        const val ZOOM = 15.5
        const val PITCH = 40.0

        /** How long the camera takes to come to the car, in seconds. */
        const val ARRIVE_SECONDS = 0.8
    }
}
