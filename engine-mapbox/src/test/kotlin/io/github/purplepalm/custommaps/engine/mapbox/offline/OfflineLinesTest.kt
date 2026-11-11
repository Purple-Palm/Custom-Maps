package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.TileRegionErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    /** A finished download of 9 MB, of which the phone already had 1.5 MB. */
    private val counts = DownloadCounts(required = 50, completed = 50, completedBytes = 9_000_000, loadedBytes = 7_500_000)

    @Test
    fun `a download without an error is done`() {
        assertEquals(OfflineLines.Outcome("done"), OfflineLines.outcome(errorType = null, endedBy = null))
    }

    @Test
    fun `a download that finished before the app ended it is still done`() {
        assertEquals(OfflineLines.Outcome("done"), OfflineLines.outcome(errorType = null, endedBy = EndedBy.NEWER_PREVIEW))
        assertEquals(OfflineLines.Outcome("done"), OfflineLines.outcome(errorType = null, endedBy = EndedBy.GUIDANCE))
    }

    @Test
    fun `a download with resources that failed is partial, whoever ended it`() {
        assertEquals(OfflineLines.Outcome("partial"), OfflineLines.outcome("PARTIAL_LOAD", endedBy = null))
        assertEquals(OfflineLines.Outcome("partial"), OfflineLines.outcome("PARTIAL_LOAD", endedBy = EndedBy.GUIDANCE))
    }

    @Test
    fun `a cancel for a newer preview is a replacement and no fault`() {
        assertEquals(OfflineLines.Outcome("replaced"), OfflineLines.outcome("CANCELED", EndedBy.NEWER_PREVIEW))
    }

    @Test
    fun `a cancel at the start of guidance is a stop with its reason`() {
        assertEquals(OfflineLines.Outcome("stopped", "guidance"), OfflineLines.outcome("CANCELED", EndedBy.GUIDANCE))
    }

    @Test
    fun `a cancel nobody here asked for is a failure`() {
        assertEquals(OfflineLines.Outcome("failed", "canceled"), OfflineLines.outcome("CANCELED", endedBy = null))
    }

    @Test
    fun `every other error of the tile store is a failure named after it`() {
        val own = setOf(TileRegionErrorType.CANCELED, TileRegionErrorType.PARTIAL_LOAD)
        val others = TileRegionErrorType.values().filterNot { it in own }
        // The store has five more today; a new one would be named the same way.
        assertEquals(5, others.size)

        for (type in others) {
            for (endedBy in listOf(null, EndedBy.NEWER_PREVIEW, EndedBy.GUIDANCE)) {
                assertEquals(OfflineLines.Outcome("failed", type.name.lowercase()), OfflineLines.outcome(type.name, endedBy), "$type $endedBy")
            }
        }
        assertEquals("disk_full", OfflineLines.outcome("DISK_FULL", endedBy = null).why)
        assertEquals("tileset_descriptor", OfflineLines.outcome("TILESET_DESCRIPTOR", endedBy = null).why)
    }

    @Test
    fun `the two errors with an outcome of their own are spelled as the tile store spells them`() {
        assertEquals("replaced", OfflineLines.outcome(TileRegionErrorType.CANCELED.name, EndedBy.NEWER_PREVIEW).result)
        assertEquals("partial", OfflineLines.outcome(TileRegionErrorType.PARTIAL_LOAD.name, endedBy = null).result)
    }

    @Test
    fun `the line of a finished region has its counts, both sizes and the time`() {
        assertEquals(
            mapOf(
                "load" to 3,
                "routes" to 2,
                "result" to "done",
                "required" to 50L,
                "completed" to 50L,
                "completedBytes" to 9_000_000L,
                "loadedBytes" to 7_500_000L,
                "seconds" to 12.3,
            ),
            OfflineLines.region(load = 3, routes = 2, errorType = null, message = null, endedBy = null, counts = counts, seconds = 12.34),
        )
    }

    @Test
    fun `a region that had everything says that nothing was loaded`() {
        val line = OfflineLines.region(1, 1, errorType = null, message = null, endedBy = null, counts = counts.copy(loadedBytes = 0), seconds = 0.2)

        assertEquals(0L, line["loadedBytes"])
        assertEquals(9_000_000L, line["completedBytes"])
    }

    @Test
    fun `a partial region says how many resources failed and what the store said`() {
        val partial = counts.copy(completed = 46, errored = 4)
        val line = OfflineLines.region(1, 3, "PARTIAL_LOAD", "4 resources failed", endedBy = null, counts = partial, seconds = 30.0)

        assertEquals("partial", line["result"])
        assertEquals("4 resources failed", line["message"])
        assertEquals(4L, line["errored"])
        assertEquals(46L, line["completed"])
        assertEquals(50L, line["required"])
        assertFalse("why" in line)
    }

    @Test
    fun `a replaced region keeps how far it came and drops the store's word for the cancel`() {
        val soFar = DownloadCounts(required = 50, completed = 20, completedBytes = 3_000_000, loadedBytes = 2_000_000)
        val line = OfflineLines.region(1, 3, "CANCELED", "Canceled", EndedBy.NEWER_PREVIEW, soFar, seconds = 4.0)

        assertEquals(
            mapOf(
                "load" to 1,
                "routes" to 3,
                "result" to "replaced",
                "required" to 50L,
                "completed" to 20L,
                "completedBytes" to 3_000_000L,
                "loadedBytes" to 2_000_000L,
                "seconds" to 4.0,
            ),
            line,
        )
    }

    @Test
    fun `a region stopped by guidance says so`() {
        val line = OfflineLines.region(2, 1, "CANCELED", "Canceled", EndedBy.GUIDANCE, counts = null, seconds = 1.0)

        assertEquals(mapOf("load" to 2, "routes" to 1, "result" to "stopped", "why" to "guidance", "seconds" to 1.0), line)
    }

    @Test
    fun `a failed region gives the reason and the store's message, and no numbers it never had`() {
        val line = OfflineLines.region(1, 1, "TILESET_DESCRIPTOR", "the style could not be resolved", endedBy = null, counts = null, seconds = 0.44)

        assertEquals(
            mapOf(
                "load" to 1,
                "routes" to 1,
                "result" to "failed",
                "why" to "tileset_descriptor",
                "message" to "the style could not be resolved",
                "seconds" to 0.4,
            ),
            line,
        )
    }

    @Test
    fun `bytes the store never reported are left out, not written as none`() {
        val line = OfflineLines.region(1, 1, errorType = null, message = null, endedBy = null, counts = counts.copy(loadedBytes = null), seconds = 1.0)

        assertFalse("loadedBytes" in line)
        assertEquals(9_000_000L, line["completedBytes"])
        // No resource failed: nothing to say about that either.
        assertFalse("errored" in line)
    }

    @Test
    fun `a region whose routes had no line failed before anything was asked`() {
        val line = OfflineLines.region(4, 2, OfflineLines.NO_GEOMETRY, message = null, endedBy = null, counts = null, seconds = 0.0)

        assertEquals(mapOf("load" to 4, "routes" to 2, "result" to "failed", "why" to "no_geometry", "seconds" to 0.0), line)
    }

    @Test
    fun `the start line names the load, the routes and what may be used`() {
        assertEquals(
            mapOf(
                "load" to 5,
                "routes" to 3,
                "points" to 420,
                "zooms" to listOf(11, 16),
                "pixelRatio" to 2.5f,
                "network" to "NONE",
                // Each route's length in km, and how far along it the region reaches.
                "routeKm" to listOf(412.3, 38.0, 99.9),
                "keptKm" to listOf(100.1, 38.0, 99.9),
                "maxKm" to 100.0,
            ),
            OfflineLines.regionStart(
                load = 5,
                routes = 3,
                points = 420,
                zooms = ZoomBand(11, 16),
                pixelRatio = 2.5f,
                network = "NONE",
                routeMeters = listOf(412_345.6, 38_012.0, 99_949.0),
                keptMeters = listOf(100_120.0, 38_012.0, 99_949.0),
                maxMeters = 100_000.0,
            ),
        )
    }

    @Test
    fun `the style pack line has the same fields without a load`() {
        val pack = DownloadCounts(required = 12, completed = 12, completedBytes = 2_400_000, loadedBytes = 2_400_000)

        assertEquals(
            mapOf("result" to "done", "required" to 12L, "completed" to 12L, "completedBytes" to 2_400_000L, "loadedBytes" to 2_400_000L, "seconds" to 3.0),
            OfflineLines.stylePack(errorType = null, message = null, endedBy = null, counts = pack, seconds = 3.04),
        )
        assertEquals(
            mapOf("result" to "stopped", "why" to "guidance", "seconds" to 0.5),
            OfflineLines.stylePack("CANCELED", "Canceled", EndedBy.GUIDANCE, counts = null, seconds = 0.5),
        )
        val failed = OfflineLines.stylePack("OTHER", "no connection", endedBy = null, counts = null, seconds = 0.5)
        assertEquals(listOf("failed", "other", "no connection"), listOf(failed["result"], failed["why"], failed["message"]))
        assertNull(failed["loadedBytes"])
    }
}
