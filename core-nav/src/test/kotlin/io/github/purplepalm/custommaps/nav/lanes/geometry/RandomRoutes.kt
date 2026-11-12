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
import io.github.purplepalm.custommaps.nav.model.RouteLane
import kotlin.random.Random

/** Made-up approaches to an exit drawn from a seeded random source, for the path's and the drawing's property tests. No place. */
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
}
