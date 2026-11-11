package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.NetworkRestriction
import com.mapbox.geojson.Point
import io.github.purplepalm.custommaps.nav.geo.Geo
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Synthetic roads in the sea near the point 0, 0. */
class PreviewDownloadTest {

    /** One degree along the equator, in meters: about 111 km. */
    private val metersPerDegree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    /** A point [east] and [north] meters from the point 0, 0. */
    private fun at(east: Double, north: Double = 0.0): Point = Point.fromLngLat(east / metersPerDegree, north / metersPerDegree)

    private fun meters(a: Point, b: Point): Double = Geo.meters(a.latitude(), a.longitude(), b.latitude(), b.longitude())

    /** A road going east with a point every [step] meters, swinging [swing] meters north and south. */
    private fun road(points: Int, step: Double, swing: Double = 0.0): List<Point> =
        List(points) { index -> at(east = index * step, north = swing * sin(index / 7.0)) }

    @Test
    fun `there is one region, and each preview loads it under the same name`() {
        assertEquals("preview-routes", PreviewDownload.REGION_ID)
    }

    @Test
    fun `the region is the map's style from zoom 11 to 16`() {
        assertEquals("mapbox://styles/mapbox/standard", PreviewDownload.STYLE)
        assertEquals(ZoomBand(11, 16), PreviewDownload.ZOOMS)
        // The two bands guidance keeps, as one range, with no level between them left out.
        assertEquals(OfflineAreas.TOWN_ZOOMS.min, PreviewDownload.ZOOMS.min)
        assertEquals(OfflineAreas.STREET_ZOOMS.max, PreviewDownload.ZOOMS.max)
    }

    @Test
    fun `for now the download may use any network`() {
        // An open question to the owner; changing this constant is the whole change.
        assertEquals(NetworkRestriction.NONE, PreviewDownload.NETWORK)
    }

    @Test
    fun `a line of points close together keeps one about every hundred meters`() {
        // 10 km with a point every 10 m.
        val line = road(points = 1_001, step = 10.0)

        val thinned = PreviewDownload.thin(line)

        assertSame(line.first(), thinned.first())
        assertSame(line.last(), thinned.last())
        // One every 100 m, give or take the rounding of a distance that is exactly the limit.
        assertTrue(thinned.size in 91..101, "${thinned.size}")
        thinned.zipWithNext().dropLast(1).forEach { (a, b) -> assertTrue(meters(a, b) >= PreviewDownload.KEEP_EVERY_METERS) }
    }

    @Test
    fun `every point left out is within the limit of one that was kept`() {
        // A winding road: 5 km with a point every 5 m, swinging 150 m to either side.
        val line = road(points = 1_001, step = 5.0, swing = 150.0)

        val thinned = PreviewDownload.thin(line)

        assertTrue(thinned.size < line.size / 5, "${thinned.size}")
        val kept = thinned.toSet()
        for (point in line) {
            if (point in kept) continue
            assertTrue(thinned.any { meters(it, point) < PreviewDownload.KEEP_EVERY_METERS }, "$point")
        }
    }

    @Test
    fun `points that are far apart are all kept, in their order`() {
        // A straight road with a point every 2 km.
        val line = road(points = 20, step = 2_000.0)

        assertEquals(line, PreviewDownload.thin(line))
    }

    @Test
    fun `a line shorter than the limit keeps its two ends`() {
        // 60 m with a point every 10 m.
        val line = road(points = 7, step = 10.0)

        assertEquals(listOf(line.first(), line.last()), PreviewDownload.thin(line))
    }

    @Test
    fun `a line of two points is kept as it is`() {
        val line = listOf(at(0.0), at(5.0))

        assertSame(line, PreviewDownload.thin(line))
    }

    @Test
    fun `the last point is kept even when it is close to the one before it`() {
        // Points at 0, 100, 200 and 230 m: the end is 30 m after a kept point.
        val line = listOf(at(0.0), at(100.5), at(201.0), at(230.0))

        assertEquals(line, PreviewDownload.thin(line))
    }

    @Test
    fun `the shape has one line for each route, thinned`() {
        val first = road(points = 501, step = 10.0)
        val second = road(points = 301, step = 10.0, swing = 80.0)

        val shape = PreviewDownload.geometry(listOf(first, second))!!

        assertEquals(2, shape.coordinates().size)
        assertEquals(PreviewDownload.thin(first), shape.coordinates()[0])
        assertEquals(PreviewDownload.thin(second), shape.coordinates()[1])
    }

    @Test
    fun `a route without a line is left out of the shape`() {
        val route = road(points = 50, step = 40.0)

        val shape = PreviewDownload.geometry(listOf(emptyList(), route, listOf(at(0.0))))!!

        assertEquals(listOf(PreviewDownload.thin(route)), shape.coordinates())
    }

    @Test
    fun `no route with a line gives no shape`() {
        assertNull(PreviewDownload.geometry(emptyList()))
        assertNull(PreviewDownload.geometry(listOf(emptyList(), listOf(at(0.0)))))
    }
}
