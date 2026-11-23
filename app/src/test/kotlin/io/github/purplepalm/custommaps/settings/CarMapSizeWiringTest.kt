package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the app hands Settings' car map size to the car's sessions: read from the app's start on, so that a car map made
 * later has the stored size before its first surface. The graph needs Android, so the statement is pinned by its source
 * ([Sources]); the session's part is the car's CarMapSizeWiringTest, the map's the engine's.
 */
class CarMapSizeWiringTest {

    @Test
    fun `the car's parts hold the stored size from the app's start on`() {
        val graph = Sources.code("AppGraph.kt").lines().map { it.trim() }
        assertTrue("mapSize = settings.carMapSize.stateIn(applicationScope, SharingStarted.Eagerly, CarMapSize.DEFAULT)," in graph)
    }
}
