package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.NetworkRestriction
import com.mapbox.common.TilesetDescriptor
import com.mapbox.geojson.MultiLineString
import com.mapbox.geojson.Point
import com.mapbox.maps.GlyphsRasterizationMode
import io.github.purplepalm.custommaps.nav.geo.Geo
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `the region's tiles are the style at both zooms, drawn for the screen`() {
        val options = PreviewDownload.descriptorOptions(pixelRatio = 2.75f)

        assertEquals(PreviewDownload.STYLE, options.styleURI)
        // Each zoom is 0 when it is left out.
        assertEquals(11.toByte(), options.minZoom)
        assertEquals(16.toByte(), options.maxZoom)
        assertEquals(2.75f, options.pixelRatio)
        assertNull(options.tilesets)
    }

    @Test
    fun `the region is loaded from the routes' start, taking what the phone has, over the chosen networks`() {
        val geometry = MultiLineString.fromLngLats(listOf(road(points = 3, step = 500.0)))
        // Made by the offline manager on the phone; a stand-in here, with no native side.
        val descriptor = object : TilesetDescriptor(0L) {}
        val origin = at(east = 10.0, north = 20.0)

        val options = PreviewDownload.loadOptions(geometry, listOf(descriptor), origin)

        assertSame(geometry, options.geometry)
        assertEquals(listOf(descriptor), options.descriptors)
        assertEquals(origin, options.startLocation)
        // Tiles past their date are taken as they are, and the network is the one constant's.
        assertTrue(options.acceptExpired)
        assertEquals(PreviewDownload.NETWORK, options.networkRestriction)
        // The SDK does not implement an average rate, and nothing forces a refresh.
        assertNull(options.averageBytesPerSecond)
        assertNull(options.extraOptions)
        assertNull(options.metadata)
    }

    @Test
    fun `the style's own files are loaded anew when past their date, ideographs drawn on the phone`() {
        val options = PreviewDownload.stylePackOptions()

        assertFalse(options.acceptExpired)
        assertEquals(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY, options.glyphsRasterizationMode)
        assertNull(options.extraOptions)
    }

    @Test
    fun `a line of points close together keeps one about every hundred meters`() {
        // 10 km with a point every 10 m.
        val line = road(points = 1_001, step = 10.0)

        val thinned = PreviewDownload.thin(line).line

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

        val thinned = PreviewDownload.thin(line).line

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

        assertEquals(line, PreviewDownload.thin(line).line)
    }

    @Test
    fun `a line shorter than the limit keeps its two ends`() {
        // 60 m with a point every 10 m.
        val line = road(points = 7, step = 10.0)

        assertEquals(listOf(line.first(), line.last()), PreviewDownload.thin(line).line)
    }

    @Test
    fun `a line of two points is kept as it is`() {
        val line = listOf(at(0.0), at(5.0))

        assertEquals(line, PreviewDownload.thin(line).line)
    }

    @Test
    fun `the last point is kept even when it is close to the one before it`() {
        // Points at 0, 100, 200 and 230 m: the end is 30 m after a kept point.
        val line = listOf(at(0.0), at(100.5), at(201.0), at(230.0))

        assertEquals(line, PreviewDownload.thin(line).line)
    }

    @Test
    fun `a route reaches at most 100 km`() {
        assertEquals(100_000.0, PreviewDownload.MAX_ROUTE_METERS)
    }

    @Test
    fun `a route longer than the limit is cut there, from its start`() {
        // 500 km going east with a point every 300 m.
        val line = road(points = 1_668, step = 300.0)

        val kept = PreviewDownload.thin(line)

        // To the first point at or past the limit: 334 steps, 100.2 km along the road.
        assertEquals(line.take(335), kept.line)
        assertEquals(100_200.0, kept.keptMeters, 1.0)
        assertEquals(500_100.0, kept.routeMeters, 1.0)
        // Nothing much beyond the limit is in the region.
        assertTrue(kept.line.all { meters(line.first(), it) < PreviewDownload.MAX_ROUTE_METERS + 300.0 })
    }

    @Test
    fun `the cut comes at the first point at or past the limit, also far from the one before`() {
        // A point every 3 km: 99 km, then 102 km.
        val line = road(points = 60, step = 3_000.0)

        val kept = PreviewDownload.thin(line)

        assertEquals(line.take(35), kept.line)
        assertEquals(102_000.0, kept.keptMeters, 1.0)
        assertEquals(177_000.0, kept.routeMeters, 1.0)
    }

    @Test
    fun `the length is measured along the road, not straight from its start`() {
        // 30 km out, 30 km back, then out again: 100 km along the road come 40 km from the start.
        val there = road(points = 101, step = 300.0)
        val line = there + there.reversed().drop(1) + road(points = 201, step = 300.0).drop(1)

        val kept = PreviewDownload.thin(line)

        assertEquals(line.take(335), kept.line)
        assertEquals(100_200.0, kept.keptMeters, 1.0)
        assertEquals(120_000.0, kept.routeMeters, 1.0)
    }

    @Test
    fun `a route shorter than the limit is kept to its end, with its length`() {
        // 5 km with a point every 10 m.
        val line = road(points = 501, step = 10.0)

        val kept = PreviewDownload.thin(line)

        assertSame(line.last(), kept.line.last())
        assertEquals(5_000.0, kept.routeMeters, 0.1)
        assertEquals(kept.routeMeters, kept.keptMeters)
    }

    @Test
    fun `the shape has one line for each route, thinned`() {
        val first = road(points = 501, step = 10.0)
        val second = road(points = 301, step = 10.0, swing = 80.0)

        val shape = PreviewDownload.geometry(listOf(first, second))!!

        assertEquals(2, shape.geometry.coordinates().size)
        assertEquals(PreviewDownload.thin(first).line, shape.geometry.coordinates()[0])
        assertEquals(PreviewDownload.thin(second).line, shape.geometry.coordinates()[1])
        assertEquals(listOf(PreviewDownload.thin(first), PreviewDownload.thin(second)), shape.routes)
    }

    @Test
    fun `each route of the shape is cut on its own`() {
        val long = road(points = 1_001, step = 300.0)
        val short = road(points = 301, step = 100.0, swing = 80.0)

        val shape = PreviewDownload.geometry(listOf(long, short))!!

        val (cut, whole) = shape.routes
        assertEquals(300_000.0, cut.routeMeters, 1.0)
        assertEquals(100_200.0, cut.keptMeters, 1.0)
        assertSame(short.last(), whole.line.last())
        assertEquals(whole.routeMeters, whole.keptMeters)
        assertEquals(shape.routes.map { it.line }, shape.geometry.coordinates())
    }

    @Test
    fun `a route without a line is left out of the shape`() {
        val route = road(points = 50, step = 40.0)

        val shape = PreviewDownload.geometry(listOf(emptyList(), route, listOf(at(0.0))))!!

        assertEquals(listOf(PreviewDownload.thin(route).line), shape.geometry.coordinates())
    }

    @Test
    fun `no route with a line gives no shape`() {
        assertNull(PreviewDownload.geometry(emptyList()))
        assertNull(PreviewDownload.geometry(listOf(emptyList(), listOf(at(0.0)))))
    }
}
