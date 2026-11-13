package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.maps.plugin.locationcomponent.LocationProvider

/**
 * A puck that draws on frames of its own: the map's location component takes it as its location
 * provider, and it takes what another provider says ([follow]) and hands it to the component frame by
 * frame. The car's map has one ([io.github.purplepalm.custommaps.engine.mapbox.car.CarPuck]); the
 * phone's location component takes the providers themselves. Main thread only.
 */
internal interface PuckRelay : LocationProvider {

    /** The provider whose updates the puck draws from now on: the platform's location, or the guidance puck. */
    fun follow(source: LocationProvider)

    /** Lets go of the provider and of the location component, and draws no more. */
    fun release()
}
