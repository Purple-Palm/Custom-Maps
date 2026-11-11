package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.os.SystemClock
import com.mapbox.bindgen.Value
import com.mapbox.common.location.AccuracyLevel
import com.mapbox.common.location.DeviceLocationProvider
import com.mapbox.common.location.Location
import com.mapbox.common.location.LocationExtraKeys
import com.mapbox.common.location.LocationProviderRequest
import com.mapbox.common.location.LocationServiceFactory
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.guidance.FixTime
import io.github.purplepalm.custommaps.nav.guidance.StaleStart
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A position as a location service reported it: where the car is, which way and how fast it moves,
 * and how old the report is at the moment it is read.
 *
 * @param bearing the direction of travel in degrees clockwise from north; null when none came
 * @param speed the speed over the ground in meters a second; null when none came
 * @param ageMillis how long ago the position was measured; never below zero, and zero for a
 *   [replayed] one
 * @param replayed a position of a simulated drive ([isReplayed]): the car's position at the
 *   moment it is played, stamped on the replayer's clock, which is not the phone's
 */
internal data class DeviceFix(
    val point: Point,
    val bearing: Double?,
    val speed: Double?,
    val ageMillis: Long,
    val replayed: Boolean = false,
) {

    /** True when the position is no older than [DeviceLocation.MAX_AGE]: it says where the car is now. */
    val live: Boolean get() = ageMillis <= DeviceLocation.MAX_AGE.inWholeMilliseconds

    /**
     * The direction to send with a route request that starts at this position, or null. Only a
     * fresh position of a moving car has one: the direction of a car that stands or crawls is
     * noise, and that of an old position says nothing about where the car heads now.
     */
    fun originBearing(): Double? = bearing?.takeIf { it.isFinite() && live && (speed ?: 0.0) >= MOVING_METERS_PER_SECOND }

    companion object {
        /** From this speed on a car counts as moving, and its direction as known: about 7 mph. */
        const val MOVING_METERS_PER_SECOND = 3.0

        /**
         * The position of [location] with its age at the given moment; see [fixAgeMillis]. A
         * replayed position is as new as the moment it is played: its own times run ahead of the
         * phone's clock and say nothing about its age.
         */
        fun of(location: Location, nowNanos: Long, nowMillis: Long): DeviceFix {
            val replayed = location.isReplayed()
            return DeviceFix(
                point = Point.fromLngLat(location.longitude, location.latitude),
                bearing = location.bearing,
                speed = location.speed,
                ageMillis = if (replayed) 0L else fixAgeMillis(location.monotonicTimestamp, location.timestamp, nowNanos, nowMillis).coerceAtLeast(0L),
                replayed = replayed,
            )
        }

        /** The position of [location] with its age now. Reads two clocks and nothing else. */
        fun of(location: Location): DeviceFix = of(location, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
    }
}

/**
 * How old a position is at the given moment, in milliseconds. The monotonic time of a position is
 * Android's elapsed realtime for positions from its location service, and is compared with
 * [nowNanos]; a position without one has only its wall clock time, which is compared with
 * [nowMillis] and can be off, or below zero, when the phone's clock was set in between.
 */
internal fun fixAgeMillis(monotonicNanos: Long?, timestampMillis: Long, nowNanos: Long, nowMillis: Long): Long =
    if (monotonicNanos != null) (nowNanos - monotonicNanos) / NANOS_PER_MILLI else nowMillis - timestampMillis

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Whether this position was played by a simulated drive. The replayer marks its positions as mock
 * positions (`LocationExtraKeys.IS_MOCK`), and the navigator keeps the mark on the positions it
 * matches; so does Android for positions of a mock location app, which count the same. Their times
 * are those of the replayer, which runs ahead of the phone's clock.
 */
internal fun Location.isReplayed(): Boolean {
    val extras = extra?.contents as? Map<*, *> ?: return false
    return (extras[LocationExtraKeys.IS_MOCK] as? Value)?.contents == true
}

/**
 * When this position was measured, on both of its clocks, and whether it was replayed
 * ([isReplayed]), as [StaleStart] compares them.
 */
internal fun Location.fixTime(): FixTime = FixTime(elapsedNanos = monotonicTimestamp, wallMillis = timestamp, replayed = isReplayed())

/**
 * The last position the platform location service knows; needs no trip session. Asking for it
 * measures nothing new and keeps no location updates running.
 */
internal class DeviceLocation {

    private val provider: DeviceLocationProvider? by lazy {
        LocationServiceFactory.getOrCreate()
            .getDeviceLocationProvider(LocationProviderRequest.Builder().accuracy(AccuracyLevel.HIGH).build())
            .value
    }

    /** The service's last answer; null before the first one, and when it had no position. */
    @Volatile
    private var last: Location? = null

    /**
     * Asks the location service for the last position it knows and waits for its answer, which
     * [lastFix] gives from then on. For callers that can wait, such as a route request.
     */
    suspend fun refresh() {
        val provider = provider ?: return
        suspendCancellableCoroutine { continuation ->
            val request = provider.getLastLocation { location ->
                last = location
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { request.cancel() }
        }
    }

    /**
     * The position of the service's last answer with its age now, however old; null before the
     * first answer and when the service had no position. Returns at once and asks nobody: it is as
     * new as the last [refresh]. [DeviceFix.live] says whether it still tells where the car is.
     */
    fun lastFix(): DeviceFix? = last?.let(DeviceFix::of)

    companion object {
        /** A position older than this says little about where a car is now. */
        val MAX_AGE: Duration = 30.seconds
    }
}
