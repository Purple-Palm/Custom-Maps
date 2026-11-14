package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.ui.theme.Sources
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the app hands Settings' switch Lane counts between lane points, and Debug's Measure the drawn lanes, to the map
 * engine: as each stored value comes, from the app's start on. The graph needs Android, so the statements are pinned by
 * its source ([Sources]); what the engine does with them is the engine's tests'.
 */
class LaneCountsHorizonWiringTest {

    private val start = Sources.functionBody(Sources.code("AppGraph.kt"), "fun start()").lines().map { it.trim() }

    @Test
    fun `the stored switch reaches the engine as it changes, and the measuring switch with it`() {
        assertTrue("applicationScope.launch { settings.laneCountsHorizon.collect(engine::setLaneCountsHorizon) }" in start)
        assertTrue("applicationScope.launch { settings.measureOwnLanes.collect(engine::setMeasureOwnLanes) }" in start)
    }
}
