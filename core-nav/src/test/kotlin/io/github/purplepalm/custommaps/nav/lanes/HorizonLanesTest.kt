package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.lanes.LaneEvidence.Kind
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.EXIT_LEFT
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.GORE
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.at
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.bare
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.hov
import io.github.purplepalm.custommaps.nav.lanes.LaneScenes.s
import io.github.purplepalm.custommaps.nav.lanes.geometry.CrossSection
import io.github.purplepalm.custommaps.nav.lanes.geometry.SceneLines
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lane counts of the electronic horizon placed between lane points ([HorizonLanes]) and laid over the evidence's
 * second pass ([LaneEvidence.withPlan]) on invented approaches to IA's exit at [GORE]: two lane points of five lanes,
 * 3,450 and 1,050 m before the gore, with a road without lane data leaving on the far side 2,250 m before, which the
 * route's own rules cannot bridge; and synthetic horizons laid from a car 550 m before the first point, an edge end at
 * every intersection. Stations are metres along the route.
 */
class HorizonLanesTest {

    private val p = GORE - 3_450.0
    private val road = GORE - 2_250.0
    private val q = GORE - 1_050.0

    private fun edge(length: Double, lanes: Int?, ramp: Boolean = false, motorway: Boolean = !ramp) =
        HorizonEdge(length, lanes, motorway = motorway, ramp = ramp, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    /** The gap scene: [first] and [second] the lanes of the two points, the exit IA's. */
    private fun gapScene(first: List<io.github.purplepalm.custommaps.nav.model.RouteLane>, second: List<io.github.purplepalm.custommaps.nav.model.RouteLane>, exit: List<io.github.purplepalm.custommaps.nav.model.RouteLane> = LaneScenes.iaExit(), id: String = "gap") =
        LaneScenes.approach(
            listOf(LaneScenes.At(3_450.0, first), bare(2_250.0, EXIT_LEFT), LaneScenes.At(1_050.0, second)),
            exit = exit,
            id = id,
        )

    private val five = List(5) { s() }

    /** A horizon laid from the car 550 m before the first point: [edges] from its edge, the next ones whole. */
    private fun horizon(routeId: String, vararg edges: HorizonEdge?) = HorizonAhead.of(routeId, p - 550.0, 0.5, edges.toList())

    /** IA's edges for the gap scene, one end at every intersection: to the first point, the far-side road, the second point, the gore and the ramp. */
    private fun gapEdges(first: Int?, between: Int?, second: Int?, gore: Int = 6) =
        arrayOf(edge(1_100.0, first), edge(1_200.0, between), edge(1_200.0, second), edge(1_050.0, gore), edge(400.0, 2, ramp = true))

    /** A station as text, to the decimetre. */
    private fun Double.dm(): String = String.format(java.util.Locale.ROOT, "%.1f", this)

    /** The evidence's stretches from [from] to [to] as text, to the decimetre. */
    private fun LaneEvidence.between(from: Double, to: Double): List<String> = stretches
        .filter { it.to > from && it.from < to }
        .map { "${it.from.dm()}..${it.to.dm()} ${it.kind}${if (it.kind == Kind.NONE) "" else " " + it.lanes}${if (it.horizon) " horizon" else ""}" }

    private fun LaneEvidence.zoneList(): List<String> = zones.map { "${it.from.dm()}..${it.to.dm()} ${it.cause}" }

    @Test
    fun `a gap the route's rules cannot bridge is bridged where the horizon agrees with every listed lane`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val base = plan.evidence()
        assertEquals(listOf("$p..${q - 1_000.0} NONE"), base.between(p, q - 1_000.0))

        val lanes = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(5, 5, 5)))
        val pair = lanes.between.first()
        assertEquals(HorizonConvention.ALL, pair.convention)
        assertEquals(listOf(HorizonLanes.Count(p, q, 5)), pair.counts)
        assertEquals(2, pair.stretches)
        assertNull(pair.placed)
        assertEquals(listOf(HorizonLanes.Piece(p, q, HorizonLanes.Piece.Kind.BRIDGED, 5)), pair.pieces)

        val drawn = plan.evidence(lanes)
        // The gap is bridged by the horizon's count; the second point's own reach stays as it was.
        assertEquals(listOf("${p.dm()}..${(q - 1_000.0).dm()} BRIDGED 5 horizon", "${(q - 1_000.0).dm()}..${q.dm()} KNOWN 5"), drawn.between(p, q))
        // A bridge's lanes are the first point's going on, as in any bridge.
        assertEquals(listOf(plan.topology.points[0].node), drawn.stretches.filter { it.horizon && it.to <= q }.map { it.point })
        // Inside the run-up, the 50 m between the second point and the gore's reach is a zone of rule 7a: bridged too, at
        // the six lanes the plan draws there, the exit-only lane that may already be there among them.
        assertTrue(plan.windows.any { q > it.from && q + 50.0 <= it.to })
        assertEquals(listOf("${q.dm()}..${(q + 50.0).dm()} GAP"), base.zoneList())
        assertEquals(listOf("${q.dm()}..${(q + 50.0).dm()} BRIDGED 6 horizon"), drawn.between(q, q + 50.0))
        assertEquals(emptyList<String>(), drawn.zoneList())
        // The plan and its own evidence are not touched.
        assertEquals(base.stretches, plan.evidence().stretches)
        assertEquals(listOf("$p..${q - 1_000.0} NONE"), plan.evidence().between(p, q - 1_000.0))
    }

    @Test
    fun `where a car-pool lane is listed the horizon may count the general lanes only, and bridges at the lanes the plan draws`() {
        val first = listOf(hov(), s(), s(), s())
        val route = gapScene(first, first, exit = listOf(hov(), s(false), s(false), LaneScenes.option(), LaneScenes.sr()), id = "carpool-gap")
        val plan = SceneLines.planOf(route)
        val general = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(3, 3, 3, gore = 4))).between.first()
        assertEquals(HorizonConvention.GENERAL, general.convention)
        // Bridged at four lanes, the car-pool lane among them, as the plan draws them.
        assertEquals(listOf(HorizonLanes.Piece(p, q, HorizonLanes.Piece.Kind.BRIDGED, 4)), general.pieces)
        assertEquals(listOf("$p..${q - 1_000.0} BRIDGED 4 horizon", "${q - 1_000.0}..$q KNOWN 4"), plan.evidence(HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(3, 3, 3, gore = 4)))).between(p, q))

        val all = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(4, 4, 4, gore = 5))).between.first()
        assertEquals(HorizonConvention.ALL, all.convention)
        assertEquals(listOf(HorizonLanes.Piece(p, q, HorizonLanes.Piece.Kind.BRIDGED, 4)), all.pieces)

        // Every listed lane at one point and the general ones at the other is neither reading.
        val mixed = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(4, 4, 3, gore = 5))).between.first()
        assertEquals(HorizonConvention.NONE, mixed.convention)
        assertTrue(mixed.pieces.isEmpty())
    }

    @Test
    fun `with neither reading nothing of the horizon is taken there`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val lanes = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(4, 5, 5)))
        val pair = lanes.between.first()
        assertEquals(HorizonConvention.NONE, pair.convention)
        assertTrue(pair.pieces.isEmpty())
        assertEquals(0, lanes.from(p).between.filter { it.before == 0 }.sumOf { it.pieces.size })
        assertEquals(plan.evidence().between(p, q), plan.evidence(lanes).between(p, q))
        // Its stretches are not taken by the drawing.
        assertEquals(1, lanes.stretchesUsed)
    }

    @Test
    fun `a gain the plan puts on the far side is placed at the edge end where the count rises, in place of the zone placed late`() {
        val route = gapScene(List(4) { s() }, five, id = "far-gain")
        val plan = SceneLines.planOf(route)
        val base = plan.evidence()
        val careful = plan.topology.alignments.first()
        assertEquals(LaneTopology.Alignment.Reason.CAREFUL, careful.by)
        // Without the horizon the zone ends at the second point, the lane on the far side opening late.
        val taper = LaneTopology.addedTaperMeters(27.0, RoadKind.FREEWAY)
        val zone = 2.0 * taper + LaneEvidence.ZONE_MIDDLE_METERS
        assertEquals(listOf("${(q - zone).dm()}..$q CHANGE"), base.zoneList().filter { it.endsWith("CHANGE") })

        val lanes = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(4, 4, 5)))
        val pair = lanes.between.first()
        assertEquals(road, pair.placed)
        assertEquals(mapOf(plan.topology.points[1].node to road), lanes.placedGains)
        assertEquals(
            listOf(
                HorizonLanes.Piece(p, road, HorizonLanes.Piece.Kind.BRIDGED, 4),
                HorizonLanes.Piece(road - zone, road, HorizonLanes.Piece.Kind.PLACED, 5),
                HorizonLanes.Piece(road, q, HorizonLanes.Piece.Kind.BRIDGED, 5),
            ),
            pair.pieces,
        )
        val drawn = plan.evidence(lanes)
        assertEquals(listOf("${(road - zone).dm()}..$road CHANGE"), drawn.zoneList().filter { it.endsWith("CHANGE") })
        assertEquals(mapOf(plan.topology.points[1].node to road), drawn.placedGains)
        assertEquals(
            listOf(
                "$p..${(road - zone).dm()} BRIDGED 4 horizon",
                "${(road - zone).dm()}..$road ZONE 5",
                "$road..${q - 1_000.0} BRIDGED 5 horizon",
                "${q - 1_000.0}..$q KNOWN 5",
            ),
            drawn.between(p, q),
        )
    }

    @Test
    fun `under the general reading a gain that adds a car-pool lane is not placed, every lane of it opening as the plan has it`() {
        // Three lanes at the first point; at the second a car-pool lane and four open lanes: the plan adds two lanes on the
        // far side, one of them kept for car pools, opening late before the second point.
        val route = gapScene(List(3) { s() }, listOf(hov(), s(), s(), s(), s()), exit = listOf(hov(), s(false), s(false), s(false), LaneScenes.option(), LaneScenes.sr()), id = "carpool-gain")
        val plan = SceneLines.planOf(route)
        val gain = plan.topology.alignments.single { it.after == 1 }
        assertEquals(2, gain.change)
        assertEquals(LaneTopology.Alignment.Reason.CAREFUL, gain.by)
        assertEquals(LaneEdge.LEFT, gain.side)
        val added = HorizonLanes.addedLanes(plan.topology, 0)
        assertEquals(listOf(false, true), added.map { it.designated }.sorted())
        val rise = p + 1_000.0
        // The horizon counts the open lanes only: three to the first point and for 1,000 m past it, four from there on.
        val general = HorizonLanes.of(plan, horizon(route.routeId, edge(1_100.0, 3), edge(1_000.0, 3), edge(1_400.0, 4), edge(1_050.0, 5), edge(400.0, 2, ramp = true)))
        val pair = general.between.first()
        assertEquals(HorizonConvention.GENERAL, pair.convention)
        assertEquals(listOf(HorizonLanes.Count(p, rise, 3), HorizonLanes.Count(rise, q, 4)), pair.counts)
        assertNull(pair.placed)
        assertTrue(general.placedGains.isEmpty())
        assertTrue(pair.pieces.none { it.kind == HorizonLanes.Piece.Kind.PLACED })
        // Nothing of the drawing names the car-pool lane from the count: every added lane opens where the plan opens it, and
        // the road between the rise and that opening has the plan's three lanes.
        val base = CrossSection.of(plan, plan.evidence())
        val drawn = CrossSection.of(plan, plan.evidence(general))
        for (lane in added) assertEquals(base.drawnFrom(lane.id), drawn.drawnFrom(lane.id), "lane ${lane.id}")
        for (station in listOf(rise + 130.0, q - 130.0)) {
            assertEquals(base.lanes(station).map { it.lane }, drawn.lanes(station).map { it.lane }, "at $station")
            assertEquals(3, drawn.lanes(station).size)
        }
        // Under every lane's reading the count rises by both lanes at once: the gain is placed, both lanes with it.
        val all = HorizonLanes.of(plan, horizon(route.routeId, edge(1_100.0, 3), edge(1_000.0, 3), edge(1_400.0, 5), edge(1_050.0, 6), edge(400.0, 2, ramp = true)))
        assertEquals(HorizonConvention.ALL, all.between.first().convention)
        assertEquals(rise, all.between.first().placed)
        // Under the general reading a gain of open lanes alone is placed: an exit on the left, a car-pool lane at the left of
        // both points, one open lane added on the far side, the right.
        val open = LaneScenes.approach(
            listOf(LaneScenes.At(3_450.0, listOf(hov(), s(), s(), s())), bare(2_250.0, LaneScenes.EXIT_RIGHT), LaneScenes.At(1_050.0, listOf(hov(), s(), s(), s(), s()))),
            exit = listOf(LaneScenes.sl(), LaneScenes.sl(), s(false), s(false), s(false), s(false)),
            maneuver = LaneScenes.OFF_LEFT,
            id = "open-gain",
        )
        val openPlan = SceneLines.planOf(open)
        assertEquals(LaneEdge.RIGHT, openPlan.topology.alignments.single { it.after == 1 }.side)
        assertTrue(HorizonLanes.addedLanes(openPlan.topology, 0).none { it.designated })
        val openPair = HorizonLanes.of(openPlan, horizon(open.routeId, edge(1_100.0, 3), edge(1_000.0, 3), edge(1_400.0, 4), edge(1_050.0, 5), edge(400.0, 2, ramp = true))).between.first()
        assertEquals(HorizonConvention.GENERAL, openPair.convention)
        assertEquals(rise, openPair.placed)
    }

    @Test
    fun `a gain whose count rises twice, or later than the plan opens its lane, is not placed`() {
        // Two changes between the points: the plan's late opening stays, each change a zone of the horizon's.
        val route = gapScene(List(4) { s() }, five, id = "far-gain")
        val plan = SceneLines.planOf(route)
        val twice = HorizonLanes.of(
            plan,
            horizon(route.routeId, edge(1_100.0, 4), edge(600.0, 5), edge(600.0, 4), edge(1_200.0, 5), edge(1_050.0, 6), edge(400.0, 2, ramp = true)),
        ).between.first()
        assertNull(twice.placed)
        assertEquals(2, twice.pieces.count { it.kind == HorizonLanes.Piece.Kind.CHANGE })
        // A count that rises past where the plan already opens the lane (by more than the tolerance), yet before the second
        // point, is no earlier start: a zone of its own.
        val opens = HorizonLanes.addedLanes(plan.topology, 0).single().opens
        val late = HorizonLanes.of(
            plan,
            horizon(route.routeId, edge(1_100.0, 4), edge(2_372.0, 4), edge(28.0, 5), edge(1_050.0, 6), edge(400.0, 2, ramp = true)),
        ).between.first()
        assertTrue(q - 28.0 > opens + HorizonLanesLine.MATCH_TOLERANCE_M && q - 28.0 < q - HorizonLanesLine.MATCH_TOLERANCE_M)
        assertNull(late.placed)
        assertEquals(1, late.pieces.count { it.kind == HorizonLanes.Piece.Kind.CHANGE })
    }

    @Test
    fun `a gain the plan puts on the exit's side is never placed, so the lit lane counts from the edge as the plan does`() {
        // Four lanes, the left one leaving by a road on the left 1,750 m before the gore, five 1,000 m before: the gain lies
        // on the exit's side, where a car-pool lane rules the careful far side out.
        val route = LaneScenes.exitSideGain()
        val plan = SceneLines.planOf(route)
        val gain = plan.topology.alignments.single()
        assertEquals(LaneEdge.RIGHT, gain.side)
        val ahead = HorizonAhead.of(
            route.routeId,
            GORE - 2_750.0,
            0.5,
            listOf(edge(800.0, 4), edge(600.0, 4), edge(430.0, 3), edge(320.0, 5), edge(1_000.0, 5), edge(400.0, 2, ramp = true)),
        )
        val lanes = HorizonLanes.of(plan, ahead)
        val pair = lanes.between.first { it.after == gain.after }
        assertEquals(HorizonConvention.ALL, pair.convention)
        assertNull(pair.placed)
        assertTrue(lanes.placedGains.isEmpty())
        // The zone placed late stays, and the second point's own reach with it.
        assertEquals(plan.evidence().zones, plan.evidence(lanes).zones)
        assertEquals(plan.evidence().stretches, plan.evidence(lanes).stretches)
    }

    @Test
    fun `a lane added and dropped between two points gives a zone at each change and none between them`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val bump = horizon(
            route.routeId,
            edge(1_100.0, 5),
            edge(450.0, 5),
            edge(500.0, 6),
            edge(250.0, 5),
            edge(1_200.0, 5),
            edge(1_050.0, 6),
            edge(400.0, 2, ramp = true),
        )
        val lanes = HorizonLanes.of(plan, bump)
        val pair = lanes.between.first()
        val added = p + 450.0
        val dropped = added + 500.0
        assertEquals(listOf(HorizonLanes.Count(p, added, 5), HorizonLanes.Count(added, dropped, 6), HorizonLanes.Count(dropped, q, 5)), pair.counts)
        // The zones' tapers at the planning speed of the road there, as the plan has it.
        val speed = PlanningSpeed.of(route.nodes.last { it.station <= dropped })
        val gainZone = 2.0 * LaneTopology.addedTaperMeters(speed, RoadKind.FREEWAY) + LaneEvidence.ZONE_MIDDLE_METERS
        val lossZone = 2.0 * LaneTopology.endingTaperMeters(speed, RoadKind.FREEWAY) + LaneEvidence.ZONE_MIDDLE_METERS
        assertEquals(
            listOf(
                HorizonLanes.Piece(p, added, HorizonLanes.Piece.Kind.BRIDGED, 5),
                HorizonLanes.Piece(added - gainZone, added, HorizonLanes.Piece.Kind.CHANGE, 5),
                HorizonLanes.Piece(dropped - lossZone, dropped, HorizonLanes.Piece.Kind.CHANGE, 5),
                HorizonLanes.Piece(added, dropped, HorizonLanes.Piece.Kind.DIFFERS, 5),
                HorizonLanes.Piece(dropped, q, HorizonLanes.Piece.Kind.BRIDGED, 5),
            ),
            pair.pieces,
        )
        val drawn = plan.evidence(lanes)
        // Lines up to the first change's zone, none until the lane is gone again, lines after.
        assertEquals(
            listOf(
                "$p..${(added - gainZone).dm()} BRIDGED 5 horizon",
                "${(added - gainZone).dm()}..$dropped ZONE 5 horizon",
                "$dropped..${q - 1_000.0} BRIDGED 5 horizon",
                "${q - 1_000.0}..$q KNOWN 5",
            ),
            drawn.between(p, q),
        )
        assertEquals(listOf("${(added - gainZone).dm()}..$dropped HORIZON"), drawn.zoneList().filter { it.endsWith("HORIZON") })
    }

    @Test
    fun `a stretch without lane data where the horizon differs is a zone only within rule 7a's limit`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val limit = EvidenceTuning.DEFAULT.zoneBridgeMaxM
        val added = p + 400.0
        fun drawnWith(bump: Double): LaneEvidence {
            // Six lanes for [bump] metres from 400 m past the first point, five before and after.
            val ahead = horizon(route.routeId, edge(1_100.0, 5), edge(400.0, 5), edge(bump, 6), edge(2_000.0 - bump, 5), edge(1_050.0, 6), edge(400.0, 2, ramp = true))
            return plan.evidence(HorizonLanes.of(plan, ahead))
        }
        fun noneBetween(e: LaneEvidence) = e.stretches.filter { it.kind == Kind.NONE && it.from >= p && it.to <= q }.map { it.from to it.to }
        // A lane for just less than the limit: one zone from the gain's zone to where the lane is gone again.
        val inside = drawnWith(limit - 15.0)
        assertEquals(emptyList<Pair<Double, Double>>(), noneBetween(inside))
        assertTrue(inside.stretches.any { it.kind == Kind.ZONE && it.horizon && it.from < added && it.to == added + limit - 15.0 })
        // Just more: nothing of ours over the lane; the drawing ends and begins again around it.
        val outside = drawnWith(limit + 15.0)
        assertEquals(listOf(added to added + limit + 15.0), noneBetween(outside))
    }

    @Test
    fun `an edge end within the tolerance of a point leads to it, one just past it does not`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val tolerance = HorizonLanesLine.MATCH_TOLERANCE_M
        fun covered(offset: Double): Boolean {
            // The edge that leads to the first point ends [offset] metres past it; the next one is as much shorter.
            val ahead = horizon(route.routeId, edge(1_100.0 + 2.0 * offset, 5), edge(1_200.0 - offset, 5), edge(1_200.0, 5), edge(1_050.0, 6), edge(400.0, 2, ramp = true))
            return HorizonLanes.of(plan, ahead).between.any { it.before == 0 }
        }
        assertTrue(covered(tolerance - 0.1))
        assertTrue(covered(-(tolerance - 0.1)))
        assertTrue(!covered(tolerance + 0.1))
        assertTrue(!covered(-(tolerance + 0.1)))
    }

    @Test
    fun `a ramp edge, a road that is no motorway, a missing count, a turning node or partial cover leave a pair alone`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        fun pairs(vararg edges: HorizonEdge?) = HorizonLanes.of(plan, horizon(route.routeId, *edges)).between.filter { it.before == 0 }
        val rest = arrayOf(edge(1_050.0, 6), edge(400.0, 2, ramp = true))
        assertTrue(pairs(edge(1_100.0, 5), edge(1_200.0, 5, ramp = true), edge(1_200.0, 5), *rest).isEmpty())
        assertTrue(pairs(edge(1_100.0, 5), edge(1_200.0, 5, motorway = false), edge(1_200.0, 5), *rest).isEmpty())
        assertTrue(pairs(edge(1_100.0, 5), edge(1_200.0, null), edge(1_200.0, 5), *rest).isEmpty())
        // No metadata for an edge: the stretches end there, short of the second point.
        assertTrue(pairs(edge(1_100.0, 5), edge(1_200.0, 5), null, *rest).isEmpty())
        // The car past the first point: its approach is behind.
        assertTrue(HorizonLanes.of(plan, HorizonAhead.of(route.routeId, p + 12.0, 0.0, gapEdges(5, 5, 5).drop(1))).between.none { it.before == 0 })
        // A node where the route turns between two points: two runs, nothing between them.
        val fork = LaneScenes.route(
            LaneScenes.node(0.0, maneuver = LaneScenes.DEPART, planMps = 27.0),
            LaneScenes.node(p, *five.toTypedArray(), planMps = 27.0),
            LaneScenes.node(road, maneuver = NodeManeuver(ManeuverType.FORK, TurnModifier.SLIGHT_LEFT, viewStaysUp = true), planMps = 27.0),
            LaneScenes.node(q, *five.toTypedArray(), planMps = 27.0),
            LaneScenes.node(GORE, *LaneScenes.iaExit().toTypedArray(), road = RoadKind.RAMP, maneuver = LaneScenes.OFF_RIGHT, planMps = 20.0),
            LaneScenes.node(GORE + 400.0, road = RoadKind.STREET, maneuver = LaneScenes.ARRIVE, planMps = 15.0),
            id = "fork-between",
        )
        val forkPlan = SceneLines.planOf(fork)
        assertTrue(HorizonLanes.of(forkPlan, horizon(fork.routeId, *gapEdges(5, 5, 5))).between.none { it.from == p })
        // Another route's horizon gives nothing.
        assertTrue(HorizonLanes.of(plan, HorizonAhead.of("another", p - 550.0, 0.5, gapEdges(5, 5, 5).toList())).between.isEmpty())
    }

    @Test
    fun `a change the plan draws within the tolerance of the horizon's is no zone of the horizon's`() {
        // The right lane of five leaves by a road on the right 2,250 m before the gore: the plan draws four lanes past it.
        val route = LaneScenes.approach(
            listOf(at(3_450.0, *five.toTypedArray()), at(2_250.0, s(), s(), s(), s(), s(false), roads = LaneScenes.EXIT_RIGHT), at(1_050.0, s(), s(), s(), s())),
            exit = listOf(s(false), s(false), s(false), LaneScenes.option(), LaneScenes.sr()),
            id = "leaves-right",
        )
        val plan = SceneLines.planOf(route)
        // The horizon's count drops at an edge end 12 m past the road: the plan's own drop, a few metres off.
        val ahead = horizon(route.routeId, edge(1_100.0, 5), edge(1_200.0, 5), edge(12.0, 5), edge(1_188.0, 4), edge(1_050.0, 5), edge(400.0, 2, ramp = true))
        val lanes = HorizonLanes.of(plan, ahead)
        val pair = lanes.between.first { it.from == road }
        assertTrue(pair.pieces.none { it.kind == HorizonLanes.Piece.Kind.CHANGE || it.kind == HorizonLanes.Piece.Kind.DIFFERS }, pair.pieces.toString())
    }

    @Test
    fun `the pairs a window takes from a station on, and those it keeps from before`() {
        val route = gapScene(five, five)
        val plan = SceneLines.planOf(route)
        val lanes = HorizonLanes.of(plan, horizon(route.routeId, *gapEdges(5, 5, 5)))
        assertEquals(listOf(p, q), lanes.between.map { it.from })
        assertEquals(listOf(q), lanes.from(p + 1.0).between.map { it.from })
        assertEquals(listOf(p, q), lanes.from(p).between.map { it.from })
        assertEquals(listOf(p), lanes.before(q).between.map { it.from })
        assertEquals(listOf(p, q), (lanes.from(q) + lanes.before(q)).between.map { it.from })
        assertTrue(HorizonLanes.none(route.routeId).isEmpty)
        assertEquals(plan.evidence().stretches, plan.evidence(HorizonLanes.none(route.routeId)).stretches)
    }

    @Test
    fun `a lane point's own reach, IA's whole approach, stays as the plan draws it`() {
        val ia = LaneScenes.ia()
        val plan = SceneLines.planOf(ia)
        val ahead = HorizonAhead.of(
            "ia",
            3_000.0,
            0.5,
            listOf(edge(1_640.0, 5), edge(850.0, 5), edge(640.0, 5), edge(50.0, 5), edge(640.0, 6), edge(400.0, 2, ramp = true)),
        )
        val lanes = HorizonLanes.of(plan, ahead)
        assertTrue(lanes.between.all { it.convention == HorizonConvention.ALL })
        // The plan draws four lanes past IA0 until its careful late lane: the horizon counts five, but the reach is IA1's own.
        assertTrue(lanes.between.first().pieces.any { it.kind == HorizonLanes.Piece.Kind.DIFFERS })
        assertEquals(plan.evidence().stretches, plan.evidence(lanes).stretches)
        assertEquals(plan.evidence().zones.map { it.cause }, plan.evidence(lanes).zones.map { it.cause })
    }
}
