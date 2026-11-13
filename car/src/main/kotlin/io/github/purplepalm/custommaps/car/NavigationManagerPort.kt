package io.github.purplepalm.custommaps.car

import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Trip
import io.github.purplepalm.custommaps.car.routing.CarRouting

/**
 * The calls of the car's NavigationManager the guidance bridge makes, so that its rules are tested on
 * the JVM ([CarGuidanceBridge]). Every call is on the main thread and may throw: the bridge catches each.
 */
interface NavigationManagerPort {
    /** Sets the callback, in the form with one argument: [onStop] runs on the main thread when the host asks the app to stop navigating. */
    fun setCallback(onStop: () -> Unit)

    /** Clears the callback; the library refuses it while navigating. */
    fun clearCallback()

    /** The app navigates now; the library refuses it without a callback and does nothing the second time. */
    fun navigationStarted()

    /** The app navigates no more; nothing the second time. */
    fun navigationEnded()

    /** Sends the trip of [routing]; the library refuses it while not navigating. */
    fun updateTrip(routing: CarRouting)
}

/** The session's own NavigationManager behind [NavigationManagerPort], with the trip made by [trip]. */
internal class CarNavigationManager(
    private val manager: NavigationManager,
    private val trip: (CarRouting) -> Trip,
) : NavigationManagerPort {

    override fun setCallback(onStop: () -> Unit) {
        manager.setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() = onStop()
            },
        )
    }

    override fun clearCallback() = manager.clearNavigationManagerCallback()

    override fun navigationStarted() = manager.navigationStarted()

    override fun navigationEnded() = manager.navigationEnded()

    override fun updateTrip(routing: CarRouting) = manager.updateTrip(trip(routing))
}
