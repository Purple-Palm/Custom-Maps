package io.github.purplepalm.custommaps.engine.mapbox.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OfflineLinesTest {

    @Test
    fun `the store is shared when the map and the navigator both have it`() {
        assertEquals(
            mapOf("shared" to true, "map" to true, "navigator" to true, "usage" to "READ_ONLY"),
            OfflineLines.tileStore(mapHasStore = true, navigatorHasStore = true, usageMode = "READ_ONLY"),
        )
    }

    @Test
    fun `the store is not shared when either side lacks it`() {
        val mapOnly = OfflineLines.tileStore(mapHasStore = true, navigatorHasStore = false, usageMode = "READ_ONLY")
        val navigatorOnly = OfflineLines.tileStore(mapHasStore = false, navigatorHasStore = true, usageMode = "READ_ONLY")
        val neither = OfflineLines.tileStore(mapHasStore = false, navigatorHasStore = false, usageMode = "DISABLED")

        assertEquals(false, mapOnly["shared"])
        assertEquals(false, navigatorOnly["shared"])
        assertEquals(false, neither["shared"])
        // Which side lacks it stays readable.
        assertEquals(listOf(true, false), listOf(mapOnly["map"], mapOnly["navigator"]))
        assertEquals(listOf(false, true), listOf(navigatorOnly["map"], navigatorOnly["navigator"]))
        assertEquals("DISABLED", neither["usage"])
    }
}
