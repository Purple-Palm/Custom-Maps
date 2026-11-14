package io.github.purplepalm.custommaps.nav.lanes.geometry

import io.github.purplepalm.custommaps.nav.geo.LocalPoint
import io.github.purplepalm.custommaps.nav.lanes.DesignatedKind
import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence
import io.github.purplepalm.custommaps.nav.lanes.LanePlan
import io.github.purplepalm.custommaps.nav.lanes.LanePlanner
import io.github.purplepalm.custommaps.nav.lanes.LaneRoute
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.At
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.sr
import io.github.purplepalm.custommaps.nav.lanes.LaneTopology
import io.github.purplepalm.custommaps.nav.lanes.PlanSegment
import io.github.purplepalm.custommaps.nav.lanes.PlanningSpeed
import io.github.purplepalm.custommaps.nav.lanes.RoadKind
import io.github.purplepalm.custommaps.nav.lanes.StreetWays
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Kind
import io.github.purplepalm.custommaps.nav.lanes.geometry.LaneDrawing.Part
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteLane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The trust properties of the app's own lanes on freeways (the design's 10.1, LaneTrust 1 to 11), on the made-up routes of
 * [LaneScenes] and on the seeded random routes of [RandomRoutes]: what the drawing claims is the route's data, a rule, or
 * nothing. The path's own rules are [RoutePathTest]'s; here the path is held against the lanes as they are drawn, and the
 * plan's named lane stands for the lane the map will light.
 */
class LaneTrustTest {

    private val gore = LaneScenes.GORE

    /** How often each property met what it checks, by name: the properties must not hold for want of cases. */
    private val seen = HashMap<String, Int>()

    private fun saw(what: String) {
        seen[what] = (seen[what] ?: 0) + 1
    }

    private fun assertSeen(vararg what: String) {
        for (w in what) assertTrue((seen[w] ?: 0) > 0, "no case of $w among ${seen.toSortedMap()}")
    }

    private class Built(
        val name: String,
        val route: LaneRoute,
        val plan: LanePlan,
        val evidence: LaneEvidence,
        val section: CrossSection,
        val path: RoutePath,
        val drawing: LaneDrawing,
        val features: List<LaneDrawing.Feature>,
    )

    private fun build(name: String, route: LaneRoute, line: List<GeoPoint> = SceneLines.of(route), streets: StreetWays? = null): Built {
        val plan = SceneLines.planOf(route)
        val evidence = plan.evidence()
        val section = CrossSection.of(plan, evidence, streets)
        val path = RoutePath.of(plan, line, version = 1, evidence = evidence, section = section)
        val drawing = LaneDrawing.of(plan, path, evidence, section)
        return Built(name, route, plan, evidence, section, path, drawing, drawing.features())
    }

    @Test
    fun `every trust property holds on every scene`() {
        for ((name, route) in LaneScenes.all() + extraScenes()) check(build(name, route))
        assertSeen("line", "paint", "stub", "diamond", "kept", "arrow", "named", "toward", "opens", "possible", "leaves", "begins", "ends", "turns", "beyond")
        assertSeen("simpleLine", "simpleRing", "lit", "tint", "litBeyond", "wedge", "litOnLanes", "litInLane", "bareGore", "bareRamp", "gore", "litPastEdge")
        assertSeen("lanesSettle")
    }

    @Test
    fun `every trust property holds on seeded random approaches`() {
        val rng = Random(RANDOM_SEED)
        repeat(RANDOM_ROUTES) { k ->
            val route = RandomRoutes.approach(rng, "random-$k")
            val bends = (1..8).map { rng.nextDouble(200.0, gore - 400.0) to rng.nextDouble(-4.0, 4.0) }
            check(build("random-$k", route, SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first })))
        }
        assertSeen("line", "paint", "stub", "diamond", "arrow", "named", "toward", "opens", "possible", "leaves", "begins", "turns", "beyond")
        assertSeen("simpleLine", "simpleRing", "lit", "tint", "litBeyond", "wedge", "litOnLanes", "litInLane", "gore")
    }

    @Test
    fun `every trust property holds where lanes are kept for car pools or buses, on seeded random routes`() {
        val rng = Random(KEPT_SEED)
        repeat(KEPT_ROUTES) { k ->
            val route = RandomRoutes.keptLane(rng, "kept-$k")
            val bends = (1..8).map { rng.nextDouble(200.0, gore - 400.0) to rng.nextDouble(-4.0, 4.0) }
            check(build("kept-$k", route, SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first })))
        }
        // 6: two of these routes move the named lane away from the exit's edge, where the road leaves no other way.
        assertSeen("forcedBack")
        assertSeen("line", "paint", "stub", "diamond", "kept", "arrow", "named", "toward", "opens", "possible", "leaves", "begins", "ends", "turns", "beyond")
        assertSeen("simpleLine", "simpleRing", "lit", "tint", "litBeyond", "wedge", "litOnLanes", "litInLane", "bareGore", "bareRamp", "gore")
    }

    @Test
    fun `every trust property holds on seeded random approaches where a conflict ends beside a lane still opening`() {
        // A second seed of the approaches: among them conflicts that end where the lane the plan names next still opens beside
        // the path on the road's line, and approaches where no lane is named before a ramp with lanes.
        val rng = Random(OPENING_SEED)
        var waited = 0
        repeat(OPENING_ROUTES) { k ->
            val route = RandomRoutes.approach(rng, "opening-$k")
            val bends = (1..8).map { rng.nextDouble(200.0, gore - 400.0) to rng.nextDouble(-4.0, 4.0) }
            val built = build("opening-$k", route, SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first }))
            if (litFromLater(built)) waited++
            check(built)
        }
        assertTrue(waited > 0, "no lane lit only from where it lies about the path")
        assertSeen("line", "paint", "named", "toward", "opens", "lit", "tint", "litOnLanes", "litInLane", "simpleLine", "simpleRing", "litOverFold")
    }

    @Test
    fun `the road's line lies half a lane inside our carriageway where no lane is named, also where the rules laid it off`() {
        // Routes of the drawing's sweeps where the cross-section's rules laid our carriageway off the road's line before an approach
        // where no lane is named: lanes that leave on one side and join on the other in a careful zone, a lane leaving at the edge.
        val routes = listOf(
            RandomRoutes.nth(Random(3_007), 0, "s3", RandomRoutes::keptLane),
            RandomRoutes.nth(Random(1_011), 144, "k1", RandomRoutes::keptLane),
            RandomRoutes.nth(Random(2_011), 2, "k2", RandomRoutes::keptLane),
            RandomRoutes.nth(Random(6_011), 128, "k6", RandomRoutes::keptLane),
            RandomRoutes.nth(Random(KEPT_SEED), 120, "kept", RandomRoutes::keptLane),
        )
        for ((route, line) in routes) {
            val built = build(route.routeId, route, line)
            assertTrue(built.plan.noLane.isNotEmpty(), "${route.routeId}: an approach where no lane is named")
            check(built)
        }
        // Through a fork into a stretch where no lane can be named, a drawing beginning just past the fork's gore, its asphalt's
        // taper inside the fork's turn: every property, the tapers at every end among them.
        val (route, line) = RandomRoutes.nth(Random(4_013), 110, "t4", RandomRoutes::twoManeuvers) { it.nodes.last().station - 800.0 }
        val built = build(route.routeId, route, line)
        assertEquals(2, built.plan.noLane.size)
        check(built)
        assertSeen("roadLineInside")
    }

    /**
     * Whether [b] names a lane in a card window, the path neither moving nor in the lane, and lights it only farther on: the
     * path on the road's line beside a lane still opening, or reaching the drawing so, as the plan's lane begins.
     */
    private fun litFromLater(b: Built): Boolean {
        val lit = b.drawing.litStretches()
        return b.plan.segments.any { seg ->
            val named = seg.named ?: return@any false
            val x = seg.from + 1.0
            seg.inWindow && seg.noLane == null && !b.plan.topology.lanes[named].designated &&
                b.path.drawings.any { x > it.from && x < it.to } && lit.none { x >= it.start && x <= it.endInclusive } &&
                lit.any { it.start > x && it.start < seg.to }
        }
    }

    @Test
    fun `every trust property holds on the street scenes, two-way and one-way`() {
        for ((name, route) in streetScenes()) {
            for (oneWay in listOf(false, true)) check(build("$name-${if (oneWay) "one-way" else "two-way"}", route, streets = LaneScenes.ways(route, oneWay)))
        }
        assertSeen("line", "named", "lit", "arrow", "kept", "stopLine", "twoWayLine", "junctionEnd", "junctionStart", "begins", "ends", "interiorPlain")
        assertSeen("pocketLine", "pocketTint", "pocketEntry", "pocketPossible", "litTurn", "uTurn", "roundabout", "roadLineTwoWay")
    }

    @Test
    fun `every trust property holds on seeded random streets, two-way, one-way and without a source`() {
        val rng = Random(STREET_SEED)
        var undrawn = 0
        repeat(STREET_ROUTES) { k ->
            val route = RandomRoutes.street(rng, "street-$k")
            val bends = (1..6).map { rng.nextDouble(200.0, gore - 200.0) to rng.nextDouble(-4.0, 4.0) }
            val line = SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first })
            val streets = when (rng.nextInt(6)) {
                0 -> null
                1, 2 -> LaneScenes.ways(route, oneWay = true)
                else -> LaneScenes.ways(route, oneWay = false)
            }
            val built = build("street-$k", route, line, streets)
            if (streets == null) {
                // Without a source nothing of a street is drawn, and the path is the route's own line.
                assertTrue(built.path.drawings.isEmpty() && built.features.isEmpty(), "street-$k drawn without a source")
                assertEquals(built.path.route, built.path.points)
                undrawn++
            }
            check(built)
        }
        assertTrue(undrawn > 0)
        assertSeen("line", "named", "lit", "tint", "arrow", "stopLine", "twoWayLine", "junctionEnd", "junctionStart", "begins", "ends", "interiorPlain")
        assertSeen("litAcross", "beginsPastJunction", "pocketLine", "pocketTint", "pocketEntry", "pocketPossible", "litTurn", "uTurn", "roundabout")
    }

    @Test
    fun `every trust property holds on seeded random streets whose sources tell street by street`() {
        // Each street told by a source or by none, one-way or two-way, now and then otherwise than the road's first: a drawing
        // that begins and ends at intersections, and the approaches to card windows drawn all or none.
        val rng = Random(WAYS_SEED)
        var cut = 0
        repeat(WAYS_ROUTES) { k ->
            val route = RandomRoutes.street(rng, "ways-$k")
            val bends = (1..6).map { rng.nextDouble(200.0, gore - 200.0) to rng.nextDouble(-4.0, 4.0) }
            val line = SceneLines.line(route.lengthMeters, (bends + SceneLines.bendsOf(route)).sortedBy { it.first })
            val road = rng.nextBoolean()
            val told = HashMap<Int, StreetWays.Way>()
            for (n in route.nodes.indices) {
                when (rng.nextInt(5)) {
                    0 -> Unit
                    1 -> told[n] = StreetWays.Way(StreetWays.carriageway(!road), StreetWays.Source.HORIZON)
                    else -> told[n] = StreetWays.Way(StreetWays.carriageway(road), StreetWays.Source.HORIZON)
                }
            }
            val built = build("ways-$k", route, line, StreetWays.taken(route, told))
            // Drawn where its streets are, never where one is listed undrawn.
            for (u in built.section.undrawnStreets) assertFalse(built.section.drawsAt((u.from + u.to) / 2.0), "ways-$k: drawn in $u")
            if (built.section.drawnStreets.isNotEmpty() && built.section.undrawnStreets.isNotEmpty()) cut++
            check(built)
        }
        assertTrue(cut >= WAYS_ROUTES / 4, "drawn and undrawn streets together on $cut routes")
        assertSeen("line", "named", "lit", "arrow", "stopLine", "twoWayLine", "junctionEnd", "begins", "ends", "interiorPlain")
    }

    @Test
    fun `11 the same route gives byte-equal features, whole and in windows`() {
        for (route in listOf(LaneScenes.iw31(), LaneScenes.ia(), LaneScenes.carpoolEnds(), LaneScenes.thirdLaneForCarPools())) {
            val one = build(route.routeId, route)
            val two = build(route.routeId, route)
            assertEquals(one.features, two.features)
            assertEquals(one.features.toString(), two.features.toString())
            val a = gore - 2_345.6
            val b = gore + 123.4
            assertEquals(one.drawing.features(a, b).toString(), two.drawing.features(a, b).toString())
        }
    }

    @Test
    fun `3 a lane only for bicycles is drawn at its own width with a solid line beside it, never as a vehicle lane`() {
        val built = build("bicycle-lane", bicycleLane())
        check(built)
        val lane = built.plan.topology.lanes.first { it.kinds == setOf(DesignatedKind.BICYCLE) }
        val place = built.drawing.lanesAt(gore - 1_200.0).single { it.lane == lane.id }
        assertEquals(CrossSection.BICYCLE_LANE_METERS, place.width, 1e-9)
        val beside = built.features.filter { it.kind == Kind.LANE_LINE && it.between?.first == lane.id }
        assertTrue(beside.isNotEmpty())
        assertTrue(beside.all { it.pattern == LaneDrawing.Pattern.SOLID && it.width == LaneDrawing.Width.NORMAL })
        assertTrue(built.features.none { (it.kind == Kind.KEPT_LANE || it.kind == Kind.ARROW || it.kind == Kind.DIAMOND) && it.lane == lane.id })
    }

    /** Every property of the design's LaneTrust that the lanes, the lines, the symbols and the path through them can break. */
    private fun check(b: Built) {
        linesOnData(b)
        noDoubleNoCentreNoChevron(b)
        streetLines(b)
        junctionInteriorsPlain(b)
        litTurnIntoTheReceivingLane(b)
        designatedByKind(b)
        namedLaneDrawn(b)
        movesTowardThePlansLane(b)
        neverBack(b)
        lanesOpenAndLeave(b)
        tapersAtEveryEnd(b)
        neverAwayFromTheExit(b)
        notTheEdgeLaneWhileOneMayBeBeyond(b)
        litAndTints(b)
        litOnTheNamedLane(b)
        bareWhereReachedOnTheLine(b)
        goreWhereLanesAreLeft(b)
        noLoops(b)
        litGoesOn(b)
        lanesSettleAtRampStarts(b)
        roadLineInsideWhereNoLaneIsNamed(b)
    }

    // ------------------------------------------------------------------ C1: the road's line inside our carriageway

    /**
     * Where no lane is named (C1) the path runs on the road's line, and over the band before such an approach it heads for it
     * (design 3.3: "the line on the road's middle, over no lines of ours"): there the path lies at least half a lane inside
     * our edge lines, which move sideways where the cross-section's rules laid them off the road's line. Not where the path
     * turns onto another road and settles there, nor across a gore it reaches on the road's line, nor around a corner where it
     * keeps to that line.
     */
    private fun roadLineInsideWhereNoLaneIsNamed(b: Built) {
        val path = b.path
        val skip = path.folds.filter { it.toLine }.map { it.from - RoutePath.RETURN_METERS to it.to + RoutePath.RETURN_METERS } +
            b.section.rampStarts.mapNotNull { rs ->
                path.axisAt(rs.station)?.let { axis -> rs.begin(axis) to maxOf(rs.station + CrossSection.RAMP_BLEND_METERS, rs.settled(axis)) }
            } +
            path.moves.filter { it.kind == RoutePath.Move.Kind.TURN }.map { it.from to it.to } +
            path.diagonals.map { it.from to it.to }
        for (n in b.plan.noLane) {
            if (!n.zone) continue
            // The band heads for the approach within the drawing that holds the approach's start.
            val home = path.drawings.firstOrNull { n.from >= it.from - 1e-6 && n.from < it.to }
            val from = if (home == null) n.from else maxOf(bandFrom(b.plan, n), home.from)
            val to = path.axisAt(n.to)?.let { CrossSection.beginOf(it, n.to) } ?: n.to
            var x = from
            while (x < to) {
                if (path.drawings.any { x >= it.from && x < it.to } && skip.none { x >= it.first - 1.0 && x <= it.second + 1.0 }) {
                    val lanes = b.drawing.lanesAt(x).filter { it.width > 1e-6 }
                    if (lanes.isNotEmpty()) {
                        val half = LaneTopology.laneMeters(b.section.roadAt(x) ?: RoadKind.FREEWAY) / 2.0
                        val t = path.offsetAt(x)
                        if (b.section.carriagewayAt(x) == CrossSection.Carriageway.TWO_WAY) {
                            // On a two-way street the road's line is the line between its halves, where no line of ours lies: the
                            // path keeps to it or to our half, never over the oncoming one, at least half a lane inside our right edge.
                            assertTrue(t <= 1e-6 && t >= lanes.minOf { it.right } + half - ROAD_LINE_SLACK_METERS, "${b.name}: no lane named before ${n.to}; at $x the path at $t")
                            saw("roadLineTwoWay")
                        } else {
                            val inside = minOf(lanes.maxOf { it.left } - t, t - lanes.minOf { it.right })
                            assertTrue(
                                inside >= half - ROAD_LINE_SLACK_METERS,
                                "${b.name}: no lane named before ${n.to}; at $x the path lies $inside m inside our edge lines",
                            )
                            saw("roadLineInside")
                        }
                    }
                }
                x += 2.0
            }
        }
    }

    /**
     * Where the band that heads for the approach [n] begins: back over the plan's stretches of its run, to where the plan names
     * a lane in a card window or another such approach ends.
     */
    private fun bandFrom(plan: LanePlan, n: LanePlan.NoLane): Double {
        val segments = plan.segments
        val k = segments.indexOfFirst { it.to > n.from + 1e-6 }
        if (k < 0) return n.from
        val run = segments[k].run
        var from = n.from
        for (i in k downTo 0) {
            val seg = segments[i]
            if (seg.run != run) break
            if (seg.from >= n.from - 1e-6) continue
            if (seg.noLane != null && seg.to < n.from - 1e-6 || seg.noLane == null && seg.inWindow && seg.named != null) return minOf(from, seg.to)
            if (seg.noLane == null) from = seg.from
        }
        return from
    }

    // ------------------------------------------------------------------ 1, 2: no line and no ring of ours crosses itself

    /**
     * Where a line lies farther out than a tight arc's radius it turns where it would cross itself (design 3.3): no line of
     * ours (a lane line, an edge or channelizing line, a lit lane's edge, a diamond's outline) and no ring (the asphalt, a lane
     * drawn darker, a tint, the lit lane, an arrow) crosses itself anywhere, on the route's carriageway, a stub or past a turn.
     */
    private fun noLoops(b: Built) {
        for (f in b.features) {
            for (part in f.parts) {
                val closed = f.kind.polygon || f.kind == Kind.DIAMOND
                val crossing = DrawingProbe.selfCrossing(part.map { SceneLines.local(it) }, closed)
                assertTrue(crossing == null, "${b.name}: a ${f.part} ${f.kind} ${f.between ?: f.side ?: f.lane} over ${f.from}..${f.to} crosses itself, pieces $crossing")
                saw(if (closed) "simpleRing" else "simpleLine")
            }
        }
    }

    // ------------------------------------------------------------------ 8: past a gore the old road's lanes go on and taper away

    /**
     * At every ramp's start inside a drawing where lanes of the old road beyond the lanes that go on, on the far side from the
     * exit's edge, do not go on onto the new road, those lanes go on for the stub's length past a gore and taper away: the
     * gore's two channelizing lines end at a nose within the stub, and the stub's asphalt tapers into the map's band past it,
     * whatever the turn's arc.
     */
    private fun goreWhereLanesAreLeft(b: Built) {
        val topology = b.plan.topology
        for (start in b.section.rampStarts) {
            if (b.path.drawings.none { start.station > it.from && start.station < it.to }) continue
            val edge = topology.points.firstOrNull { it.node == start.node }?.edge ?: continue
            val after = b.section.lanes(start.station + 1e-3).filter { it.width > 1e-6 }.map { it.lane }.toSet()
            if (after.isEmpty()) continue
            val slots = after.map { topology.lanes[it].slot }
            val left = b.section.lanes(start.station - 1e-3).filter { p ->
                val slot = topology.lanes[p.lane].slot
                p.width > 1e-6 && p.lane !in after && if (edge == LaneEdge.RIGHT) slot < slots.min() else slot > slots.max()
            }
            if (left.isEmpty()) continue
            assertTrue(
                b.drawing.noses.any { (_, nose) -> nose >= start.station - 1e-6 && nose <= start.station + LaneDrawing.STUB_METERS + 1e-6 },
                "${b.name}: no gore at the ramp's start at ${start.station}, where lanes ${left.map { it.lane }} go on along the old road",
            )
            val taperEnd = start.station + LaneDrawing.STUB_METERS + RoutePath.ASPHALT_TAPER_METERS
            assertTrue(
                b.features.any { it.kind == Kind.ASPHALT && it.part == Part.STUB && abs(it.to - taperEnd) < 1e-6 },
                "${b.name}: the old road's asphalt does not taper away to $taperEnd past the ramp's start at ${start.station}",
            )
            saw("gore")
        }
    }

    // ------------------------------------------------------------------ 8: past a ramp's start the lanes settle as the path does

    /**
     * At every ramp's start inside a drawing, each lane that goes on settles onto its place on the new road as the path
     * settles past a turn: from where the turn's arc begins until the lanes lie on the new road for good, its middle closes
     * in on that place from one side, never moves away from it and never swings past it.
     */
    private fun lanesSettleAtRampStarts(b: Built) {
        for (start in b.section.rampStarts) {
            if (b.path.drawings.none { start.station > it.from && start.station < it.to }) continue
            val axis = b.path.axisAt(start.station + 1e-3) ?: continue
            val after = start.station + 1e-3
            for (lane in b.section.lanes(after).filter { it.width > 1e-6 }.map { it.lane }) {
                var side = 0.0
                var last = Double.MAX_VALUE
                for (s in along(start.begin(axis), start.settled(axis) + 1.0, 0.5)) {
                    val place = b.section.place(lane, maxOf(s, after)) ?: break
                    val drawn = b.section.place(lane, s, axis) ?: continue
                    val off = drawn.centre - place.centre
                    if (side == 0.0 && abs(off) > 1e-6) side = Math.signum(off)
                    assertTrue(off * side >= -1e-6, "${b.name}: lane $lane swings past its place on the new road at $s ($off)")
                    assertTrue(abs(off) <= last + 1e-6, "${b.name}: lane $lane moves away from its place on the new road at $s ($off)")
                    last = abs(off)
                }
                saw("lanesSettle")
            }
        }
    }

    // ------------------------------------------------------------------ 1: lane lines only where the data gives the count

    private fun linesOnData(b: Built) {
        val turns = b.route.nodes.filter { LaneEvidence.planTurns(it) }.map { it.station }
        for (f in b.features) {
            if (f.kind != Kind.LANE_LINE) continue
            when (f.part) {
                Part.ROUTE -> {
                    val between = requireNotNull(f.between)
                    for (x in along(f.from, f.to, LINE_SAMPLE_METERS)) {
                        val stretch = b.evidence.stretches.firstOrNull { x >= it.from && x < it.to }
                        assertTrue(
                            stretch != null && (stretch.kind == LaneEvidence.Kind.KNOWN || stretch.kind == LaneEvidence.Kind.BRIDGED),
                            "${b.name}: the line $between at $x on a ${stretch?.kind} stretch",
                        )
                        val lanes = b.drawing.lanesAt(x)
                        val l = lanes.indexOfFirst { it.lane == between.first }
                        assertTrue(l >= 0 && lanes.getOrNull(l + 1)?.lane == between.second, "${b.name}: the line $between at $x lies between no two neighbours")
                        // Beside a lane only possible no line, but for a turn pocket's dotted extension beside the lane feeding it.
                        val left = b.plan.topology.lanes[between.first]
                        val right = b.plan.topology.lanes[between.second]
                        val pocket = left.pocket && left.fedBy == right.id || right.pocket && right.fedBy == left.id
                        if (pocket && (lanes[l].state == CrossSection.State.POSSIBLE || lanes[l + 1].state == CrossSection.State.POSSIBLE)) {
                            assertEquals(LaneDrawing.Pattern.EXTENSION, f.pattern, "${b.name}: the line $between at $x beside a pocket that may already be there")
                            saw("pocketLine")
                        } else {
                            assertTrue(
                                lanes[l].state != CrossSection.State.POSSIBLE && lanes[l + 1].state != CrossSection.State.POSSIBLE,
                                "${b.name}: the line $between at $x beside a lane that is only possible",
                            )
                        }
                        saw("line")
                    }
                }
                // A stub carries the lanes the route does not take on for its length, by rule.
                Part.STUB -> {
                    assertTrue(
                        turns.any { f.from >= it - CrossSection.RAMP_START_MAX_METERS && f.to <= it + LaneDrawing.STUB_METERS + 1e-6 },
                        "${b.name}: a stub's line over ${f.from}..${f.to}",
                    )
                    saw("stub")
                }
                // Past a turn without lane data no count is known: no line between its lanes.
                Part.BEYOND -> error("${b.name}: a lane line past a turn without lane data at ${f.from}")
            }
        }
    }

    // ------------------------------------------------------------------ 2: no double line, no centre line, no chevron

    private fun noDoubleNoCentreNoChevron(b: Built) {
        // No crosswalk among them: the route gives no crossing.
        val known = setOf(
            Kind.ASPHALT, Kind.KEPT_LANE, Kind.TINT, Kind.LIT, Kind.LIT_EDGE, Kind.LANE_LINE, Kind.EDGE_LINE, Kind.CHANNEL_LINE, Kind.DIAMOND, Kind.ARROW,
            Kind.STOP_LINE,
        )
        for (f in b.features) {
            assertTrue(f.kind in known, "${b.name}: ${f.kind}")
            // Yellow only on the left edge: never a centre line, never an oncoming carriageway's line.
            if (f.paint == LaneDrawing.Paint.YELLOW) assertTrue(f.kind == Kind.EDGE_LINE && f.side == LaneEdge.LEFT, "${b.name}: a yellow ${f.kind} ${f.side}")
            // The gore's lines are two plain wide white lines, never chevrons.
            if (f.kind == Kind.CHANNEL_LINE) {
                assertEquals(LaneDrawing.Width.WIDE, f.width)
                assertEquals(LaneDrawing.Paint.WHITE, f.paint)
                assertEquals(LaneDrawing.Pattern.SOLID, f.pattern)
                assertEquals(1, f.parts.size)
            }
            // Lines run between lanes of the route's own carriageway, or at its edges.
            if (f.kind == Kind.LANE_LINE) {
                val between = requireNotNull(f.between)
                assertTrue(between.first in b.plan.topology.lanes.indices && between.second in b.plan.topology.lanes.indices)
            }
            // A solid line is one line: a second part beside it would be drawn as a double line.
            if ((f.kind == Kind.LANE_LINE || f.kind == Kind.EDGE_LINE) && f.pattern == LaneDrawing.Pattern.SOLID) {
                assertEquals(1, f.parts.size, "${b.name}: a solid ${f.kind} ${f.between ?: f.side} in ${f.parts.size} parts over ${f.from}..${f.to}")
            }
        }
        // One line on each boundary at a time.
        for (part in Part.entries) {
            val lines = b.features.filter { it.part == part && (it.kind == Kind.LANE_LINE || it.kind == Kind.EDGE_LINE || it.kind == Kind.CHANNEL_LINE) }
            for ((key, group) in lines.groupBy { Triple(it.kind, it.between, it.side) }) {
                val sorted = group.sortedBy { it.from }
                for (i in 1 until sorted.size) {
                    assertTrue(sorted[i].from > sorted[i - 1].to, "${b.name}: two $part lines $key over ${sorted[i].from}..${sorted[i - 1].to}")
                }
            }
        }
        // On the route's carriageway no more lane lines than boundaries between the lanes drawn, and at most one line at each edge.
        for (d in b.path.drawings) {
            for (x in along(d.from + 0.37, d.to, 5.0)) {
                val here = b.features.filter { it.part == Part.ROUTE && x >= it.from && x <= it.to }
                val lanes = b.drawing.lanesAt(x).size
                assertTrue(here.count { it.kind == Kind.LANE_LINE } <= maxOf(0, lanes - 1), "${b.name} at $x: more lane lines than boundaries")
                for (side in LaneEdge.entries) {
                    assertTrue(
                        here.count { (it.kind == Kind.EDGE_LINE || it.kind == Kind.CHANNEL_LINE) && it.side == side } <= 1,
                        "${b.name} at $x: two lines at the $side edge",
                    )
                }
                // Across the road, the paint itself: each boundary is crossed by at most one line, solid, broken or dotted,
                // whatever the features it comes in.
                val axis = b.path.axisAt(x) ?: continue
                val paint = here.filter { it.kind == Kind.LANE_LINE || it.kind == Kind.EDGE_LINE || it.kind == Kind.CHANNEL_LINE }
                // Each part once where it crosses: a line that folds over itself on an arc too tight for its offset crosses the
                // normal there two or three times within a few millimetres, and is still one line.
                val crossings = paint.flatMap { it.parts }.flatMap { part ->
                    val cuts = DrawingProbe.across(axis, x, listOf(part))
                    cuts.filterIndexed { k, c -> k == 0 || c - cuts[k - 1] >= DOUBLE_LINE_METERS }
                }.sorted()
                for ((a, c) in crossings.zipWithNext()) {
                    assertTrue(c - a >= DOUBLE_LINE_METERS, "${b.name} at $x: two lines ${c - a} m apart, at $a and $c")
                    saw("paint")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2 on streets: no centre line, no oncoming line, stop lines from flags

    /**
     * On a two-way street no line marks the road's line or anything of the oncoming half: no edge line at all (the left one
     * would be a centre line of a kind the data does not give), and no line left of the route's leftmost lane. A stop line
     * stands only before an intersection whose flags give a traffic signal or a stop sign, [LaneTopology.STOP_LINE_METERS]
     * before it, [LaneDrawing.STOP_LINE_METERS] deep, white, from edge to edge of the route's lanes there; never one where the
     * route reaches the intersection on no street.
     */
    private fun streetLines(b: Built) {
        val nodes = b.route.nodes
        for (f in b.features) {
            if (f.part != Part.ROUTE) continue
            if (f.kind == Kind.STOP_LINE) {
                val node = nodes.indices.firstOrNull { abs(nodes[it].station - LaneTopology.STOP_LINE_METERS - f.to) < 1e-6 }
                assertTrue(node != null && (nodes[node].trafficSignal || nodes[node].stopSign), "${b.name}: a stop line to ${f.to} without a flag")
                assertEquals(RoadKind.STREET, b.route.approachRoad(requireNotNull(node)), "${b.name}: a stop line off a street")
                assertEquals(LaneDrawing.STOP_LINE_METERS, f.to - f.from, 1e-9)
                assertEquals(LaneDrawing.Width.STOP, f.width)
                assertEquals(LaneDrawing.Paint.WHITE, f.paint)
                val middle = f.from + LaneDrawing.STOP_LINE_METERS / 2.0
                val axis = requireNotNull(b.path.axisAt(middle))
                val ends = f.parts.single().map { offsetOf(axis, middle, it) }.sorted()
                val lanes = b.drawing.lanesAt(middle)
                assertEquals(lanes.minOf { it.right }, ends.first(), 1e-6, "${b.name}: the stop line's right end at $middle")
                assertEquals(lanes.maxOf { it.left }, ends.last(), 1e-6, "${b.name}: the stop line's left end at $middle")
                saw("stopLine")
                continue
            }
            if (f.kind != Kind.LANE_LINE && f.kind != Kind.EDGE_LINE && f.kind != Kind.CHANNEL_LINE) continue
            for (part in f.parts) {
                for (p in part) {
                    val (station, offset) = DrawingProbe.project(b.path.axes, (f.from + f.to) / 2.0, p)
                    if (b.section.carriagewayAt(station) != CrossSection.Carriageway.TWO_WAY || station < f.from - 1.0 || station > f.to + 1.0) continue
                    assertTrue(f.kind == Kind.LANE_LINE, "${b.name}: a ${f.kind} ${f.side} on a two-way street at $station")
                    val lanes = b.drawing.lanesAt(station)
                    if (lanes.isEmpty()) continue
                    assertTrue(offset <= lanes.maxOf { it.left } + 0.01, "${b.name}: a line at $offset over the oncoming half at $station")
                    saw("twoWayLine")
                }
            }
        }
        // Every flagged intersection on a drawn street approach has its stop line where the route's lanes are drawn there.
        for ((i, node) in nodes.withIndex()) {
            if (i == 0 || !(node.trafficSignal || node.stopSign) || b.route.approachRoad(i) != RoadKind.STREET) continue
            val station = node.station - LaneTopology.STOP_LINE_METERS
            val inside = b.drawing.junctionInteriors.any { station - LaneDrawing.STOP_LINE_METERS < it.endInclusive && station > it.start }
            val drawn = b.path.drawings.any { station - LaneDrawing.STOP_LINE_METERS >= it.from && station <= it.carriagewayTo } &&
                b.drawing.lanesAt(station - LaneDrawing.STOP_LINE_METERS).isNotEmpty() && !inside
            assertEquals(drawn, b.drawing.stopLines.any { it.node == i }, "${b.name}: the stop line before intersection $i")
        }
    }

    /**
     * Inside a street junction the drawing goes on through, the map's own road shows (the design's junction interiors): no
     * asphalt, line, darker lane, arrow, stop line or tint of ours lies across the road there, only the lit lane on the path;
     * the lanes go on as laid out, undrawn.
     */
    private fun junctionInteriorsPlain(b: Built) {
        for (interior in b.drawing.junctionInteriors) {
            for (s in listOf(interior.start + 0.5, (interior.start + interior.endInclusive) / 2.0, interior.endInclusive - 0.5)) {
                val axis = b.path.axisAt(s) ?: continue
                for (f in b.features) {
                    if (f.from > interior.endInclusive || f.to < interior.start) continue
                    val across = DrawingProbe.across(axis, s, f.parts).filter { abs(it) < INTERIOR_REACH_METERS }
                    if (f.kind == Kind.LIT || f.kind == Kind.LIT_EDGE) {
                        if (across.isNotEmpty()) saw("litAcross")
                        continue
                    }
                    assertTrue(across.isEmpty(), "${b.name}: a ${f.kind} inside the junction from ${interior.start} to ${interior.endInclusive}, at $s")
                }
                assertNull(b.drawing.asphaltAt(s), "${b.name}: asphalt inside the junction at $s")
                saw("interiorPlain")
            }
        }
    }

    /**
     * Where the route turns at a street junction from the lit lane into a road drawn from the junction's far side, the lit lane
     * goes on across the junction along the path's curve, and the curve ends in the middle of the receiving road's lane: one
     * open to every car, the nearest from the turn's inside, never in the oncoming half. At a U-turn no lane is claimed past
     * the junction: the curve ends on the route's line and nothing is lit across. At a roundabout there is no curve, and
     * nothing of ours lies past its approach's near side until the way round it ends.
     */
    private fun litTurnIntoTheReceivingLane(b: Built) {
        val nodes = b.route.nodes
        for (j in b.path.junctions) {
            val maneuver = requireNotNull(nodes[j.node].maneuver)
            assertFalse(maneuver.type in ROUNDABOUTS, "${b.name}: a curve of ours into a roundabout at ${j.from}")
            val lane = j.receiving
            if (maneuver.modifier == TurnModifier.U_TURN) {
                assertNull(lane, "${b.name}: a receiving lane past the U-turn at ${j.from}")
                assertEquals(0.0, j.offsetTo, 1e-9, "${b.name}: the U-turn's curve off the route's line")
                saw("uTurn")
            }
            val lit = b.features.filter { it.kind == Kind.LIT && it.to > j.from + 1e-6 && it.from < j.to - 1e-6 }
            if (lane == null) {
                assertEquals(0.0, j.offsetTo, 1e-9, "${b.name}: the curve at ${j.from} off the new road's line with no lane there")
                assertTrue(lit.isEmpty(), "${b.name}: lit across the junction at ${j.from} with no receiving lane")
                continue
            }
            // The receiving lane: drawn there, open to every car, the curve's end in its middle, in our half.
            val places = b.section.lanes(j.to).filter { it.width > 1e-6 }
            val place = requireNotNull(places.firstOrNull { it.lane == lane }) { "${b.name}: the receiving lane $lane not drawn at ${j.to}" }
            assertFalse(b.plan.topology.lanes[lane].designated, "${b.name}: the receiving lane $lane is designated")
            assertEquals(place.centre, j.offsetTo, 0.01, "${b.name}: the curve's end off the receiving lane's middle")
            assertTrue(j.offsetTo <= places.first().left + 1e-9 && j.offsetTo >= places.last().right - 1e-9, "${b.name}: the curve ends off our lanes")
            val general = places.filter { !b.plan.topology.lanes[it.lane].designated }
            val modifier = maneuver.modifier
            if (modifier == TurnModifier.RIGHT || modifier == TurnModifier.SLIGHT_RIGHT || modifier == TurnModifier.SHARP_RIGHT) {
                if (b.plan.topology.points.single { it.node == j.node }.allowed.size == 1) assertEquals(general.last().lane, lane, "${b.name}: not the nearest lane")
            } else if (modifier == TurnModifier.LEFT || modifier == TurnModifier.SLIGHT_LEFT || modifier == TurnModifier.SHARP_LEFT) {
                if (b.plan.topology.points.single { it.node == j.node }.allowed.size == 1) assertEquals(general.first().lane, lane, "${b.name}: not the nearest lane")
            }
            // Lit across where the lane before it is lit at the near side, all the way to the far side.
            val litNear = b.features.any { it.kind == Kind.LIT && it.from < j.from - 1e-6 && it.to >= j.from - 1e-6 }
            if (litNear) {
                assertTrue(lit.isNotEmpty(), "${b.name}: the lit lane stops at the junction's near side at ${j.from}")
                assertEquals(j.to, lit.maxOf { it.to }, 1e-6, "${b.name}: the lit turn short of the far side")
                assertTrue(j.to - 1e-6 <= b.drawing.litStretches().first { it.start <= j.from && it.endInclusive >= j.from }.endInclusive)
                assertTrue(nodes[j.node].station in b.drawing.turns(), "${b.name}: no arrow along the lit turn at ${j.from}")
                saw("litTurn")
            }
        }
        // The maneuver's arrow follows the path at every curve of its own but a U-turn's, lit or not.
        for (j in b.path.junctions) {
            val uTurn = nodes[j.node].maneuver?.modifier == TurnModifier.U_TURN
            assertEquals(!uTurn, nodes[j.node].station in b.drawing.turns(), "${b.name}: the arrow at the curve at ${j.from}")
        }
        // A roundabout: nothing of ours from its approach's near side to the end of the way round it.
        for ((i, node) in nodes.withIndex()) {
            if (node.maneuver?.type !in ROUNDABOUTS || node.maneuver?.type in ROUNDABOUT_EXITS || i == 0) continue
            val exit = nodes.drop(i + 1).firstOrNull { it.maneuver?.type in ROUNDABOUT_EXITS }?.station ?: continue
            val inside = b.features.filter { it.to > node.station - LaneTopology.STOP_LINE_METERS + 1e-6 && it.from < exit }
            assertTrue(inside.isEmpty(), "${b.name}: ${inside.map { it.kind }} inside the roundabout at ${node.station}")
            assertTrue(b.drawing.turns().none { it == node.station })
            saw("roundabout")
        }
    }

    // ------------------------------------------------------------------ 3: designated lanes by their kind

    private fun designatedByKind(b: Built) {
        val lanes = b.plan.topology.lanes
        for (f in b.features) {
            val lane = f.lane?.let { lanes[it] }
            when (f.kind) {
                Kind.DIAMOND -> {
                    assertTrue(lane != null && DesignatedKind.HOV in lane.kinds, "${b.name}: a diamond in lane ${f.lane}")
                    val middle = f.from + LaneDrawing.DIAMOND_LENGTH_METERS / 2.0
                    val place = b.drawing.lanesAt(middle).single { it.lane == f.lane }
                    val ring = f.parts.single().dropLast(1)
                    val centre = GeoPoint(ring.sumOf { it.latitude } / ring.size, ring.sumOf { it.longitude } / ring.size)
                    assertEquals(place.centre, offsetOf(requireNotNull(b.path.axisAt(middle)), middle, centre), 0.01, "${b.name}: a diamond off its lane's middle at ${f.from}")
                    saw("diamond")
                }
                Kind.KEPT_LANE -> {
                    assertTrue(
                        lane != null && lane.designated && DesignatedKind.HOV !in lane.kinds && lane.kinds.any { it != DesignatedKind.BICYCLE },
                        "${b.name}: lane ${f.lane} drawn darker, kept for ${lane?.kinds}",
                    )
                    saw("kept")
                }
                Kind.ARROW -> {
                    assertFalse(requireNotNull(lane).designated, "${b.name}: an arrow on the designated lane ${f.lane}")
                    val place = b.drawing.lanesAt(f.from).firstOrNull { it.lane == f.lane }
                    assertTrue(place != null && place.state != CrossSection.State.POSSIBLE, "${b.name}: an arrow at ${f.from} on lane ${f.lane}, not drawn as there")
                    // Every corner of it inside its lane.
                    val axis = requireNotNull(b.path.axisAt(f.from))
                    val start = axis.at(f.from)
                    for (p in f.parts.flatten()) {
                        val q = axis.toLocal(p)
                        val at = f.from + (q.x - start.point.x) * start.tx + (q.y - start.point.y) * start.ty
                        val drawn = b.drawing.lanesAt(at).single { it.lane == f.lane }
                        assertTrue(abs(offsetOf(axis, at, p) - drawn.centre) <= drawn.width / 2.0, "${b.name}: an arrow's corner outside lane ${f.lane} at $at")
                    }
                    saw("arrow")
                }
                else -> Unit
            }
        }
        // A lane only for bicycles is drawn at its own width wherever it is drawn (across the road: not where the lanes go on
        // straight across a turn): never as a vehicle lane.
        val turning = turns(b)
        for (d in b.path.drawings) {
            for (x in along(d.from, d.to, 20.0)) {
                if (turning.any { x >= it.first && x <= it.second }) continue
                for (p in b.drawing.lanesAt(x)) {
                    if (lanes[p.lane].kinds == setOf(DesignatedKind.BICYCLE)) assertTrue(p.width <= CrossSection.BICYCLE_LANE_METERS + 1e-9, "${b.name} at $x")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 4: the named lane is a lane of the drawing, the path in it

    private fun namedLaneDrawn(b: Built) {
        val excluded = b.path.moves.map { it.from - 1e-6 to it.to + 1e-6 } + turns(b) + folds(b)
        for (window in b.plan.windows) {
            for (x in along(window.from, window.to, 2.0)) {
                val seg = b.plan.segmentAt(x) ?: continue
                val named = seg.named ?: continue
                if (!seg.inWindow || seg.noLane != null || b.path.drawings.none { x >= it.from && x < it.carriagewayTo }) continue
                val lanes = b.drawing.lanesAt(x)
                val lane = b.plan.topology.lanes[named]
                assertFalse(lane.designated, "${b.name}: the designated lane $named named at $x")
                val feeder = lane.fedBy?.let { f -> lanes.firstOrNull { it.lane == f } }
                val place = lanes.firstOrNull { it.lane == named }
                if (place == null) {
                    // A lane entered along its taper opens from nothing: where it begins, it is still its feeder's width.
                    assertTrue(feeder != null && x - lane.opens < OPENING_METERS, "${b.name}: the named lane $named is not drawn at $x")
                    continue
                }
                assertTrue(place.state != CrossSection.State.POSSIBLE, "${b.name}: the named lane $named is drawn as only possible at $x")
                if (excluded.any { x >= it.first && x <= it.second }) continue
                val left = maxOf(place.left, feeder?.left ?: place.left)
                val right = minOf(place.right, feeder?.right ?: place.right)
                val offset = b.path.offsetAt(x)
                assertTrue(offset <= left + 1e-9 && offset >= right - 1e-9, "${b.name} at $x: the path at $offset outside its drawn lane $right..$left")
                saw("named")
            }
        }
    }

    // ------------------------------------------------------------------ 5: the path moves only toward the plan's lane

    private fun movesTowardThePlansLane(b: Built) {
        // Back to the road's line only where a drawing ends outside card windows.
        for (move in b.path.moves) {
            if (move.kind == RoutePath.Move.Kind.RETURN) assertFalse(b.plan.segmentAt(move.from + 0.5)?.inWindow == true, "${b.name}: a return inside a card window")
        }
        if (b.plan.moves.any { it.back }) return
        val skip = turns(b) + folds(b)
        for (drawing in b.path.drawings) {
            var station = drawing.from
            var first = 0.0 // the first move's direction while it lasts; 2 once it is over
            var prev = b.path.offsetAt(station)
            var approach = approachOf(b, station)
            while (station < drawing.carriagewayTo) {
                station = minOf(drawing.carriagewayTo, station + 1.0)
                val now = b.path.offsetAt(station)
                val here = approachOf(b, station)
                if (here != approach) {
                    approach = here
                    first = 0.0
                } else if (skip.none { station >= it.first && station <= it.second } && abs(now - prev) > 1e-9) {
                    val sign = if (now > prev) 1.0 else -1.0
                    val edge = edgeSign(b, station) ?: sign
                    when {
                        first == 0.0 -> first = sign
                        first != 2.0 && sign == first -> Unit
                        else -> {
                            first = 2.0
                            assertEquals(edge, sign, "${b.name} at $station: the path moves back ($prev to $now)")
                        }
                    }
                }
                prev = now
            }
        }
    }

    // ------------------------------------------------------------------ 6: never back between maneuvers

    private fun neverBack(b: Built) {
        // The named lane moves away from the exit's edge only where the road leaves no other way: where it leaves the band
        // and no lane the band held where the lane was named could have got there without such a move (the only lane
        // leaves at an earlier exit on the exit's side, or a fork just past the gore needs the lane that opens beyond it).
        for (move in b.plan.moves.filter { it.back }) {
            assertTrue(forcedBack(b, move), "${b.name}: the plan moves back at ${move.station} (${move.from} -> ${move.to}), where it need not")
            saw("forcedBack")
        }
        // While the named lane stays the same, its count from the exit's edge grows only where a lane opens, or may begin,
        // between it and that edge.
        for (i in 1 until b.plan.segments.size) {
            val before = b.plan.segments[i - 1]
            val seg = b.plan.segments[i]
            val count = seg.namedFromEdge ?: continue
            val last = before.namedFromEdge ?: continue
            if (seg.named != before.named || seg.edge != before.edge || seg.run != before.run || !sameRoad(b, before.from, seg.from)) continue
            assertTrue(count <= last || laneBeginsBetween(b, seg), "${b.name} at ${seg.from}: lane ${seg.named} counts $count from the edge after $last")
        }
    }

    /**
     * Whether the move [move] away from the exit's edge is one the road forces: from where the plan began naming lanes for
     * the maneuver ahead (its card window, or the maneuver before), the lanes a plan could hold without ever moving away
     * from the edge, stretch by stretch of the band, run out by the move's station.
     */
    private fun forcedBack(b: Built, move: LanePlan.Move): Boolean {
        val lanes = b.plan.topology.lanes
        val window = b.plan.windows.firstOrNull { move.station >= it.from && move.station <= it.to } ?: return false
        val turned = b.plan.topology.points.filter { it.turns && it.station < move.station }.maxOfOrNull { it.station } ?: Double.NEGATIVE_INFINITY
        val from = maxOf(window.from, turned)
        var held: Set<Int>? = null
        for (seg in b.plan.segments) {
            if (seg.to <= from || seg.from > move.station || !seg.inWindow || seg.noLane != null) continue
            val band = seg.band.toSet()
            val before = held
            held = if (before == null) {
                band
            } else {
                band.filter { l ->
                    before.any { r ->
                        r == l || when (seg.edge) {
                            LaneEdge.RIGHT -> lanes[l].slot >= lanes[r].slot
                            LaneEdge.LEFT -> lanes[l].slot <= lanes[r].slot
                            null -> true
                        }
                    }
                }.toSet()
            }
            if (held.isEmpty()) return true
        }
        return false
    }

    /** Whether at the start of [seg] a lane open to every car opens, or may begin, between its named lane and the exit's edge. */
    private fun laneBeginsBetween(b: Built, seg: PlanSegment): Boolean {
        val named = b.plan.topology.lanes[requireNotNull(seg.named)]
        return b.plan.topology.lanes.any { lane ->
            val begins = listOfNotNull(lane.opens, lane.possibleFrom).any { abs(it - seg.from) <= LanePlanner.GRID_METERS + 1e-9 }
            lane.run == named.run && !lane.designated && begins && if (seg.edge == LaneEdge.RIGHT) lane.slot > named.slot else lane.slot < named.slot
        }
    }

    // ------------------------------------------------------------------ 7: lanes open no earlier and leave the band no later than the rules

    private fun lanesOpenAndLeave(b: Built) {
        val topology = b.plan.topology
        for (lane in topology.lanes) {
            val addedAt = lane.addedAt ?: continue
            val point = topology.points[addedAt]
            val evidence = point.station
            if (lane.id in b.plan.named) {
                // A lane the plan names opens no earlier than its first evidence less its taper (a deceleration lane's own
                // length at an exit), and is not drawn before it opens.
                val node = b.route.nodes[point.node]
                val taper = if (LaneEvidence.planTurns(node)) {
                    LaneTopology.DECEL_LATE_FULL_METERS + LaneTopology.DECEL_TAPER_METERS
                } else {
                    LaneTopology.addedTaperMeters(PlanningSpeed.of(b.route.nodes[maxOf(0, point.node - 1)]), b.route.approachRoad(point.node))
                }
                assertTrue(lane.opens >= evidence - taper - 1e-6, "${b.name}: the named lane ${lane.id} opens at ${lane.opens}, its evidence at $evidence")
                saw("opens")
                val before = lane.opens - 1.0
                if (b.path.drawings.any { before > it.from && before < it.to }) {
                    val place = b.drawing.lanesAt(before).firstOrNull { it.lane == lane.id }
                    if (lane.pocket && lane.possibleFrom != null) {
                        // A turn pocket the plan names is drawn before it opens only as possible, from where it may begin.
                        assertTrue(place == null || place.state == CrossSection.State.POSSIBLE, "${b.name}: the named pocket ${lane.id} at $before, before it opens")
                        saw("pocketPossible")
                    } else {
                        assertTrue(place == null, "${b.name}: the named lane ${lane.id} drawn at $before, before it opens")
                    }
                }
            }
            val possible = lane.possibleFrom ?: continue
            if (lane.id in b.plan.named && !lane.pocket) continue
            // An exit-side lane the plan does not name, and a turn pocket, are drawn from where they may begin, as only possible
            // until they open.
            val at = possible + POSSIBLE_SHOWN_METERS
            if (at < lane.opens && b.path.drawings.any { at > it.from && at < it.to }) {
                val place = b.drawing.lanesAt(at).firstOrNull { it.lane == lane.id }
                assertTrue(place != null && place.state == CrossSection.State.POSSIBLE, "${b.name}: lane ${lane.id} is not drawn as possible at $at")
                saw("possible")
            }
            if (b.path.drawings.any { possible - 1.0 > it.from && possible - 1.0 < it.to }) {
                assertTrue(b.drawing.lanesAt(possible - 1.0).none { it.lane == lane.id }, "${b.name}: lane ${lane.id} drawn before it may begin")
            }
        }
        // A lane that ends leaves the band no later than where a comfortable change still takes the car out of it: its end less
        // the reaction and half a comfortable change (the planner keeps a lane until the car is half way across), a grid step or
        // two aside; not where the plan flags the band, where a maneuver comes first, or where a lane it feeds takes it over.
        for (lane in topology.lanes) {
            if (lane.closes >= topology.runs[lane.run].to - 1e-6) continue
            val inBand = b.plan.segments.filter { lane.id in it.band }
            if (inBand.isEmpty()) continue
            val lastIn = inBand.maxOf { it.to }
            val node = b.route.nodes.lastOrNull { it.station <= lane.closes } ?: continue
            val latest = lane.closes - PlanningSpeed.of(node) * (LanePlanner.REACTION_SECONDS + LanePlanner.COMFORTABLE_CHANGE_SECONDS / 2.0)
            val flagged = b.plan.flags.any { it.to > latest && it.from < lane.closes }
            val fed = topology.lanes.any { f -> f.fedBy == lane.id && b.plan.segmentAt(lastIn)?.band?.contains(f.id) == true }
            val maneuver = b.route.nodes.any { it.station > lastIn && it.station <= lane.closes && LaneEvidence.planTurns(it) }
            if (!flagged && !fed && !maneuver) {
                assertTrue(lastIn <= latest + 2.0 * LanePlanner.GRID_METERS, "${b.name}: lane ${lane.id} ends at ${lane.closes} and works up to $lastIn")
                saw("leaves")
            }
        }
    }

    // ------------------------------------------------------------------ 8: every drawn stretch begins and ends with a taper

    private fun tapersAtEveryEnd(b: Built) {
        val end = b.path.routeStations.last()
        val asphalt = b.features.filter { it.kind == Kind.ASPHALT }
        val interiors = b.drawing.junctionInteriors
        fun inside(s: Double) = interiors.any { s > it.start + 1e-6 && s < it.endInclusive - 1e-6 }
        for (d in b.path.drawings) {
            // From the map's band where the drawing begins, if the route does not begin with it; square at a street junction's far
            // side, where the junction's inside is the map's own road.
            val from = d.asphaltFrom
            // A street junction the drawing goes on through that it begins inside, or just past.
            val before = interiors.lastOrNull { it.start <= d.from + 1e-6 && it.endInclusive > from + 1e-6 }
            // A drawing before it on the route whose asphalt reaches where this one's begins, past a street no source tells of:
            // the asphalt there is that drawing's way out.
            val reached = b.path.drawings.any { it.from < d.from - 1e-6 && it.asphaltTo > from - 1e-6 }
            if (d.junctionStart) {
                assertEquals(d.from, from, 0.0, "${b.name}: the asphalt past a junction begins at its far side")
                assertTrue(asphalt.any { it.part == Part.ROUTE && abs(it.from - from) < 1e-6 }, "${b.name}: no asphalt from the junction's far side at $from")
                saw("junctionStart")
            } else if (reached) {
                assertTrue(asphalt.any { it.part == Part.ROUTE && it.from <= d.from + 1e-6 && it.to > d.from } || inside(d.from), "${b.name}: no asphalt where the drawing begins at ${d.from}")
                saw("beginsInAnother")
            } else if (before != null) {
                // Square at that junction's far side, with nothing of it before.
                val first = asphalt.filter { it.part == Part.ROUTE && it.to > from + 1e-6 && it.from < before.endInclusive + 1e-6 }.minByOrNull { it.from }
                assertEquals(before.endInclusive, requireNotNull(first) { "${b.name}: no asphalt past the junction at ${before.endInclusive}" }.from, 1e-6, "${b.name}: the asphalt where the drawing begins at ${d.from}")
                saw("beginsPastJunction")
            } else if (from > 0.0) {
                val first = requireNotNull(asphalt.firstOrNull { it.part == Part.ROUTE && abs(it.from - from) < 1e-6 }) { "${b.name}: no asphalt from $from" }
                assertTrue(first.to > d.from || interiors.any { abs(it.start - first.to) < 1e-6 }, "${b.name}: the asphalt from $from ends at ${first.to}")
                assertEquals(bandAt(b, from), width(b.drawing.asphaltAt(from)), 1e-6, "${b.name}: the asphalt's width where it begins at $from")
                saw("begins")
            }
            if (!inside(d.from)) {
                assertEquals(carriageway(b, d.from), width(b.drawing.asphaltAt(d.from)), 1e-6, "${b.name}: the asphalt's width where the drawing begins at ${d.from}")
            }
            if (d.junctionEnd) {
                // At a street junction the carriageway ends square at its near side, at the drawn lanes' own width, and nothing
                // of the drawing's own lies past it but the next drawing beyond the junction; or at the near side of a junction
                // it goes on through just before.
                val near = interiors.firstOrNull { it.start < d.carriagewayTo - 1e-6 && it.endInclusive > d.carriagewayTo - 1e-6 }?.start ?: d.carriagewayTo
                val main = asphalt.filter { it.part == Part.ROUTE && it.from < d.carriagewayTo && it.to > d.from }.maxByOrNull { it.to } ?: continue
                assertEquals(near, main.to, 1e-6, "${b.name}: the asphalt before the junction at ${d.to}")
                assertEquals(carriageway(b, near), width(b.drawing.asphaltAt(near)), 1e-6, "${b.name}: square at the junction's near side")
                assertTrue(
                    asphalt.none { it.from > d.carriagewayTo + 1e-6 && it.from < d.to + RoutePath.JUNCTION_FAR_SIDE_METERS - 1e-6 },
                    "${b.name}: asphalt inside the junction at ${d.to}",
                )
                assertTrue(b.drawing.lanesAt(d.carriagewayTo + 0.5).isEmpty(), "${b.name}: lanes inside the junction at ${d.to}")
                saw("junctionEnd")
            } else if (d.turnsAtEnd) {
                // At a turn the stub and the lanes beyond take the asphalt over across the same cut.
                val main = asphalt.filter { it.part == Part.ROUTE && it.from < d.to && it.to > d.from }.maxByOrNull { it.to } ?: continue
                assertTrue(
                    asphalt.any { it.part != Part.ROUTE && abs(it.from - main.to) < 1e-6 },
                    "${b.name}: nothing takes the asphalt over at ${main.to}, where the drawing ending at ${d.to} turns",
                )
                saw("turns")
            } else if (d.asphaltTo < end && !inside(d.asphaltTo)) {
                // Into the map's band where it ends on its road.
                assertTrue(asphalt.any { it.part == Part.ROUTE && abs(it.to - d.asphaltTo) < 1e-6 }, "${b.name}: no asphalt to ${d.asphaltTo}")
                assertEquals(bandAt(b, d.asphaltTo - 1e-3), width(b.drawing.asphaltAt(d.asphaltTo)), 1e-6, "${b.name}: the asphalt's width where it ends at ${d.asphaltTo}")
                saw("ends")
            }
        }
        // The path leaves the road's line where a drawing begins and is back on it where the drawing's path ends.
        val reach = CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS + RoutePath.RETURN_METERS
        val sorted = b.path.drawings.sortedBy { it.from }
        for ((i, d) in sorted.withIndex()) {
            val afterAnother = i > 0 && d.from <= sorted[i - 1].back + reach
            val beforeAnother = i < sorted.size - 1 && sorted[i + 1].from <= d.back + reach
            if (!afterAnother) assertEquals(0.0, b.path.offsetAt(d.from), END_METERS, "${b.name}: the path off the road's line where a drawing begins at ${d.from}")
            if (!beforeAnother && d.back < end) assertEquals(0.0, b.path.offsetAt(d.back), END_METERS, "${b.name}: the path off the road's line at ${d.back}")
        }
    }

    // ------------------------------------------------------------------ 9: inside a card window never away from the exit's edge

    private fun neverAwayFromTheExit(b: Built) {
        val excluded = b.path.moves.map { it.from - 1e-6 to it.to + 1e-6 } + turns(b) + folds(b)
        for (window in b.plan.windows) {
            val doubtful = b.plan.moves.any { it.back && it.station >= window.from && it.station <= window.to } ||
                b.plan.flags.any { it.kind == LanePlan.Flag.Kind.CONFLICT && it.to > window.from && it.from < window.to }
            if (doubtful) continue
            var last: Double? = null
            var lastStation = window.from
            var station = window.from
            while (station < window.to) {
                val seg = b.plan.segmentAt(station)
                val drawn = b.path.drawings.any { station >= it.from && station < it.carriagewayTo }
                if (seg != null && seg.inWindow && seg.named != null && seg.noLane == null && drawn && excluded.none { station >= it.first && station <= it.second }) {
                    val offset = b.path.offsetAt(station)
                    val sign = edgeSign(b, station) ?: 0.0
                    val prev = last
                    if (prev != null && sign != 0.0 && station - lastStation < 1.5 && sameRoad(b, lastStation, station)) {
                        assertTrue((offset - prev) * sign >= -1e-9, "${b.name} at $station: away from the exit's edge ($prev to $offset)")
                        saw("toward")
                    }
                    last = offset
                    lastStation = station
                } else {
                    last = null
                }
                station += 1.0
            }
        }
    }

    // ------------------------------------------------------------------ 10: never the drawn edge lane while a lane may lie beyond it

    private fun notTheEdgeLaneWhileOneMayBeBeyond(b: Built) {
        val topology = b.plan.topology
        for (window in b.plan.windows) {
            for (x in along(window.from, window.to, 5.0)) {
                val seg = b.plan.segmentAt(x) ?: continue
                val named = seg.named ?: continue
                val edge = seg.edge ?: continue
                if (b.path.drawings.none { x >= it.from && x < it.carriagewayTo }) continue
                val run = topology.lanes[named].run
                val beyond = topology.lanes.any { lane ->
                    val from = lane.possibleFrom
                    from != null && lane.run == run && lane.id !in b.plan.named && x >= from + POSSIBLE_SHOWN_METERS && x < lane.opens
                }
                if (!beyond) continue
                val lanes = b.drawing.lanesAt(x)
                val outer = if (edge == LaneEdge.RIGHT) lanes.lastOrNull() else lanes.firstOrNull()
                assertTrue(outer != null && outer.lane != named, "${b.name} at $x: the named lane $named is the drawn edge lane while a lane may lie beyond it")
                saw("beyond")
            }
        }
    }

    // ------------------------------------------------------------------ 3, 4, 10: the lit lane and the tints

    private fun litAndTints(b: Built) {
        val lanes = b.plan.topology.lanes
        val ids = HashSet<Long>()
        val turning = turns(b) + folds(b)
        val moving = b.path.moves.map { it.from - 1e-6 to it.to + 1e-6 }
        // Across a street junction into the receiving road's lane the lit lane goes on to the junction's far side.
        val crossings = b.path.junctions.filter { it.receiving != null }.map { it.from to it.to }
        val windows = merged(b.plan.windows.map { it.from to it.to } + crossings)
        val drawn = merged(b.path.drawings.map { it.from to it.to } + crossings)
        for (f in b.features) {
            if (f.kind != Kind.LIT && f.kind != Kind.TINT && f.kind != Kind.LIT_EDGE) continue
            // Only inside card windows, and on our asphalt or across a junction into the receiving road's lane.
            assertTrue(windows.any { f.from >= it.first - 1e-6 && f.to <= it.second + 1e-6 }, "${b.name}: a ${f.kind} over ${f.from}..${f.to} outside the card windows")
            assertTrue(drawn.any { f.from >= it.first - 1e-6 && f.to <= it.second + 1e-6 }, "${b.name}: a ${f.kind} over ${f.from}..${f.to} outside the drawings")
            if (f.kind == Kind.TINT) assertTrue(crossings.none { f.to > it.first + 1e-6 && f.from < it.second - 1e-6 }, "${b.name}: a tint across a junction at ${f.from}")
            val lane = lanes[requireNotNull(f.lane)]
            // 3: never a designated lane.
            assertFalse(lane.designated, "${b.name}: a ${f.kind} on the designated lane ${lane.id}")
            if (f.kind == Kind.LIT_EDGE) {
                assertEquals(null, f.id)
                continue
            }
            // In pieces whose ids are the same in every window.
            val id = requireNotNull(f.id) { "${b.name}: a ${f.kind} piece without an id" }
            assertTrue(ids.add(id), "${b.name}: two pieces with the id $id")
            val piece = LaneDrawing.pieceOf(f.from)
            // 50 m pieces from the route's start.
            assertTrue(f.to <= (piece + 1) * 50.0 + 1e-6 && f.from >= piece * 50.0 - 1e-6, "${b.name}: a ${f.kind} piece over ${f.from}..${f.to}")
            assertEquals(if (f.kind == Kind.LIT) LaneDrawing.litId(piece) else LaneDrawing.tintId(lane.id, piece), id)
            // Each part of a piece (a piece may hold two, where its lane is lit or tinted on both sides of a gap) at its middle.
            val middles = f.parts.map { ring ->
                val stations = ring.map { DrawingProbe.project(b.path.axes, (f.from + f.to) / 2.0, it).first }
                (stations.min() + stations.max()) / 2.0
            }
            val middle = (f.from + f.to) / 2.0
            for (x in middles) {
                // Across a street junction the lit lane is the turn's own, checked apart ([litTurnIntoTheReceivingLane]); a part
                // along the junction's curve projects onto the road's axis a little off the curve's own stations.
                if (crossings.any { x > it.first - SQUARE_SEARCH_METERS && x < it.second + SQUARE_SEARCH_METERS }) continue
                val seg = requireNotNull(b.plan.segmentAt(x))
                if (f.kind == Kind.TINT) {
                    val place = b.drawing.lanesAt(x).firstOrNull { it.lane == lane.id }
                    if (lane.pocket && lane.fedBy == seg.named && lane.id in b.plan.named && place?.state == CrossSection.State.POSSIBLE) {
                        // A turn pocket the plan names later, where it may already be beside the lit lane that feeds it.
                        assertTrue(x < lane.opens && x >= requireNotNull(lane.possibleFrom), "${b.name}: the pocket ${lane.id} tinted at $x")
                        saw("pocketTint")
                    } else {
                        // A lane that still works beside the lit one, drawn as there, where the data gives the lanes.
                        assertTrue(lane.id in seg.band && lane.id != seg.named && seg.noLane == null, "${b.name}: lane ${lane.id} tinted at $x")
                        assertTrue(place != null && place.state != CrossSection.State.POSSIBLE, "${b.name}: lane ${lane.id} tinted at $x where it is not drawn as there")
                    }
                    val stretch = b.evidence.stretches.firstOrNull { x >= it.from && x < it.to }
                    assertTrue(stretch != null && (stretch.kind == LaneEvidence.Kind.KNOWN || stretch.kind == LaneEvidence.Kind.BRIDGED), "${b.name}: a tint at $x on a ${stretch?.kind} stretch")
                } else {
                    assertTrue(seg.named != null && seg.noLane == null, "${b.name}: lit at $x where no lane is named")
                }
            }
            if (f.kind == Kind.TINT) {
                saw("tint")
                continue
            }
            // 4: the lit lane is the lane the plan names, and the path's: every corner lies half that lane's width from the
            // path, square to it (the turns, where the lanes go on straight, and the folds aside).
            if (turning.any { it.first < f.to && it.second > f.from }) continue
            val road = b.section.roadAt(f.from) ?: b.route.nodes.last { it.station <= f.from }.road
            val half = CrossSection.laneMeters(road, lane.kinds) / 2.0
            for (p in f.parts.flatten()) {
                val (s, _) = DrawingProbe.project(b.path.axes, middle, p)
                // Not within the foot's search of a street junction's curve, where the path is that curve, not an offset.
                if (b.path.junctions.any { s > it.from - SQUARE_SEARCH_METERS && s < it.to + SQUARE_SEARCH_METERS }) continue
                assertEquals(half, squareFromPath(b, p, s), LIT_CORNER_METERS, "${b.name}: a corner of the lit lane at $s")
            }
            saw("lit")
        }
        // 10: the lit lane, where it lies in a lane (outside moves), is never the drawn edge lane while a lane may lie beyond it.
        for (f in b.features.filter { it.kind == Kind.LIT }) {
            for (x in along(f.from, f.to, 5.0)) {
                if (moving.any { x >= it.first && x <= it.second } || turning.any { x >= it.first && x <= it.second }) continue
                val seg = b.plan.segmentAt(x) ?: continue
                val edge = seg.edge ?: continue
                val run = lanes[seg.named ?: continue].run
                val beyond = lanes.any { lane ->
                    val from = lane.possibleFrom
                    from != null && lane.run == run && lane.id !in b.plan.named && x >= from + POSSIBLE_SHOWN_METERS && x < lane.opens
                }
                if (!beyond) continue
                val drawn = b.drawing.lanesAt(x)
                val offset = b.path.offsetAt(x)
                val under = drawn.firstOrNull { offset <= it.left + 1e-9 && offset >= it.right - 1e-9 }
                val outer = if (edge == LaneEdge.RIGHT) drawn.lastOrNull() else drawn.firstOrNull()
                assertTrue(under != null && outer != null && under.lane != outer.lane, "${b.name} at $x: the lit lane is the drawn edge lane while a lane may lie beyond it")
                saw("litBeyond")
            }
        }
        // The wedges: a tint whose lane stops working where it ends (or in the zone that begins there) narrows to nothing at
        // its edge toward the lit lane.
        for ((lane, pieces) in b.features.filter { it.kind == Kind.TINT }.groupBy { requireNotNull(it.lane) }) {
            for ((from, to) in merged(pieces.map { it.from to it.to })) {
                val after = to + 1e-3
                val seg = b.plan.segmentAt(after) ?: continue
                val zone = b.evidence.stretches.firstOrNull { after >= it.from && after < it.to && it.kind == LaneEvidence.Kind.ZONE }
                val stops = if (zone != null) {
                    b.plan.segments.any { it.from < zone.to && it.to > after && it.named != null && it.noLane == null && lane !in it.band }
                } else {
                    seg.named != null && seg.noLane == null && lane !in seg.band && b.drawing.lanesAt(after).any { it.lane == lane }
                }
                if (!stops) continue
                val x = to - WEDGE_TIP_METERS
                // Inside a street junction the drawing goes on through no tint is drawn; the wedge goes on past it.
                if (b.drawing.junctionInteriors.any { x > it.start && x < it.endInclusive }) continue
                val place = b.drawing.lanesAt(x).firstOrNull { it.lane == lane } ?: continue
                val across = DrawingProbe.across(requireNotNull(b.path.axisAt(x)), x, pieces.filter { x >= it.from && x <= it.to }.flatMap { it.parts })
                assertEquals(2, across.size, "${b.name}: lane $lane's tint at $x")
                // Over the last 110 m of the tint, or all of it where it is shorter.
                val wedge = minOf(110.0, to - from)
                if (wedge >= WEDGE_SAMPLED_METERS) {
                    val expected = place.width * (1.0 - CrossSection.ramp(x, to - wedge, to))
                    assertEquals(expected, across[1] - across[0], RoutePath.TOLERANCE_METERS, "${b.name}: lane $lane's tint at $x, where it stops working at $to")
                } else {
                    // A tint of a metre or two, between the samples a metre apart: narrower than its lane all the same.
                    assertTrue(across[1] - across[0] < place.width, "${b.name}: lane $lane's tint at $x, where it stops working at $to")
                }
                // Its near edge stays on the lane's side toward the lit lane (a lane width from the far one), simplified like the path.
                if (place.centre > b.path.offsetAt(x)) {
                    assertEquals(place.right, across[0], RoutePath.TOLERANCE_METERS, "${b.name}: lane $lane's wedge at $x, left of the lit lane")
                } else {
                    assertEquals(place.left, across[1], RoutePath.TOLERANCE_METERS, "${b.name}: lane $lane's wedge at $x, right of the lit lane")
                }
                saw("wedge")
            }
        }
    }

    /**
     * How far [p] lies from the path, square to the path's own way: from the path's point where the line to [p] is square to
     * the path, found near the station [near].
     */
    private fun squareFromPath(b: Built, p: GeoPoint, near: Double): Double {
        val v = SceneLines.local(p)
        val first = b.path.stations.first()
        val last = b.path.stations.last()
        fun point(s: Double): LocalPoint {
            val axis = b.path.axisAt(s)
            if (axis != null) return SceneLines.local(axis.toGeo(axis.offset(s, b.path.offsetAt(s))))
            // Away from the drawings the path is the route's own line.
            val st = b.path.routeStations
            val k = (1 until st.size).firstOrNull { st[it] >= s } ?: (st.size - 1)
            val a = SceneLines.local(b.path.route[k - 1])
            val c = SceneLines.local(b.path.route[k])
            val f = if (st[k] > st[k - 1]) ((s - st[k - 1]) / (st[k] - st[k - 1])).coerceIn(0.0, 1.0) else 0.0
            return LocalPoint(a.x + (c.x - a.x) * f, a.y + (c.y - a.y) * f)
        }
        fun ahead(s: Double): Double {
            val a = point(s - SLOPE_METERS)
            val c = point(s + SLOPE_METERS)
            val m = point(s)
            return (v.x - m.x) * (c.x - a.x) + (v.y - m.y) * (c.y - a.y)
        }
        var lo = maxOf(first + SLOPE_METERS, near - SQUARE_SEARCH_METERS)
        var hi = minOf(last - SLOPE_METERS, near + SQUARE_SEARCH_METERS)
        repeat(SQUARE_STEPS) {
            val mid = (lo + hi) / 2.0
            if (ahead(mid) > 0.0) lo = mid else hi = mid
        }
        return v.distanceTo(point((lo + hi) / 2.0))
    }

    /** [spans] joined where they meet or overlap, in order. */
    private fun merged(spans: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (span in spans.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && span.first <= last.second + 1e-6) out[out.size - 1] = last.first to maxOf(last.second, span.second) else out.add(span)
        }
        return out
    }

    // ------------------------------------------------------------------ 4, 10: the lit lane is the named lane, on our lanes

    /**
     * Wherever a lane is lit, the lit lane lies on the drawn lanes, and outside the path's moves its middle, the path, lies
     * within half a lane of the named lane's drawn middle (in its feeder while a lane its neighbour feeds opens): at a ramp's
     * start too, where the lanes go on straight, and at a gore. Sampled a metre apart inside each lit stretch.
     */
    private fun litOnTheNamedLane(b: Built) {
        val lanes = b.plan.topology.lanes
        val moving = b.path.moves.map { it.from - 1e-6 to it.to + 1e-6 }
        val crossings = b.path.junctions.filter { it.receiving != null }
        for (run in b.drawing.litStretches()) {
            for (x in along(run.start + LIT_END_METERS, run.endInclusive - LIT_END_METERS, 1.0)) {
                // Across a street junction the lit lane is the turn's own, checked apart ([litTurnIntoTheReceivingLane]).
                if (crossings.any { x >= it.from - 1e-6 && x <= it.to + 1e-6 }) continue
                val named = requireNotNull(b.plan.segmentAt(x)?.named) { "${b.name}: lit at $x where no lane is named" }
                val places = b.drawing.lanesAt(x)
                assertTrue(places.isNotEmpty(), "${b.name}: lit at $x where no lane is drawn")
                val road = b.section.roadAt(x) ?: b.route.nodes.last { it.station <= x }.road
                val half = CrossSection.laneMeters(road, lanes[named].kinds) / 2.0
                val t = b.path.offsetAt(x)
                val beyond = maxOf(t + half - places.first().left, places.last().right - (t - half))
                assertTrue(beyond <= LaneDrawing.LIT_FIT_METERS + LIT_SLACK_METERS, "${b.name} at $x: the lit lane $beyond m past the drawn lanes")
                saw("litOnLanes")
                if (moving.any { x >= it.first && x <= it.second }) continue
                val place = places.firstOrNull { it.lane == named }
                val feeder = lanes[named].fedBy?.let { f -> places.firstOrNull { it.lane == f } }
                val late = b.section.lateEntry(named, x)
                if (feeder != null && place != null && late != null && late < 1.0) {
                    // Into a turn pocket along its late taper, from its feeder's middle to its own.
                    val expected = feeder.centre * (1.0 - late) + place.centre * late
                    assertEquals(expected, t, RoutePath.TOLERANCE_METERS + LIT_SLACK_METERS, "${b.name} at $x: the path entering pocket $named")
                    saw("pocketEntry")
                    continue
                }
                if (feeder != null && (place == null || place.width < 2.0 * half - 1e-6)) {
                    // Along a lane's taper, from its feeder.
                    val left = maxOf(place?.left ?: feeder.left, feeder.left)
                    val right = minOf(place?.right ?: feeder.right, feeder.right)
                    assertTrue(t <= left + LIT_SLACK_METERS && t >= right - LIT_SLACK_METERS, "${b.name} at $x: the lit lane off lane $named and its feeder")
                    continue
                }
                assertTrue(place != null, "${b.name}: lane $named lit at $x, not drawn there")
                val off = abs(t - requireNotNull(place).centre)
                assertTrue(off <= half + RoutePath.TOLERANCE_METERS + LIT_SLACK_METERS, "${b.name} at $x: the lit lane's middle $off m from lane $named's")
                saw("litInLane")
            }
        }
    }

    // ------------------------------------------------------------------ 4, 10: where the named lane goes on, the lit lane goes on

    /**
     * Between two lit stretches inside one card window and one drawing, where the plan names one lane all along, nothing is
     * dark: across a fold the path keeps its lane and the lit lane goes on with it, and a move out of a lane that leaves at
     * the edge with no taper is done by where it leaves, so the path never lies off the asphalt there.
     */
    private fun litGoesOn(b: Built) {
        val windows = merged(b.plan.windows.map { it.from to it.to })
        val lit = b.drawing.litStretches()
        for ((a, c) in lit.zipWithNext()) {
            val from = a.endInclusive
            val to = c.start
            if (windows.none { from >= it.first - 1e-6 && to <= it.second + 1e-6 }) continue
            if (b.path.drawings.none { from >= it.from && to <= it.carriagewayTo }) continue
            val between = b.plan.segments.filter { it.to > from && it.from < to }
            val named = between.firstOrNull()?.named ?: continue
            val goesOn = between.all { it.named == named && it.noLane == null && it.inWindow }
            assertFalse(goesOn, "${b.name}: lane $named named all along, dark from $from to $to")
        }
        for (fold in b.path.folds.filter { !it.toLine }) {
            if (lit.any { fold.from > it.start && fold.to < it.endInclusive }) saw("litOverFold")
        }
        for (move in b.path.moves.filter { it.kind == RoutePath.Move.Kind.LANE }) {
            if (b.plan.topology.lanes.any { abs(it.closes - move.to) < 1e-6 } && lit.any { move.to > it.start && move.to < it.endInclusive }) saw("litPastEdge")
        }
    }

    // ------------------------------------------------------------------ 1, 2: a gore reached on the road's line is bare

    /**
     * Where the path reaches a gore on the road's line (no lane named on the approach, C1), the route geometry's diagonal runs
     * across the old road's lanes: past the gore no line of ours is drawn on the stub or the lanes beyond, none on the route's
     * carriageway along the path's diagonal past a ramp's start, nothing is lit there, the app lays no maneuver arrow of its
     * own there, and the path crosses none of our lines from the gore to the stub's end.
     */
    private fun bareWhereReachedOnTheLine(b: Built) {
        val lines = b.features.filter { it.kind == Kind.LANE_LINE || it.kind == Kind.EDGE_LINE || it.kind == Kind.CHANNEL_LINE }
        // A street junction is no gore: the carriageway ends at its near side.
        val gores = b.section.rampStarts.map { it.station } + b.path.drawings.filter { it.turnsAtEnd && !it.junctionEnd }.map { it.to }
        for (g in gores) {
            if (b.plan.segmentAt(g - 1e-6)?.noLane == null || b.path.drawings.none { g > it.from && g <= it.to }) continue
            assertTrue(b.drawing.gores().none { abs(it - g) < GoreArrow.SAME_PLACE_METERS }, "${b.name}: the app's own arrow at the gore at $g")
            val stubEnd = g + LaneDrawing.STUB_METERS + RoutePath.ASPHALT_TAPER_METERS
            for (f in lines) {
                if (f.part != Part.ROUTE) assertFalse(f.from >= g - 1e-6 && f.from <= stubEnd, "${b.name}: a ${f.part} ${f.kind} at the gore at $g")
            }
            val diagonal = b.path.diagonals.firstOrNull { abs(it.from - g) < 1e-6 }
            if (diagonal != null) {
                for (f in lines.filter { it.part == Part.ROUTE }) {
                    assertFalse(f.to > diagonal.from + 1e-6 && f.from < diagonal.to - 1e-6, "${b.name}: a ${f.kind} ${f.between ?: f.side} on the diagonal ${diagonal.from}..${diagonal.to}")
                }
                assertTrue(b.drawing.litStretches().none { it.endInclusive > diagonal.from + 1e-6 && it.start < diagonal.to - 1e-6 }, "${b.name}: lit on the diagonal")
                saw("bareRamp")
            }
            // The path from the gore on, against every line of ours there.
            val path = b.path.points.indices.filter { b.path.stations[it] >= g && b.path.stations[it] <= maxOf(stubEnd, diagonal?.to ?: g) }
                .map { SceneLines.local(b.path.points[it]) }
            for (f in lines) {
                for (part in f.parts) {
                    assertFalse(crosses(path, part.map { SceneLines.local(it) }), "${b.name}: the path crosses a ${f.part} ${f.kind} ${f.between ?: f.side} past the gore at $g")
                }
            }
            saw("bareGore")
        }
    }

    /** Whether the polylines [a] and [b] cross, each segment's ends at least [CROSS_CLEAR_METERS] off the other's line. */
    private fun crosses(a: List<LocalPoint>, b: List<LocalPoint>): Boolean {
        fun side(p: LocalPoint, q: LocalPoint, r: LocalPoint): Double {
            val len = p.distanceTo(q)
            return if (len < 1e-9) 0.0 else ((q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)) / len
        }
        fun opposite(u: Double, v: Double) = (u > CROSS_CLEAR_METERS && v < -CROSS_CLEAR_METERS) || (u < -CROSS_CLEAR_METERS && v > CROSS_CLEAR_METERS)
        for (i in 0 until a.size - 1) {
            for (j in 0 until b.size - 1) {
                if (opposite(side(b[j], b[j + 1], a[i]), side(b[j], b[j + 1], a[i + 1])) && opposite(side(a[i], a[i + 1], b[j]), side(a[i], a[i + 1], b[j + 1]))) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ helpers

    /** Stations from [from] to [to] every [step] metres, and one just before [to]. */
    private fun along(from: Double, to: Double, step: Double): List<Double> {
        if (to <= from) return emptyList()
        val out = ArrayList<Double>()
        var x = from
        while (x < to) {
            out.add(x)
            x += step
        }
        out.add(to - 1e-4)
        return out
    }

    private fun width(edges: Pair<Double, Double>?): Double = requireNotNull(edges).let { it.first - it.second }

    /** The width of the drawn carriageway at [station] with its aprons. */
    private fun carriageway(b: Built, station: Double): Double {
        val lanes = b.drawing.lanesAt(station)
        // On a two-way street the oncoming half beside the route's lanes.
        val left = b.section.oncomingEdge(station, b.path.axisAt(station)) ?: lanes.first().left
        return left - lanes.last().right + 2.0 * CrossSection.APRON_METERS
    }

    /** The map's own band of the road at [station]. */
    private fun bandAt(b: Built, station: Double): Double {
        return when (b.route.nodes.lastOrNull { it.station <= station }?.road ?: RoadKind.FREEWAY) {
            RoadKind.FREEWAY -> LaneDrawing.STANDARD_FREEWAY_BAND_METERS
            RoadKind.RAMP -> LaneDrawing.STANDARD_RAMP_BAND_METERS
            RoadKind.STREET -> LaneDrawing.STANDARD_STREET_BAND_METERS
        }
    }

    /** The stretches where the route turns onto another road with lanes, and the path's turns: the path's rules do not hold across them. */
    private fun turns(b: Built): List<Pair<Double, Double>> = b.section.rampStarts.map {
        it.station - 60.0 to it.station + CrossSection.RAMP_START_MAX_METERS + CrossSection.RAMP_BLEND_METERS
    } + b.path.moves.filter { it.kind == RoutePath.Move.Kind.TURN }.map { it.from to it.to }

    /** The folds the path keeps to the road's line through, with the blends around them. */
    private fun folds(b: Built): List<Pair<Double, Double>> =
        b.path.folds.filter { it.toLine }.map { it.from - RoutePath.RETURN_METERS - RoadAxis.SAMPLE_METERS to it.to + RoutePath.RETURN_METERS + RoadAxis.SAMPLE_METERS }

    /** Which approach [station] lies on: the index of the next maneuver the plan counts. */
    private fun approachOf(b: Built, station: Double): Int = b.route.nodes.indexOfFirst { it.station >= station && LaneEvidence.planTurns(it) }

    private fun sameRoad(b: Built, a: Double, c: Double) = b.route.nodes.none { it.station > a && it.station <= c && LaneEvidence.planTurns(it) }

    /** The direction toward the exit's edge at [station]: left positive. */
    private fun edgeSign(b: Built, station: Double): Double? = when (b.plan.segmentAt(station)?.edge) {
        LaneEdge.RIGHT -> -1.0
        LaneEdge.LEFT -> 1.0
        null -> null
    }

    /** The offset, left positive, of [p] from [axis] at [station]. */
    private fun offsetOf(axis: RoadAxis, station: Double, p: GeoPoint): Double {
        val q = axis.toLocal(p)
        val place = axis.at(station)
        return (q.x - place.point.x) * place.nx + (q.y - place.point.y) * place.ny
    }

    /**
     * Scenes of the geometry's tests beyond [LaneScenes.all]: lanes whose kinds change, possible lanes beside an edge, a bicycle
     * lane, a lane that leaves at the edge while the path moves out of it, a fork's run-up only the latest changes meet.
     */
    private fun extraScenes(): Map<String, LaneRoute> = linkedMapOf(
        "right-lane-for-buses" to LaneScenes.rightLaneForBuses(),
        "third-lane-for-car-pools" to LaneScenes.thirdLaneForCarPools(),
        "exit-side-gain" to LaneScenes.exitSideGain(),
        "leaves-beside-possible" to LaneScenes.leavesBesidePossible(),
        "bicycle-lane" to bicycleLane(),
        "carpool-beyond-split" to carpoolBeyondSplit(200.0),
        "carpool-beyond-split-far" to carpoolBeyondSplit(450.0),
        "edge-leaves-mid-move" to LaneScenes.edgeLeavesMidMove(),
        "tight-fork-before-exit" to LaneScenes.tightForkBeforeExit(),
    )

    /** The street scenes of the geometry's tests. */
    private fun streetScenes(): Map<String, LaneRoute> = linkedMapOf(
        "street-turn" to LaneScenes.streetTurn(),
        "street-turn-bicycle-bus" to LaneScenes.streetTurn(bicycle = true, bus = true),
        "street-turn-receiving" to LaneScenes.streetTurn(receiving = true),
        "street-pocket" to LaneScenes.streetPocket(),
        "junction-pocket" to LaneScenes.junctionPocket(),
        "left-beside-bicycles" to LaneScenes.leftBesideBicycles(),
        "street-u-turn" to LaneScenes.streetUTurn(),
        "street-roundabout" to LaneScenes.roundabout(),
        "street-onto-freeway" to LaneScenes.streetOntoFreeway(),
        "freeway-onto-street" to LaneScenes.freewayOntoStreet(),
    )

    /**
     * [LaneScenes.carpoolBeyond]'s left exit beyond a car-pool lane, no lane named on its approach, with lane data on its ramp:
     * two lanes the ramp splits into [split] metres past the gore, the route keeping left. Within its run-up's reach of the
     * gore, the split's card window names the ramp's left lane from the gore on.
     */
    private fun carpoolBeyondSplit(split: Double): LaneRoute = LaneScenes.approach(
        listOf(LaneScenes.at(1_500.0, LaneScenes.hov(), s(), s(), s())),
        exit = listOf(LaneScenes.sl(), LaneScenes.hov(), s(false), s(false), s(false)),
        maneuver = LaneScenes.OFF_LEFT,
        rampPoints = listOf(split to LaneScenes.NodeAndLanes(listOf(LaneScenes.sl(true, true), sr(false)), maneuver = LaneScenes.FORK_LEFT)),
        rampMeters = split + 400.0,
        id = "carpool-beyond-split",
    )

    /** Three lanes and, on their left, a lane only for bicycles, 1,600 and 900 m before a right exit from the right lane. */
    private fun bicycleLane(): LaneRoute {
        val bike = RouteLane(setOf(TurnModifier.STRAIGHT), valid = false, active = false, kinds = setOf(DesignatedKind.BICYCLE))
        return LaneScenes.approach(
            listOf(At(1_600.0, listOf(bike, s(), s(), s())), At(900.0, listOf(bike, s(), s(), s()))),
            exit = listOf(bike, s(false), s(false), sr()),
            id = "bicycle-lane",
        )
    }

    private companion object {
        const val RANDOM_SEED = 20261112
        const val RANDOM_ROUTES = 60
        const val KEPT_SEED = 7
        const val KEPT_ROUTES = 200

        /** Seeded random streets, and how many. */
        const val STREET_SEED = 61
        const val STREET_ROUTES = 120

        /** Seeded random streets whose sources tell street by street, and how many. */
        const val WAYS_SEED = 62
        const val WAYS_ROUTES = 120

        /** How far across the road a feature crossing a junction's inside is looked for. */
        const val INTERIOR_REACH_METERS = 30.0

        /** The maneuvers into, round and out of a roundabout... */
        val ROUNDABOUTS = setOf(ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY, ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)

        /** ...and out of one. */
        val ROUNDABOUT_EXITS = setOf(ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)

        /** A second seed of approaches, and how many. */
        const val OPENING_SEED = 232
        const val OPENING_ROUTES = 100

        /** A lit stretch is sampled from this far inside each of its ends: they lie where the light is checked a metre apart. */
        const val LIT_END_METERS = 1.0

        /** The lit lane is checked between the stations it was made at: it may move this much more there. */
        const val LIT_SLACK_METERS = 0.05

        /** Two lines cross where each one's ends lie at least this far off the other: not where they run on together. */
        const val CROSS_CLEAR_METERS = 0.11

        /** A lane line is checked this often along its run. */
        const val LINE_SAMPLE_METERS = 2.0

        /**
         * Two lines across the road lie at least this far apart: a line beside a lane is drawn only where the lane is half its
         * width or more (1.83 m on a freeway, a lane for bicycles 1.8 m), while a double line's two lines lie a line or two
         * apart.
         */
        const val DOUBLE_LINE_METERS = 1.0

        /** A possible lane opens from nothing along its taper: it is checked this far past where it may begin. */
        const val POSSIBLE_SHOWN_METERS = 5.0

        /** A lane entered along its taper is narrower than any drawn lane for this far after it opens. */
        const val OPENING_METERS = 1.0

        /** A lit lane's corner lies half a lane from the path, square to it, within this. */
        const val LIT_CORNER_METERS = 0.005

        /** A wedge at least this long is sampled finely enough to follow its curve. */
        const val WEDGE_SAMPLED_METERS = 12.0

        /** The path's way is read this far before and after a station, as the drawing reads it. */
        const val SLOPE_METERS = 0.25

        /** A corner's foot on the path is looked for this far about where it projects onto the road's axis... */
        const val SQUARE_SEARCH_METERS = 4.0

        /** ...in this many halvings. */
        const val SQUARE_STEPS = 40

        /** A wedge is measured this far before its tip. */
        const val WEDGE_TIP_METERS = 0.37

        /** The path meets the road's line at a drawing's ends within this. */
        const val END_METERS = 0.01

        /**
         * Where no lane is named the path lies half a lane inside our edge lines to within this: the move that puts it there is
         * worked out every [CrossSection.SHIFT_STEP_METERS] and straight between, as the edges curve along their tapers.
         */
        const val ROAD_LINE_SLACK_METERS = 0.05
    }
}
