package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The car's bar over the app's drawn lanes on the made-up routes of [LaneScenes], along made-up lines at sea that run due
 * north from 0, 0 ([SceneLines]): before a route's first bend a point's station is its northing and its offset from the
 * road's line, left positive, its westing.
 */
class CarBarTest {

    private val gore = LaneScenes.GORE

    private class Drawn(val path: RoutePath, val section: CrossSection, val drawing: LaneDrawing, val bar: CarBar)

    private fun draw(route: LaneRoute, carriageway: CrossSection.Carriageway = CrossSection.Carriageway.ONE_WAY): Drawn {
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence) { carriageway }
        val path = RoutePath.of(plan, SceneLines.of(route), version = 1, evidence = evidence, section = section)
        val drawing = LaneDrawing.of(plan, path, evidence, section)
        return Drawn(path, section, drawing, CarBar.of(drawing, 0.0, route.lengthMeters))
    }

    /** The bar's middle as east and north metres of 0, 0. */
    private fun middle(bar: CarBar) = SceneLines.local(GeoPoint(bar.latitude, bar.longitude))

    /** The route's own line at [station]: the place the navigator matches the car to, the puck's. */
    private fun onLine(path: RoutePath, station: Double): GeoPoint {
        val k = RoutePath.indexAtOrBefore(path.routeStations, station).coerceIn(0, path.route.size - 2)
        val f = (station - path.routeStations[k]) / (path.routeStations[k + 1] - path.routeStations[k])
        val a = path.route[k]
        val b = path.route[k + 1]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
    }

    @Test
    fun `the bar lies across the drawn carriageway at the car's station, edge line to edge line and square to the lanes`() {
        val d = draw(LaneScenes.ia())
        assertEquals(2.0, CarBar.DEPTH_METERS)
        for (station in listOf(gore - 2_400.0, gore - 1_500.0, gore - 800.0, gore - 120.0)) {
            assertTrue(d.bar.at(station), "at $station")
            val lanes = d.drawing.lanesAt(station).filter { it.width > 0.0 }
            val left = lanes.maxOf { it.left }
            val right = lanes.minOf { it.right }
            val m = middle(d.bar)
            assertEquals(station, m.y, 1e-3, "along the road at $station")
            assertEquals((left + right) / 2.0, -m.x, 1e-3, "halfway between the edge lines at $station")
            // Square to the lanes: the way their edge lines run here, north but where the lanes shift together.
            val (l0, r0) = requireNotNull(d.drawing.edgesAt(station - 1.0)).let { SceneLines.local(it.first) to SceneLines.local(it.second) }
            val (l1, r1) = requireNotNull(d.drawing.edgesAt(station + 1.0)).let { SceneLines.local(it.first) to SceneLines.local(it.second) }
            val leftWay = Math.toDegrees(kotlin.math.atan2(l1.x - l0.x, l1.y - l0.y))
            val rightWay = Math.toDegrees(kotlin.math.atan2(r1.x - r0.x, r1.y - r0.y))
            val way = (d.bar.bearing + 180.0) % 360.0 - 180.0
            assertTrue(abs(way - leftWay) < 1e-3 || abs(way - rightWay) < 1e-3, "at $station: $way against $leftWay and $rightWay")
            assertTrue(abs(way) < 1.0, "about north at $station: $way")
            // From edge line to edge line, measured square to that way.
            assertEquals((left - right) * cos(Math.toRadians(way)), d.bar.width, 1e-3, "at $station")
            assertFalse(d.bar.twoWay)
        }
        // Where a lane opens at one edge the other edge runs with the lanes, and the bar keeps square to them.
        for (station in listOf(gore - 2_150.0, gore - 1_300.0)) {
            assertTrue(d.bar.at(station))
            val way = (d.bar.bearing + 180.0) % 360.0 - 180.0
            assertTrue(abs(way) < 0.5, "at $station: $way")
        }
        // Where a lane opens along its taper, the bar widens with it, metre by metre.
        val opening = (3_000..5_999).map { it.toDouble() }.first { s ->
            d.drawing.lanesAt(s).any { it.state == CrossSection.State.OPENING } && d.drawing.lanesAt(s + 12.0).any { it.state == CrossSection.State.OPENING }
        }
        val widths = (0..12).map { k -> assertTrue(d.bar.at(opening + k)); d.bar.width }
        assertTrue(widths.zipWithNext().all { (a, b) -> b > a }, "$widths from $opening")
    }

    @Test
    fun `the puck's station on the route's line places the bar, and a puck off the line places none`() {
        val d = draw(LaneScenes.ia())
        for (station in listOf(gore - 2_000.0, gore - 1_999.0, gore - 1_000.0, gore - 12.0)) {
            val puck = onLine(d.path, station)
            assertTrue(d.bar.place(puck.latitude, puck.longitude), "at $station")
            assertEquals(station, d.bar.station, 1e-3)
        }
        // A puck a few metres beside the line, as a glide between two matched places cuts a bend: its station all the same.
        val beside = SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(4.0, gore - 500.0))
        assertTrue(d.bar.place(beside.latitude, beside.longitude))
        assertEquals(gore - 500.0, d.bar.station, 1e-3)
        // Farther than the projection's limit: no bar, the dot.
        for (east in listOf(CarBar.MAX_OFF_LINE_METERS + 0.5, 200.0)) {
            val off = SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(east, gore - 500.0))
            assertFalse(d.bar.place(off.latitude, off.longitude), "$east m east")
            assertTrue(d.bar.latitude.isNaN())
        }
        // Within it, found again near where it was last, also after a jump far along the line.
        val near = SceneLines.geo(io.github.purplepalm.custommaps.nav.geo.LocalPoint(CarBar.MAX_OFF_LINE_METERS - 0.5, gore - 500.0))
        assertTrue(d.bar.place(near.latitude, near.longitude))
        val far = onLine(d.path, gore - 2_500.0)
        assertTrue(d.bar.place(far.latitude, far.longitude))
        assertEquals(gore - 2_500.0, d.bar.station, 1e-3)
    }

    @Test
    fun `at a gore the bar never travels the route line's diagonal across the lanes`() {
        // IW31: the route's line leaves the freeway's line at the gore and runs off at 17 degrees onto the ramp, across the lanes
        // the route leaves; the ramp takes the two right lanes, which go on straight before they settle onto the ramp.
        val d = draw(LaneScenes.iw31())
        val before = ArrayList<Double>()
        for (s in -30..-1) {
            assertTrue(d.bar.at(gore + s))
            before.add(middle(d.bar).x)
            assertTrue(d.bar.bearing < 1e-6 || d.bar.bearing > 360.0 - 1e-6)
        }
        assertTrue(before.all { abs(it - before.first()) < 1e-6 }, "the whole carriageway's middle before the gore: $before")
        // From the gore on: the puck runs the line's diagonal, the bar stays on the lanes the route takes.
        var lastX = Double.NEGATIVE_INFINITY
        var lastBearing = 0.0
        var settled = false
        val ramp = Math.toRadians(17.0)
        for (s in 0..150) {
            val puck = onLine(d.path, gore + s)
            assertTrue(d.bar.place(puck.latitude, puck.longitude), "at the gore + $s")
            assertEquals(gore + s, d.bar.station, 1e-3)
            val m = middle(d.bar)
            val line = SceneLines.local(puck)
            // Never back toward the old road's middle, and never past the ramp's line to its far side.
            assertTrue(m.x >= lastX - 1e-6, "back west at the gore + $s: ${m.x} after $lastX")
            val acrossRamp = (m.x - line.x) * cos(ramp) - (m.y - line.y) * sin(ramp)
            assertTrue(acrossRamp >= -1e-3, "past the ramp's line at the gore + $s by $acrossRamp m")
            // Turning toward the ramp's way only, and never past it.
            assertTrue(d.bar.bearing >= lastBearing - 1e-6 && d.bar.bearing <= 17.0 + 1e-6, "bearing ${d.bar.bearing} at the gore + $s")
            lastX = m.x
            lastBearing = d.bar.bearing
            if (abs(acrossRamp) < 1e-3) settled = true
            if (s == 1) {
                // The bar spans the two lanes the ramp takes, straight on, while the puck is still at the old road's middle.
                assertEquals(2.0 * 3.66, d.bar.width, 0.01)
                assertTrue(m.x - line.x > 4.0, "the bar ${m.x}, the line ${line.x}")
            }
        }
        assertTrue(settled, "on the ramp's lanes, centred on its line, by 150 m past the gore")
        // The straight stretch: the bar's middle stays where the two right lanes run, while the line crosses toward them.
        val straight = (1..9).map { s -> d.bar.at(gore + s); middle(d.bar).x }
        assertTrue(straight.all { abs(it - straight.first()) < 1e-6 }, "$straight")
        assertTrue(SceneLines.local(onLine(d.path, gore + 9.0)).x > 1.5, "the line has gone sideways meanwhile")
    }

    @Test
    fun `on a two-way street the bar spans our direction's lanes only`() {
        // IA laid as the route's half of a two-way road (OL6's streets): the lanes from the road's line to the right.
        val d = draw(LaneScenes.ia(), CrossSection.Carriageway.TWO_WAY)
        for (station in listOf(gore - 2_300.0, gore - 1_500.0)) {
            assertTrue(d.bar.at(station), "at $station")
            val lanes = d.drawing.lanesAt(station).filter { it.width > 0.0 }
            val left = lanes.maxOf { it.left }
            val right = lanes.minOf { it.right }
            assertEquals(left - right, d.bar.width, 1e-3)
            assertEquals((left + right) / 2.0, -middle(d.bar).x, 1e-3)
            // The oncoming half lies left of the route's lanes, plain asphalt, and the bar does not reach over it.
            val edges = requireNotNull(d.section.edges(station, d.path.axisAt(station)))
            assertEquals(left, edges.left, 1e-9)
            assertTrue(edges.asphaltLeft > left + 3.0, "the oncoming half: ${edges.asphaltLeft} against $left")
            assertTrue(-middle(d.bar).x + d.bar.width / 2.0 <= left + 1e-3)
            assertTrue(d.bar.twoWay)
        }
        // Before the first change at an edge the route's lanes begin at the road's line itself.
        assertTrue(d.bar.at(gore - 2_300.0))
        assertEquals(0.0, -middle(d.bar).x + d.bar.width / 2.0, 1e-3)
    }

    @Test
    fun `between drawn stretches and outside the window there is no bar`() {
        val d = draw(LaneScenes.ia())
        assertFalse(d.bar.at(0.0), "before the drawing")
        assertFalse(d.bar.at(Double.NaN))
        assertTrue(d.bar.from > 0.0 && d.bar.to <= LaneScenes.ia().lengthMeters)
        // A window that holds only part of the drawing has its bar only there.
        val part = CarBar.of(d.drawing, gore - 1_000.0, gore - 900.0)
        assertTrue(part.at(gore - 950.0))
        assertFalse(part.at(gore - 1_100.0))
        assertFalse(part.at(gore - 850.0))
        assertEquals(gore - 1_000.0, part.from, 1e-9)
        assertEquals(gore - 900.0, part.to, 1e-9)
        // Between two places of the table it is interpolated, one step apart.
        assertTrue(part.at(gore - 950.5))
        assertEquals(gore - 950.5, middle(part).y, 1e-3)
        // A window with no lanes has no places at all.
        val none = CarBar.of(d.drawing, 12.0, 24.0)
        assertTrue(none.from.isNaN())
        assertFalse(none.at(18.0))
    }

    @Test
    fun `the bar's image is made again only where its width changed by half a metre`() {
        assertEquals(0.5, CarBar.WIDTH_STEP_METERS)
        assertTrue(CarBar.widthChanged(Double.NaN, 12.0), "none drawn yet")
        assertFalse(CarBar.widthChanged(12.0, 12.0))
        assertFalse(CarBar.widthChanged(12.0, 12.49))
        assertFalse(CarBar.widthChanged(12.0, 11.51))
        assertTrue(CarBar.widthChanged(12.0, 12.5))
        assertTrue(CarBar.widthChanged(12.0, 11.5))
        // Along IA's approach, a car that makes the image again only at such a change makes it a few times, never every metre.
        val d = draw(LaneScenes.ia())
        var shown = Double.NaN
        var made = 0
        var s = gore - 2_600.0
        while (s < gore) {
            if (d.bar.at(s) && CarBar.widthChanged(shown, d.bar.width)) {
                shown = d.bar.width
                made++
            }
            s += 1.0
        }
        assertTrue(made in 2..40, "made $made times")
    }

    @Test
    fun `the bar's image is drawn at its ground size at every zoom`() {
        for (latitude in listOf(0.0, 35.0, -50.0)) {
            for (zoom in listOf(15.0, 17.3, 19.0)) {
                // A metre of the image, at its size, is as wide on the screen as a metre of ground.
                val size = CarBar.sizeAt(zoom, latitude)
                assertEquals(1.0 / MapScale.metersPerDp(zoom, latitude), CarBar.IMAGE_DP_PER_METER * size, 1e-9 / MapScale.metersPerDp(zoom, latitude))
                // The expression's two stops, base 2, give it exactly between them.
                val (z0, s0) = CarBar.sizeStops(latitude).first()
                val (z1, s1) = CarBar.sizeStops(latitude).last()
                val between = s0 * (s1 / s0).pow((zoom - z0) / (z1 - z0))
                assertEquals(size, between, size * 1e-9)
            }
        }
    }

    @Test
    fun `bearings turn the short way round`() {
        assertEquals(9.0, CarBar.turnTowards(348.0, 30.0, 0.5), 1e-9)
        assertEquals(354.0, CarBar.turnTowards(9.0, 339.0, 0.5), 1e-9)
        assertEquals(85.0, CarBar.turnTowards(0.0, 170.0, 0.5), 1e-9)
        assertEquals(12.0, CarBar.turnTowards(12.0, 24.0, 0.0), 1e-9)
    }

    @Test
    fun `placing the bar each frame allocates nothing`() {
        val d = draw(LaneScenes.ia())
        val pucks = (0 until 2_000).map { onLine(d.path, gore - 2_000.0 + it * 0.5) }
        val latitudes = DoubleArray(pucks.size) { pucks[it].latitude }
        val longitudes = DoubleArray(pucks.size) { pucks[it].longitude }
        // Warmed up first, so the measure counts the frames alone.
        repeat(3) { for (i in latitudes.indices) d.bar.place(latitudes[i], longitudes[i]) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        var placed = 0
        for (i in latitudes.indices) if (d.bar.place(latitudes[i], longitudes[i])) placed++
        val bytes = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(latitudes.size, placed)
        assertTrue(bytes < 1_024, "$bytes bytes for ${latitudes.size} frames")
    }
}
