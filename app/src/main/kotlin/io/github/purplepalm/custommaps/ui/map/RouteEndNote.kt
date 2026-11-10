package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.format.DisplayDistance
import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint

/**
 * A route that ends farther than this from a place without a stop point gets a note in the route
 * sheet. Nearer, it ends at the place for all a driver can tell: a road is seldom closer to the
 * middle of a building.
 */
internal val ROUTE_END_NOTE_ABOVE: Distance = Distance.meters(50.0)

/**
 * What the route sheet says about a route that ends away from the place: the distance between the
 * route's end and the place, ready to show, or null when there is nothing to say.
 *
 * Without a stop point a route ends on the road nearest to the place, and for a large building
 * that can be a road on its far side. The note tells the driver before the drive, where the pin
 * and the route's end on the map show the same thing. A place with a stop point never gets it:
 * its routes end where the search service says a car should stop for the place, and that is meant
 * to be some way from the building.
 *
 * @param stopPoint the place's stop point, or null when it has none
 * @param endGap how far the route's last point is from the place, or null when that is not known
 */
internal fun routeEndNote(stopPoint: GeoPoint?, endGap: Distance?): DisplayDistance? =
    endGap?.takeIf { stopPoint == null && it > ROUTE_END_NOTE_ABOVE }?.let(ImperialFormat::distance)
