package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Made-up roads in local metres around 0, 0 (and one window at latitude 35 at sea); no place. */
class RoadAxisTest {

    private val frame = LocalMeters(0.0, 0.0)
    private val origin = GeoPoint(0.0, 0.0)

    private fun geo(x: Double, y: Double) = GeoPoint(frame.latitudeOf(LocalPoint(x, y)), frame.longitudeOf(LocalPoint(x, y)))

    /** The axis of a polyline given in local metres, in the frame of 0, 0, with its own lengths as stations. */
    private fun axis(vararg xy: Pair<Double, Double>): RoadAxis {
        val local = xy.map { LocalPoint(it.first, it.second) }
        val stations = DoubleArray(local.size)
        for (i in 1 until local.size) stations[i] = stations[i - 1] + local[i - 1].distanceTo(local[i])
        return RoadAxis.of(local.map { geo(it.x, it.y) }, stations, origin)
    }

    /** Two 40 m segments meeting at 0, 0 with a turn of [degrees] to the left. */
    private fun corner(degrees: Double): RoadAxis {
        val th = Math.toRadians(degrees)
        return axis(-40.0 to 0.0, 0.0 to 0.0, 40.0 * cos(th) to 40.0 * sin(th))
    }

    @Test
    fun `a straight road offsets exactly to either side`() {
        val road = axis(0.0 to 0.0, 30.0 to 40.0)
        assertTrue(road.fillets.isEmpty())
        val place = road.at(25.0)
        assertEquals(15.0, place.point.x, 1e-9)
        assertEquals(20.0, place.point.y, 1e-9)
        for (t in listOf(5.49, -5.49, 12.81)) {
            val p = road.offset(25.0, t)
            assertEquals(Math.abs(t), p.distanceTo(place.point), 1e-9)
            // Square to the road: left of the way for a positive offset.
            val along = (p.x - place.point.x) * 0.6 + (p.y - place.point.y) * 0.8
            assertEquals(0.0, along, 1e-9)
            val left = -(p.x - place.point.x) * 0.8 + (p.y - place.point.y) * 0.6
            assertEquals(t, left, 1e-9)
            assertFalse(road.folds(25.0, t))
        }
    }

    @Test
    fun `an arc's radius is the smallest of the cap, the fit and the deviation`() {
        // GEOM's table (a copy of its axis.py on the same invented corners): 40 m segments, 0.5 m.
        val expected = mapOf(
            2.0 to 2291.598465, 5.0 to 524.832357, 10.0 to 130.895619, 20.0 to 32.411524,
            30.0 to 14.173870, 45.0 to 6.068536, 60.0 to 3.232051, 90.0 to 1.207107,
        )
        for ((degrees, radius) in expected) {
            val fillet = corner(degrees).fillets.single()
            assertEquals(radius, fillet.radius, 1e-5, "radius at $degrees degrees")
            assertEquals(degrees, fillet.turnDegrees, 1e-9)
        }
        // At 2 degrees the fit decides, so the arc keeps clear of the vertex by less than the most it may.
        assertEquals(0.349074712, corner(2.0).fillets.single().deviation, 1e-8)
        assertEquals(40.0 / Math.tan(Math.toRadians(1.0)), corner(2.0).fillets.single().fitRadius, 1e-6)
        // A vertex that barely turns keeps the cap.
        assertEquals(RoadAxis.MAX_RADIUS_METERS, corner(0.5).fillets.single().radius, 1e-9)
    }

    @Test
    fun `an arc moves the axis no more than half a metre from its vertex`() {
        for (degrees in listOf(5.0, 10.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 170.0)) {
            val road = corner(degrees)
            val fillet = road.fillets.single()
            // Exactly the most where the deviation decides: never more, and not less.
            assertEquals(RoadAxis.MAX_DEV_METERS, fillet.deviation, 1e-9, "at $degrees degrees")
            assertEquals(RoadAxis.MAX_DEV_METERS, road.at(fillet.station).point.distanceTo(LocalPoint(0.0, 0.0)), 1e-9)
            for (s in road.stations()) assertTrue(distanceToPolyline(road.at(s).point, corner = degrees) <= RoadAxis.MAX_DEV_METERS + 1e-9)
        }
        // A right angle: r = 0.5 / (sec 45 - 1).
        assertEquals(0.5 / (1.0 / cos(PI / 4.0) - 1.0), corner(90.0).fillets.single().radius, 1e-12)
    }

    /** The distance from [p] to the two 40 m segments of [corner]'s polyline. */
    private fun distanceToPolyline(p: LocalPoint, corner: Double): Double {
        val th = Math.toRadians(corner)
        val a = LocalPoint(-40.0, 0.0)
        val b = LocalPoint(0.0, 0.0)
        val c = LocalPoint(40.0 * cos(th), 40.0 * sin(th))
        return minOf(segmentDistance(p, a, b), segmentDistance(p, b, c))
    }

    private fun segmentDistance(p: LocalPoint, a: LocalPoint, b: LocalPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val f = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + dx * f), p.y - (a.y + dy * f))
    }

    @Test
    fun `offsets on an arc are arcs of the radius less the offset`() {
        val road = corner(30.0)
        val arc = road.pieces.filterIsInstance<RoadAxis.Arc>().single()
        assertEquals(1, arc.turn)
        for (t in listOf(5.49, -5.49, 9.15, 12.81)) {
            for (k in 0..10) {
                val s = arc.from + (arc.to - arc.from) * k / 10.0
                val p = road.offset(s, t)
                assertEquals(arc.radius - t, p.distanceTo(arc.centre), 1e-9)
                // At its last station the straight piece after it begins.
                if (k < 10) assertEquals(1.0 / arc.radius, road.at(s).curvature, 1e-12)
            }
        }
    }

    @Test
    fun `a circle drawn with chords offsets within the chords' sag`() {
        // A half circle of radius 60 m drawn with 10 m chords, offset 5.49 m both ways (GEOM 6.2; the copy of its
        // prototype gives 0.208696 m and arcs of 59.791304 m).
        val r = 60.0
        val step = 2.0 * asin(10.0 / (2.0 * r))
        val n = (PI / step).toInt()
        val pts = (0 until n).map { i -> (r * cos(-PI / 2 + i * step)) to (r * sin(-PI / 2 + i * step) + r) }
        val road = axis(*pts.toTypedArray())
        for (f in road.fillets) assertEquals(59.791303716, f.radius, 1e-8)
        for (t in listOf(5.49, -5.49)) {
            val samples = road.stations()
            var worst = 0.0
            for (k in 1 until samples.size - 1) {
                val p = road.offset(samples[k], t)
                worst = maxOf(worst, Math.abs(hypot(p.x, p.y - r) - (r - t)))
            }
            assertEquals(0.208696284, worst, 1e-6, "offset $t")
        }
    }

    @Test
    fun `an offset folds exactly where it reaches the arc's centre`() {
        val road = corner(90.0)
        val arc = road.pieces.filterIsInstance<RoadAxis.Arc>().single()
        val inside = (arc.from + arc.to) / 2.0
        // The inner side of a left turn is the left, positive offsets.
        assertFalse(road.folds(inside, arc.radius - 0.001))
        assertTrue(road.folds(inside, arc.radius))
        assertTrue(road.folds(inside, arc.radius + 0.001))
        assertEquals(0.0, road.regularity(inside, arc.radius), 1e-12)
        // The outer side never folds, and a straight piece never does.
        assertFalse(road.folds(inside, -30.0))
        assertFalse(road.folds(arc.from - 5.0, 30.0))
        assertTrue(road.straightAt(arc.from - 5.0))
        assertFalse(road.straightAt(inside))
    }

    @Test
    fun `stations cover every piece and turn at most five degrees apart on arcs`() {
        val road = corner(90.0)
        val samples = road.stations()
        assertEquals(road.from, samples.first())
        assertEquals(road.to, samples.last())
        for (k in 1 until samples.size) assertTrue(samples[k] > samples[k - 1])
        for (piece in road.pieces) {
            assertTrue(samples.any { Math.abs(it - piece.from) < 1e-12 })
            assertTrue(samples.any { Math.abs(it - piece.to) < 1e-12 })
        }
        val arc = road.pieces.filterIsInstance<RoadAxis.Arc>().single()
        val onArc = samples.filter { it >= arc.from && it <= arc.to }
        // 90 degrees in 5-degree steps, though the arc covers under 3 m of stations.
        assertTrue(onArc.size >= 19)
        // On straight pieces the stations are the polyline's own: 40 m in, the vertex's station is 40.
        assertEquals(40.0, road.fillets.single().station, 1e-12)
        assertEquals(40.0 - arc.radius, arc.from, 1e-9)
        assertEquals(40.0 + arc.radius, arc.to, 1e-9)
        assertEquals(LocalPoint(-20.0, 0.0).x, road.at(20.0).point.x, 1e-9)
    }

    @Test
    fun `a road that turns back on itself keeps a round cap outside and folds inside`() {
        val road = axis(0.0 to 0.0, 0.0 to 50.0, 0.001 to 0.0)
        val arc = road.pieces.filterIsInstance<RoadAxis.Arc>().single()
        assertTrue(Math.toDegrees(arc.sweep) > 179.0)
        val mid = (arc.from + arc.to) / 2.0
        // Turning right: the outer side is the left; the cap's middle lies past the vertex, the offset's distance from it.
        val cap = road.offset(mid, 3.0)
        assertTrue(cap.y > 50.0)
        assertEquals(3.0, cap.distanceTo(road.at(mid).point), 1e-9)
        assertTrue(road.folds(mid, -0.01))
        assertFalse(road.folds(mid, 3.0))
    }

    @Test
    fun `a point taken to the window's frame and back is the same point`() {
        val road = RoadAxis.of(listOf(GeoPoint(0.0004, -0.0003), GeoPoint(0.0021, 0.0009)))
        val p = GeoPoint(0.0011, 0.0007)
        val back = road.toGeo(road.toLocal(p))
        assertEquals(p.latitude, back.latitude, 1e-15)
        assertEquals(p.longitude, back.longitude, 1e-15)
        // Its own points come back too.
        assertEquals(0.0021, road.toGeo(road.at(road.to).point).latitude, 1e-15)
    }

    @Test
    fun `a three kilometre window's east-west scale drifts by under 0 point 05 percent at latitude 35`() {
        // A made-up road at sea: 3 km due north from 35 degrees north, 30 degrees west.
        val start = GeoPoint(35.0, -30.0)
        val degree = Math.toRadians(1.0) * Geo.EARTH_RADIUS_METERS
        val drift = { km: Double ->
            val road = RoadAxis.of(listOf(start, GeoPoint(35.0 + km * 1000.0 / degree, -30.0)))
            val t = 10.0
            val far = road.to
            val error = Geo.meters(road.toGeo(road.at(far).point), road.toGeo(road.offset(far, t))) - t
            Math.abs(error) / t
        }
        val window = drift(3.0)
        assertTrue(window < 0.0005, "drift $window")
        // It is the frame's fixed scale, as tan 35 x 3 km / R says, not noise.
        assertEquals(Math.tan(Math.toRadians(35.0)) * 3000.0 / Geo.EARTH_RADIUS_METERS, window, 2e-6)
        // At the origin's latitude it is exact; over 30 km it would not be within the bound, hence one origin per window.
        val road = RoadAxis.of(listOf(start, GeoPoint(35.01, -30.0)))
        assertEquals(10.0, Geo.meters(road.toGeo(road.at(road.from).point), road.toGeo(road.offset(road.from, 10.0))), 1e-6)
        assertTrue(drift(30.0) > 0.0005)
    }

    @Test
    fun `an axis needs two points that differ`() {
        assertThrows<IllegalArgumentException> { RoadAxis.of(listOf(origin)) }
        assertThrows<IllegalArgumentException> { RoadAxis.of(listOf(origin, origin)) }
        assertThrows<IllegalArgumentException> { RoadAxis.of(listOf(origin, geo(0.0, 10.0)), DoubleArray(1)) }
        // Repeated points are dropped, as where two steps of a route meet.
        val road = axis(0.0 to 0.0, 0.0 to 10.0, 0.0 to 10.0, 0.0 to 20.0)
        assertTrue(road.fillets.isEmpty())
        assertEquals(20.0, road.to, 1e-9)
    }

    // ------------------------------------------------------------------ GEOM's experiment, on Kotlin's own random curves

    @Test
    fun `no offset of an invented curve crosses itself unless a sample is flagged`() {
        val rng = Random(20261111)
        val offsets = listOf(1.83, 5.49, 9.15, 12.81)
        var flagged = 0
        var regular = 0
        for (kind in Kind.entries) {
            var made = 0
            while (made < CURVES) {
                val poly = randomPolyline(rng, kind) ?: continue
                if (crossings(poly) > 0) continue // the road itself crosses: no fair test of offsetting
                made++
                val road = axis(*poly.toTypedArray())
                val samples = road.stations()
                for (t in offsets) {
                    for (sign in listOf(1.0, -1.0)) {
                        val tt = sign * t
                        val folded = samples.any { road.folds(it, tt) }
                        if (folded) {
                            flagged++
                            continue
                        }
                        regular++
                        val line = samples.map { road.offset(it, tt) }
                        assertEquals(0, crossings(line), "$kind at $tt crosses without a flag")
                    }
                }
            }
        }
        // Freeways and ramps fold nowhere; some street corners are too sharp for wide offsets.
        assertTrue(flagged > 0)
        assertTrue(regular > flagged)
    }

    private enum class Kind { FREEWAY, STREET, STREET_BLOCK, RAMP }

    /** GEOM's invented OSM-like polylines (offset_experiment.py), with Kotlin's seeded generator. */
    private fun randomPolyline(rng: Random, kind: Kind): List<Pair<Double, Double>>? {
        val pts = ArrayList<Pair<Double, Double>>()
        pts.add(0.0 to 0.0)
        var heading = rng.nextDouble(0.0, 2 * PI)
        if (kind == Kind.RAMP) {
            val r = rng.nextDouble(45.0, 90.0)
            val chord = rng.nextDouble(8.0, 15.0)
            val step = 2.0 * asin(minOf(1.0, chord / (2.0 * r)))
            val sweep = rng.nextDouble(Math.toRadians(180.0), Math.toRadians(270.0))
            repeat((sweep / step).toInt()) {
                heading += step * (1.0 + gauss(rng) * 0.25)
                val last = pts.last()
                pts.add((last.first + chord * cos(heading)) to (last.second + chord * sin(heading)))
            }
            return pts
        }
        val count = if (kind == Kind.FREEWAY) 40 else 30
        repeat(count) {
            val length: Double
            val turn: Double
            if (kind == Kind.FREEWAY) {
                length = rng.nextDouble(20.0, 120.0)
                turn = gauss(rng) * Math.toRadians(3.0)
            } else {
                length = rng.nextDouble(4.0, 60.0)
                val u = rng.nextDouble()
                turn = when {
                    u < 0.80 -> gauss(rng) * Math.toRadians(6.0)
                    u < 0.95 -> rng.nextDouble(-1.0, 1.0) * Math.toRadians(35.0)
                    else -> (if (rng.nextBoolean()) 1.0 else -1.0) * Math.toRadians(rng.nextDouble(70.0, 100.0))
                }
            }
            heading += turn
            val last = pts.last()
            pts.add((last.first + length * cos(heading)) to (last.second + length * sin(heading)))
        }
        if (kind == Kind.STREET_BLOCK) {
            // Within a block: no turn sharper than 35 degrees (corners are junctions).
            for (i in 1 until pts.size - 1) {
                val (ax, ay) = pts[i].first - pts[i - 1].first to pts[i].second - pts[i - 1].second
                val (bx, by) = pts[i + 1].first - pts[i].first to pts[i + 1].second - pts[i].second
                if ((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)) < cos(Math.toRadians(35.0))) return null
            }
        }
        return pts
    }

    private fun gauss(rng: Random): Double {
        val u = 1.0 - rng.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * rng.nextDouble())
    }

    private fun crossings(points: List<Any>): Int {
        val xs = DoubleArray(points.size)
        val ys = DoubleArray(points.size)
        for ((i, p) in points.withIndex()) {
            when (p) {
                is LocalPoint -> { xs[i] = p.x; ys[i] = p.y }
                is Pair<*, *> -> { xs[i] = p.first as Double; ys[i] = p.second as Double }
            }
        }
        val cell = 10.0
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in 0 until points.size - 1) {
            val x0 = Math.floor(minOf(xs[i], xs[i + 1]) / cell).toInt()
            val x1 = Math.floor(maxOf(xs[i], xs[i + 1]) / cell).toInt()
            val y0 = Math.floor(minOf(ys[i], ys[i + 1]) / cell).toInt()
            val y1 = Math.floor(maxOf(ys[i], ys[i + 1]) / cell).toInt()
            for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(gx.toLong() * 1_000_003L + gy) { ArrayList() }.add(i)
        }
        val seen = HashSet<Long>()
        var found = 0
        for (list in grid.values) {
            for (a in list.indices) {
                for (b in a + 1 until list.size) {
                    val i = minOf(list[a], list[b])
                    val j = maxOf(list[a], list[b])
                    if (j - i < 2 || !seen.add(i.toLong() * 1_000_003L + j)) continue
                    if (cross(xs, ys, i, j)) found++
                }
            }
        }
        return found
    }

    /** Whether the segments [i] and [j] cross strictly. */
    private fun cross(xs: DoubleArray, ys: DoubleArray, i: Int, j: Int): Boolean {
        fun side(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double) = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
        val d1 = side(xs[j], ys[j], xs[j + 1], ys[j + 1], xs[i], ys[i])
        val d2 = side(xs[j], ys[j], xs[j + 1], ys[j + 1], xs[i + 1], ys[i + 1])
        val d3 = side(xs[i], ys[i], xs[i + 1], ys[i + 1], xs[j], ys[j])
        val d4 = side(xs[i], ys[i], xs[i + 1], ys[i + 1], xs[j + 1], ys[j + 1])
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0) && d1 != 0.0 && d2 != 0.0 && d3 != 0.0 && d4 != 0.0
    }

    private companion object {
        /** Invented curves per kind, as GEOM's experiment drew. */
        const val CURVES = 150
    }
}
