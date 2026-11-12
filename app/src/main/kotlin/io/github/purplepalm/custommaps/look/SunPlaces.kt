package io.github.purplepalm.custommaps.look

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import java.time.Instant
import java.time.zone.ZoneRules

/**
 * Where the sun is computed for, best first ([PlaceSource]): the newest position this process has,
 * the location service's last known one, or the meridian of the phone's time zone. Each is rounded
 * to a tenth of a degree as it is read ([SunPlace.of]) and kept in memory only: never stored,
 * logged or put into a message.
 */
class SunPlaces(
    /** The newest position this process has: the navigator's or the puck's. */
    private val fix: () -> GeoPoint?,
    /** The location service's last known position ([LastKnownPosition]). */
    private val lastKnown: () -> GeoPoint?,
    /** The rules of the phone's time zone. */
    private val zone: () -> ZoneRules,
    private val now: () -> Instant,
) {

    /** The best place there is now. Never fails: a source that cannot answer is passed over. */
    fun current(): SunPlace =
        read(fix)?.let { SunPlace.of(it.latitude, it.longitude, PlaceSource.FIX) }
            ?: read(lastKnown)?.let { SunPlace.of(it.latitude, it.longitude, PlaceSource.LAST_KNOWN) }
            ?: timeZone()

    private fun read(source: () -> GeoPoint?): GeoPoint? =
        try {
            source()
        } catch (e: RuntimeException) {
            null
        }

    /**
     * The meridian of the zone's standard offset, without summer time, which moves the clocks and
     * not the sun. A zone that cannot be read stands in as the prime meridian.
     */
    private fun timeZone(): SunPlace =
        try {
            SunPlace.ofTimeZone(zone().getStandardOffset(now()).totalSeconds)
        } catch (e: RuntimeException) {
            SunPlace.ofTimeZone(0)
        }
}

/**
 * The newest last known position of the location providers that are on, as the location service
 * keeps it: a call that turns nothing on and answers at once. Null without the location permission,
 * or when no provider has one.
 */
class LastKnownPosition(private val context: Context) : () -> GeoPoint? {

    override fun invoke(): GeoPoint? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { positionOrNull(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            // The permission was taken back between the check and the call.
            null
        } catch (e: IllegalArgumentException) {
            // A provider went away between the list and the call.
            null
        }
    }
}

/** A position for values that are one, or null: never an exception whose message would carry them. */
internal fun positionOrNull(latitude: Double, longitude: Double): GeoPoint? =
    if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        GeoPoint(latitude, longitude)
    } else {
        null
    }
