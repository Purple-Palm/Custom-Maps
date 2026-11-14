package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.At
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.JOIN_RIGHT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.NodeAndLanes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.THROUGH
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.hov
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.option
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sl
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.RouteNode
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.RouteLane
import kotlin.random.Random

/** Made-up routes drawn from a seeded random source, for the path's and the drawing's property tests. No place. */
internal object RandomRoutes {

    /** A made-up approach to an exit: random lane points, counts, unusable edge lanes, roads, an exit to either side, a split. */
    fun approach(rng: Random, id: String): LaneRoute {
        val right = rng.nextBoolean()
        val carpool = rng.nextDouble() < 0.25
        var count = rng.nextInt(3, 6)
        val points = ArrayList<At>()
        var before = rng.nextDouble(2_400.0, 3_400.0)
        while (true) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0 || points.size >= 3) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            val lanes = ArrayList<RouteLane>()
            if (carpool) lanes.add(hov())
            repeat(count) { lanes.add(s()) }
            val roads = when (rng.nextInt(5)) {
                0 -> {
                    lanes[if (carpool) 1 else 0] = s(false)
                    EXIT_LEFT
                }
                1 -> {
                    lanes[lanes.size - 1] = s(false)
                    EXIT_RIGHT
                }
                2 -> JOIN_RIGHT
                else -> THROUGH
            }
            points.add(At(before, lanes, roads))
        }
        val exit = ArrayList<RouteLane>()
        if (carpool) exit.add(hov())
        repeat(count) { exit.add(s(false)) }
        val usable = rng.nextInt(1, 3)
        val edge = if (right) exit.size - 1 else if (carpool) 1 else 0
        exit[edge] = if (right) sr() else sl()
        if (usable == 2) {
            val inner = if (right) edge - 1 else edge + 1
            if (inner in exit.indices && !exit[inner].designated) exit[inner] = option()
        }
        if (rng.nextBoolean()) exit.add(if (right) exit.size else (if (carpool) 1 else 0), if (right) sr() else sl())
        val split = rng.nextDouble() < 0.3
        val maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT
        val rampPoints = if (split) {
            listOf(rng.nextDouble(150.0, 340.0) to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT))
        } else {
            emptyList()
        }
        return LaneScenes.approach(points, exit, maneuver, planMps = rng.nextDouble(22.0, 34.0), rampPoints = rampPoints, rampMeters = 600.0, id = id)
    }

    /**
     * A made-up approach to an exit with a lane kept for car pools or buses: two to six lanes at up to three random lane
     * points, one lane at a random index (either side or between others) kept for car pools or buses from a random point on,
     * edge lanes leaving by roads, an exit on either side whose lanes may keep that lane or open it to every car again, one
     * or two lanes to take, an added exit lane that may lie beyond the kept one, and a split after the gore in 30 %.
     */
    fun keptLane(rng: Random, id: String): LaneRoute {
        val right = rng.nextBoolean()
        val bus = rng.nextBoolean()
        var count = rng.nextInt(2, 6)
        val points = ArrayList<At>()
        var before = rng.nextDouble(2_400.0, 3_400.0)
        var lane = -1
        val from = rng.nextInt(0, 3)
        while (true) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0 || points.size >= 3) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            if (lane < 0 && points.size >= from) lane = rng.nextInt(count)
            if (lane >= count) lane = count - 1
            val lanes = ArrayList<RouteLane>()
            repeat(count) { i -> lanes.add(if (i == lane) (if (bus) LaneScenes.bus() else hov()) else s()) }
            val roads = when (rng.nextInt(5)) {
                0 -> {
                    if (lane != 0) lanes[0] = s(false)
                    EXIT_LEFT
                }
                1 -> {
                    if (lane != lanes.size - 1) lanes[lanes.size - 1] = s(false)
                    EXIT_RIGHT
                }
                2 -> JOIN_RIGHT
                else -> THROUGH
            }
            points.add(At(before, lanes, roads))
        }
        val exit = ArrayList<RouteLane>()
        val keep = rng.nextBoolean()
        repeat(count) { i -> exit.add(if (i == lane && keep) (if (bus) LaneScenes.bus() else hov()) else s(false)) }
        val usable = rng.nextInt(1, 3)
        val edge = if (right) exit.size - 1 else 0
        exit[edge] = if (right) sr() else sl()
        if (usable == 2) {
            val inner = if (right) edge - 1 else edge + 1
            if (inner in exit.indices && !exit[inner].designated) exit[inner] = option()
        }
        if (rng.nextBoolean()) exit.add(if (right) exit.size else 0, if (right) sr() else sl())
        val split = rng.nextDouble() < 0.3
        val maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT
        val rampPoints = if (split) {
            listOf(rng.nextDouble(150.0, 340.0) to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT))
        } else {
            emptyList()
        }
        return LaneScenes.approach(points, exit, maneuver, planMps = rng.nextDouble(22.0, 34.0), rampPoints = rampPoints, rampMeters = 600.0, id = id)
    }

    /**
     * A made-up route through two maneuvers: two to four lanes at one or two lane points, a fork to one side about 3 km on that
     * takes some of them, then up to three more lane points with a lane kept for car pools or buses from a random point on and
     * edge lanes leaving by roads, and an exit on either side whose lanes may keep that lane, onto a ramp that ends at a street.
     */
    fun twoManeuvers(rng: Random, id: String): LaneRoute {
        val v = rng.nextDouble(22.0, 34.0)
        val nodes = ArrayList<RouteNode>()
        nodes.add(LaneScenes.node(0.0, maneuver = LaneScenes.DEPART, planMps = v))
        val forkLeft = rng.nextBoolean()
        val n0 = rng.nextInt(4, 7)
        val goOn = rng.nextInt(2, n0 - 1)
        val m1 = rng.nextDouble(2_600.0, 3_600.0)
        var at = m1 - rng.nextDouble(1_200.0, 2_000.0)
        repeat(rng.nextInt(1, 3)) {
            nodes.add(LaneScenes.node(at, *Array(n0) { s() }, planMps = v))
            at += rng.nextDouble(300.0, 700.0)
            if (at > m1 - 200.0) return@repeat
        }
        val forkLanes = Array(n0) { i ->
            val onSide = if (forkLeft) i < goOn else i >= n0 - goOn
            if (onSide) (if (forkLeft) sl() else sr()) else (if (forkLeft) sr(false) else sl(false))
        }
        nodes.add(
            LaneScenes.node(
                m1, *forkLanes, roads = if (forkLeft) EXIT_RIGHT else EXIT_LEFT,
                maneuver = if (forkLeft) LaneScenes.FORK_LEFT else LaneScenes.FORK_RIGHT, planMps = v,
            ),
        )
        val right = rng.nextBoolean()
        val bus = rng.nextBoolean()
        val m2 = m1 + rng.nextDouble(2_000.0, 3_400.0)
        var count = goOn
        var lane = -1
        val keptFrom = rng.nextInt(0, 3)
        var before = m2 - m1 - rng.nextDouble(250.0, 500.0)
        var k = 0
        while (true) {
            before -= rng.nextDouble(300.0, 1_100.0)
            if (before < 250.0 || k >= 3) break
            count = (count + rng.nextInt(-1, 2)).coerceIn(2, 6)
            if (lane < 0 && k >= keptFrom) lane = rng.nextInt(count)
            if (lane >= count) lane = count - 1
            val lanesHere = ArrayList<RouteLane>()
            repeat(count) { i -> lanesHere.add(if (i == lane) (if (bus) LaneScenes.bus() else hov()) else s()) }
            val roads = when (rng.nextInt(5)) {
                0 -> {
                    if (lane != 0) lanesHere[0] = s(false)
                    EXIT_LEFT
                }
                1 -> {
                    if (lane != lanesHere.size - 1) lanesHere[lanesHere.size - 1] = s(false)
                    EXIT_RIGHT
                }
                2 -> JOIN_RIGHT
                else -> THROUGH
            }
            nodes.add(LaneScenes.node(m2 - before, *lanesHere.toTypedArray(), roads = roads, planMps = v))
            k++
        }
        nodes.sortBy { it.station }
        val exit = ArrayList<RouteLane>()
        val keep = rng.nextBoolean()
        repeat(count) { i -> exit.add(if (i == lane && keep) (if (bus) LaneScenes.bus() else hov()) else s(false)) }
        val edge = if (right) exit.size - 1 else 0
        exit[edge] = if (right) sr() else sl()
        if (rng.nextBoolean()) {
            val inner = if (right) edge - 1 else edge + 1
            if (inner in exit.indices && !exit[inner].designated) exit[inner] = option()
        }
        if (rng.nextBoolean()) exit.add(if (right) exit.size else 0, if (right) sr() else sl())
        nodes.add(LaneScenes.node(m2, *exit.toTypedArray(), road = RoadKind.RAMP, maneuver = if (right) LaneScenes.OFF_RIGHT else LaneScenes.OFF_LEFT, planMps = 20.0))
        nodes.add(LaneScenes.node(m2 + 500.0, road = RoadKind.STREET, maneuver = LaneScenes.TURN_LEFT, planMps = 15.0))
        nodes.add(LaneScenes.node(m2 + 600.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = 15.0))
        return LaneScenes.route(*nodes.toTypedArray(), id = id)
    }

    /**
     * A made-up street to a turn at [LaneScenes.GORE] to either side, planned at 25 to 55 mph: one to three general lanes, a
     * lane kept for buses at the left edge in a fifth of the routes and one only for bicycles at the right edge in a third;
     * two to four junctions before the turn, 120 to 400 m apart, each with a road crossing it and a traffic signal or a stop
     * sign at random, a lane point in four of five; the turn from the general lane at its side or, in half the routes, from
     * a turn pocket the turn's point adds at that side (left of the bicycle lane, never beside a lane kept for buses), at a
     * signal, a stop sign or neither; in two of five a lane point 60 to 240 m along the road after it; the arrival 300 m past
     * the turn.
     */
    fun street(rng: Random, id: String): LaneRoute {
        val v = rng.nextDouble(25.0, 55.0) * PlanningSpeed.MPS_PER_MPH
        val general = rng.nextInt(1, 4)
        val right = rng.nextBoolean()
        val pocket = rng.nextBoolean()
        val bus = rng.nextDouble() < 0.2 && !(pocket && !right)
        val bike = rng.nextDouble() < 1.0 / 3.0
        fun lanes(turn: Boolean): Array<RouteLane> {
            val out = ArrayList<RouteLane>()
            if (bus) out.add(LaneScenes.bus())
            if (turn && pocket && !right) out.add(LaneScenes.left())
            val edge = if (right) general - 1 else 0
            repeat(general) { i ->
                out.add(
                    when {
                        !turn -> s()
                        pocket -> s(false)
                        i == edge -> if (right) LaneScenes.right() else LaneScenes.left()
                        else -> s(false)
                    },
                )
            }
            if (turn && pocket && right) out.add(LaneScenes.right())
            if (bike) out.add(LaneScenes.bike())
            return out.toTypedArray()
        }
        fun flags(): Pair<Boolean, Boolean> = when (rng.nextInt(3)) {
            0 -> true to false
            1 -> false to true
            else -> false to false
        }
        val nodes = ArrayList<RouteNode>()
        nodes.add(LaneScenes.street(0.0, maneuver = LaneScenes.DEPART, planMps = v))
        val stations = ArrayList<Double>()
        var at = LaneScenes.GORE
        repeat(rng.nextInt(2, 5)) {
            at -= rng.nextDouble(120.0, 400.0)
            stations.add(at)
        }
        for (station in stations.sorted()) {
            val (signal, stop) = flags()
            val withLanes = rng.nextDouble() < 0.8
            nodes.add(
                if (withLanes) {
                    LaneScenes.street(station, *lanes(false), roads = LaneScenes.CROSSING, signal = signal, stop = stop, planMps = v)
                } else {
                    LaneScenes.street(station, roads = LaneScenes.CROSSING, signal = signal, stop = stop, planMps = v)
                },
            )
        }
        val (signal, stop) = flags()
        nodes.add(
            LaneScenes.street(
                LaneScenes.GORE, *lanes(true), roads = LaneScenes.CROSSING, maneuver = if (right) LaneScenes.TURN_RIGHT else LaneScenes.TURN_LEFT,
                signal = signal, stop = stop, planMps = v,
            ),
        )
        if (rng.nextDouble() < 0.4) nodes.add(LaneScenes.street(LaneScenes.GORE + rng.nextDouble(60.0, 240.0), *Array(rng.nextInt(1, 3)) { s() }, planMps = v))
        nodes.add(LaneScenes.street(LaneScenes.GORE + 300.0, maneuver = LaneScenes.ARRIVE, planMps = v))
        return LaneScenes.route(*nodes.toTypedArray(), id = id)
    }

    /**
     * Route [k] of a seeded family as the sweeps drew them from [rng]: each route from [route], named [name] and its number,
     * then eight random bends of its line up to [until] of it, drawn with the same source; that route and its line.
     */
    fun nth(
        rng: Random,
        k: Int,
        name: String,
        route: (Random, String) -> LaneRoute,
        until: (LaneRoute) -> Double = { LaneScenes.GORE - 400.0 },
    ): Pair<LaneRoute, List<GeoPoint>> {
        var out: Pair<LaneRoute, List<GeoPoint>>? = null
        for (i in 0..k) {
            val r = route(rng, "$name-$i")
            val bends = (1..8).map { rng.nextDouble(200.0, until(r)) to rng.nextDouble(-4.0, 4.0) }
            if (i == k) out = r to SceneLines.line(r.lengthMeters, (bends + SceneLines.bendsOf(r)).sortedBy { it.first })
        }
        return requireNotNull(out)
    }
}
