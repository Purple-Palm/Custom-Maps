package io.github.purplepalm.custommaps.engine.mapbox.navigation

import android.content.Context
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import io.github.purplepalm.custommaps.nav.trip.TripVitals

/**
 * The phone's counters a trip's summary compares at its start and end: all the bytes the app has
 * received since the phone started, over any network, and the battery level. Each asks the system:
 * call [read] off the main thread.
 */
internal class PhoneVitals(context: Context) {

    private val battery: BatteryManager? = context.getSystemService(BatteryManager::class.java)

    fun read(): TripVitals = TripVitals(
        // UNSUPPORTED (-1) where the phone keeps no count.
        receivedBytes = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0L },
        batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..FULL },
    )

    private companion object {
        const val FULL = 100
    }
}
