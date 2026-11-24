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
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

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
                    // Two solid lines inside the square, first of all: 0.03 in from each side, from 0.14 to 0.86, 0.04 wide,
                    // whatever the lane's arrows and their fit.
                    assertEquals(listOf(LaneRole.SIDE_LINE, LaneRole.SIDE_LINE), marks.take(2).map { it.role }, what)
                    assertTrue(marks.take(2).all { it.ink == GlyphInk.DIM && it.part is GlyphPart.Segment }, what)
                    assertEquals(
                        listOf(GlyphPart.Segment(GlyphPoint(0.03f, 0.14f), GlyphPoint(0.03f, 0.86f), 0.04f), GlyphPart.Segment(GlyphPoint(0.97f, 0.14f), GlyphPoint(0.97f, 0.86f), 0.04f))
                            .map(::numbersOf),
                        marks.take(2).map { numbersOf(it.part as GlyphPart.Segment) },
                        what,
                    )
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
    fun `a lane still to come has its heads smaller where two or more arrows share it, each hollow a rim inside its head`() {
        for (set in listOf(emptySet<TurnModifier>()) + SETS) {
            val lane = Lane(set, isRecommended = false, outline = true)
            val marks = LaneShapes.lane(lane)
            val what = name(set)
            val fit = fitOf(lane)
            val arrows = GlyphShapes.laneArrows(lane)
            // Hollow heads are large: 0.85 of their size in a lane of two or more arrows, so they keep apart.
            val size = if (arrows.size >= 2) 0.85f else 1f
            val heads = marks.filter { it.role == LaneRole.HEAD }
            val hollows = marks.filter { it.role == LaneRole.HOLLOW && it.part is GlyphPart.Fill && it.turn != null }
            assertEquals(arrows, heads.map { it.turn }.toSet(), what)
            assertEquals(arrows, hollows.map { it.turn }.toSet(), what)
            for (head in heads) {
                val corners = cornersOf(head.part as GlyphPart.Fill)
                assertEquals(LaneShapes.OUTLINE.headWidth * size * fit, baseOf(corners), 1e-5f, "$what: ${head.turn}'s head")
                assertEquals(LaneShapes.OUTLINE.headLength * size * fit, lengthOf(corners), 1e-5f, "$what: ${head.turn}'s head")
                // The hollow is the head drawn in by the rim on every side.
                val hollow = cornersOf(hollows.single { it.turn == head.turn }.part as GlyphPart.Fill)
                assertEquals(inradiusOf(corners) - LaneShapes.RIM * fit, inradiusOf(hollow), 1e-5f, "$what: ${head.turn}'s hollow")
            }
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
        // The design's plans of every lane roads paint, shares of the square to three places: the stem's line and its
        // top, how large the heads are, the fit, and each arrow's tip. All of them, so no number of the layout table
        // moves one of these lanes unseen.
        val design = listOf(
            Stand(setOf(STRAIGHT), 0.500f, 0.357f, 1.00f, 1.000f, STRAIGHT to (0.500f to 0.090f)),
            Stand(setOf(STRAIGHT, SLIGHT_LEFT), 0.674f, 0.767f, 1.00f, 1.000f, STRAIGHT to (0.674f to 0.090f), SLIGHT_LEFT to (0.106f to 0.162f)),
            Stand(
                setOf(STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT), 0.500f, 0.839f, 0.85f, 1.000f,
                STRAIGHT to (0.500f to 0.090f), SLIGHT_LEFT to (0.086f to 0.391f), SLIGHT_RIGHT to (0.914f to 0.391f),
            ),
            Stand(setOf(STRAIGHT, SLIGHT_LEFT, RIGHT), 0.493f, 0.839f, 0.85f, 0.998f, STRAIGHT to (0.493f to 0.092f), SLIGHT_LEFT to (0.080f to 0.392f), RIGHT to (0.920f to 0.599f)),
            Stand(setOf(STRAIGHT, LEFT), 0.635f, 0.714f, 1.00f, 1.000f, STRAIGHT to (0.635f to 0.090f), LEFT to (0.145f to 0.598f)),
            Stand(setOf(STRAIGHT, LEFT, SLIGHT_RIGHT), 0.507f, 0.839f, 0.85f, 0.998f, STRAIGHT to (0.507f to 0.092f), LEFT to (0.080f to 0.599f), SLIGHT_RIGHT to (0.920f to 0.392f)),
            Stand(setOf(STRAIGHT, LEFT, RIGHT), 0.500f, 0.718f, 1.00f, 0.982f, STRAIGHT to (0.500f to 0.105f), LEFT to (0.080f to 0.604f), RIGHT to (0.920f to 0.604f)),
            Stand(setOf(STRAIGHT, SHARP_LEFT), 0.644f, 0.500f, 1.00f, 1.000f, STRAIGHT to (0.644f to 0.090f), SHARP_LEFT to (0.136f to 0.866f)),
            Stand(setOf(STRAIGHT, U_TURN), 0.660f, 0.625f, 0.85f, 1.000f, STRAIGHT to (0.660f to 0.090f), U_TURN to (0.340f to 0.910f)),
            Stand(setOf(STRAIGHT, SLIGHT_RIGHT), 0.326f, 0.767f, 1.00f, 1.000f, STRAIGHT to (0.326f to 0.090f), SLIGHT_RIGHT to (0.894f to 0.162f)),
            Stand(setOf(STRAIGHT, RIGHT), 0.365f, 0.714f, 1.00f, 1.000f, STRAIGHT to (0.365f to 0.090f), RIGHT to (0.855f to 0.598f)),
            Stand(setOf(STRAIGHT, SHARP_RIGHT), 0.356f, 0.500f, 1.00f, 1.000f, STRAIGHT to (0.356f to 0.090f), SHARP_RIGHT to (0.864f to 0.866f)),
            Stand(setOf(SLIGHT_LEFT), 0.639f, 0.553f, 1.00f, 1.000f, SLIGHT_LEFT to (0.271f to 0.112f)),
            Stand(setOf(SLIGHT_LEFT, SLIGHT_RIGHT), 0.500f, 0.553f, 1.00f, 1.000f, SLIGHT_LEFT to (0.133f to 0.112f), SLIGHT_RIGHT to (0.867f to 0.112f)),
            Stand(setOf(SLIGHT_LEFT, RIGHT), 0.470f, 0.553f, 1.00f, 1.000f, SLIGHT_LEFT to (0.102f to 0.112f), RIGHT to (0.898f to 0.357f)),
            Stand(setOf(LEFT), 0.722f, 0.500f, 1.00f, 1.000f, LEFT to (0.188f to 0.357f)),
            Stand(setOf(LEFT, U_TURN), 0.763f, 0.826f, 0.85f, 0.984f, U_TURN to (0.464f to 0.572f), LEFT to (0.149f to 0.756f)),
            Stand(setOf(LEFT, SLIGHT_RIGHT), 0.530f, 0.553f, 1.00f, 1.000f, LEFT to (0.102f to 0.357f), SLIGHT_RIGHT to (0.898f to 0.112f)),
            Stand(setOf(LEFT, RIGHT), 0.500f, 0.507f, 1.00f, 0.982f, LEFT to (0.080f to 0.367f), RIGHT to (0.920f to 0.367f)),
            Stand(setOf(SHARP_LEFT), 0.744f, 0.250f, 1.00f, 1.000f, SHARP_LEFT to (0.166f to 0.704f)),
            Stand(setOf(U_TURN), 0.752f, 0.357f, 1.00f, 1.000f, U_TURN to (0.378f to 0.803f)),
            Stand(setOf(SLIGHT_RIGHT), 0.361f, 0.553f, 1.00f, 1.000f, SLIGHT_RIGHT to (0.729f to 0.112f)),
            Stand(setOf(RIGHT), 0.278f, 0.500f, 1.00f, 1.000f, RIGHT to (0.812f to 0.357f)),
            Stand(setOf(SHARP_RIGHT), 0.256f, 0.250f, 1.00f, 1.000f, SHARP_RIGHT to (0.834f to 0.704f)),
        )
        assertEquals(COMMON.toSet(), design.map { it.set }.toSet())
        assertAll(
            design.map { stand ->
                Executable {
                    val what = name(stand.set)
                    val marks = LaneShapes.lane(Lane(stand.set, isRecommended = false))
                    val stem = cornersOf(marks.single { it.role == LaneRole.STEM }.part as GlyphPart.Fill)
                    val left = stem.minOf { it.x }
                    val right = stem.maxOf { it.x }
                    // The fit as drawn: a lane not to take has its stem at the dim line, times the fit.
                    val fit = (right - left) / LaneShapes.DIM.line
                    assertEquals(stand.stemX, (left + right) / 2, PLACES, "$what: the stem's line")
                    assertEquals(stand.stemTop, stem.minOf { it.y }, PLACES, "$what: the stem's top")
                    assertEquals(stand.fit, fit, PLACES, "$what: the fit")
                    val heads = marks.filter { it.role == LaneRole.HEAD }
                    assertEquals(stand.tips.keys, heads.map { it.turn }.toSet(), what)
                    for (head in heads) {
                        val corners = cornersOf(head.part as GlyphPart.Fill)
                        val (x, y) = stand.tips.getValue(head.turn!!)
                        assertEquals(x, corners[0].x, PLACES, "$what: ${head.turn}'s tip")
                        assertEquals(y, corners[0].y, PLACES, "$what: ${head.turn}'s tip")
                        // Its base the dim heads' width, times the lane's heads and its fit.
                        assertEquals(stand.heads, baseOf(corners) / (LaneShapes.DIM.headWidth * fit), PLACES, "$what: ${head.turn}'s head")
                    }
                }
            },
        )
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
        return abs(baseOf(cornersOf(mark.part as GlyphPart.Fill)) - thinWidth) < 1e-5f
    }

    /** The fit of [lane]'s arrows in the box: its stem's line drawn at the dim or the route's weight shows it. */
    private fun fitOf(lane: Lane): Float {
        val plan = LaneArrows.plan(GlyphShapes.laneArrows(lane))
        return LaneArrows.place(plan, LaneShapes.BOX, LaneArrows.Weight.most(LaneShapes.BOLD, LaneShapes.ANY, LaneShapes.DIM, LaneShapes.OUTLINE)).fit
    }

    private fun cornersOf(fill: GlyphPart.Fill): List<GlyphPoint> = listOf(fill.start) + fill.steps.map { (it as GlyphStep.LineTo).to }

    /** How wide a head is at its base: from one end of its base to the other ([corners] its tip, then the base's ends). */
    private fun baseOf(corners: List<GlyphPoint>): Float = hypot(corners[1].x - corners[2].x, corners[1].y - corners[2].y)

    /** How long a head is: from the middle of its base to its tip. */
    private fun lengthOf(corners: List<GlyphPoint>): Float =
        hypot(corners[0].x - (corners[1].x + corners[2].x) / 2, corners[0].y - (corners[1].y + corners[2].y) / 2)

    /** The radius of the largest circle inside the triangle [corners]: twice its area over its perimeter. */
    private fun inradiusOf(corners: List<GlyphPoint>): Float {
        val (a, b, c) = corners
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2
        return 2 * area / (hypot(b.x - c.x, b.y - c.y) + hypot(c.x - a.x, c.y - a.y) + hypot(a.x - b.x, a.y - b.y))
    }

    /**
     * Where the design put a lane of [set] in its square: its stem's line ([stemX]) and top, how large its heads are
     * ([heads], the crowded factor), its [fit], and each arrow's tip ([tip], x and y).
     */
    private class Stand(val set: Set<TurnModifier>, val stemX: Float, val stemTop: Float, val heads: Float, val fit: Float, vararg tip: Pair<TurnModifier, Pair<Float, Float>>) {
        val tips: Map<TurnModifier, Pair<Float, Float>> = tip.toMap()
    }

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

    /** A straight line's numbers to six places: its ends and its width. */
    private fun numbersOf(segment: GlyphPart.Segment): List<Long> =
        listOf(segment.from.x, segment.from.y, segment.to.x, segment.to.y, segment.width).map { Math.round(it * 1e6) }

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

        /** How near a number comes to the design's, which it printed to three places. */
        const val PLACES = 0.0006f

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
