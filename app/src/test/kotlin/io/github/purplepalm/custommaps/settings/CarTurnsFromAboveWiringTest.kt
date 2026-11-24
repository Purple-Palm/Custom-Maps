package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the app hands Settings' Turns from above to the car's sessions: the stored switch, every change of it. The graph
 * needs Android, so the statement is pinned by its source ([Sources]); the session's part is the car's
 * CarTurnsFromAboveWiringTest, the map's the engine's.
 */
class CarTurnsFromAboveWiringTest {

    @Test
    fun `the car's parts follow the stored switch`() {
        val graph = Sources.code("AppGraph.kt").lines().map { it.trim() }
        assertTrue("turnsFromAbove = settings.carTurnsFromAbove," in graph)
    }
}
