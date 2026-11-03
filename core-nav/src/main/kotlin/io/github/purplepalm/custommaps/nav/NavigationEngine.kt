package io.github.purplepalm.custommaps.nav

import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Routing and turn-by-turn guidance, independent of the map data provider. */
interface NavigationEngine {
    val guidanceState: StateFlow<GuidanceState>

    /** Emits while guidance is active. */
    val routeProgress: Flow<RouteProgressSnapshot>
}
