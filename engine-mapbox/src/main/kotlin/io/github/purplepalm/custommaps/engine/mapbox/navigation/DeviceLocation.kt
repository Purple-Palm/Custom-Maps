package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.common.location.AccuracyLevel
import com.mapbox.common.location.DeviceLocationProvider
import com.mapbox.common.location.LocationProviderRequest
import com.mapbox.common.location.LocationServiceFactory
import com.mapbox.geojson.Point
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Last known device position from the platform location service; needs no trip session. */
internal class DeviceLocation {

    private val provider: DeviceLocationProvider? by lazy {
        LocationServiceFactory.getOrCreate()
            .getDeviceLocationProvider(LocationProviderRequest.Builder().accuracy(AccuracyLevel.HIGH).build())
            .value
    }

    suspend fun lastKnown(): Point? {
        val provider = provider ?: return null
        return suspendCancellableCoroutine { continuation ->
            val request = provider.getLastLocation { location ->
                if (continuation.isActive) {
                    continuation.resume(location?.let { Point.fromLngLat(it.longitude, it.latitude) })
                }
            }
            continuation.invokeOnCancellation { request.cancel() }
        }
    }
}
