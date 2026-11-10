package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import android.content.Context
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.DefaultLocationProvider
import com.mapbox.maps.plugin.locationcomponent.LocationComponentConstants
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider

/**
 * The puck outside guidance: the phone's own location, which needs no trip session. Give it to the
 * map's location component as its location provider.
 *
 * It is the Maps SDK's [DefaultLocationProvider] with one addition: [onGlide] hears each new
 * position at the moment the puck starts to glide there. The provider asks for locations only while
 * the location component listens, which is while the map's activity is started, so nothing here
 * keeps the location on in the background. Main thread only.
 */
internal class PlatformPuck(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private var provider = DefaultLocationProvider(appContext)

    /** What each consumer of this provider is registered as with the provider inside. */
    private val taps = LinkedHashMap<LocationConsumer, Tap>()

    /** Called with each new position, right after the puck was told to glide there over [GLIDE_MS]. */
    var onGlide: ((Point) -> Unit)? = null

    /**
     * Starts over with a new provider inside. One that was created before the driver granted
     * location access notices the grant only at its next check, which can take a few seconds.
     */
    fun restart() {
        val previous = provider
        provider = DefaultLocationProvider(appContext)
        for (tap in taps.values) {
            previous.unRegisterLocationConsumer(tap)
            provider.registerLocationConsumer(tap)
        }
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        if (locationConsumer in taps) return
        val tap = Tap(locationConsumer)
        taps[locationConsumer] = tap
        provider.registerLocationConsumer(tap)
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        val tap = taps.remove(locationConsumer) ?: return
        provider.unRegisterLocationConsumer(tap)
    }

    /** Passes everything on to [consumer] unchanged, and reports where the puck is going. */
    private inner class Tap(private val consumer: LocationConsumer) : LocationConsumer {

        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
            consumer.onLocationUpdated(*location, options = options)
            location.lastOrNull()?.let { target -> onGlide?.invoke(target) }
        }

        override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) =
            consumer.onBearingUpdated(*bearing, options = options)

        override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) =
            consumer.onHorizontalAccuracyRadiusUpdated(*radius, options = options)

        override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumer.onPuckLocationAnimatorDefaultOptionsUpdated(options)

        override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumer.onPuckBearingAnimatorDefaultOptionsUpdated(options)

        override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) =
            consumer.onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options)

        override fun onError(error: LocationError) = consumer.onError(error)
    }

    companion object {
        /**
         * How long the location component glides the puck to a position of this provider: its own
         * default, at a steady speed.
         */
        const val GLIDE_MS = LocationComponentConstants.DEFAULT_INTERVAL_MILLIS
    }
}
