package io.github.purplepalm.custommaps.nav.lanes

/**
 * The "lane_evidence" line of the debug log: one for each primary route a drive uses ([Routes]), in
 * numbers only, from which a drive's log tells how much of the route each stage of the app's own
 * lanes would draw. Counts, metres along the route, the route's id and kinds of road and lane:
 * never a coordinate, a shape, a road's name or an instruction.
 */
object LaneEvidenceLine {

    const val TYPE = "lane_evidence"

    /**
     * The line's fields for [evidence]:
     * - "routeId", "lengthM" along the steps' shapes, "steps";
     * - "lanePoints": the intersections with lanes, by the kind of road that leads to them;
     * - "freewayM", "rampM", "streetM": the metres of each kind of road whose lanes are "known" (a
     *   lane point's own reach), "bridged" (between two points that agree) and "none";
     * - "gaps": stretches without lane data between two points on one carriageway that are not
     *   bridged, their "count" and metres ("m"), how many reach into a card window ("inWindow") and
     *   their metres inside windows ("inWindowM"), and how many are short outside every window
     *   ("shortOutside");
     * - "unexplained": changes of the lane count between points on one carriageway that nothing explains;
     * - "exitSideAdded": lanes a point lists and the point before it does not ("count"), and of those
     *   the ones whose side the arrows do not tell ("sideUnknown");
     * - "unknownExits": roads without lane data that leave on the exit's side inside bridges;
     * - "windows": the card windows, freeway run-ups ("runUps") and street maneuvers ("street");
     * - "streetTurns": the turns at junctions off freeways, with lanes at the junction and without;
     * - "designated": the lanes the lane points list for each kind of designated lane;
     * - "noLanes": true when the route has no lane point at all.
     */
    fun fields(evidence: LaneEvidence): Map<String, Any?> {
        val route = evidence.route
        val lanePoints = RoadKind.entries.associateWithTo(LinkedHashMap()) { 0 }
        for (p in evidence.lanePoints) {
            val road = route.approachRoad(p)
            lanePoints[road] = lanePoints.getValue(road) + 1
        }
        val streetTurnsWithLanes = evidence.streetTurns.count { route.nodes[it].isLanePoint }
        return linkedMapOf(
            "routeId" to route.routeId,
            "lengthM" to Math.round(route.lengthMeters),
            "steps" to route.steps,
            "lanePoints" to lanePoints.mapKeys { it.key.logName },
            "freewayM" to metres(evidence, RoadKind.FREEWAY),
            "rampM" to metres(evidence, RoadKind.RAMP),
            "streetM" to metres(evidence, RoadKind.STREET),
            "gaps" to linkedMapOf(
                "count" to evidence.gaps.size,
                "m" to Math.round(evidence.gaps.sumOf { it.meters }),
                "inWindow" to evidence.gaps.count { it.inWindow },
                "inWindowM" to Math.round(evidence.gaps.sumOf { it.inWindowMeters }),
                "shortOutside" to evidence.gaps.count { it.shortOutside },
            ),
            "unexplained" to evidence.changes.count { !it.explained },
            "exitSideAdded" to linkedMapOf(
                "count" to evidence.added.sumOf { it.lanes },
                "sideUnknown" to evidence.added.filter { it.side == null }.sumOf { it.lanes },
            ),
            "unknownExits" to evidence.unknownExits.size,
            "windows" to linkedMapOf(
                "runUps" to evidence.windows.count { it.kind == CardWindow.Kind.RUN_UP },
                "street" to evidence.windows.count { it.kind == CardWindow.Kind.STREET },
            ),
            "streetTurns" to linkedMapOf(
                "withLanes" to streetTurnsWithLanes,
                "withoutLanes" to evidence.streetTurns.size - streetTurnsWithLanes,
            ),
            "designated" to evidence.designated.mapKeys { it.key.logName },
            "noLanes" to evidence.lanePoints.isEmpty(),
        )
    }

    /** The whole metres of [road] by what is known of their lanes. */
    private fun metres(evidence: LaneEvidence, road: RoadKind): Map<String, Long> {
        val onRoad = evidence.stretches.filter { it.road == road }
        return linkedMapOf(
            "known" to Math.round(onRoad.filter { it.kind == LaneEvidence.Kind.KNOWN }.sumOf { it.meters }),
            "bridged" to Math.round(onRoad.filter { it.kind == LaneEvidence.Kind.BRIDGED }.sumOf { it.meters }),
            "none" to Math.round(onRoad.filter { it.kind == LaneEvidence.Kind.NONE }.sumOf { it.meters }),
        )
    }

    /**
     * Which of a drive's routes get a line: every primary route the drive uses, once. The one it
     * starts on once the navigator has it ([onCommitted]), and every new primary route after it
     * ([onRoutesChanged]): a reroute, a route the driver switched to, a route the start's plan put in
     * place; never a refresh, which changes only the traffic and the times of the same route. Called
     * on the main thread.
     */
    class Routes {
        private var open = false
        private var lastId: String? = null

        /** A drive begins: its routes are on their way to the navigator, and no line is written before they are in. */
        fun begin() {
            open = false
            lastId = null
        }

        /** The navigator has the routes the drive starts on, [routeId] the first: whether its line is to be written. */
        fun onCommitted(routeId: String?): Boolean {
            open = true
            return take(routeId)
        }

        /** The navigator's routes changed, [routeId] the first now, a [refresh] or not: whether its line is to be written. */
        fun onRoutesChanged(routeId: String?, refresh: Boolean): Boolean = open && !refresh && take(routeId)

        /** The drive ended: nothing more is written for it. */
        fun end() {
            open = false
            lastId = null
        }

        private fun take(routeId: String?): Boolean {
            if (routeId == null || routeId == lastId) return false
            lastId = routeId
            return true
        }
    }
}
