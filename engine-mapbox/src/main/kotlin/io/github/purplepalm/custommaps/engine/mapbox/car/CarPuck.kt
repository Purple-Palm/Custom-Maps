package io.github.purplepalm.custommaps.engine.mapbox.car

import android.animation.ValueAnimator
import android.view.Choreographer
import com.mapbox.common.location.LocationError
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.LocationComponentConstants
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import io.github.purplepalm.custommaps.engine.mapbox.map.camera.PuckRelay
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.car.PuckTrack
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * The car map's puck on the car's frame clock. Mapbox's location component glides the puck with an
 * animator for each update, and an animator moves only on the main thread's display frames: on the
 * first car test the car's puck stood still while those were gone. So the car's location component
 * takes this as its location provider. It hears the provider it [follow]s, the platform's location
 * outside guidance or the engine's guidance puck during a drive, with each update's points and its
 * animator options (how long the glide takes, 0 for a jump), works out where the puck is at each tick
 * of the car's clock ([PuckTrack]: one speed through the points, the bearing the short way) and hands
 * the component that position and bearing with no duration, which it draws at once, without waiting
 * for a frame. The car's camera follows this puck as it follows the phone's.
 *
 * The location component's own defaults are its glide's: a second, at one speed. With Android's
 * animations off every update is a jump, as the phone's puck has it. While a frame is wanted the puck
 * asks the clock first, so the camera of the same tick sees where the puck is now. Every call into it
 * catches what is thrown and writes a "car_error" line: the car and the phone share one process.
 * Main thread only.
 */
internal class CarPuck(private val frames: CarFrames, private val log: DebugLog) : PuckRelay, Choreographer.FrameCallback {

    private val track = PuckTrack()

    /** The car map's location component, while it listens. */
    private var component: LocationConsumer? = null

    /** The provider whose updates the puck draws. */
    private var source: LocationProvider? = null

    private var posted = false
    private var released = false

    // An update's points, copied for the track.
    private var latitudes = DoubleArray(POINTS)
    private var longitudes = DoubleArray(POINTS)
    private var bearings = DoubleArray(POINTS)

    // What the component was handed last, so a puck at rest is not handed the same again.
    private var drawnLatitude = Double.NaN
    private var drawnLongitude = Double.NaN
    private var drawnBearing = Double.NaN

    /** The glide of an update without options: the location component's default, unless the provider changes it. */
    private var positionMillis = LocationComponentConstants.DEFAULT_INTERVAL_MILLIS
    private var bearingMillis = LocationComponentConstants.DEFAULT_INTERVAL_MILLIS

    /** Reads the duration an update's options give, without running an animation. */
    private val probe = ValueAnimator()

    /** What the provider says, for the track; what is not about the puck's place goes to the component as it is. */
    private val tap = object : LocationConsumer {
        override fun onLocationUpdated(vararg location: Point, options: (ValueAnimator.() -> Unit)?) = guard("puckLocation") {
            val count = location.size
            if (count == 0) return@guard
            if (latitudes.size < count) {
                latitudes = DoubleArray(count)
                longitudes = DoubleArray(count)
            }
            for (index in 0 until count) {
                latitudes[index] = location[index].latitude()
                longitudes[index] = location[index].longitude()
            }
            track.onPosition(latitudes, longitudes, count, durationNanos(options, positionMillis), System.nanoTime())
            wake()
        }

        override fun onBearingUpdated(vararg bearing: Double, options: (ValueAnimator.() -> Unit)?) = guard("puckBearing") {
            val count = bearing.size
            if (count == 0) return@guard
            if (bearings.size < count) bearings = DoubleArray(count)
            for (index in 0 until count) bearings[index] = bearing[index]
            track.onBearing(bearings, count, durationNanos(options, bearingMillis), System.nanoTime())
            wake()
        }

        override fun onPuckLocationAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = guard("puckLocationDefaults") {
            positionMillis = durationMillis(options, positionMillis)
        }

        override fun onPuckBearingAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = guard("puckBearingDefaults") {
            bearingMillis = durationMillis(options, bearingMillis)
        }

        override fun onHorizontalAccuracyRadiusUpdated(vararg radius: Double, options: (ValueAnimator.() -> Unit)?) = guard("puckAccuracy") {
            component?.onHorizontalAccuracyRadiusUpdated(*radius, options = options)
        }

        override fun onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options: ValueAnimator.() -> Unit) = guard("puckAccuracyDefaults") {
            component?.onPuckAccuracyRadiusAnimatorDefaultOptionsUpdated(options)
        }

        override fun onError(error: LocationError) = guard("puckError") { component?.onError(error) }
    }

    override fun follow(source: LocationProvider) {
        if (released || source === this.source) return
        guard("puckFollow") {
            if (component != null) this.source?.unRegisterLocationConsumer(tap)
            this.source = source
            // A provider replays its newest position to a new listener, which puts the puck there.
            if (component != null) source.registerLocationConsumer(tap)
        }
    }

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        if (released || locationConsumer === component) return
        guard("puckRegister") {
            val listening = component != null
            component = locationConsumer
            // Drawn anew for the component that came.
            drawnLatitude = Double.NaN
            drawnLongitude = Double.NaN
            drawnBearing = Double.NaN
            if (!listening) source?.registerLocationConsumer(tap)
            wake()
        }
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        if (locationConsumer !== component) return
        guard("puckUnregister") {
            component = null
            // The provider asks for positions only while someone listens.
            source?.unRegisterLocationConsumer(tap)
            sleep()
        }
    }

    override fun release() {
        if (released) return
        released = true
        guard("puckRelease") {
            if (component != null) source?.unRegisterLocationConsumer(tap)
            source = null
            component = null
            sleep()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        val consumer = component ?: return
        val moving = track.at(frameTimeNanos)
        val latitude = track.latitude
        val longitude = track.longitude
        if (!latitude.isNaN() && (latitude != drawnLatitude || longitude != drawnLongitude)) {
            drawnLatitude = latitude
            drawnLongitude = longitude
            consumer.onLocationUpdated(Point.fromLngLat(longitude, latitude), options = AT_ONCE)
        }
        val bearing = track.bearing
        if (!bearing.isNaN() && bearing != drawnBearing) {
            drawnBearing = bearing
            consumer.onBearingUpdated(bearing, options = AT_ONCE)
        }
        if (moving) wake()
    }

    /** Asks the clock for the next tick, ahead of the camera. */
    private fun wake() {
        if (posted || released || component == null) return
        posted = true
        frames.postFirst(this)
    }

    private fun sleep() {
        if (posted) frames.remove(this)
        posted = false
    }

    /** How long an update with [options] glides, in nanoseconds; [defaultMillis] without options, 0 with Android's animations off. */
    private fun durationNanos(options: (ValueAnimator.() -> Unit)?, defaultMillis: Long): Long {
        if (!ValueAnimator.areAnimatorsEnabled()) return 0L
        val millis = if (options == null) defaultMillis else durationMillis(options, defaultMillis)
        return millis * NANOS_PER_MILLI
    }

    /** The duration [options] set on an animator that starts with [defaultMillis]. */
    private fun durationMillis(options: ValueAnimator.() -> Unit, defaultMillis: Long): Long {
        probe.duration = defaultMillis
        probe.options()
        return probe.duration.coerceAtLeast(0L)
    }

    private inline fun guard(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            log.log(ERROR_LINE, CarFailure.fields(call, e))
        }
    }

    private companion object {
        /** The component draws the position and the bearing it is handed at once, inside the call. */
        val AT_ONCE: ValueAnimator.() -> Unit = { duration = 0L }

        /** Points of an update: the navigator sends a few key points with each position. */
        const val POINTS = 8

        const val NANOS_PER_MILLI = 1_000_000L
        const val ERROR_LINE = "car_error"
    }
}
