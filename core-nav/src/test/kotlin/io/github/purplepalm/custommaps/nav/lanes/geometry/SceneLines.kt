package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalMeters
import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.CardWindows
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Made-up lines for the made-up routes of [LaneScenes]: from 0, 0 due north at sea, a vertex every [STEP] metres,
 * the road turning at each maneuver's station by an angle of its kind. No place.
 */
internal object SceneLines {

    /** Vertices this far apart, besides those at the bends. */
    const val STEP = 100.0

    /** How far an exit's ramp leaves the freeway's way at its gore, in degrees. */
    const val RAMP_DEGREES = 17.0

    private val frame = LocalMeters(0.0, 0.0)

    /** The plan of [route] as the engine makes it: the card's windows and the unknown exits as the scenes read them. */
    fun planOf(route: LaneRoute): LanePlan =
        LanePlanner.plan(LaneTopology.of(LaneEvidence.of(route)), CardWindows.latched(route), LaneScenes.sideBranches(route))

    /** The line of [route]: north from 0, 0, turning at every maneuver by [bendOf]'s angle; its length is the route's. */
    fun of(route: LaneRoute, rampDegrees: Double = RAMP_DEGREES): List<GeoPoint> = line(route.lengthMeters, bendsOf(route, rampDegrees))

    /** Where [route]'s line turns, at its maneuvers: station to degrees, left positive. */
    fun bendsOf(route: LaneRoute, rampDegrees: Double = RAMP_DEGREES): List<Pair<Double, Double>> = route.nodes.mapNotNull { node ->
        val maneuver = node.maneuver ?: return@mapNotNull null
        val degrees = bendOf(maneuver.type, maneuver.modifier, rampDegrees)
        if (degrees == 0.0) null else node.station to degrees
    }

    /**
     * A line [length] metres long from 0, 0 due north, turning by each of [bends] (station to degrees, left positive); a vertex
     * every [STEP] metres but where it would lie within [clearOfBends] metres of a bend.
     */
    fun line(length: Double, bends: List<Pair<Double, Double>> = emptyList(), clearOfBends: Double = 0.0): List<GeoPoint> =
        local(length, bends, clearOfBends).map { geo(it) }

    /** The same in local metres of 0, 0. */
    fun local(length: Double, bends: List<Pair<Double, Double>> = emptyList(), clearOfBends: Double = 0.0): List<LocalPoint> {
        val steps = generateSequence(0.0) { it + STEP }.takeWhile { it < length }.filter { s -> s == 0.0 || bends.none { abs(it.first - s) < clearOfBends } }
        val stops = (steps.toList() + bends.map { it.first } + length)
            .filter { it in 0.0..length }.distinct().sorted()
        val out = ArrayList<LocalPoint>()
        var heading = Math.PI / 2.0 // north, counterclockwise from east
        var at = LocalPoint(0.0, 0.0)
        var station = 0.0
        out.add(at)
        for (stop in stops.drop(1)) {
            val d = stop - station
            at = LocalPoint(at.x + d * cos(heading), at.y + d * sin(heading))
            out.add(at)
            station = stop
            bends.firstOrNull { it.first == stop }?.let { heading += Math.toRadians(it.second) }
        }
        return out
    }

    fun geo(p: LocalPoint) = GeoPoint(frame.latitudeOf(p), frame.longitudeOf(p))

    fun local(p: GeoPoint): LocalPoint = frame.toLocal(p.latitude, p.longitude)

    /** The turn of a maneuver of [type] to [modifier] in degrees, left positive: a ramp leaves at [rampDegrees], a fork at 8. */
    fun bendOf(type: ManeuverType, modifier: TurnModifier?, rampDegrees: Double): Double {
        val side = when (modifier) {
            TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT, TurnModifier.U_TURN -> 1.0
            TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT -> -1.0
            else -> 0.0
        }
        val degrees = when (type) {
            ManeuverType.DEPART, ManeuverType.ARRIVE -> 0.0
            ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP -> rampDegrees
            ManeuverType.FORK, ManeuverType.CONTINUE, ManeuverType.MERGE -> 8.0
            else -> when (modifier) {
                TurnModifier.SLIGHT_LEFT, TurnModifier.SLIGHT_RIGHT -> 30.0
                TurnModifier.SHARP_LEFT, TurnModifier.SHARP_RIGHT -> 135.0
                else -> 90.0
            }
        }
        return side * degrees
    }
}
