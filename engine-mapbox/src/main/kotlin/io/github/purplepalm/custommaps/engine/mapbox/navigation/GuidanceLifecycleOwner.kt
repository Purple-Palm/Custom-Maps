package io.github.purplepalm.custommaps.engine.mapbox.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Attached to MapboxNavigationApp for the length of a drive, so the navigator (and with it the
 * trip session) survives the phone screen being closed. Recreating the navigator would bill a new trip.
 */
internal class GuidanceLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }

    override val lifecycle: Lifecycle get() = registry
}
