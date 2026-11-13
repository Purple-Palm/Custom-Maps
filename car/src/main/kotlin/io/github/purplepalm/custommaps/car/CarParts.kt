package io.github.purplepalm.custommaps.car

import androidx.car.app.CarContext
import androidx.lifecycle.LifecycleOwner
import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What every car session is made from, one for the process, wired by hand in the app's graph
 * (DECISIONS 12). The car knows the drive only through the engine, the same one the phone's screen
 * shows, and draws its map through the factory the engine gives it.
 */
class CarParts(
    /** The drive: the same one the phone shows, which outlives both screens (DECISIONS 32). */
    val navigation: NavigationEngine,
    /** Whether maps may be shown yet, and for which token. */
    val mapsAccess: StateFlow<MapsAccess>,
    /** Makes the map on the car's surface for a session's car context; one per session at a time. */
    val createMap: (CarContext) -> CarMapRenderer,
    /**
     * Keeps the navigator alive while the given session is at least created. Never undone by hand:
     * the navigator lets the session go by itself when it is destroyed. Starts nothing that is billed.
     */
    val attachHost: (LifecycleOwner) -> Unit,
    /** The settings the car's map follows, as the phone's does. */
    val mapSettings: CarMapSettings,
    val debugLog: DebugLog,
)

/** The phone's settings that the car's map takes too, each as it changes. */
class CarMapSettings(
    val cameraStyle: Flow<CameraStyle>,
    val laneZoom: Flow<LaneZoom>,
    val laneDetails: Flow<Boolean>,
    val ownRouteLine: Flow<Boolean>,
    val drawOwnLanes: Flow<Boolean>,
)
