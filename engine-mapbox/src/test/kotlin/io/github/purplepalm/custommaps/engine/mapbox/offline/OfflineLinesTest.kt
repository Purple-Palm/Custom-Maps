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

    @Test
    fun `the predictive cache line gives every area as car, route and destination`() {
        assertEquals(
            mapOf(
                "roadData" to mapOf("meters" to listOf(10_000, 3_000, 10_000)),
                "streetMap" to mapOf("zooms" to listOf(15, 16), "meters" to listOf(2_000, 1_000, 2_000)),
                "townMap" to mapOf("zooms" to listOf(11, 14), "meters" to listOf(10_000, 5_000, 10_000)),
            ),
            OfflineLines.predictiveCache(),
        )
    }
}
