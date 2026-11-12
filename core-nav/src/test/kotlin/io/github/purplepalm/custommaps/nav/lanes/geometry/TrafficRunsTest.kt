package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.CLOSED
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.HEAVY
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.LOW
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.MODERATE
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.SEVERE
import io.github.purplepalm.custommaps.nav.lanes.geometry.TrafficLevel.UNKNOWN
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Traffic along made-up lines at sea near 0, 0. */
class TrafficRunsTest {

    private val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS

    /** A line due north from 0, 0 with a point every [step] metres, [n] segments, measured as its own path. */
    private fun straight(n: Int, step: Double = 100.0): Pair<StationTable, DoubleArray> {
        val points = (0..n).map { GeoPoint(it * step / degree, 0.0) }
        val stations = DoubleArray(points.size)
        for (i in 1 until points.size) stations[i] = stations[i - 1] + Geo.meters(points[i - 1], points[i])
        return StationTable.of("route-1", 0, points, stations, points, stations) to stations
    }

    private fun levels(runs: List<TrafficRun>) = runs.map { it.level }

    /** The runs cover 0 to 1 exactly once: in order, each beginning where the one before ended, none empty. */
    private fun assertCovers(runs: List<TrafficRun>) {
        assertEquals(0.0, runs.first().from)
        assertEquals(1.0, runs.last().to)
        for ((i, run) in runs.withIndex()) {
            assertTrue(run.to > run.from, "$run")
            if (i > 0) assertEquals(runs[i - 1].to, run.from)
        }
    }

    @Test
    fun `congestion is read in the navigation SDK's ranges`() {
        assertEquals(LOW, TrafficRuns.level(0))
        assertEquals(LOW, TrafficRuns.level(39))
        assertEquals(MODERATE, TrafficRuns.level(40))
        assertEquals(MODERATE, TrafficRuns.level(59))
        assertEquals(HEAVY, TrafficRuns.level(60))
        assertEquals(HEAVY, TrafficRuns.level(79))
        assertEquals(SEVERE, TrafficRuns.level(80))
        assertEquals(SEVERE, TrafficRuns.level(100))
        assertEquals(UNKNOWN, TrafficRuns.level(101))
        assertEquals(UNKNOWN, TrafficRuns.level(-1))
        assertEquals(UNKNOWN, TrafficRuns.level(null))
    }

    @Test
    fun `a leg's congestion becomes runs, one a level, at the segments' path fractions`() {
        val (table, stations) = straight(6)
        val runs = TrafficRuns.of(table, listOf(LegTraffic(stations, listOf(10, 50, 57, 70, 90, null))))
        assertEquals(listOf(LOW, MODERATE, HEAVY, SEVERE, UNKNOWN), levels(runs))
        assertCovers(runs)
        assertEquals(table.pathFraction(stations[1]), runs[1].from)
        assertEquals(table.pathFraction(stations[3]), runs[1].to)
        assertEquals(table.pathFraction(stations[5]), runs[4].from)
    }

    @Test
    fun `a closure is closed whatever its congestion`() {
        val (table, stations) = straight(6)
        val runs = TrafficRuns.of(table, listOf(LegTraffic(stations, listOf(10, 10, 10, 10, 10, 10), closures = listOf(2..3))))
        assertEquals(listOf(LOW, CLOSED, LOW), levels(runs))
        assertEquals(table.pathFraction(stations[2]), runs[1].from)
        assertEquals(table.pathFraction(stations[4]), runs[1].to)
    }

    @Test
    fun `an empty or shorter annotation leaves the rest unknown, as after a refresh that removed expired congestion`() {
        val (table, stations) = straight(6)
        assertEquals(listOf(TrafficRun(0.0, 1.0, UNKNOWN)), TrafficRuns.of(table, listOf(LegTraffic(stations, emptyList()))))
        assertEquals(listOf(TrafficRun(0.0, 1.0, UNKNOWN)), TrafficRuns.of(table, emptyList()))
        val shorter = TrafficRuns.of(table, listOf(LegTraffic(stations, listOf(65, 65))))
        assertEquals(listOf(HEAVY, UNKNOWN), levels(shorter))
        assertEquals(table.pathFraction(stations[2]), shorter[1].from)
        assertCovers(shorter)
    }

    @Test
    fun `a seam always begins a run, so the gradient steps there`() {
        val (table, stations) = straight(4)
        val seam = table.pathFraction(150.0)
        val runs = TrafficRuns.of(table, listOf(LegTraffic(stations, listOf(5, 5, 5, 5))), seams = listOf(seam, 0.0, 1.0, seam))
        assertEquals(listOf(TrafficRun(0.0, seam, LOW), TrafficRun(seam, 1.0, LOW)), runs)
        // A seam where the level changes anyway adds nothing.
        val change = TrafficRuns.of(table, listOf(LegTraffic(stations, listOf(5, 5, 85, 85))), seams = listOf(table.pathFraction(stations[2])))
        assertEquals(listOf(LOW, SEVERE), levels(change))
    }

    @Test
    fun `legs follow each other and what lies before or after them is unknown`() {
        val (table, stations) = straight(8)
        val first = LegTraffic(stations.copyOfRange(1, 5), listOf(30, 30, 30))
        val second = LegTraffic(stations.copyOfRange(4, 8), listOf(45, 45, 30))
        val runs = TrafficRuns.of(table, listOf(first, second))
        assertEquals(listOf(UNKNOWN, LOW, MODERATE, LOW, UNKNOWN), levels(runs))
        assertCovers(runs)
    }

    @Test
    fun `on a path off the route's line the runs lie at the path's own fractions and cover it once`() {
        val route = LaneScenes.iw31()
        val path = RoutePath.of(SceneLines.planOf(route), SceneLines.of(route))
        val table = StationTable.of(path)
        val rng = Random(11)
        repeat(30) {
            val congestion = (0 until path.route.size - 1).map { if (rng.nextInt(8) == 0) null else rng.nextInt(-5, 106) }
            val closures = if (rng.nextBoolean()) listOf(rng.nextInt(0, 20).let { it..it + rng.nextInt(0, 4) }) else emptyList()
            val runs = TrafficRuns.of(table, listOf(LegTraffic(path.routeStations, congestion.take(rng.nextInt(0, congestion.size + 1)), closures)))
            assertCovers(runs)
            for (i in 1 until runs.size) assertTrue(runs[i].level != runs[i - 1].level, "neighbours of one level are one run")
        }
        // Where the path is off the route's line, a run begins at the path's fraction of the station, not the route's.
        val station = LaneScenes.GORE - 100.0
        val k = path.routeStations.indexOfFirst { it >= station }
        val congestion = (0 until path.route.size - 1).map { if (it < k) 10 else 90 }
        val runs = TrafficRuns.of(table, listOf(LegTraffic(path.routeStations, congestion)))
        assertEquals(table.pathFraction(path.routeStations[k]), runs[1].from)
        assertTrue(Math.abs(table.pathFraction(path.routeStations[k]) - table.routeFraction(path.routeStations[k])) > 1e-9)
    }
}
