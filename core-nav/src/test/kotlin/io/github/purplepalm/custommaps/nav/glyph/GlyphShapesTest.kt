package io.github.purplepalm.custommaps.nav.glyph

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arrows of the phone's card and the car's routing card, as data. The phone drew them straight
 * from code until they moved here; [Reference] is that code's geometry, point for point, in pixels,
 * so the shapes are shown to be the ones the phone drew, on a square glyph and on one that is not.
 */
class GlyphShapesTest {

    @Test
    fun `a turn is the phone's arrow, with its shaft, pivot, arm and head`() {
        for (turn in TurnModifier.entries - TurnModifier.U_TURN + listOf<TurnModifier?>(null)) {
            for (canvas in CANVASES) {
                val parts = GlyphShapes.maneuver(ManeuverType.TURN, turn, null)
                assertSameDrawing(Reference.turn(canvas, Reference.angle(turn), GlyphShapes.MANEUVER_STROKE), parts, canvas, "$turn")
            }
        }
    }

    @Test
    fun `a U-turn is the phone's left U-turn, for any kind but the arrival and roundabouts`() {
        for (kind in listOf(ManeuverType.TURN, ManeuverType.CONTINUE, ManeuverType.END_OF_ROAD, ManeuverType.UNKNOWN)) {
            for (canvas in CANVASES) {
                val parts = GlyphShapes.maneuver(kind, TurnModifier.U_TURN, null)
                assertSameDrawing(Reference.uTurn(canvas, GlyphShapes.MANEUVER_STROKE), parts, canvas, "$kind")
            }
        }
    }

    @Test
    fun `a roundabout's exit arm points where its degrees say, else where the turn goes`() {
        for (degrees in listOf<Double?>(null, 30.0, 90.0, 180.0, 270.0, 345.0, 360.0)) {
            for (turn in listOf(null, TurnModifier.RIGHT, TurnModifier.LEFT, TurnModifier.U_TURN)) {
                for (canvas in CANVASES) {
                    val parts = GlyphShapes.maneuver(ManeuverType.ROUNDABOUT, turn, degrees)
                    val arm = Reference.roundaboutArmAngle(degrees, turn)
                    assertSameDrawing(Reference.roundabout(canvas, arm), parts, canvas, "$degrees $turn")
                }
            }
        }
        assertEquals(90f, GlyphShapes.roundaboutArmAngle(90.0, null))
        assertEquals(-160f, GlyphShapes.roundaboutArmAngle(360.0, null))
        assertEquals(-160f, GlyphShapes.roundaboutArmAngle(null, TurnModifier.U_TURN))
    }

    @Test
    fun `the arrival is the destination's mark whatever its turn`() {
        for (turn in TurnModifier.entries + listOf<TurnModifier?>(null)) {
            for (canvas in CANVASES) {
                assertSameDrawing(Reference.destination(canvas), GlyphShapes.maneuver(ManeuverType.ARRIVE, turn, 90.0), canvas, "$turn")
            }
        }
    }

    @Test
    fun `every arrow of every kind and turn stays inside its square`() {
        for (type in ManeuverType.entries) {
            for (turn in TurnModifier.entries + listOf<TurnModifier?>(null)) {
                for (degrees in listOf<Double?>(null, 1.0, 90.0, 180.0, 270.0, 359.0)) {
                    val parts = GlyphShapes.maneuver(type, turn, degrees)
                    assertTrue(parts.isNotEmpty(), "$type $turn")
                    for (point in parts.flatMap(::pointsOf)) {
                        assertTrue(point.x in 0f..1f && point.y in 0f..1f, "$type $turn $degrees: $point")
                    }
                }
            }
        }
    }

    @Test
    fun `a lane draws its other arrows dim first, then the route's arrow lit`() {
        val lane = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
        val parts = GlyphShapes.lane(lane)
        val straight = GlyphShapes.maneuver(ManeuverType.TURN, TurnModifier.STRAIGHT).map { it.withWidth(GlyphShapes.LANE_STROKE_OTHER) }
        val right = GlyphShapes.maneuver(ManeuverType.TURN, TurnModifier.SLIGHT_RIGHT).map { it.withWidth(GlyphShapes.LANE_STROKE_ACTIVE) }
        assertEquals(straight.map { InkedPart(it, GlyphInk.DIM) } + right.map { InkedPart(it, GlyphInk.LIT) }, parts)
    }

    @Test
    fun `a lane without arrows is a straight one, a lane not to take is all dim, a car pool lane lit in colour alone`() {
        val none = GlyphShapes.lane(Lane(emptySet(), isRecommended = false))
        assertEquals(GlyphShapes.maneuver(ManeuverType.TURN, TurnModifier.STRAIGHT).map { InkedPart(it.withWidth(GlyphShapes.LANE_STROKE_OTHER), GlyphInk.DIM) }, none)
        val notToTake = GlyphShapes.lane(Lane(setOf(TurnModifier.LEFT, TurnModifier.U_TURN), isRecommended = false))
        assertTrue(notToTake.all { it.ink == GlyphInk.DIM })
        val carPool = GlyphShapes.lane(Lane(setOf(TurnModifier.STRAIGHT), true, TurnModifier.STRAIGHT, designated = true))
        assertTrue(carPool.all { it.ink == GlyphInk.LIT && widthOf(it.part) == GlyphShapes.LANE_STROKE_OTHER })
        // A U-turn lane arrow is the phone's U-turn at the lane's width.
        val uTurn = GlyphShapes.lane(Lane(setOf(TurnModifier.U_TURN), true, TurnModifier.U_TURN))
        assertEquals(GlyphShapes.maneuver(ManeuverType.TURN, TurnModifier.U_TURN).map { InkedPart(it.withWidth(GlyphShapes.LANE_STROKE_ACTIVE), GlyphInk.LIT) }, uTurn)
    }

    @Test
    fun `the lane strokes and which lanes are to take`() {
        assertEquals(0.15f, GlyphShapes.laneStroke(active = true))
        assertEquals(0.09f, GlyphShapes.laneStroke(active = false))
        assertEquals(0.11f, GlyphShapes.MANEUVER_STROKE)
        assertTrue(GlyphShapes.isLaneToTake(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true)))
        assertTrue(!GlyphShapes.isLaneToTake(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, designated = true)))
        assertTrue(!GlyphShapes.isLaneToTake(Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false)))
    }

    /** A canvas of [width] by [height] pixels. */
    private data class Canvas(val width: Float, val height: Float) {
        val side: Float get() = min(width, height)
    }

    /** What the phone drew: lines through pixel points, straight segments, rings and dots, with their widths in pixels. */
    private sealed interface Drawn {
        data class Line(val points: List<Pair<Float, Float>>, val width: Float) : Drawn
        data class Arc(val left: Float, val top: Float, val right: Float, val bottom: Float, val start: Float, val sweep: Float) : Drawn
        data class Segment(val from: Pair<Float, Float>, val to: Pair<Float, Float>, val width: Float) : Drawn
        data class Ring(val center: Pair<Float, Float>, val radius: Float, val width: Float) : Drawn
        data class Dot(val center: Pair<Float, Float>, val radius: Float) : Drawn
    }

    /** [parts] laid on [canvas] the way the phone lays them: in the middle, as large as the shorter side. */
    private fun drawnOf(parts: List<GlyphPart>, canvas: Canvas): List<Drawn> {
        fun px(p: GlyphPoint) = (canvas.width / 2 + (p.x - 0.5f) * canvas.side) to (canvas.height / 2 + (p.y - 0.5f) * canvas.side)
        return parts.flatMap { part ->
            when (part) {
                is GlyphPart.Line -> {
                    // A line with an arc is compared as its straight pieces and the arc's oval.
                    val points = mutableListOf(px(part.start))
                    val arcs = mutableListOf<Drawn>()
                    part.steps.forEach { step ->
                        when (step) {
                            is GlyphStep.LineTo -> points += px(step.to)
                            is GlyphStep.ArcTo -> {
                                val (l, t) = px(GlyphPoint(step.left, step.top))
                                val (r, b) = px(GlyphPoint(step.right, step.bottom))
                                arcs += Drawn.Arc(l, t, r, b, step.startDegrees, step.sweepDegrees)
                            }
                        }
                    }
                    listOf(Drawn.Line(points, canvas.side * part.width)) + arcs
                }
                is GlyphPart.Segment -> listOf(Drawn.Segment(px(part.from), px(part.to), canvas.side * part.width))
                is GlyphPart.Ring -> listOf(Drawn.Ring(px(part.center), canvas.side * part.radius, canvas.side * part.width))
                is GlyphPart.Dot -> listOf(Drawn.Dot(px(part.center), canvas.side * part.radius))
            }
        }
    }

    private fun assertSameDrawing(expected: List<Drawn>, parts: List<GlyphPart>, canvas: Canvas, what: String) {
        val actual = drawnOf(parts, canvas)
        assertEquals(expected.size, actual.size, "$what: parts")
        for ((e, a) in expected.zip(actual)) assertClose(e, a, "$what on $canvas")
    }

    private fun assertClose(expected: Drawn, actual: Drawn, what: String) {
        assertEquals(expected::class, actual::class, what)
        val e = numbers(expected)
        val a = numbers(actual)
        assertEquals(e.size, a.size, what)
        for ((x, y) in e.zip(a)) assertTrue(abs(x - y) < TOLERANCE_PX, "$what: $expected vs $actual")
    }

    private fun numbers(drawn: Drawn): List<Float> = when (drawn) {
        is Drawn.Line -> drawn.points.flatMap { listOf(it.first, it.second) } + drawn.width
        is Drawn.Arc -> listOf(drawn.left, drawn.top, drawn.right, drawn.bottom, drawn.start, drawn.sweep)
        is Drawn.Segment -> listOf(drawn.from.first, drawn.from.second, drawn.to.first, drawn.to.second, drawn.width)
        is Drawn.Ring -> listOf(drawn.center.first, drawn.center.second, drawn.radius, drawn.width)
        is Drawn.Dot -> listOf(drawn.center.first, drawn.center.second, drawn.radius)
    }

    private fun pointsOf(part: GlyphPart): List<GlyphPoint> = when (part) {
        is GlyphPart.Line -> listOf(part.start) + part.steps.flatMap { step ->
            when (step) {
                is GlyphStep.LineTo -> listOf(step.to)
                is GlyphStep.ArcTo -> listOf(GlyphPoint(step.left, step.top), GlyphPoint(step.right, step.bottom))
            }
        }
        is GlyphPart.Segment -> listOf(part.from, part.to)
        is GlyphPart.Ring -> listOf(GlyphPoint(part.center.x - part.radius, part.center.y - part.radius), GlyphPoint(part.center.x + part.radius, part.center.y + part.radius))
        is GlyphPart.Dot -> listOf(part.center)
    }

    private fun widthOf(part: GlyphPart): Float? = when (part) {
        is GlyphPart.Line -> part.width
        is GlyphPart.Segment -> part.width
        is GlyphPart.Ring -> part.width
        is GlyphPart.Dot -> null
    }

    private fun GlyphPart.withWidth(width: Float): GlyphPart = when (this) {
        is GlyphPart.Line -> copy(width = width)
        is GlyphPart.Segment -> copy(width = width)
        is GlyphPart.Ring -> copy(width = width)
        is GlyphPart.Dot -> this
    }

    /**
     * The phone's drawing code before the shapes moved into data (ui/guidance/ManeuverGlyph.kt),
     * kept here as plain geometry: the same points, angles and lengths, worked out in pixels as the
     * phone worked them out.
     */
    private object Reference {
        private fun point(c: Canvas, x: Float, y: Float) = (c.width / 2 + (x - 0.5f) * c.side) to (c.height / 2 + (y - 0.5f) * c.side)

        private fun pointAlong(from: Pair<Float, Float>, degrees: Float, length: Float): Pair<Float, Float> {
            val radians = degrees * PI / 180.0
            return (from.first + (length * sin(radians)).toFloat()) to (from.second - (length * cos(radians)).toFloat())
        }

        fun angle(turn: TurnModifier?): Float = when (turn) {
            TurnModifier.SHARP_RIGHT -> 135f
            TurnModifier.RIGHT -> 90f
            TurnModifier.SLIGHT_RIGHT -> 45f
            TurnModifier.SLIGHT_LEFT -> -45f
            TurnModifier.LEFT -> -90f
            TurnModifier.SHARP_LEFT -> -135f
            TurnModifier.STRAIGHT, TurnModifier.U_TURN, null -> 0f
        }

        fun roundaboutArmAngle(exitDegrees: Double?, turn: TurnModifier?): Float {
            val angle = when {
                exitDegrees != null -> 180f - exitDegrees.toFloat()
                turn == TurnModifier.U_TURN -> -180f
                else -> angle(turn)
            }
            return angle.coerceIn(-160f, 160f)
        }

        private fun head(c: Canvas, tip: Pair<Float, Float>, degrees: Float, share: Float): Drawn {
            val length = c.side * 0.17f
            val left = pointAlong(tip, degrees + 180f - 34f, length)
            val right = pointAlong(tip, degrees + 180f + 34f, length)
            return Drawn.Line(listOf(left, tip, right), c.side * share)
        }

        fun turn(c: Canvas, degrees: Float, share: Float): List<Drawn> {
            val base = point(c, 0.5f, 0.92f)
            val pivot = point(c, 0.5f, 0.52f)
            val tip = pointAlong(pivot, degrees, c.side * 0.34f)
            return listOf(Drawn.Line(listOf(base, pivot, tip), c.side * share), head(c, tip, degrees, share))
        }

        fun uTurn(c: Canvas, share: Float): List<Drawn> {
            val base = point(c, 0.64f, 0.92f)
            val up = point(c, 0.64f, 0.42f)
            val topLeft = point(c, 0.30f, 0.25f)
            val bottomRight = point(c, 0.64f, 0.59f)
            val end = point(c, 0.30f, 0.68f)
            return listOf(
                Drawn.Line(listOf(base, up, end), c.side * share),
                Drawn.Arc(topLeft.first, topLeft.second, bottomRight.first, bottomRight.second, 0f, -180f),
                head(c, end, 180f, share),
            )
        }

        fun roundabout(c: Canvas, exitDegrees: Float): List<Drawn> {
            val center = point(c, 0.5f, 0.42f)
            val radius = c.side * 0.16f
            val width = c.side * 0.11f
            val entry = point(c, 0.5f, 0.92f)
            val exitStart = pointAlong(center, exitDegrees, radius)
            val tip = pointAlong(exitStart, exitDegrees, c.side * 0.22f)
            return listOf(
                Drawn.Ring(center, radius, width),
                Drawn.Segment(entry, center.first to center.second + radius, width),
                Drawn.Segment(exitStart, tip, width),
                head(c, tip, exitDegrees, 0.11f),
            )
        }

        fun destination(c: Canvas): List<Drawn> {
            val center = point(c, 0.5f, 0.40f)
            val width = c.side * 0.11f
            return listOf(
                Drawn.Ring(center, c.side * 0.22f, width),
                Drawn.Dot(center, c.side * 0.08f),
                Drawn.Segment(point(c, 0.5f, 0.62f), point(c, 0.5f, 0.92f), width),
            )
        }
    }

    private companion object {
        /** The phone's card glyph (72 dp at 2.75x), the Then chip's, and one wider than high. */
        val CANVASES = listOf(Canvas(198f, 198f), Canvas(110f, 110f), Canvas(160f, 96f))

        /** Float rounding of the same sums in another order; far below a pixel. */
        const val TOLERANCE_PX = 0.001f
    }
}
