package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import android.content.Context
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.DefaultLocationProvider
import com.mapbox.maps.plugin.locationcomponent.LocationComponentConstants
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import io.github.purplepalm.custommaps.nav.camera.IdleFollow

/**
 * The puck outside guidance: the phone's own location, which needs no trip session. Give it to the
 * map's location component as its location provider.
 *
 * It is the Maps SDK's [DefaultLocationProvider] with two additions. [onGlide] hears each new
 * position at the moment the puck starts to glide there. And a position far from where the puck is
 * drawn ([IdleFollow.isJump]) puts the puck there at once: the location component would glide it
 * there in a second whatever the distance, and a camera that follows would cross all the map in
 * between. That happens when the map comes back on screen after the car drove on, and after a
 * long time without a position.
 *
 * The provider asks for locations only while the location component listens, which is while the
 * map's activity is started, so nothing here keeps the location on in the background. Main thread
 * only.
 *
 * @param drawn where the map draws the puck now; null before its first position
 */
internal class PlatformPuck(context: Context, private val drawn: () -> Point?) : LocationProvider {

    private val appContext = context.applicationContext
    private var provider = DefaultLocationProvider(appContext)

    /** What each consumer of this provider is registered as with the provider inside. */
    private val taps = LinkedHashMap<LocationConsumer, Tap>()

    /**
     * Called with each new position, right after the puck was told to go there, and with how long
     * the puck takes: [GLIDE_MS], or 0 when it is put there at once.
     */
    var onGlide: ((target: Point, glideMillis: Long) -> Unit)? = null

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

    /**
     * Passes everything on to [consumer], a far position as a jump and the rest unchanged, and
     * reports where the puck is going.
     */
    private inner class Tap(private val consumer: LocationConsumer) : LocationConsumer {

        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) {
            val target = location.lastOrNull()
            val from = drawn()
            val jump = target != null && from != null &&
                IdleFollow.isJump(from.latitude(), from.longitude(), target.latitude(), target.longitude())
            consumer.onLocationUpdated(*location, options = if (jump) JUMP else options)
            if (target != null) onGlide?.invoke(target, if (jump) 0L else GLIDE_MS)
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

        private val JUMP: ValueAnimator.() -> Unit = { duration = 0L }
    }
}
