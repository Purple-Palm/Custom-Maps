package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The station table on made-up lines within 3 degrees of 0, 0, and one made-up 333 km line due north from it. */
class StationTableTest {

    /** The navigation SDK's calculateDistance, written out again here from its formula (EPSG:3857, fractions of the world). */
    private fun sdkDistance(a: GeoPoint, b: GeoPoint): Double {
        fun x(lon: Double) = lon / 360.0 + 0.5
        fun y(lat: Double): Double {
            val s = sin(lat * PI / 180.0)
            val v = 0.5 - 0.25 * ln((1 + s) / (1 - s)) / PI
            return if (v < 0) 0.0 else if (v > 1) 1.1 else v
        }
        val dx = x(a.longitude) - x(b.longitude)
        val dy = y(a.latitude) - y(b.latitude)
        return sqrt(dx * dx + dy * dy)
    }

    private fun cumulative(points: List<GeoPoint>, measure: (GeoPoint, GeoPoint) -> Double): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + measure(points[i - 1], points[i])
        return out
    }

    @Test
    fun `the projection is the navigation SDK's, its clamp as it is`() {
        assertEquals(0.0, StationTable.projectX(-180.0))
        assertEquals(0.5, StationTable.projectX(0.0))
        assertEquals(1.0, StationTable.projectX(180.0))
        assertEquals(0.5, StationTable.projectY(0.0), 1e-15)
        // Web Mercator's edge of the world.
        assertEquals(0.0, StationTable.projectY(85.0511287798), 1e-9)
        assertEquals(1.0, StationTable.projectY(-85.0511287798), 1e-9)
        assertEquals(0.0, StationTable.projectY(89.9999))
        assertEquals(1.1, StationTable.projectY(-89.9999))
        val rng = Random(7)
        repeat(100) {
            val a = GeoPoint(rng.nextDouble(-3.0, 3.0), rng.nextDouble(-3.0, 3.0))
            val b = GeoPoint(rng.nextDouble(-3.0, 3.0), rng.nextDouble(-3.0, 3.0))
            assertEquals(sdkDistance(a, b), StationTable.projectedDistance(a, b), 1e-18)
        }
    }

    @Test
    fun `the table's fractions are the projected-length ratios of invented lines within 3 degrees of 0, 0`() {
        val rng = Random(20261112)
        repeat(40) { k ->
            val route = randomLine(rng)
            val routeStations = cumulative(route) { a, b -> Geo.meters(a, b) }
            val (path, pathStations) = randomPath(rng, route, routeStations)
            val table = StationTable.of("route-$k", 3, route, routeStations, path, pathStations)
            val routeLengths = cumulative(route, ::sdkDistance)
            val pathLengths = cumulative(path, ::sdkDistance)
            for (i in route.indices) {
                val expected = routeLengths[i] / routeLengths.last()
                assertEquals(expected, table.routeFraction(routeStations[i]), 1e-9, "route $k at point $i")
                if (i == 0 || routeStations[i] > routeStations[i - 1]) {
                    assertEquals(routeStations[i], table.stationAt(expected), 1e-6 * maxOf(1.0, routeStations[i] / 1000.0), "route $k station at point $i")
                }
                // The SDK's fraction at a point of its line gives the path's own fraction there.
                assertEquals(table.pathFraction(routeStations[i]), table.pathFractionAt(expected), 1e-9)
            }
            for (j in path.indices) {
                assertEquals(pathLengths[j] / pathLengths.last(), table.pathFraction(pathStations[j]), 1e-9, "path $k at point $j")
            }
        }
    }

    @Test
    fun `a metre fraction would be 58 m off on a 333 km line due north from 0, 0, the table not`() {
        // Every kilometre, due north along longitude 0 (WORK's mercator.py: a metre fraction errs by 58.4 m at 192.3 km).
        val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        val route = (0..333).map { GeoPoint(it * 1000.0 / degree, 0.0) }
        val stations = cumulative(route) { a, b -> Geo.meters(a, b) }
        val table = StationTable.of("north", 0, route, stations, route, stations)
        val lengths = cumulative(route, ::sdkDistance)
        var worst = 0.0
        var worstAt = 0.0
        for (i in route.indices) {
            // Where the navigation SDK puts the car at the i-th kilometre: a fraction of the projected length.
            val sdk = lengths[i] / lengths.last()
            assertEquals(stations[i], table.stationAt(sdk), 1e-6, "at ${stations[i]}")
            val metres = sdk * stations.last() - stations[i]
            if (abs(metres) > abs(worst)) {
                worst = metres
                worstAt = stations[i]
            }
        }
        assertEquals(-58.4, worst, 0.1)
        assertEquals(192_000.0, worstAt, 1_000.0)
        // Near 0, 0 on a short route the same mistake hides: 4 cm over 30 km.
        val short = route.take(31)
        val shortStations = stations.copyOf(31)
        val shortLengths = cumulative(short, ::sdkDistance)
        val worstShort = short.indices.maxOf { abs(shortLengths[it] / shortLengths.last() * shortStations.last() - shortStations[it]) }
        assertTrue(worstShort < 0.05 && worstShort > 0.01, "$worstShort")
    }

    @Test
    fun `a fraction is read only through its own route's and path's table`() {
        val route = LaneScenes.iw31()
        val path = RoutePath.of(SceneLines.planOf(route), SceneLines.of(route), version = 4)
        val table = StationTable.of(path)
        assertEquals("iw31", table.routeId)
        assertEquals(4, table.pathVersion)
        val f = table.routeFraction(LaneScenes.GORE)
        assertEquals(table.pathFractionAt(f), table.pathFractionAt("iw31", 4, f))
        assertNull(table.pathFractionAt("iw31", 3, f))
        assertNull(table.pathFractionAt("another", 4, f))
    }

    @Test
    fun `on a path that is its route the fractions agree, and the path's own differ only where it leaves the line`() {
        val route = LaneScenes.ia()
        val line = SceneLines.of(route)
        val stations = cumulative(line) { a, b -> Geo.meters(a, b) }
        val same = StationTable.of("ia", 0, line, stations, line, stations)
        for (s in listOf(0.0, 1_000.0, 3_456.7, 6_000.0, route.lengthMeters)) assertEquals(same.routeFraction(s), same.pathFraction(s), 1e-12)
        val path = RoutePath.of(SceneLines.planOf(route), line)
        val table = StationTable.of(path)
        // Before the drawing both lines are one; across it the path's own length differs from the route's.
        assertEquals(table.routeFraction(1_000.0) * table.routeLength, table.pathFraction(1_000.0) * table.pathLength, 1e-15)
        assertTrue(abs(table.pathLength - table.routeLength) > 1e-12)
        assertEquals(0.0, table.pathFraction(-5.0))
        assertEquals(1.0, table.pathFraction(route.lengthMeters + 5.0))
        assertEquals(0.0, table.stationAt(-0.5))
        assertEquals(path.routeStations.last(), table.stationAt(1.5), 1e-9)
        assertEquals(0.0, table.routeFraction(Double.NaN))
    }

    /** A made-up line within 3 degrees of 0, 0: a walk of 30 to 200 steps of 5 m to 3 km. */
    private fun randomLine(rng: Random): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var lat = rng.nextDouble(-1.5, 1.5)
        var lon = rng.nextDouble(-1.5, 1.5)
        var heading = rng.nextDouble(0.0, 2 * PI)
        out.add(GeoPoint(lat, lon))
        val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        repeat(rng.nextInt(30, 200)) {
            heading += rng.nextDouble(-0.6, 0.6)
            val step = if (rng.nextInt(10) == 0) rng.nextDouble(1_000.0, 3_000.0) else rng.nextDouble(5.0, 400.0)
            lat = (lat + step * cos(heading) / degree).coerceIn(-3.0, 3.0)
            lon = (lon + step * sin(heading) / degree).coerceIn(-3.0, 3.0)
            out.add(GeoPoint(lat, lon))
            // Now and then a point twice, as where two steps of a route meet.
            if (rng.nextInt(15) == 0) out.add(GeoPoint(lat, lon))
        }
        return out
    }

    /** A made-up path along [route]: its points moved up to 10 m across, and points between them, each with its station. */
    private fun randomPath(rng: Random, route: List<GeoPoint>, stations: DoubleArray): Pair<List<GeoPoint>, DoubleArray> {
        val points = ArrayList<GeoPoint>()
        val at = ArrayList<Double>()
        val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        for (i in route.indices) {
            val p = route[i]
            // One path point a station, as a path has: a point the route repeats is not repeated.
            if (i > 0 && stations[i] <= stations[i - 1]) continue
            points.add(GeoPoint(p.latitude + rng.nextDouble(-10.0, 10.0) / degree, p.longitude + rng.nextDouble(-10.0, 10.0) / degree))
            at.add(stations[i])
            if (i + 1 < route.size && stations[i + 1] > stations[i] + 2.0) {
                val f = rng.nextDouble(0.1, 0.9)
                val q = route[i + 1]
                points.add(GeoPoint(p.latitude + (q.latitude - p.latitude) * f, p.longitude + (q.longitude - p.longitude) * f))
                at.add(stations[i] + (stations[i + 1] - stations[i]) * f)
            }
        }
        return points to at.toDoubleArray()
    }
}
