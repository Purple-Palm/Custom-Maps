package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Reads a drawing's features back along a road's axis: where a point lies, and where a polygon's outline crosses a station. */
internal object DrawingProbe {

    /** The station and the offset, left positive, of [p] along [axis], found from the station [guess]. */
    fun project(axis: RoadAxis, guess: Double, p: GeoPoint): Pair<Double, Double> {
        val q = axis.toLocal(p)
        var s = guess.coerceIn(axis.from, axis.to)
        repeat(ITERATIONS) {
            val place = axis.at(s)
            s = (s + (q.x - place.point.x) * place.tx + (q.y - place.point.y) * place.ty).coerceIn(axis.from, axis.to)
        }
        val place = axis.at(s)
        return s to (q.x - place.point.x) * place.nx + (q.y - place.point.y) * place.ny
    }

    /**
     * The station and the offset of [p] along whichever of [axes] it lies beside: the one where it projects square to the axis
     * rather than onto an end the station was held to.
     */
    fun project(axes: List<RoadAxis>, guess: Double, p: GeoPoint): Pair<Double, Double> = axes.map { axis ->
        val found = project(axis, guess, p)
        val q = axis.toLocal(p)
        val place = axis.at(found.first)
        found to abs((q.x - place.point.x) * place.tx + (q.y - place.point.y) * place.ty)
    }.minByOrNull { it.second }!!.first

    /** The offsets, left positive and sorted, where the outlines [rings] cross the normal of [axis] at [station]. */
    fun across(axis: RoadAxis, station: Double, rings: List<List<GeoPoint>>): List<Double> {
        val place = axis.at(station)
        val out = ArrayList<Double>()
        for (ring in rings) {
            for (k in 0 until ring.size - 1) {
                val a = axis.toLocal(ring[k])
                val b = axis.toLocal(ring[k + 1])
                val ua = (a.x - place.point.x) * place.tx + (a.y - place.point.y) * place.ty
                val ub = (b.x - place.point.x) * place.tx + (b.y - place.point.y) * place.ty
                if (ua * ub > 0.0 || ua == ub) continue
                val f = ua / (ua - ub)
                val x = a.x + (b.x - a.x) * f
                val y = a.y + (b.y - a.y) * f
                out.add((x - place.point.x) * place.nx + (y - place.point.y) * place.ny)
            }
        }
        return out.sorted()
    }

    /**
     * Two pieces of the line [points] (a ring where [closed]: its last point its first) that cross, by the indices of their
     * first points, each end of either at least [CROSS_CLEAR_METERS] off the other's line; null where none do. Every pair of
     * pieces that are not neighbours, found through a grid of [CELL_METERS].
     */
    fun selfCrossing(points: List<LocalPoint>, closed: Boolean): Pair<Int, Int>? {
        val n = points.size - 1
        if (n < 3) return null
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            val a = points[i]
            val b = points[i + 1]
            for (x in floor(min(a.x, b.x) / CELL_METERS).toLong()..floor(max(a.x, b.x) / CELL_METERS).toLong()) {
                for (y in floor(min(a.y, b.y) / CELL_METERS).toLong()..floor(max(a.y, b.y) / CELL_METERS).toLong()) {
                    grid.getOrPut(x * 1_000_003L + y) { ArrayList() }.add(i)
                }
            }
        }
        for (cell in grid.values) {
            for (p in cell.indices) for (q in p + 1 until cell.size) {
                val i = min(cell[p], cell[q])
                val j = max(cell[p], cell[q])
                if (j - i < 2 || (closed && i == 0 && j == n - 1)) continue
                if (crosses(points[i], points[i + 1], points[j], points[j + 1])) return i to j
            }
        }
        return null
    }

    /** Whether the pieces [a] to [b] and [c] to [d] cross, each end of either at least [CROSS_CLEAR_METERS] off the other's line. */
    private fun crosses(a: LocalPoint, b: LocalPoint, c: LocalPoint, d: LocalPoint): Boolean {
        fun side(p: LocalPoint, q: LocalPoint, r: LocalPoint): Double {
            val len = p.distanceTo(q)
            return if (len < 1e-12) 0.0 else ((q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)) / len
        }
        fun opposite(u: Double, v: Double) = (u > CROSS_CLEAR_METERS && v < -CROSS_CLEAR_METERS) || (u < -CROSS_CLEAR_METERS && v > CROSS_CLEAR_METERS)
        return opposite(side(c, d, a), side(c, d, b)) && opposite(side(a, b, c), side(a, b, d))
    }

    /** Projections from a guess within a few metres converge to well under a micrometre in this many steps. */
    private const val ITERATIONS = 12

    /** Two pieces cross where each end of either lies at least this far off the other's line: a tenth of a micrometre. */
    private const val CROSS_CLEAR_METERS = 1e-7

    /** The grid [selfCrossing] finds pieces near each other with. */
    private const val CELL_METERS = 4.0
}
