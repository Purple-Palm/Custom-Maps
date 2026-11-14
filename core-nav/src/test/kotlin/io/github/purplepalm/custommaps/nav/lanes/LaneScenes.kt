package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.guidance.RunUpRule
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/**
 * Made-up routes for the own lanes' tests: invented approaches with round synthetic stations, the gore
 * of the exit at [GORE] metres from the route's start, no place. Lanes are written from the left; a
 * lane the route cannot be followed from is written with false, and the route prefers a lane where it
 * can be followed from it unless told otherwise. The route comes in from the south (bearing 180) and
 * leaves to the north (0) at every intersection; other roads are given by their bearing and whether a
 * car may take them.
 */
internal object LaneScenes {

    /** The gore of every approach's exit, in metres from the route's start. */
    const val GORE = 6_000.0

    private val straight = TurnModifier.STRAIGHT
    private val slightRight = TurnModifier.SLIGHT_RIGHT
    private val slightLeft = TurnModifier.SLIGHT_LEFT

    fun s(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(straight), valid = valid, active = active)
    fun sr(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(slightRight), valid = valid, active = active)
    fun sl(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(slightLeft), valid = valid, active = active)

    /** A lane with the straight arrow and the one to the right: the option lane of a right exit. */
    fun option(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(straight, slightRight), valid = valid, active = active)

    /** A lane with the left turn's arrow, as a left pocket at a junction lists it. */
    fun left(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(TurnModifier.LEFT), valid = valid, active = active)

    /** A lane kept for car pools. */
    fun hov(valid: Boolean = false, active: Boolean? = valid) =
        RouteLane(setOf(straight), valid = valid, active = active, kinds = setOf(DesignatedKind.HOV))

    /** A lane kept for buses, with [arrow]. */
    fun bus(arrow: TurnModifier = straight, valid: Boolean = false, active: Boolean? = valid) =
        RouteLane(setOf(arrow), valid = valid, active = active, kinds = setOf(DesignatedKind.BUS))

    /** The roads at an intersection besides the two the route takes, each as its bearing and whether a car may take it. */
    class Roads(vararg val others: Pair<Int, Boolean>)

    val THROUGH = Roads()
    val EXIT_RIGHT = Roads(30 to true)
    val EXIT_LEFT = Roads(330 to true)
    val JOIN_RIGHT = Roads(160 to false)
    val JOIN_LEFT = Roads(200 to false)

    val DEPART = NodeManeuver(ManeuverType.DEPART)
    val ARRIVE = NodeManeuver(ManeuverType.ARRIVE)
    val OFF_RIGHT = NodeManeuver(ManeuverType.OFF_RAMP, slightRight, viewStaysUp = true)
    val OFF_LEFT = NodeManeuver(ManeuverType.OFF_RAMP, slightLeft, viewStaysUp = true)
    val FORK_LEFT = NodeManeuver(ManeuverType.FORK, slightLeft, viewStaysUp = true)
    val FORK_RIGHT = NodeManeuver(ManeuverType.FORK, slightRight, viewStaysUp = true)
    val TURN_LEFT = NodeManeuver(ManeuverType.TURN, TurnModifier.LEFT)
    val TURN_RIGHT = NodeManeuver(ManeuverType.TURN, TurnModifier.RIGHT)
    val U_TURN = NodeManeuver(ManeuverType.TURN, TurnModifier.U_TURN)

    /** The posted speed of a road the own lanes plan at [planMps]. */
    fun posted(planMps: Double) = planMps - PlanningSpeed.MARGIN_MPH * PlanningSpeed.MPS_PER_MPH

    fun node(
        at: Double,
        vararg lanes: RouteLane,
        roads: Roads = THROUGH,
        road: RoadKind = RoadKind.FREEWAY,
        maneuver: NodeManeuver? = null,
        planMps: Double? = null,
    ) = RouteNode(
        station = at,
        lanes = lanes.toList(),
        bearings = listOf(180, 0) + roads.others.map { it.first },
        entry = listOf(false, true) + roads.others.map { it.second },
        inIndex = 0,
        outIndex = 1,
        freeway = road == RoadKind.FREEWAY,
        ramp = road == RoadKind.RAMP,
        maneuver = maneuver,
        postedMps = planMps?.let { posted(it) },
    )

    fun route(vararg nodes: RouteNode, id: String = "route-1", length: Double = nodes.last().station) =
        LaneRoute(id, steps = nodes.count { it.maneuver != null }, lengthMeters = length, nodes = nodes.toList())

    /**
     * The stations of the roads without lane data that leave on the side of their step's exit, as the
     * engine's lane choices read them for the card's words (`exitSideBranches`): an intersection after its
     * step's first, without lanes, reached on a freeway, where a road that may be entered leaves on the side
     * of the maneuver that ends the step.
     */
    fun sideBranches(route: LaneRoute): List<Double> {
        val nodes = route.nodes
        val out = ArrayList<Double>()
        var stepStart = 0
        for (m in nodes.indices) {
            val maneuver = nodes[m].maneuver ?: continue
            if (m > 0) {
                val edge = RunUpRule.exitEdge(maneuver.modifier, nodes[m].lanes)
                if (edge != null) {
                    for (k in stepStart + 1 until m) {
                        if (!nodes[k].isLanePoint && nodes[k - 1].freeway && LaneEvidence.branchesOff(nodes[k], edge)) out.add(nodes[k].station)
                    }
                }
            }
            stepStart = m
        }
        return out
    }

    /** A lane point of an approach, [before] metres before the gore. */
    class At(val before: Double, val lanes: List<RouteLane>, val roads: Roads = THROUGH)

    fun at(before: Double, vararg lanes: RouteLane, roads: Roads = THROUGH) = At(before, lanes.toList(), roads)

    /** A node of an approach without lane data, [before] metres before the gore. */
    fun bare(before: Double, roads: Roads) = At(before, emptyList(), roads)

    /**
     * A freeway approach to an exit at [GORE]: the departure at 0, [points] at metres before the gore (the
     * freeway planned at [planMps]), the exit with [exit] as its lanes and [maneuver], the ramp planned at
     * [rampMps] with [rampPoints] at metres after the gore, a turn at its end [rampMeters] after the gore
     * and the arrival 100 m later.
     */
    fun approach(
        points: List<At>,
        exit: List<RouteLane>,
        maneuver: NodeManeuver = OFF_RIGHT,
        planMps: Double = 27.0,
        rampMps: Double = 20.0,
        rampPoints: List<Pair<Double, NodeAndLanes>> = emptyList(),
        rampMeters: Double = 400.0,
        id: String = "route-1",
    ): LaneRoute {
        val nodes = ArrayList<RouteNode>()
        nodes.add(node(0.0, maneuver = DEPART, planMps = planMps))
        for (p in points.sortedByDescending { it.before }) {
            nodes.add(node(GORE - p.before, *p.lanes.toTypedArray(), roads = p.roads, planMps = planMps))
        }
        nodes.add(node(GORE, *exit.toTypedArray(), road = RoadKind.RAMP, maneuver = maneuver, planMps = rampMps))
        for ((after, n) in rampPoints.sortedBy { it.first }) {
            nodes.add(node(GORE + after, *n.lanes.toTypedArray(), roads = n.roads, road = RoadKind.RAMP, maneuver = n.maneuver, planMps = rampMps))
        }
        nodes.add(node(GORE + rampMeters, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0))
        nodes.add(node(GORE + rampMeters + 100.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0))
        return route(*nodes.toTypedArray(), id = id)
    }

    /** A node on a ramp: its lanes, roads and maneuver. */
    class NodeAndLanes(val lanes: List<RouteLane>, val roads: Roads = THROUGH, val maneuver: NodeManeuver? = null)

    // ------------------------------------------------------------------ the scenes

    /** The exit of IA: the two right lanes of six, the fifth an option lane, the sixth exit-only. */
    fun iaExit() = listOf(s(false), s(false), s(false), s(false), option(), sr())

    /**
     * IA: five general lanes and no car-pool lane at every point, the exit on the right. At IA0, 2,180 m
     * before the gore, the left lane leaves by a road on the left; at IA1, 1,330 m, the right lane leaves by
     * a road on the right; at IA2a and IA2b, 690 and 640 m (a pair 50 m apart with the same lists), the two
     * left lanes cannot be used: no road leaves there, the route only narrows toward its exit. The exit
     * takes the two right lanes of six. Planned at 27 m/s. [farSide]: the right lane leaves at IA0 and the
     * left one at IA1. [ia0At] moves IA0, for the seam's border.
     */
    fun ia(farSide: Boolean = false, ia0At: Double = 2_180.0): LaneRoute {
        val leftLeaves = listOf(s(false), s(), s(), s(), s())
        val rightLeaves = listOf(s(), s(), s(), s(), s(false))
        val ia0 = if (farSide) At(ia0At, rightLeaves, EXIT_RIGHT) else At(ia0At, leftLeaves, EXIT_LEFT)
        val ia1 = if (farSide) At(1_330.0, leftLeaves, EXIT_LEFT) else At(1_330.0, rightLeaves, EXIT_RIGHT)
        val narrowing = listOf(s(false), s(false), s(), s(), s())
        return approach(listOf(ia0, ia1, At(690.0, narrowing), At(640.0, narrowing)), iaExit(), id = if (farSide) "ia-far" else "ia")
    }

    /**
     * IW31: four general lanes g1 to g4 and no car-pool lane. P0 at 2,600 m before the gore, every lane goes
     * on; P1 at 1,850 m, the leftmost general lane cannot be used; an entrance joins on the right at
     * 1,550 m; P2 at 620 m is the first point to list the exit-only lane a5 beside the option lane g4. The
     * gore lists the same five lanes; the ramp splits 260 m after the gore and the route takes its left
     * branch, which only the option lane leads to. 65 mph before the gore, 45 mph on the ramp.
     */
    fun iw31(): LaneRoute {
        val mph65 = 65.0 * PlanningSpeed.MPS_PER_MPH
        val mph45 = 45.0 * PlanningSpeed.MPS_PER_MPH
        val p2 = listOf(s(false), s(false), s(false), option(true, true), sr(true, false))
        return approach(
            listOf(
                at(2_600.0, s(), s(), s(), s()),
                at(1_850.0, s(false), s(), s(), s()),
                bare(1_550.0, JOIN_RIGHT),
                At(620.0, p2),
            ),
            exit = p2,
            planMps = mph65,
            rampMps = mph45,
            rampPoints = listOf(260.0 to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = FORK_LEFT)),
            rampMeters = 700.0,
            id = "iw31",
        )
    }

    /** S5: a left exit; a car-pool lane on the left, the exit from the leftmost open lane; the two right lanes leave 1,100 m before. */
    fun s5() = approach(
        listOf(at(1_100.0, hov(), s(), s(), s(false), sr(false), roads = EXIT_RIGHT)),
        exit = listOf(hov(), sl(), s(false), s(false), s(false)),
        maneuver = OFF_LEFT,
        id = "s5",
    )

    /** S6: a two-lane exit with an option lane; a split 1,200 m before takes the two left lanes. */
    fun s6() = approach(
        listOf(at(1_200.0, sl(false), sl(false), s(), s(), s(), roads = EXIT_LEFT)),
        exit = listOf(s(false), s(false), s(false), option(), sr()),
        planMps = 30.0,
        id = "s6",
    )

    /** S7: a fork, the route on its left branch, three of five lanes. */
    fun s7() = approach(
        listOf(at(800.0, s(), s(), s(), s(), s())),
        exit = listOf(sl(), sl(), sl(), sr(false), sr(false)),
        maneuver = FORK_LEFT,
        planMps = 30.0,
        id = "s7",
    )

    /** S8: a lane drop: the right lane ends 500 m before a right exit, with no road there. */
    fun s8() = approach(
        listOf(at(500.0, s(), s(), s(), s(false))),
        exit = listOf(s(false), s(false), s(false), sr()),
        planMps = 30.0,
        id = "s8",
    )

    /** S9: exits close together: exit A's gore 300 m before the route's exit B takes the right lane. */
    fun s9() = approach(
        listOf(at(300.0, s(), s(), s(), s(), sr(false), roads = EXIT_RIGHT)),
        exit = listOf(s(false), s(false), s(false), s(false), sr()),
        id = "s9",
    )

    /** S13: a split 3,000 m before the exit takes the two right lanes; nothing between; the exit is IA's. */
    fun s13() = approach(
        listOf(at(3_000.0, s(), s(), s(), sr(false), sr(false), roads = EXIT_RIGHT)),
        exit = iaExit(),
        id = "s13",
    )

    /** S15: two points where lanes leave on the right: one at 1,400 m, two at 800 m; the exit takes the right lane of three. */
    fun s15() = approach(
        listOf(
            at(1_400.0, s(), s(), s(), s(), sr(false), roads = EXIT_RIGHT),
            at(800.0, s(), s(), sr(false), sr(false), roads = EXIT_RIGHT),
        ),
        exit = listOf(s(false), s(false), sr()),
        id = "s15",
    )

    /** S16: a left exit; the leftmost lane leaves at 1,200 m, then the new leftmost lane is kept for car pools. */
    fun s16() = approach(
        listOf(at(1_200.0, sl(false), s(), s(), s(), s(), roads = EXIT_LEFT)),
        exit = listOf(hov(), sl(), s(false), s(false)),
        maneuver = OFF_LEFT,
        id = "s16",
    )

    /** S17: S9 where exit A's intersection has no lanes: a road without lane data leaves on the right 300 m before the exit. */
    fun s17() = unknownExit(300.0, 27.0, id = "s17")

    /**
     * Five lanes and no lane point before a right exit that only the right lane leads to; a road without lane
     * data leaves on the right [before] metres before the gore. Planned at [planMps].
     */
    fun unknownExit(before: Double, planMps: Double, id: String = "unknown-exit") = approach(
        listOf(bare(before, EXIT_RIGHT)),
        exit = listOf(s(false), s(false), s(false), s(false), sr()),
        planMps = planMps,
        id = id,
    )

    /** S20: the right lane leaves at 1,600 m and at 700 m by roads on the right, four lanes at each; a lane added between. */
    fun s20() = approach(
        listOf(at(1_600.0, s(), s(), s(), sr(false), roads = EXIT_RIGHT), at(700.0, s(), s(), s(), sr(false), roads = EXIT_RIGHT)),
        exit = listOf(s(false), s(false), s(false), sr()),
        id = "s20",
    )

    /**
     * GEOM's S2: a two-lane exit, an option lane the route prefers and a lane dropped to the ramp, whose
     * ramp splits [splitAfter] m after the gore; the route takes the left branch. 65 mph, 45 on the ramp
     * unless [rampMps] says otherwise.
     */
    fun geomS2(splitAfter: Double, rampMps: Double = 45.0 * PlanningSpeed.MPS_PER_MPH) = approach(
        emptyList(),
        exit = listOf(s(false), s(false), option(true, true), sr(true, false)),
        planMps = 65.0 * PlanningSpeed.MPS_PER_MPH,
        rampMps = rampMps,
        rampPoints = listOf(splitAfter to NodeAndLanes(listOf(sl(true, true), sr(false)), maneuver = FORK_LEFT)),
        rampMeters = splitAfter + 300.0,
        id = "geom-s2",
    )

    /** GEOM's S5: only the car-pool lane can be used at the exit. */
    fun geomS5() = approach(emptyList(), exit = listOf(hov(true), s(false), s(false)), maneuver = OFF_LEFT, planMps = 65.0 * PlanningSpeed.MPS_PER_MPH, id = "geom-s5")

    /** GEOM's S6: a lane that ends with no exit: three lanes at 1,500 m before, two at 500 m, no road between. */
    fun geomS6() = approach(
        listOf(at(1_500.0, s(), s(), s()), at(500.0, s(), s())),
        exit = listOf(s(false), sr()),
        planMps = 65.0 * PlanningSpeed.MPS_PER_MPH,
        id = "geom-s6",
    )

    // ------------------------------------------------------------------ P6's order and where the named lane moves

    /**
     * A lane point 1,200 m before a right exit lists the exit's five lanes and prefers the exit-only lane; the
     * exit itself prefers the option lane beside it, as IW31's does.
     */
    fun prefersOther() = approach(
        listOf(at(1_200.0, s(false), s(false), s(false), option(true, false), sr(true, true))),
        exit = listOf(s(false), s(false), s(false), option(true, true), sr(true, false)),
        id = "prefers-other",
    )

    /** A lane point 700 m before a right exit prefers the two middle lanes of four; only the right lane leads to the exit. */
    fun prefersMiddle() = approach(
        listOf(at(700.0, s(true, false), s(true, true), s(true, true), s(true, false))),
        exit = listOf(s(false), s(false), s(false), sr(true, true)),
        id = "prefers-middle",
    )

    /** [prefersMiddle] where the point prefers the two left lanes and the exit takes the two right ones. */
    fun prefersLeft() = approach(
        listOf(at(700.0, s(true, true), s(true, true), s(true, false), s(true, false))),
        exit = listOf(s(false), s(false), option(), sr()),
        id = "prefers-left",
    )

    /**
     * An exit whose ramp splits 150 m after the gore, the route on the right branch: the exit prefers its option
     * lane, but only its other lane leads on to the right branch without a change.
     */
    fun splitRight() = approach(
        emptyList(),
        exit = listOf(s(false), s(false), option(true, true), sr(true, false)),
        rampPoints = listOf(150.0 to NodeAndLanes(listOf(sl(false), sr(true, true)), maneuver = FORK_RIGHT)),
        rampMeters = 450.0,
        id = "split-right",
    )

    /**
     * A split at [GORE] where the route keeps left onto the branch the two left lanes of four lead to, given as
     * [type] (a continue, as the route gives a keep left on its road, or a fork); the other branch leaves on the
     * right. A lane point 1,200 m before, where the left lane leaves by a road on the left; the arrival, on the
     * right, 3,000 m after the split. Planned at 27 m/s.
     */
    fun keepLeft(type: ManeuverType = ManeuverType.CONTINUE) = route(
        node(0.0, maneuver = DEPART, planMps = 27.0),
        node(GORE - 1_200.0, s(false), s(), s(), s(), s(), roads = EXIT_LEFT, planMps = 27.0),
        node(GORE, sl(), sl(), s(false), s(false), roads = EXIT_RIGHT, maneuver = NodeManeuver(type, slightLeft, viewStaysUp = true), planMps = 27.0),
        node(GORE + 3_000.0, maneuver = NodeManeuver(ManeuverType.ARRIVE, TurnModifier.RIGHT), planMps = 27.0),
        id = "keep-left",
    )

    /**
     * The right lane leaves at an exit with lane data 1,650 m before a right exit, and a road without lane data
     * leaves on the right 950 m before: the lane that is the right lane between the two may be that road's own.
     */
    fun edgeBeforeRoad() = approach(
        listOf(at(1_650.0, s(), s(), s(), s(), s(false), roads = EXIT_RIGHT), bare(950.0, EXIT_RIGHT)),
        exit = listOf(s(false), s(false), s(false), sr()),
        id = "edge-before-road",
    )

    /**
     * Only the right lane of three can be used at a point 1,700 m before a right exit, a lane is added on the far
     * side at a point 1,200 m before, and a road without lane data leaves on the right 800 m before.
     */
    fun droppedHoldStays() = approach(
        listOf(at(1_700.0, s(false), s(false), s()), at(1_200.0, s(), s(), s(), s()), bare(800.0, EXIT_RIGHT)),
        exit = listOf(s(false), s(false), s(false), sr()),
        id = "dropped-hold-stays",
    )

    /**
     * A lane gone before an edge point with nothing that says where: five lanes 2,000 m before a right exit, four at 1,000 m,
     * where the right one leaves by a road on the right; the exit takes the right lane of the three that go on. The careful
     * reading ends the right lane of the five early, 30 m past the first point: until then the lane lit at the car has one
     * lane more on its right than at the edge point. Planned at 27 m/s. For the card's tests, not among [all].
     */
    fun laneEndsBeforeEdge() = approach(
        listOf(at(2_000.0, s(), s(), s(), s(), s()), at(1_000.0, s(), s(), s(), s(false), roads = EXIT_RIGHT)),
        exit = listOf(s(false), s(false), sr()),
        id = "lane-ends-before-edge",
    )

    /**
     * A lane that comes and goes before an edge point: four lanes 2,200 m before a right exit, a road joining on the right
     * 1,950 m before, five lanes at 1,800 m and four again at 1,400 m with nothing that says where the fifth went; at
     * 1,000 m the right lane leaves by a road on the right, and the exit takes the right lane of the three that go on. The
     * lane added on the exit's side may be there from the point before it, and the careful reading ends it 30 m past the
     * point that lists it. Planned at 27 m/s. For the card's tests, not among [all].
     */
    fun shortLaneBeforeEdge() = approach(
        listOf(
            at(2_200.0, s(), s(), s(), s()),
            bare(1_950.0, JOIN_RIGHT),
            at(1_800.0, s(), s(), s(), s(), s()),
            at(1_400.0, s(), s(), s(), s()),
            at(1_000.0, s(), s(), s(), s(false), roads = EXIT_RIGHT),
        ),
        exit = listOf(s(false), s(false), sr()),
        id = "short-lane-before-edge",
    )

    /**
     * A lane that comes and goes right of the lane lit at the car, before an exit whose own lane is added at its gore: the
     * right lane of five leaves by a road on the right 1,200 m before a right exit; four lanes at 1,000 m, a road joining on
     * the right 900 m before, five lanes at 800 m and four again at 500 m with nothing that says where the fifth went; the
     * exit adds its lane on the right, entered from the right lane along its taper. The lane that comes and goes lies right
     * of the lane lit at the car and left of the exit's lane, which the topology adds after it. Planned at 27 m/s. For the
     * card's tests, not among [all].
     */
    fun shortLaneBeforeExitLane() = approach(
        listOf(
            at(1_200.0, s(), s(), s(), s(), sr(false), roads = EXIT_RIGHT),
            at(1_000.0, s(), s(), s(), s()),
            bare(900.0, JOIN_RIGHT),
            at(800.0, s(), s(), s(), s(), s()),
            at(500.0, s(), s(), s(), s()),
        ),
        exit = listOf(s(false), s(false), s(false), s(false), sr()),
        id = "short-lane-before-exit-lane",
    )

    /**
     * An earlier exit on the exit's side: four lanes, the right one leaving by a road on the right 1,500 m before a right
     * exit, which takes the right one of the three that go on. Before the earlier exit the lane at the exit's edge is not
     * the exit's lane: it leaves first. Planned at 27 m/s. For the card's tests, not among [all].
     */
    fun earlierExitOnSide() = approach(
        listOf(at(1_500.0, s(), s(), s(), sr(false), roads = EXIT_RIGHT)),
        exit = listOf(s(false), s(false), sr()),
        id = "earlier-exit-on-side",
    )

    /** Two lanes added on the right at a point 900 m before a right exit, which takes the inner one only. */
    fun twoAdded() = approach(
        listOf(at(2_000.0, s(), s(), s(), s()), at(900.0, s(), s(), s(), s(), sr(), sr())),
        exit = listOf(s(false), s(false), s(false), s(false), sr(), sr(false)),
        id = "two-added",
    )

    // ------------------------------------------------------------------ where no lane can be named (C1)

    /**
     * A left exit beyond the car-pool lane: a car-pool lane and three open lanes 1,500 m before; the exit adds a
     * lane on the left of the car-pool lane, the only one it can be taken from. Planned at 27 m/s.
     */
    fun carpoolBeyond() = approach(
        listOf(at(1_500.0, hov(), s(), s(), s())),
        exit = listOf(sl(), hov(), s(false), s(false), s(false)),
        maneuver = OFF_LEFT,
        id = "carpool-beyond",
    )

    /** [carpoolBeyond] where the car-pool lane is open to every car from a lane point 800 m before the gore. */
    fun carpoolEnds() = approach(
        listOf(at(1_500.0, hov(), s(), s(), s()), at(800.0, s(), s(), s(), s())),
        exit = listOf(sl(), s(false), s(false), s(false), s(false)),
        maneuver = OFF_LEFT,
        id = "carpool-ends",
    )

    /**
     * Two lanes before a left exit, the right one kept for buses from the lane point 1,200 m before the gore and open
     * to every car again at the exit: a lane whose kinds change twice. For the geometry's tests, not among [all].
     */
    fun rightLaneForBuses() = approach(
        listOf(at(2_700.0, s(), s()), at(1_200.0, s(), bus()), at(450.0, sl(), bus())),
        exit = listOf(sl(), s(false)),
        maneuver = OFF_LEFT,
        id = "right-lane-for-buses",
    )

    /**
     * Four lanes before a right exit, the third kept for car pools from the lane point 1,400 m before the gore on: a
     * lane between others whose kinds change. For the geometry's tests, not among [all].
     */
    fun thirdLaneForCarPools() = approach(
        listOf(at(2_600.0, s(), s(), s(), s()), at(1_400.0, s(), s(), hov(), s()), at(700.0, s(false), s(false), hov(), option())),
        exit = listOf(s(false), s(false), hov(), option(), sr()),
        id = "third-lane-for-car-pools",
    )

    /**
     * A right exit where a gain nothing explains has to lie on the exit's side: four lanes 2,350 m before the gore, the left
     * one leaving by a road on the left 1,750 m before, and five lanes 1,000 m before, the right one kept for car pools.
     * The careful reading would add the lanes on the far side, which would put the car-pool lane on a lane open to every
     * car, so both lie on the right. The exit takes the right lane open to every car. For the geometry's tests, not among
     * [all].
     */
    fun exitSideGain() = approach(
        listOf(at(2_350.0, s(), s(), s(), s()), at(1_750.0, s(false), s(), s(), s(), roads = EXIT_LEFT), at(1_000.0, s(), s(), s(), s(), hov())),
        exit = listOf(s(false), s(false), s(false), sr(), hov()),
        id = "exit-side-gain",
    )

    /**
     * A right exit where a lane leaves by a road at the edge with possible lanes beyond it: three lanes 2,600 m before the
     * gore; at a point 1,200 m before, the right one leaves by a road on the right. The exit adds two exit-only lanes on the
     * right, which may be there from that point on; the plan names neither, so both are drawn as possible from there.
     * Planned at 26.5 m/s. For the geometry's tests, not among [all].
     */
    fun leavesBesidePossible() = approach(
        listOf(at(2_600.0, s(), s(), s()), at(1_200.0, s(), s(), s(false), roads = EXIT_RIGHT)),
        exit = listOf(s(false), sr(), sr(false), sr(false)),
        planMps = 26.5,
        id = "leaves-beside-possible",
    )

    /**
     * A left exit where the left lane leaves twice by a road at the left edge with no taper: two lanes; 2,100 m before the
     * gore the left one leaves and a lane opens on the right before a point 1,100 m before, where the left one leaves again.
     * The plan names the new right lane while the left one is still there, so the path moves out of a lane that leaves at
     * the edge it moves from. The exit takes the left two of the three lanes at its gore. For the geometry's tests, not
     * among [all].
     */
    fun edgeLeavesMidMove() = approach(
        listOf(at(2_100.0, s(false), s(), roads = EXIT_LEFT), at(1_100.0, s(false), s(), roads = EXIT_LEFT)),
        exit = listOf(sl(), sl(), s(false)),
        maneuver = OFF_LEFT,
        id = "edge-leaves-mid-move",
    )

    /**
     * A left fork whose run-up only the latest lane changes can meet, for the exit beyond it: six lanes; the fork at
     * 2,600 m takes the left three, of which the left one ends just past it; two lanes go on, a third joins on the right,
     * the two old ones end where a car-pool lane takes the left one's place, and a left exit at 4,900 m leaves from the
     * lane that joined. Planned at 24 m/s. No lane meets every point with comfortable changes, so the plan names by the
     * latest ones. For the planner's tests, not among [all].
     */
    fun tightForkBeforeExit() = route(
        node(0.0, maneuver = DEPART, planMps = 24.0),
        node(700.0, s(), s(), s(), s(), s(), s(), planMps = 24.0),
        node(1_000.0, s(), s(), s(), s(), s(), s(), planMps = 24.0),
        node(2_600.0, sl(), sl(), sl(), sr(false), sr(false), sr(false), roads = EXIT_RIGHT, maneuver = FORK_LEFT, planMps = 24.0),
        node(3_500.0, s(), s(), roads = JOIN_RIGHT, planMps = 24.0),
        node(4_150.0, s(), s(), s(), roads = JOIN_RIGHT, planMps = 24.0),
        node(4_550.0, hov(), s(), roads = EXIT_LEFT, planMps = 24.0),
        node(4_900.0, sl(), option(), road = RoadKind.RAMP, maneuver = OFF_LEFT, planMps = 20.0),
        node(5_400.0, road = RoadKind.STREET, maneuver = TURN_LEFT, planMps = 15.0),
        node(5_500.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0),
        id = "tight-fork-before-exit",
    )

    /**
     * A street into a roundabout: two lanes 150 m before it, both of which the route can be followed from; the
     * route leaves the roundabout 60 m on. 35 mph planned. With [turnInstead] a plain right turn takes its place.
     */
    fun roundabout(turnInstead: Boolean = false): LaneRoute {
        val street = RoadKind.STREET
        val mps = 35.0 * PlanningSpeed.MPS_PER_MPH
        val entry = if (turnInstead) TURN_RIGHT else NodeManeuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT)
        val out = if (turnInstead) NodeManeuver(ManeuverType.TURN, TurnModifier.LEFT) else NodeManeuver(ManeuverType.EXIT_ROUNDABOUT, TurnModifier.RIGHT)
        return route(
            node(0.0, road = street, maneuver = DEPART, planMps = mps),
            node(GORE - 150.0, s(), s(), road = street, planMps = mps),
            node(GORE, s(), s(), road = street, maneuver = entry, planMps = mps),
            node(GORE + 60.0, road = street, maneuver = out, planMps = mps),
            node(GORE + 300.0, road = street, maneuver = ARRIVE, planMps = mps),
            id = if (turnInstead) "turn" else "roundabout",
        )
    }

    /**
     * [roundabout] with lanes listed on its way round: two lanes at the way out 80 m past the entry where [atExit], and at an
     * intersection on the way round 40 m past it, where a road crosses, where [onWayRound]; the road out with two lanes to a
     * lane point 200 m past the entry, the arrival 300 m past it. [onRamp]: the way in a ramp. 35 mph planned.
     */
    fun roundaboutLanesRound(atExit: Boolean = true, onWayRound: Boolean = false, onRamp: Boolean = false): LaneRoute {
        val mps = 35.0 * PlanningSpeed.MPS_PER_MPH
        val approach = if (onRamp) RoadKind.RAMP else RoadKind.STREET
        val nodes = arrayListOf(
            node(0.0, road = approach, maneuver = DEPART, planMps = mps),
            node(GORE - 150.0, s(), s(), road = approach, planMps = mps),
            street(GORE, s(), s(), maneuver = NodeManeuver(ManeuverType.ROUNDABOUT, TurnModifier.RIGHT), planMps = mps),
        )
        if (onWayRound) nodes.add(street(GORE + 40.0, s(), s(), roads = CROSSING, planMps = mps))
        val exitLanes = if (atExit) arrayOf(s(), s()) else emptyArray()
        nodes.add(street(GORE + 80.0, *exitLanes, maneuver = NodeManeuver(ManeuverType.EXIT_ROUNDABOUT, TurnModifier.RIGHT), planMps = mps))
        nodes.add(street(GORE + 200.0, s(), s(), planMps = mps))
        nodes.add(street(GORE + 300.0, maneuver = ARRIVE, planMps = mps))
        return route(*nodes.toTypedArray(), id = "roundabout-lanes-${if (atExit) "exit" else "none"}-${if (onWayRound) "round" else "none"}${if (onRamp) "-ramp" else ""}")
    }

    /** A street turn right that only the bus lane on the right is valid for; two open lanes beside it. 35 mph planned. */
    fun busOnlyTurn(): LaneRoute {
        val street = RoadKind.STREET
        val mps = 35.0 * PlanningSpeed.MPS_PER_MPH
        return route(
            node(0.0, road = street, maneuver = DEPART, planMps = mps),
            node(GORE - 150.0, s(), s(), bus(), road = street, planMps = mps),
            node(GORE, s(false), s(false), bus(TurnModifier.RIGHT, valid = true), road = street, maneuver = TURN_RIGHT, planMps = mps),
            node(GORE + 300.0, road = street, maneuver = ARRIVE, planMps = mps),
            id = "bus-only-turn",
        )
    }

    // ------------------------------------------------------------------ a ramp beside a freeway (D8)

    /**
     * A route that begins on a one-lane ramp running beside a freeway, as one does after a moment matched onto
     * it: a lane point 100 m on, a right turn with its lane 300 m on, the arrival 100 m after it. 20 m/s planned.
     */
    fun besideRamp() = route(
        node(0.0, road = RoadKind.RAMP, maneuver = DEPART, planMps = 20.0),
        node(100.0, s(), road = RoadKind.RAMP, planMps = 20.0),
        node(300.0, sr(), road = RoadKind.RAMP, maneuver = TURN_RIGHT, planMps = 20.0),
        node(400.0, road = RoadKind.STREET, maneuver = ARRIVE, planMps = 15.0),
        id = "beside-ramp",
    )

    /** A street turn: two lanes 200 m before a left turn whose list adds a left pocket. 35 mph planned. */
    fun streetPocket(): LaneRoute {
        val street = RoadKind.STREET
        val mps = 35.0 * PlanningSpeed.MPS_PER_MPH
        return route(
            node(0.0, road = street, maneuver = DEPART, planMps = mps),
            node(GORE - 200.0, s(), s(), road = street, planMps = mps),
            node(GORE, left(true, true), s(false), s(false), road = street, maneuver = TURN_LEFT, planMps = 25.0 * PlanningSpeed.MPS_PER_MPH),
            node(GORE + 300.0, road = street, maneuver = ARRIVE, planMps = 25.0 * PlanningSpeed.MPS_PER_MPH),
            id = "street-pocket",
        )
    }

    // ------------------------------------------------------------------ streets (OL6)

    /** Every street of [route] one-way, or two-way, as a source would tell ([StreetWays]). */
    fun ways(route: LaneRoute, oneWay: Boolean): StreetWays = StreetWays.of(route) { oneWay }

    /** The planning speed of the streets below: 35 mph. */
    val STREET_MPS = 35.0 * PlanningSpeed.MPS_PER_MPH

    /** A lane with the right turn's arrow, as a right turn's lane at a junction lists it. */
    fun right(valid: Boolean = true, active: Boolean? = valid) = RouteLane(setOf(TurnModifier.RIGHT), valid = valid, active = active)

    /** A lane only for bicycles. */
    fun bike() = RouteLane(setOf(straight), valid = false, active = false, kinds = setOf(DesignatedKind.BICYCLE))

    /** A lane with a U-turn's arrow. */
    fun uTurn() = RouteLane(setOf(TurnModifier.U_TURN), valid = true, active = true)

    /** A road that crosses the route's street at a junction, both of its arms open to cars. */
    val CROSSING = Roads(90 to true, 270 to true)

    /** A node on a street planned at [planMps], with a traffic [signal] or a [stop] sign where given. */
    fun street(
        at: Double,
        vararg lanes: RouteLane,
        roads: Roads = THROUGH,
        maneuver: NodeManeuver? = null,
        signal: Boolean = false,
        stop: Boolean = false,
        planMps: Double = STREET_MPS,
    ) = node(at, *lanes, roads = roads, road = RoadKind.STREET, maneuver = maneuver, planMps = planMps).copy(trafficSignal = signal, stopSign = stop)

    /**
     * A street with two lanes to a right turn at [GORE]: a junction it goes straight through 400 m before the turn, where a
     * road crosses it and a traffic signal stands; a lane point 200 m before the turn; and the turn, at a stop sign, whose
     * right lane turns. The arrival lies 300 m past the turn. [bicycle] adds a lane only for bicycles at the right edge all
     * along, [bus] one kept for buses at the left edge; [receiving] gives the road after the turn a lane point 150 m on, with
     * two lanes.
     */
    fun streetTurn(bicycle: Boolean = false, bus: Boolean = false, receiving: Boolean = false, id: String = "street-turn"): LaneRoute {
        fun lanes(vararg general: RouteLane): Array<RouteLane> =
            ((if (bus) listOf(bus()) else emptyList()) + general.toList() + (if (bicycle) listOf(bike()) else emptyList())).toTypedArray()
        val nodes = ArrayList<RouteNode>()
        nodes.add(street(0.0, maneuver = DEPART))
        nodes.add(street(GORE - 400.0, *lanes(s(), s()), roads = CROSSING, signal = true))
        nodes.add(street(GORE - 200.0, *lanes(s(), s())))
        nodes.add(street(GORE, *lanes(s(false), right()), roads = CROSSING, maneuver = TURN_RIGHT, stop = true))
        if (receiving) nodes.add(street(GORE + 150.0, s(), s()))
        nodes.add(street(GORE + 300.0, maneuver = ARRIVE))
        return route(*nodes.toTypedArray(), id = id)
    }

    /**
     * The design's street junction (its street scene): two lanes each way at 35 mph to a left turn at [GORE] where a traffic
     * signal stands and a road crosses, whose list adds a left pocket; a lane point 300 m before it; the road after it with two
     * lanes to a lane point 150 m on; the arrival 300 m past the turn.
     */
    fun junctionPocket(): LaneRoute = route(
        street(0.0, maneuver = DEPART),
        street(GORE - 300.0, s(), s()),
        street(GORE, left(), s(false), s(false), roads = CROSSING, maneuver = TURN_LEFT, signal = true),
        street(GORE + 150.0, s(), s()),
        street(GORE + 300.0, maneuver = ARRIVE),
        id = "junction-pocket",
    )

    /**
     * A left turn at [GORE] whose list adds a left pocket, planned at [mph]: a lane point 300 m before it, and where
     * [junctionBack] is given a junction without lane data that far before the turn, a road crossing it.
     */
    fun pocketAt(mph: Double, junctionBack: Double? = null): LaneRoute {
        val v = mph * PlanningSpeed.MPS_PER_MPH
        val nodes = arrayListOf(street(0.0, maneuver = DEPART, planMps = v), street(GORE - 300.0, s(), s(), planMps = v))
        if (junctionBack != null) nodes.add(street(GORE - junctionBack, roads = CROSSING, planMps = v))
        nodes.add(street(GORE, left(), s(false), s(false), roads = CROSSING, maneuver = TURN_LEFT, planMps = v))
        nodes.add(street(GORE + 300.0, maneuver = ARRIVE, planMps = v))
        return route(*nodes.toTypedArray(), id = "pocket-$mph-${junctionBack ?: 0}")
    }

    /**
     * A left turn at [GORE] from the left of two lanes, beside a lane only for bicycles at the right edge, at a traffic signal
     * where a road crosses: a lane point 275 m before it, and the road after it with two lanes to a lane point 135 m on; the
     * arrival 300 m past the turn. One-way, the lit turn's curve has one of its points a few centimetres past the end of a
     * piece of the drawing.
     */
    fun leftBesideBicycles(): LaneRoute = route(
        street(0.0, maneuver = DEPART),
        street(GORE - 275.0, s(), bike(), roads = CROSSING),
        street(GORE, left(), bike(), roads = CROSSING, maneuver = TURN_LEFT, signal = true),
        street(GORE + 135.0, s(), s()),
        street(GORE + 300.0, maneuver = ARRIVE),
        id = "left-beside-bicycles",
    )

    /**
     * A U-turn at [GORE] from the left of two lanes, at a junction where a road crosses, and the road back with two lanes to a
     * lane point 150 m on: the route's line turns back there.
     */
    fun streetUTurn(): LaneRoute = route(
        street(0.0, maneuver = DEPART),
        street(GORE - 300.0, s(), s()),
        street(GORE, uTurn(), s(false), roads = CROSSING, maneuver = U_TURN, signal = true),
        street(GORE + 150.0, s(), s()),
        street(GORE + 300.0, maneuver = ARRIVE),
        id = "street-u-turn",
    )

    /** The planning speed of the freeway the streets below go on onto, or come from, without a turn. */
    const val ONTO_FREEWAY_MPS = 27.0

    /**
     * A street that goes on without a turn onto a freeway: two lanes at a lane point 1,000 m before [GORE], where the street
     * leaves it; the freeway from 800 m before [GORE], two lanes at its lane points there and 300 m before [GORE]; the arrival
     * 500 m past [GORE].
     */
    fun streetOntoFreeway(): LaneRoute = route(
        street(0.0, maneuver = DEPART),
        street(GORE - 1_000.0, s(), s()),
        node(GORE - 800.0, s(), s(), planMps = ONTO_FREEWAY_MPS),
        node(GORE - 300.0, s(), s(), planMps = ONTO_FREEWAY_MPS),
        node(GORE + 500.0, maneuver = ARRIVE, planMps = ONTO_FREEWAY_MPS),
        id = "street-onto-freeway",
    )

    /**
     * A freeway that goes on without a turn onto a street: two lanes at a lane point 1,000 m before [GORE]; the street from
     * 500 m before [GORE], two lanes at its lane points there and 300 m before [GORE]; the arrival 500 m past [GORE].
     */
    fun freewayOntoStreet(): LaneRoute = route(
        node(0.0, maneuver = DEPART, planMps = ONTO_FREEWAY_MPS),
        node(GORE - 1_000.0, s(), s(), planMps = ONTO_FREEWAY_MPS),
        street(GORE - 500.0, s(), s()),
        street(GORE - 300.0, s(), s()),
        street(GORE + 500.0, maneuver = ARRIVE),
        id = "freeway-onto-street",
    )

    /** Every scene of the planner's tests, by name. */
    fun all(): Map<String, LaneRoute> = linkedMapOf(
        "ia" to ia(),
        "ia-far" to ia(farSide = true),
        "iw31" to iw31(),
        "s5" to s5(),
        "s6" to s6(),
        "s7" to s7(),
        "s8" to s8(),
        "s9" to s9(),
        "s13" to s13(),
        "s15" to s15(),
        "s16" to s16(),
        "s17" to s17(),
        "s20" to s20(),
        "geom-s2-400" to geomS2(400.0),
        "geom-s2-150" to geomS2(150.0),
        "geom-s5" to geomS5(),
        "geom-s6" to geomS6(),
        "street-pocket" to streetPocket(),
        "carpool-beyond" to carpoolBeyond(),
        "carpool-ends" to carpoolEnds(),
        "roundabout" to roundabout(),
        "bus-only-turn" to busOnlyTurn(),
        "prefers-other" to prefersOther(),
        "prefers-middle" to prefersMiddle(),
        "prefers-left" to prefersLeft(),
        "split-right" to splitRight(),
        "two-added" to twoAdded(),
        "edge-before-road" to edgeBeforeRoad(),
        "dropped-hold-stays" to droppedHoldStays(),
        "keep-left" to keepLeft(),
    )
}
