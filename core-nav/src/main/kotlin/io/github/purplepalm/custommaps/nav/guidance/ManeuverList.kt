package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot

/**
 * The list of the maneuvers ahead, which the phone shows in place of its map during a drive while a
 * car's screen shows the map ([io.github.purplepalm.custommaps.nav.car.PhoneScreenRule]): the leg's
 * maneuvers after the next one as the progress carries them ([RouteProgressSnapshot.upcoming]), the
 * same list for the whole step, each with how far it is from the car, which changes with every
 * update. The maneuver card above the list shows the next maneuver itself. Pure.
 */
object ManeuverList {

    /**
     * How far [item], one of [progress]'s maneuvers ahead, is from the car: on to the next maneuver,
     * then from it to [item]. The screen rounds it as the card rounds its own distance.
     */
    fun distanceFromCar(progress: RouteProgressSnapshot, item: FollowingManeuver): Distance =
        progress.distanceToNextManeuver + item.distanceAfterNext

    /**
     * Whether the list is dimmed: while the card waits for a new route's first instruction, and while
     * the car is off the route, where the maneuvers ahead may no longer be the ones it will drive.
     */
    fun dimmed(progress: RouteProgressSnapshot): Boolean = progress.pending || progress.offRoute
}
