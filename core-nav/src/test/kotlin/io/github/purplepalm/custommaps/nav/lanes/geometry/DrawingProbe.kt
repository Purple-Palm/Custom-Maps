package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.model.GeoPoint
import kotlin.math.abs

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

    /** Projections from a guess within a few metres converge to well under a micrometre in this many steps. */
    private const val ITERATIONS = 12
}
