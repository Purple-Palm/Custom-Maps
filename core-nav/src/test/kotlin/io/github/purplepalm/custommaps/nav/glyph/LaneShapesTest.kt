package io.github.purplepalm.custommaps.nav.glyph

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.nav.model.TurnModifier.LEFT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.RIGHT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.SHARP_LEFT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.SHARP_RIGHT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.SLIGHT_LEFT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.SLIGHT_RIGHT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.STRAIGHT
import io.github.purplepalm.custommaps.nav.model.TurnModifier.U_TURN
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.Arc2D
import java.awt.geom.Area
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The lanes' arrows of the cards (DECISIONS 60), candidate A, measured on the shapes as they are drawn: lines stroked
 * with round ends and corners, arcs as Android draws them, filled shapes filled, all with java.awt.geom. Every lane of
 * one to three of the eight directions, in every state: not to take, lit on each of its arrows, lit with none named, an
 * outline, a lane for car pools dim and lit. Distances are shares of the lane's square.
 */
class LaneShapesTest {

    @Test
    fun `every part of every lane lies inside its square, in every state`() {
        var worst = 0f
        var at = ""
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            for ((state, lane) in allStates(set)) {
                for (mark in LaneShapes.lane(lane)) {
                    val b = shape(mark.part).bounds2D
                    val out = maxOf(-b.minX, -b.minY, b.maxX - S, b.maxY - S).toFloat() / S
                    if (out > worst) {
                        worst = out
                        at = "${name(set)} $state"
                    }
                }
            }
        }
        assertTrue(worst <= 0f, "a part leaves its square by $worst in $at")
    }

    @Test
    fun `in the lanes roads paint heads keep apart, from each other and from the other arrows' lines and the stem`() {
        var heads = Float.MAX_VALUE
        var lines = Float.MAX_VALUE
        for (set in COMMON) {
            for ((state, lane) in allStates(set)) {
                if (lane.outline) continue
                val (hh, hl) = clearances(LaneShapes.lane(lane))
                assertTrue(hh >= HEADS_APART, "${name(set)} $state: heads $hh apart")
                assertTrue(hl >= HEAD_TO_LINE, "${name(set)} $state: a head $hl from a line")
                heads = min(heads, hh)
                lines = min(lines, hl)
            }
        }
        // As the design measured them: 0.060 between heads, 0.049 from a head to a line.
        assertEquals(0.060f, heads, 0.0015f)
        assertEquals(0.049f, lines, 0.0015f)
    }

    @Test
    fun `every mark on a tile keeps clear of the tile's edge`() {
        val edge = segments(shape(LaneShapes.tile()))
        var least = Float.MAX_VALUE
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            for ((state, lane) in allStates(set)) {
                val marks = LaneShapes.lane(lane)
                if (marks.none { it.ink == GlyphInk.TILE }) continue
                for (mark in marks.filter { it.ink == GlyphInk.ON_TILE }) {
                    val room = distance(segments(shape(mark.part)), edge)
                    assertTrue(room >= TILE_MARGIN, "${name(set)} $state: a mark $room from the tile's edge")
                    least = min(least, room)
                }
            }
        }
        // The design's measure: 0.040 at the least, in a lane roads hardly paint.
        assertEquals(0.040f, least, 0.0015f)
    }

    @Test
    fun `a lane to take is its tile, then its other arrows thin, the stem and the route's arrow bold, all in the dark ink`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            for (active in listOf<TurnModifier?>(null) + EIGHT) {
                val lane = Lane(set, isRecommended = true, activeIndication = active)
                assertTrue(GlyphShapes.isLaneToTake(lane))
                val marks = LaneShapes.lane(lane)
                val what = "${name(set)} on ${active ?: "none"}"
                assertEquals(LaneMark(LaneShapes.tile(), GlyphInk.TILE, null, LaneRole.TILE), marks.first(), what)
                assertTrue(marks.drop(1).all { it.ink == GlyphInk.ON_TILE }, what)
                val route = GlyphShapes.routeArrows(lane)
                val fit = fitOf(lane)
                val stem = marks.indexOfFirst { it.role == LaneRole.STEM }
                val routeWeight = if (active == null) LaneShapes.ANY else LaneShapes.BOLD
                for ((index, mark) in marks.withIndex().drop(1)) {
                    if (mark.role == LaneRole.STEM) {
                        assertEquals(routeWeight.line * fit, widthOfStem(mark.part), 1e-6f, what)
                        continue
                    }
                    val ofRoute = mark.turn in route
                    // The other arrows before the stem, the route's after it, over where the others leave it.
                    assertTrue(if (ofRoute) index > stem else index < stem, "$what: ${mark.turn} at $index, the stem at $stem")
                    if (mark.role == LaneRole.LINE) {
                        val weight = if (ofRoute) routeWeight else LaneShapes.THIN
                        assertEquals(weight.line * fit, (mark.part as GlyphPart.Line).width, 1e-6f, "$what: ${mark.turn}")
                    }
                }
                if (active == null) assertEquals(GlyphShapes.laneArrows(lane), route, "$what: every arrow the route's")
            }
        }
    }

    @Test
    fun `a lane not to take is grey, a car pool lane has its side lines first and is lit by colour alone where the route marks it`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            val dim = LaneShapes.lane(Lane(set, isRecommended = false))
            assertTrue(dim.all { it.ink == GlyphInk.DIM }, name(set))
            assertTrue(dim.none { it.role == LaneRole.SIDE_LINE }, name(set))
            val fit = fitOf(Lane(set, isRecommended = false))
            for (mark in dim) if (mark.role == LaneRole.LINE) assertEquals(LaneShapes.DIM.line * fit, (mark.part as GlyphPart.Line).width, 1e-6f, name(set))
            for (active in listOf<TurnModifier?>(null) + set) {
                for (recommended in listOf(false, true)) {
                    if (!recommended && active != null) continue
                    val lane = Lane(set, isRecommended = recommended, activeIndication = active, designated = true)
                    val marks = LaneShapes.lane(lane)
                    val what = "${name(set)} car pool, recommended $recommended on ${active ?: "none"}"
                    assertFalse(GlyphShapes.isLaneToTake(lane), what)
                    // Two solid lines inside the square, first of all.
                    assertEquals(listOf(LaneRole.SIDE_LINE, LaneRole.SIDE_LINE), marks.take(2).map { it.role }, what)
                    assertTrue(marks.take(2).all { it.ink == GlyphInk.DIM && it.part is GlyphPart.Segment }, what)
                    assertTrue(marks.none { it.ink == GlyphInk.TILE || it.ink == GlyphInk.ON_TILE }, what)
                    val route = GlyphShapes.routeArrows(lane)
                    for (mark in marks.drop(2)) {
                        val lit = if (mark.role == LaneRole.STEM) route.isNotEmpty() else mark.turn in route
                        assertEquals(if (lit) GlyphInk.LIT else GlyphInk.DIM, mark.ink, "$what: ${mark.turn} ${mark.role}")
                        if (mark.role == LaneRole.LINE) assertEquals(LaneShapes.DIM.line * fit, (mark.part as GlyphPart.Line).width, 1e-6f, what)
                    }
                    // The same arrows in the same place as the lane not to take, only in another ink.
                    assertEquals(dim.map { it.part }.toSet(), marks.drop(2).map { it.part }.toSet(), what)
                }
            }
        }
    }

    @Test
    fun `a lane still to come is hollow grey arrows, never lit and never a tile, whatever else it says`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            val plain = LaneShapes.lane(Lane(set, isRecommended = false, outline = true))
            val what = name(set)
            // The outside first, then every hollow, so no outside covers a hollow cut before it.
            val firstHole = plain.indexOfFirst { it.ink == GlyphInk.HOLE }
            assertTrue(firstHole > 0, what)
            assertTrue(plain.take(firstHole).all { it.ink == GlyphInk.DIM }, what)
            assertTrue(plain.drop(firstHole).all { it.ink == GlyphInk.HOLE && it.role == LaneRole.HOLLOW }, what)
            val fit = fitOf(Lane(set, isRecommended = false, outline = true))
            for (mark in plain.take(firstHole)) if (mark.role == LaneRole.LINE) assertEquals(LaneShapes.OUTLINE.line * fit, (mark.part as GlyphPart.Line).width, 1e-6f, what)
            // Its hollow lines leave a rim of 0.05 at each side.
            for (mark in plain.drop(firstHole)) {
                val part = mark.part
                if (part is GlyphPart.Line) assertEquals((LaneShapes.OUTLINE.line - 2 * LaneShapes.RIM) * fit, part.width, 1e-6f, what)
            }
            for (active in listOf<TurnModifier?>(null) + EIGHT) {
                for (designated in listOf(false, true)) {
                    val odd = Lane(set, isRecommended = true, activeIndication = active, designated = designated, outline = true)
                    assertFalse(GlyphShapes.isLaneToTake(odd), what)
                    assertEquals(emptySet<TurnModifier>(), GlyphShapes.routeArrows(odd), what)
                    assertEquals(plain, LaneShapes.lane(odd), "$what marked to take on ${active ?: "none"}, designated $designated")
                }
            }
            assertEquals(plain, LaneShapes.lane(Lane(set, isRecommended = false, designated = true, outline = true)), what)
        }
    }

    @Test
    fun `every lane draws exactly its arrows, the route's ones as the route's`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            for ((state, lane) in allStates(set)) {
                val marks = LaneShapes.lane(lane)
                val what = "${name(set)} $state"
                val heads = marks.filter { it.role == LaneRole.HEAD }
                // One head an arrow: those painted, a straight one for none, the route's arrow should the data not list it.
                assertEquals(GlyphShapes.laneArrows(lane).toSet(), heads.map { it.turn }.toSet(), what)
                assertEquals(heads.size, heads.map { it.turn }.toSet().size, what)
                val routeHeads = heads.filter { mark ->
                    when {
                        lane.outline -> false
                        GlyphShapes.isLaneToTake(lane) -> !isThin(mark, lane)
                        else -> mark.ink == GlyphInk.LIT
                    }
                }
                assertEquals(GlyphShapes.routeArrows(lane), routeHeads.map { it.turn }.toSet(), what)
            }
        }
        // The arrows, by the lane's rules.
        assertEquals(setOf(STRAIGHT), GlyphShapes.laneArrows(Lane(emptySet(), isRecommended = false)))
        assertEquals(setOf(STRAIGHT, SLIGHT_LEFT), GlyphShapes.laneArrows(Lane(setOf(STRAIGHT), isRecommended = true, activeIndication = SLIGHT_LEFT)))
        assertEquals(setOf(STRAIGHT), GlyphShapes.laneArrows(Lane(setOf(STRAIGHT), isRecommended = true, activeIndication = SLIGHT_LEFT, outline = true)))
        assertEquals(setOf(STRAIGHT, LEFT), GlyphShapes.routeArrows(Lane(setOf(STRAIGHT, LEFT), isRecommended = true)))
        assertEquals(setOf(LEFT), GlyphShapes.routeArrows(Lane(setOf(STRAIGHT, LEFT), isRecommended = true, activeIndication = LEFT)))
        assertEquals(emptySet<TurnModifier>(), GlyphShapes.routeArrows(Lane(setOf(STRAIGHT, LEFT), isRecommended = false)))
    }

    @Test
    fun `each head points its way, a sharp turn goes across with no curve and a U-turn comes down left of its stem`() {
        for (set in SETS) {
            for ((state, lane) in allStates(set)) {
                val marks = LaneShapes.lane(lane)
                val what = "${name(set)} $state"
                val stem = marks.first { it.role == LaneRole.STEM }.part as GlyphPart.Fill
                val stemX = stemX(stem)
                for (head in marks.filter { it.role == LaneRole.HEAD }) {
                    val turn = head.turn!!
                    val (hx, hy) = headingOf(head.part as GlyphPart.Fill)
                    val (ex, ey) = HEADINGS.getValue(turn)
                    assertEquals(ex, hx, 1e-4f, "$what: $turn")
                    assertEquals(ey, hy, 1e-4f, "$what: $turn")
                    if (turn == U_TURN) assertTrue(centreX(head.part) < stemX, "$what: the U-turn's head left of its stem")
                }
                for (line in marks.filter { it.role == LaneRole.LINE && (it.turn == SHARP_LEFT || it.turn == SHARP_RIGHT) }) {
                    val part = line.part as GlyphPart.Line
                    assertTrue(part.steps.none { it is GlyphStep.ArcTo }, "$what: ${line.turn} has no curve")
                    val points = listOf(part.start) + part.steps.map { (it as GlyphStep.LineTo).to }
                    val across = points.zipWithNext().any { (a, b) -> abs(a.y - b.y) < 1e-6f && abs(a.x - b.x) > 0.05f }
                    assertTrue(across, "$what: ${line.turn} goes straight across: $points")
                }
            }
        }
    }

    @Test
    fun `a lane stands in one place whatever its state`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            val xs = allStates(set).map { (state, lane) ->
                state to stemX(LaneShapes.lane(lane).first { it.role == LaneRole.STEM }.part as GlyphPart.Fill)
            }
            for ((state, x) in xs) assertEquals(xs.first().second, x, 1e-6f, "${name(set)} $state")
        }
    }

    @Test
    fun `the common lanes stand where the design put them`() {
        // From the design's plans: the stem's line and each tip, shares of the square.
        val expected = listOf(
            Triple(setOf(STRAIGHT), 0.500f, mapOf(STRAIGHT to (0.500f to 0.090f))),
            Triple(setOf(STRAIGHT, RIGHT), 0.365f, mapOf(STRAIGHT to (0.365f to 0.090f), RIGHT to (0.855f to 0.598f))),
            Triple(setOf(STRAIGHT, SLIGHT_LEFT), 0.674f, mapOf(STRAIGHT to (0.674f to 0.090f), SLIGHT_LEFT to (0.106f to 0.162f))),
            Triple(setOf(LEFT, STRAIGHT, RIGHT), 0.500f, mapOf(STRAIGHT to (0.500f to 0.105f), LEFT to (0.080f to 0.604f), RIGHT to (0.920f to 0.604f))),
            Triple(setOf(LEFT, U_TURN), 0.763f, mapOf(U_TURN to (0.464f to 0.572f), LEFT to (0.149f to 0.756f))),
            Triple(setOf(SHARP_RIGHT), 0.256f, mapOf(SHARP_RIGHT to (0.834f to 0.704f))),
            Triple(setOf(U_TURN), 0.752f, mapOf(U_TURN to (0.378f to 0.803f))),
        )
        for ((set, stemX, tips) in expected) {
            val marks = LaneShapes.lane(Lane(set, isRecommended = false))
            assertEquals(stemX, stemX(marks.first { it.role == LaneRole.STEM }.part as GlyphPart.Fill), 0.0006f, name(set))
            for ((turn, tip) in tips) {
                val head = marks.first { it.role == LaneRole.HEAD && it.turn == turn }.part as GlyphPart.Fill
                assertEquals(tip.first, head.start.x, 0.0006f, "${name(set)} $turn")
                assertEquals(tip.second, head.start.y, 0.0006f, "${name(set)} $turn")
            }
        }
        // Every lane's foot on one line, the box's; a lane with a U-turn, whose hook would reach above the box, a little
        // lower, never past the room the box leaves under the foot.
        for (set in COMMON) {
            val stem = LaneShapes.lane(Lane(set, isRecommended = false)).first { it.role == LaneRole.STEM }.part as GlyphPart.Fill
            val box = LaneShapes.BOX
            if (U_TURN in set) {
                assertTrue(stem.start.y >= box.footY - 1e-6f && stem.start.y <= box.footY + box.below, "${name(set)}: ${stem.start.y}")
            } else {
                assertEquals(box.footY, stem.start.y, 1e-6f, name(set))
            }
        }
    }

    @Test
    fun `three turns on one side are stacked, every one of them drawn, the route's bold on the tile`() {
        val sides = listOf(
            setOf(SLIGHT_LEFT, LEFT, SHARP_LEFT),
            setOf(SLIGHT_LEFT, LEFT, U_TURN),
            setOf(SLIGHT_LEFT, SHARP_LEFT, U_TURN),
            setOf(LEFT, SHARP_LEFT, U_TURN),
            setOf(SLIGHT_RIGHT, RIGHT, SHARP_RIGHT),
        )
        for (set in sides) {
            assertEquals(set, LaneArrows.plan(set).branches.map { it.turn }.toSet(), name(set))
            for (turn in set) {
                val lane = Lane(set, isRecommended = true, activeIndication = turn)
                val lines = LaneShapes.lane(lane).filter { it.role == LaneRole.LINE && it.turn == turn }
                assertEquals(1, lines.size, "${name(set)} on $turn")
                assertEquals(LaneShapes.BOLD.line * fitOf(lane), (lines.single().part as GlyphPart.Line).width, 1e-6f, "${name(set)} on $turn")
            }
            // A route arrow the data does not list on a pair of turns makes three on that side: stacked too.
            val third = Lane(set.take(2).toSet(), isRecommended = true, activeIndication = set.last())
            assertEquals(set, LaneShapes.lane(third).filter { it.role == LaneRole.HEAD }.map { it.turn }.toSet(), name(set))
        }
    }

    @Test
    fun `the tile and the weights are candidate A's`() {
        assertEquals(
            GlyphPart.Fill(
                GlyphPoint(0.18f, 0.02f),
                listOf(
                    GlyphStep.ArcTo(0.66f, 0.02f, 0.98f, 0.34f, 270f, 90f),
                    GlyphStep.ArcTo(0.66f, 0.66f, 0.98f, 0.98f, 0f, 90f),
                    GlyphStep.ArcTo(0.02f, 0.66f, 0.34f, 0.98f, 90f, 90f),
                    GlyphStep.ArcTo(0.02f, 0.02f, 0.34f, 0.34f, 180f, 90f),
                ),
            ).let(::rounded),
            rounded(LaneShapes.tile()),
        )
        assertEquals(LaneArrows.Weight(0.17f, 0.44f, 0.29f), LaneShapes.BOLD)
        assertEquals(LaneArrows.Weight(0.08f, 0.29f, 0.21f), LaneShapes.THIN)
        assertEquals(LaneArrows.Weight(0.14f, 0.34f, 0.24f), LaneShapes.ANY)
        assertEquals(LaneArrows.Weight(0.12f, 0.36f, 0.25f), LaneShapes.DIM)
        assertEquals(LaneArrows.Weight(0.18f, 0.40f, 0.28f), LaneShapes.OUTLINE)
        assertEquals(0.05f, LaneShapes.RIM)
        assertEquals(LaneArrows.Box(footY = 0.91f, tipY = 0.09f, left = 0.08f, right = 0.92f, top = 0.08f, below = 0.03f), LaneShapes.BOX)
        // The lane's other arrows on a tile never under 0.08, the lanes not to take at 0.12, for night.
        assertTrue(LaneShapes.THIN.line >= 0.08f && LaneShapes.DIM.line >= 0.12f)
    }

    /** How near heads come to each other and to another branch's line or the stem, in one lane's coloured parts. */
    private fun clearances(marks: List<LaneMark>): Pair<Float, Float> {
        val coloured = marks.filter { it.ink != GlyphInk.HOLE }
        val heads = coloured.filter { it.role == LaneRole.HEAD }
        val lines = coloured.filter { it.role == LaneRole.LINE }
        val stems = coloured.filter { it.role == LaneRole.STEM }
        var hh = Float.MAX_VALUE
        var hl = Float.MAX_VALUE
        for ((i, h) in heads.withIndex()) {
            val ha = shape(h.part)
            for (o in heads.drop(i + 1)) if (o.turn != h.turn) hh = min(hh, distance(ha, shape(o.part)))
            for (l in lines) if (l.turn != h.turn) hl = min(hl, distance(ha, shape(l.part)))
            if (h.turn != STRAIGHT) for (s in stems) hl = min(hl, distance(ha, shape(s.part)))
        }
        return hh to hl
    }

    /** Whether [mark], a head on [lane]'s tile, is drawn at the thin weight of the lane's other arrows. */
    private fun isThin(mark: LaneMark, lane: Lane): Boolean {
        val heads = LaneArrows.plan(GlyphShapes.laneArrows(lane)).heads
        val thinWidth = LaneShapes.THIN.headWidth * heads * fitOf(lane)
        val corners = cornersOf(mark.part as GlyphPart.Fill)
        val base = hypot(corners[1].x - corners[2].x, corners[1].y - corners[2].y)
        return abs(base - thinWidth) < 1e-5f
    }

    /** The fit of [lane]'s arrows in the box: its stem's line drawn at the dim or the route's weight shows it. */
    private fun fitOf(lane: Lane): Float {
        val plan = LaneArrows.plan(GlyphShapes.laneArrows(lane))
        return LaneArrows.place(plan, LaneShapes.BOX, LaneArrows.Weight.most(LaneShapes.BOLD, LaneShapes.ANY, LaneShapes.DIM, LaneShapes.OUTLINE)).fit
    }

    private fun cornersOf(fill: GlyphPart.Fill): List<GlyphPoint> = listOf(fill.start) + fill.steps.map { (it as GlyphStep.LineTo).to }

    /** Which way a head points: from the middle of its base to its tip, a unit step (y down). */
    private fun headingOf(head: GlyphPart.Fill): Pair<Float, Float> {
        val (tip, a, b) = cornersOf(head)
        val dx = tip.x - (a.x + b.x) / 2
        val dy = tip.y - (a.y + b.y) / 2
        val length = hypot(dx, dy)
        return dx / length to dy / length
    }

    private fun centreX(part: GlyphPart): Float = shape(part).bounds2D.centerX.toFloat() / S

    private fun stemX(stem: GlyphPart.Fill): Float = cornersOf(stem).let { (it.minOf { p -> p.x } + it.maxOf { p -> p.x }) / 2 }

    private fun widthOfStem(stem: GlyphPart): Float = cornersOf(stem as GlyphPart.Fill).let { it.maxOf { p -> p.x } - it.minOf { p -> p.x } }

    /** A filled shape's numbers to six places, for comparing a computed shape with a written one. */
    private fun rounded(fill: GlyphPart.Fill): List<Long> {
        val numbers = listOf(fill.start.x, fill.start.y) + fill.steps.flatMap { (it as GlyphStep.ArcTo).let { a -> listOf(a.left, a.top, a.right, a.bottom, a.startDegrees, a.sweepDegrees) } }
        return numbers.map { Math.round(it * 1e6) }
    }

    /** Every lane of [set] in every state, with its name. */
    private fun allStates(set: Set<TurnModifier>): List<Pair<String, Lane>> =
        listOf("dim" to Lane(set, isRecommended = false)) +
            set.map { "lit on $it" to Lane(set, isRecommended = true, activeIndication = it) } +
            listOf(
                "none named" to Lane(set, isRecommended = true),
                "outline" to Lane(set, isRecommended = false, outline = true),
                "car pool dim" to Lane(set, isRecommended = false, designated = true),
                "car pool, none named" to Lane(set, isRecommended = true, designated = true),
            ) +
            set.map { "car pool lit on $it" to Lane(set, isRecommended = true, activeIndication = it, designated = true) }

    private fun name(set: Set<TurnModifier>) = set.joinToString("+").ifEmpty { "none" }

    // ---- The shapes as drawn, with java.awt.geom, in a square of S. ----

    /** [part] as the painters draw it: a line stroked with round ends and corners, a filled shape filled. */
    private fun shape(part: GlyphPart): Area = when (part) {
        is GlyphPart.Line -> Area(BasicStroke(part.width * S, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path(part.start, part.steps, close = false)))
        is GlyphPart.Fill -> Area(path(part.start, part.steps, close = true))
        is GlyphPart.Segment -> Area(
            BasicStroke(part.width * S, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(Line2D.Float(part.from.x * S, part.from.y * S, part.to.x * S, part.to.y * S)),
        )
        is GlyphPart.Ring, is GlyphPart.Dot -> error("no lane draws $part")
    }

    /**
     * A glyph path in Java2D as Android draws it: an arc is `Path.arcTo(oval, start, sweep, false)`, a straight piece to
     * where it starts, then the arc; Android counts the angles clockwise on the screen, Java2D the other way.
     */
    private fun path(start: GlyphPoint, steps: List<GlyphStep>, close: Boolean): Path2D.Float {
        val path = Path2D.Float(Path2D.WIND_NON_ZERO)
        path.moveTo(start.x * S, start.y * S)
        for (step in steps) {
            when (step) {
                is GlyphStep.LineTo -> path.lineTo(step.to.x * S, step.to.y * S)
                is GlyphStep.ArcTo -> {
                    val arc = Arc2D.Float(step.left * S, step.top * S, (step.right - step.left) * S, (step.bottom - step.top) * S, -step.startDegrees, -step.sweepDegrees, Arc2D.OPEN)
                    path.append(arc, true)
                }
            }
        }
        if (close) path.closePath()
        return path
    }

    /** The straight pieces of [shape]'s outline, its curves flattened. */
    private fun segments(shape: Shape): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val it = shape.getPathIterator(null, 0.2)
        val c = FloatArray(6)
        var sx = 0f
        var sy = 0f
        var px = 0f
        var py = 0f
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> { sx = c[0]; sy = c[1]; px = sx; py = sy }
                PathIterator.SEG_LINETO -> { out += floatArrayOf(px, py, c[0], c[1]); px = c[0]; py = c[1] }
                PathIterator.SEG_CLOSE -> { out += floatArrayOf(px, py, sx, sy); px = sx; py = sy }
            }
            it.next()
        }
        return out
    }

    /** The room between two shapes, a share of the square; 0 where they touch or overlap. */
    private fun distance(a: Area, b: Area): Float {
        val both = Area(a).also { it.intersect(b) }
        if (!both.isEmpty) return 0f
        return distance(segments(a), segments(b))
    }

    /** The least distance between two outlines' pieces, a share of the square. */
    private fun distance(a: List<FloatArray>, b: List<FloatArray>): Float {
        var best = Float.MAX_VALUE
        for (x in a) for (y in b) best = min(best, segSeg(x, y))
        return best / S
    }

    private fun pointSeg(x: Float, y: Float, s: FloatArray): Float {
        val dx = s[2] - s[0]
        val dy = s[3] - s[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - s[0]) * dx + (y - s[1]) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (s[0] + t * dx), y - (s[1] + t * dy))
    }

    private fun segSeg(a: FloatArray, b: FloatArray): Float = minOf(
        minOf(pointSeg(a[0], a[1], b), pointSeg(a[2], a[3], b)),
        minOf(pointSeg(b[0], b[1], a), pointSeg(b[2], b[3], a)),
    )

    private companion object {
        /** The square's side for java.awt.geom: shares times this. */
        const val S = 1000f

        /** The least room between heads of different arrows, and from a head to another arrow's line or the stem. */
        const val HEADS_APART = 0.055f
        const val HEAD_TO_LINE = 0.04f

        /** The least room between a mark on a tile and the tile's edge. */
        const val TILE_MARGIN = 0.035f

        val EIGHT = listOf(STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT)

        /** Every set of one to three of the eight directions: 92. */
        val SETS: List<Set<TurnModifier>> = buildList {
            for (i in EIGHT.indices) {
                add(linkedSetOf(EIGHT[i]))
                for (j in i + 1 until EIGHT.size) {
                    add(linkedSetOf(EIGHT[i], EIGHT[j]))
                    for (k in j + 1 until EIGHT.size) add(linkedSetOf(EIGHT[i], EIGHT[j], EIGHT[k]))
                }
            }
        }

        /**
         * The lanes roads paint: one direction, straight with one turn, a turn to each side with or without straight, the
         * U-turn with a left turn or with straight.
         */
        val COMMON: List<Set<TurnModifier>> = EIGHT.map { setOf(it) } +
            listOf(SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT).map { setOf(STRAIGHT, it) } +
            listOf(
                setOf(LEFT, RIGHT), setOf(SLIGHT_LEFT, SLIGHT_RIGHT), setOf(SLIGHT_LEFT, RIGHT), setOf(LEFT, SLIGHT_RIGHT),
                setOf(LEFT, STRAIGHT, RIGHT), setOf(SLIGHT_LEFT, STRAIGHT, SLIGHT_RIGHT), setOf(SLIGHT_LEFT, STRAIGHT, RIGHT), setOf(LEFT, STRAIGHT, SLIGHT_RIGHT),
                setOf(U_TURN, LEFT),
            )

        /** Where each direction's head points, a unit step on the screen (y down). */
        val HEADINGS: Map<TurnModifier, Pair<Float, Float>> = mapOf(
            STRAIGHT to (0f to -1f),
            SLIGHT_LEFT to (-0.70710677f to -0.70710677f),
            LEFT to (-1f to 0f),
            SHARP_LEFT to (-0.70710677f to 0.70710677f),
            U_TURN to (0f to 1f),
            SLIGHT_RIGHT to (0.70710677f to -0.70710677f),
            RIGHT to (1f to 0f),
            SHARP_RIGHT to (0.70710677f to 0.70710677f),
        )
    }
}
