package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import com.mapbox.common.location.Location
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider

/**
 * The puck during guidance: it glides to each matched position of the navigator, through the points
 * the navigator sends with it. Give it to the map's location component as its location provider.
 *
 * Every drive gets a fresh [NavigationLocationProvider] inside. A provider replays its last fix to a
 * consumer that registers, so one kept from the drive before would first put the puck where that
 * drive ended. Main thread only.
 */
internal class GuidancePuck : LocationProvider {

    /** How long the puck glides to each new position. */
    var glideMillis = STANDARD_GLIDE_MS
        set(value) {
            field = value
            provider.puckAnimationDuration = value
        }

    /**
     * Whether a jump of the matched position, as when the navigator matches the car to another road,
     * moves the puck there at once. Otherwise it slides across the map like any other move.
     */
    var jumpOnTeleport = false

    private val consumers = LinkedHashSet<LocationConsumer>()
    private var provider = newProvider()

    /** The latest position of this drive; null before the first. */
    val lastLocation: Location? get() = provider.lastLocation

    /** Call when guidance starts: forgets the positions of the drive before. */
    fun newDrive() {
        val previous = provider
        provider = newProvider()
        for (consumer in consumers) {
            previous.unRegisterLocationConsumer(consumer)
            provider.registerLocationConsumer(consumer)
        }
    }

    fun onFix(result: LocationMatcherResult) {
        val jump = if (jumpOnTeleport && result.isTeleport) JUMP else null
        provider.changePosition(result.enhancedLocation, result.keyPoints, jump, jump)
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        if (consumers.add(locationConsumer)) provider.registerLocationConsumer(locationConsumer)
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.remove(locationConsumer)
        provider.unRegisterLocationConsumer(locationConsumer)
    }

    private fun newProvider() = NavigationLocationProvider().apply { puckAnimationDuration = glideMillis }

    companion object {
        /** The navigation SDK's own glide: as long as the navigator's updates are apart. */
        const val STANDARD_GLIDE_MS = 1_000L

        /**
         * A little longer than the updates are apart, also when one comes late, so the puck never
         * stops between two of them. It trails the matched position by 0.1 s more.
         */
        const val SMOOTH_GLIDE_MS = 1_100L

        private val JUMP: ValueAnimator.() -> Unit = { duration = 0L }
    }
}
