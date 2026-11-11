package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackLoadProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which of the store's numbers ends up where on a line. Every number is different, so a swap shows. */
class StoreCountsTest {

    private val region = TileRegion("preview-routes", 101L, 102L, 103L, null, null)

    /** Completed 201, completed bytes 202, errored 203, required 204, loaded 205, loaded bytes 206. */
    private val regionProgress = TileRegionLoadProgress(201L, 202L, 203L, 204L, 205L, 206L)

    private val pack = StylePack("mapbox://styles/mapbox/standard", GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY, 301L, 302L, 303L, null, null)

    /** Completed 401, completed bytes 402, errored 403, required 404, loaded 405, loaded bytes 406. */
    private val packProgress = StylePackLoadProgress(401L, 402L, 403L, 404L, 405L, 406L)

    @Test
    fun `a region's sizes come from the region, what it cost and what failed from the last progress`() {
        assertEquals(
            DownloadCounts(required = 101, completed = 102, completedBytes = 103, loadedBytes = 206, errored = 203),
            downloadCounts(region, regionProgress),
        )
    }

    @Test
    fun `a region that reported no progress has no loaded bytes`() {
        assertEquals(
            DownloadCounts(required = 101, completed = 102, completedBytes = 103, loadedBytes = null, errored = 0),
            downloadCounts(region, null as TileRegionLoadProgress?),
        )
    }

    @Test
    fun `without the region every number comes from the progress`() {
        assertEquals(
            DownloadCounts(required = 204, completed = 201, completedBytes = 202, loadedBytes = 206, errored = 203),
            downloadCounts(null as TileRegion?, regionProgress),
        )
    }

    @Test
    fun `a region load the store said nothing about has no numbers`() {
        assertNull(downloadCounts(null as TileRegion?, null as TileRegionLoadProgress?))
    }

    @Test
    fun `a style pack's numbers are read the same way`() {
        assertEquals(
            DownloadCounts(required = 301, completed = 302, completedBytes = 303, loadedBytes = 406, errored = 403),
            downloadCounts(pack, packProgress),
        )
        assertEquals(
            DownloadCounts(required = 301, completed = 302, completedBytes = 303, loadedBytes = null, errored = 0),
            downloadCounts(pack, null as StylePackLoadProgress?),
        )
        assertEquals(
            DownloadCounts(required = 404, completed = 401, completedBytes = 402, loadedBytes = 406, errored = 403),
            downloadCounts(null as StylePack?, packProgress),
        )
        assertNull(downloadCounts(null as StylePack?, null as StylePackLoadProgress?))
    }
}
