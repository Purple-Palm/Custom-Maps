package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The maneuver arrow at a gore laid along the app's own path: on made-up lines due north from 0, 0 at sea, and on IW31
 * of [LaneScenes] as the drawing tests draw it.
 */
class GoreArrowTest {

    private val frame = LocalMeters(0.0, 0.0)

    /** A point [north] metres due north of 0, 0 and [east] metres east of the line. */
    private fun point(north: Double, east: Double = 0.0) = GeoPoint(frame.latitudeOf(LocalPoint(east, north)), frame.longitudeOf(LocalPoint(east, north)))

    private fun north(p: GeoPoint) = frame.toLocal(p.latitude, p.longitude).y

    /** A path due north from 0, 0 with a point at each of [at] (metres), each at its own station. */
    private fun straight(at: List<Double>) = at.map { point(it) } to at.toDoubleArray()

    /** The northings of [arrow]'s points, to the millimetre. */
    private fun norths(arrow: List<GeoPoint>) = arrow.map { Math.round(north(it) * 1_000.0) / 1_000.0 }

    @Test
    fun `the arrow runs along the path from 12 m before the gore to 30 m past it, through the path's points between`() {
        val (points, stations) = straight((0..40).map { it * 9.0 })
        val arrow = GoreArrow.along(points, stations, gore = 204.0)
        assertEquals(listOf(192.0, 198.0, 207.0, 216.0, 225.0, 234.0), norths(arrow))
        assertEquals(GoreArrow.BEFORE_METERS + GoreArrow.AFTER_METERS, Geo.meters(arrow.first(), arrow.last()), 1e-6)
        // The path's own points in between, unchanged.
        assertEquals(points.subList(22, 26), arrow.subList(1, 5))
        // A gore on one of the path's points: the arrow's ends between two points.
        assertEquals(listOf(186.0, 189.0, 198.0, 207.0, 216.0, 225.0, 228.0), norths(GoreArrow.along(points, stations, 198.0)))
    }

    @Test
    fun `near the path's ends the arrow is cut short, and with no length left there is none`() {
        val (points, stations) = straight((0..8).map { it * 9.0 })
        assertEquals(listOf(0.0, 9.0, 18.0, 27.0, 35.0), norths(GoreArrow.along(points, stations, 5.0)))
        assertEquals(listOf(54.0, 63.0, 72.0), norths(GoreArrow.along(points, stations, 66.0)))
        // The gore past the path's end by more than the arrow's lead: nothing of it is on the path.
        assertTrue(GoreArrow.along(points, stations, 72.0 + GoreArrow.BEFORE_METERS + 1.0).isEmpty())
        assertTrue(GoreArrow.along(points.take(1), stations.copyOf(1), 0.0).isEmpty())
    }

    @Test
    fun `the arrow's last piece is never shorter than a metre, so its head points along the path`() {
        // A point of the path 0.3 m before where the arrow ends: dropped, the head drawn from the point before it.
        val at = listOf(0.0, 9.0, 18.0, 28.5, 29.7, 44.0)
        val (points, stations) = straight(at)
        val arrow = GoreArrow.along(points, stations, gore = 0.0)
        assertEquals(listOf(0.0, 9.0, 18.0, 28.5, 30.0), norths(arrow))
        assertTrue(Geo.meters(arrow[arrow.size - 2], arrow.last()) >= GoreArrow.HEAD_PIECE_METERS)
        // A path that bends right at 15 m: the head points the way the last piece goes.
        val bent = listOf(point(0.0), point(15.0), point(30.0, 15.0))
        val bentArrow = GoreArrow.along(bent, doubleArrayOf(0.0, 15.0, 15.0 + Math.hypot(15.0, 15.0)), gore = 0.0)
        val last = frame.toLocal(bentArrow.last().latitude, bentArrow.last().longitude)
        val before = frame.toLocal(bentArrow[bentArrow.size - 2].latitude, bentArrow[bentArrow.size - 2].longitude)
        assertEquals(1.0, (last.x - before.x) / (last.y - before.y), 1e-6)
    }

    @Test
    fun `a maneuver is a gore's only where it lies at it`() {
        val gores = listOf(1_000.0, 4_000.0)
        assertEquals(1_000.0, GoreArrow.goreAt(gores, 1_000.0 + GoreArrow.SAME_PLACE_METERS))
        assertEquals(4_000.0, GoreArrow.goreAt(gores, 4_000.0 - 0.3))
        assertNull(GoreArrow.goreAt(gores, 1_000.0 + GoreArrow.SAME_PLACE_METERS + 0.01))
        assertNull(GoreArrow.goreAt(emptyList(), 1_000.0))
    }

    @Test
    fun `on IW31 the arrow lies in the lit lane up to the gore and on the path past it, where the route's own line cuts across`() {
        val route = LaneScenes.iw31()
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence)
        val path = RoutePath.of(plan, SceneLines.of(route), version = 1, evidence = evidence, section = section)
        val drawing = LaneDrawing.of(plan, path, evidence, section)
        val gore = LaneScenes.GORE
        // The drawing's first gore is the exit's; the ramp's own split lies past the arrow's end.
        assertEquals(gore, drawing.gores().first(), 1e-6)
        assertTrue(drawing.gores().drop(1).all { it > gore + GoreArrow.AFTER_METERS }, "${drawing.gores()}")
        val arrow = GoreArrow.along(path.points, path.stations, gore)
        // It begins 12 m before the gore, on the path, in the lit lane's middle, more than a lane off the road's line that
        // the navigation SDK's arrow follows. (Between two points of the path it is placed by their stations: where the
        // road's axis bends into the ramp and the path goes on straight, within a metre of the station's own place.)
        val start = gore - GoreArrow.BEFORE_METERS
        val axis = requireNotNull(path.axisAt(start))
        val (station, offset) = DrawingProbe.project(axis, start, arrow.first())
        assertEquals(start, station, 1.0)
        assertEquals(path.offsetAt(station), offset, 0.02)
        val lit = drawing.features(station - 1.0, station + 1.0).filter { it.kind == LaneDrawing.Kind.LIT }.flatMap { it.parts }
        val across = DrawingProbe.across(axis, station, lit)
        assertEquals(2, across.size, "the lit lane's two sides at $start: $across")
        assertEquals((across[0] + across[1]) / 2.0, offset, 0.01)
        assertTrue(abs(offset) > LaneTopology.FREEWAY_LANE_METERS, "$offset m off the road's line")
        // Every point past the first is a point of the path, up to the last, which is the path's point 30 m past the gore.
        for (p in arrow.subList(1, arrow.size - 1)) assertTrue(p in path.points)
        val end = gore + GoreArrow.AFTER_METERS
        val k = path.stations.indexOfLast { it <= end }
        val f = (end - path.stations[k]) / (path.stations[k + 1] - path.stations[k])
        val expected = GeoPoint(
            path.points[k].latitude + (path.points[k + 1].latitude - path.points[k].latitude) * f,
            path.points[k].longitude + (path.points[k + 1].longitude - path.points[k].longitude) * f,
        )
        assertEquals(0.0, Geo.meters(expected, arrow.last()), 1e-6)
        // About 42 m long: the path settles onto the ramp's line from inside its bend and never swings past it, so it is a
        // little shorter than the route there.
        val length = arrow.zipWithNext { a, b -> Geo.meters(a, b) }.sum()
        assertTrue(length in (GoreArrow.BEFORE_METERS + GoreArrow.AFTER_METERS - 2.0)..(GoreArrow.BEFORE_METERS + GoreArrow.AFTER_METERS), "$length m")
    }
}
