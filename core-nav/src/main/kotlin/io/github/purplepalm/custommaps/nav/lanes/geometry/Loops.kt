package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import kotlin.math.hypot

/**
 * Lines without the small loops of a curve too tight for them (the design's 3.3, GEOM 2.2). Where a line lies farther out
 * than an arc's radius on the arc's inner side (1 - kappa t <= 0, [RoadAxis.folds]), its offset runs back over itself along
 * the arc and crosses itself a little before and after it: a loop of millimetres where a bend of a few degrees gets a short
 * arc between two close vertices, of metres at a street corner. Cut where it crosses itself, the line turns there instead,
 * as the offset of the polyline the arc rounds off would, and nothing of it is drawn where it folds.
 *
 * A loop is cut only around a place where the line turns back on itself (two of its pieces one after the other more than a
 * quarter turn apart, as at an offset's cusp) and only where it closes within [LOOP_METERS] of stations: a road that crosses
 * itself farther along, a loop ramp passing under its own bridge, is never touched. Pure.
 */
internal object Loops {

    /** A loop is cut only where it closes within this many metres of stations. */
    const val LOOP_METERS = 60.0

    /** Two pieces cross where each end of either lies at least this far off the other's line: not where they only touch. */
    private const val CLEAR_METERS = 1e-9

    /** No line is cut more often than this, whatever it holds. */
    private const val MAX_CUTS = 1_000

    /**
     * [points] at [stations] (ascending), with each small loop cut out: the line runs to the place where it crosses itself,
     * stays there from the station where it reaches that place on the way in to the station where it reaches it on the way
     * out (the place twice), and goes on from there. [local] puts a point in flat metres and [back] takes such a point back;
     * every point that is kept is the one given. Where nothing is cut, the same lists.
     */
    fun <P> cut(points: List<P>, stations: List<Double>, local: (P) -> LocalPoint, back: (LocalPoint) -> P): Pair<List<P>, List<Double>> {
        require(points.size == stations.size) { "One station per point" }
        if (points.size < 4) return points to stations
        val flat = ArrayList<LocalPoint>(points.size)
        for (p in points) flat.add(local(p))
        if (turnsBack(flat).isEmpty()) return points to stations
        val kept = ArrayList<P>(points)
        val at = ArrayList<Double>(stations)
        var cuts = 0
        while (cuts < MAX_CUTS) {
            val crossing = smallestLoop(flat, at) ?: break
            val (i, j) = crossing.pieces
            // Points i + 1 to j lie on the loop: in their place the crossing, reached on the way in and on the way out.
            val x = crossing.point
            replace(flat, i, j, x)
            replace(kept, i, j, back(x))
            for (k in j downTo i + 1) at.removeAt(k)
            at.add(i + 1, crossing.inStation)
            at.add(i + 2, crossing.outStation)
            cuts++
        }
        return if (cuts == 0) points to stations else kept to at
    }

    /**
     * Two pieces of the ring [ring] (closed: its last point its first) that cross, by the indices of their first points; null
     * where it is simple. A sweep along the ring's longer side: a ring along a road meets only its neighbours across it.
     */
    fun ringCrossing(ring: List<LocalPoint>): Pair<Int, Int>? {
        val n = ring.size - 1
        if (n < 3) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in ring) {
            minX = minOf(minX, p.x)
            maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y)
            maxY = maxOf(maxY, p.y)
        }
        val alongX = maxX - minX >= maxY - minY
        fun along(p: LocalPoint) = if (alongX) p.x else p.y
        fun low(i: Int) = minOf(along(ring[i]), along(ring[i + 1]))
        fun high(i: Int) = maxOf(along(ring[i]), along(ring[i + 1]))
        val order = (0 until n).sortedBy { low(it) }
        val active = ArrayList<Int>()
        for (i in order) {
            val from = low(i)
            active.removeAll { high(it) < from }
            for (j in active) {
                val a = minOf(i, j)
                val b = maxOf(i, j)
                if (b - a < 2 || (a == 0 && b == n - 1)) continue
                if (crosses(ring[a], ring[a + 1], ring[b], ring[b + 1])) return a to b
            }
            active.add(i)
        }
        return null
    }

    /**
     * The ring [ring] (closed) made simple: at each place where two of its pieces cross, the smaller of the two parts the
     * crossing closes off is cut away and the ring turns at the crossing. For a ring whose edges were already cut of their
     * loops and still cross, as a piece of a tint or a lit lane that begins inside a loop.
     */
    fun cutRing(ring: List<LocalPoint>): List<LocalPoint> {
        var out: List<LocalPoint> = ring
        var cuts = 0
        while (cuts < MAX_CUTS) {
            val (i, j) = ringCrossing(out) ?: break
            val x = point(out[i], out[i + 1], out[j], out[j + 1])
            val n = out.size - 1
            out = if (j - i <= n - (j - i)) {
                // The part from i + 1 to j is the smaller: the ring goes from piece i straight on into piece j's end.
                out.subList(0, i + 1) + x + out.subList(j + 1, out.size)
            } else {
                // The part from i + 1 to j is the larger: it alone is kept, closed at the crossing.
                listOf(x) + out.subList(i + 1, j + 1) + x
            }
            cuts++
        }
        return out
    }

    /** Whether the piece [a] to [b] and the piece [c] to [d] cross, each end of either off the other's line. */
    private fun crosses(a: LocalPoint, b: LocalPoint, c: LocalPoint, d: LocalPoint): Boolean {
        val rx = b.x - a.x
        val ry = b.y - a.y
        val sx = d.x - c.x
        val sy = d.y - c.y
        val ab = hypot(rx, ry)
        val cd = hypot(sx, sy)
        if (ab <= 0.0 || cd <= 0.0) return false
        return opposite((rx * (c.y - a.y) - ry * (c.x - a.x)) / ab, (rx * (d.y - a.y) - ry * (d.x - a.x)) / ab) &&
            opposite((sx * (a.y - c.y) - sy * (a.x - c.x)) / cd, (sx * (b.y - c.y) - sy * (b.x - c.x)) / cd)
    }

    /** Where the lines through [a], [b] and through [c], [d] meet (the pieces cross: [crosses]). */
    private fun point(a: LocalPoint, b: LocalPoint, c: LocalPoint, d: LocalPoint): LocalPoint {
        val sx = d.x - c.x
        val sy = d.y - c.y
        val cd = hypot(sx, sy)
        val a1 = (sx * (a.y - c.y) - sy * (a.x - c.x)) / cd
        val b1 = (sx * (b.y - c.y) - sy * (b.x - c.x)) / cd
        val u = a1 / (a1 - b1)
        return LocalPoint(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
    }

    /** The indices of the points where [line] turns back on itself: its pieces before and after them more than a quarter turn apart. */
    internal fun turnsBack(line: List<LocalPoint>): List<Int> {
        val out = ArrayList<Int>()
        for (k in 1 until line.size - 1) {
            val a = previousOther(line, k) ?: continue
            val b = nextOther(line, k) ?: continue
            val p = line[k]
            if ((p.x - a.x) * (b.x - p.x) + (p.y - a.y) * (b.y - p.y) < 0.0) out.add(k)
        }
        return out
    }

    /** Where two pieces [pieces] (the indices of their first points) cross, at [point], reached at [inStation] and [outStation]. */
    private class Crossing(val pieces: Pair<Int, Int>, val point: LocalPoint, val inStation: Double, val outStation: Double)

    /**
     * Of the loops around the places where [line] turns back on itself, the one that closes over the fewest pieces: two pieces,
     * one before such a place and one after it, that cross within [LOOP_METERS] of [stations]; null where none do.
     */
    private fun smallestLoop(line: List<LocalPoint>, stations: List<Double>): Crossing? {
        var best: Crossing? = null
        for (k in turnsBack(line)) {
            var i = k - 1
            while (i >= 0 && stations[k] - stations[i] <= LOOP_METERS) {
                var j = k
                while (j + 1 < line.size && stations[j + 1] - stations[i] <= LOOP_METERS) {
                    val found = best
                    if (j - i >= 2 && (found == null || j - i < found.pieces.second - found.pieces.first)) {
                        crossing(line, stations, i, j)?.let { best = it }
                    }
                    j++
                }
                i--
            }
        }
        return best
    }

    /** Where the piece from point [i] and the piece from point [j] of [line] cross; null where they do not. */
    private fun crossing(line: List<LocalPoint>, stations: List<Double>, i: Int, j: Int): Crossing? {
        val a = line[i]
        val b = line[i + 1]
        val c = line[j]
        val d = line[j + 1]
        val rx = b.x - a.x
        val ry = b.y - a.y
        val sx = d.x - c.x
        val sy = d.y - c.y
        val ab = hypot(rx, ry)
        val cd = hypot(sx, sy)
        if (ab <= 0.0 || cd <= 0.0) return null
        // How far each end lies to the left of the other piece's line.
        val c1 = (rx * (c.y - a.y) - ry * (c.x - a.x)) / ab
        val d1 = (rx * (d.y - a.y) - ry * (d.x - a.x)) / ab
        val a1 = (sx * (a.y - c.y) - sy * (a.x - c.x)) / cd
        val b1 = (sx * (b.y - c.y) - sy * (b.x - c.x)) / cd
        if (!opposite(c1, d1) || !opposite(a1, b1)) return null
        val u = a1 / (a1 - b1)
        val v = c1 / (c1 - d1)
        val point = LocalPoint(a.x + rx * u, a.y + ry * u)
        val inStation = stations[i] + (stations[i + 1] - stations[i]) * u
        val outStation = stations[j] + (stations[j + 1] - stations[j]) * v
        return Crossing(i to j, point, inStation, outStation)
    }

    private fun opposite(u: Double, v: Double): Boolean = (u > CLEAR_METERS && v < -CLEAR_METERS) || (u < -CLEAR_METERS && v > CLEAR_METERS)

    /** [list] with its elements [i] + 1 to [j] replaced by [x] twice. */
    private fun <T> replace(list: MutableList<T>, i: Int, j: Int, x: T) {
        for (k in j downTo i + 1) list.removeAt(k)
        list.add(i + 1, x)
        list.add(i + 2, x)
    }

    private fun previousOther(line: List<LocalPoint>, k: Int): LocalPoint? {
        for (a in k - 1 downTo 0) if (line[a] != line[k]) return line[a]
        return null
    }

    private fun nextOther(line: List<LocalPoint>, k: Int): LocalPoint? {
        for (b in k + 1 until line.size) if (line[b] != line[k]) return line[b]
        return null
    }
}
