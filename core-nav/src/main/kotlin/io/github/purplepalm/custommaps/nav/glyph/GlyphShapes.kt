package io.github.purplepalm.custommaps.nav.glyph

import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A point of a glyph, in shares of the side of its square: [x] from the left edge, [y] from the top,
 * 0 to 1 across the square. A glyph drawn into a box that is not square keeps to the middle of it,
 * as large as the box's shorter side.
 */
data class GlyphPoint(val x: Float, val y: Float)

/** One step of a glyph's line after its start ([GlyphPart.Line]). */
sealed interface GlyphStep {
    /** A straight piece to [to]. */
    data class LineTo(val to: GlyphPoint) : GlyphStep

    /**
     * Part of the oval that fills [left] to [right] and [top] to [bottom], from [startDegrees] on the
     * oval by [sweepDegrees], as a canvas path's arc: 0 degrees is to the right of its middle, and the
     * angles grow clockwise on the screen. The line goes straight to where the arc starts.
     */
    data class ArcTo(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val startDegrees: Float,
        val sweepDegrees: Float,
    ) : GlyphStep
}

/** One thing a glyph is drawn of. Widths and radii are shares of the side of its square, as points are. */
sealed interface GlyphPart {
    /** A line from [start] through [steps], [width] wide, with round ends and round corners. */
    data class Line(val start: GlyphPoint, val steps: List<GlyphStep>, val width: Float) : GlyphPart

    /** A straight line from [from] to [to], [width] wide, with round ends. */
    data class Segment(val from: GlyphPoint, val to: GlyphPoint, val width: Float) : GlyphPart

    /** The outline of a circle, [width] wide. */
    data class Ring(val center: GlyphPoint, val radius: Float, val width: Float) : GlyphPart

    /** A filled circle. */
    data class Dot(val center: GlyphPoint, val radius: Float) : GlyphPart
}

/** How a part of a lane is coloured: as a lane to take, dim as every other arrow, or cut out of what is drawn under it. */
enum class GlyphInk {
    LIT,
    DIM,

    /**
     * No colour: the part takes away the lane's own ink drawn under it, so the background shows through, as the inside
     * of an outline does. Drawn after every coloured part of its lane, on a layer of the lane's own.
     */
    HOLE,
}

/** A part of a lane's glyph with its colour, in drawing order: a later part lies over an earlier one. */
data class InkedPart(val part: GlyphPart, val ink: GlyphInk)

/**
 * The shapes of the app's own maneuver and lane arrows (DECISIONS 19), as plain data in one place:
 * the phone's card draws them with Compose and the car's routing card draws them on a canvas, so the
 * two screens always show the same arrows, and an arrow added here reaches both.
 *
 * A maneuver is a single bold arrow. At a roundabout the exit is drawn where its degrees put it,
 * falling back to the turn when they are unknown; the arrival is the destination's mark.
 */
object GlyphShapes {

    /** The maneuver arrow's line, as a share of the glyph. */
    const val MANEUVER_STROKE = 0.11f

    /** The route's arrow in a lane to take. */
    const val LANE_STROKE_ACTIVE = 0.15f

    /** Every other lane arrow. */
    const val LANE_STROKE_OTHER = 0.09f

    /** An outline lane's arrow, its outside: as wide as the route's arrow in a lane to take, so its hollow reads. */
    const val LANE_OUTLINE_STROKE = 0.15f

    /** An outline lane's arrow, its hollow: the rest of [LANE_OUTLINE_STROKE] is a rim of 0.04 on each side. */
    const val LANE_OUTLINE_HOLE = 0.07f

    /** The kinds drawn as a roundabout. */
    val ROUNDABOUTS: Set<ManeuverType> = setOf(
        ManeuverType.ROUNDABOUT,
        ManeuverType.ROUNDABOUT_TURN,
        ManeuverType.EXIT_ROUNDABOUT,
        ManeuverType.ROTARY,
        ManeuverType.EXIT_ROTARY,
    )

    /** The arrow of a maneuver of [type] that turns [turn], with a roundabout's exit at [roundaboutExitDegrees]. */
    fun maneuver(type: ManeuverType, turn: TurnModifier?, roundaboutExitDegrees: Double? = null): List<GlyphPart> = when {
        type == ManeuverType.ARRIVE -> destination()
        type in ROUNDABOUTS -> roundabout(roundaboutArmAngle(roundaboutExitDegrees, turn))
        turn == TurnModifier.U_TURN -> uTurn(MANEUVER_STROKE)
        else -> turn(turn.angle(), MANEUVER_STROKE)
    }

    /**
     * One lane: every arrow painted on it, a straight one when it has none, the route's arrow
     * [GlyphInk.LIT] and the others [GlyphInk.DIM], the route's arrow last. In a lane to take
     * ([isLaneToTake]) the route's arrow is also drawn heavier than any other lane arrow
     * ([laneStroke]), since the two colours alone differ too little in lightness to tell at a glance
     * in the sun.
     *
     * A lane drawn as an outline ([Lane.outline]) is never lit: every arrow of it is hollow, [LANE_OUTLINE_STROKE] wide
     * in [GlyphInk.DIM] with a [LANE_OUTLINE_HOLE] cut out of its middle ([GlyphInk.HOLE]), so it differs from a lane
     * not to take by its shape, not by a colour.
     */
    fun lane(lane: Lane): List<InkedPart> {
        val arrows = lane.indications.ifEmpty { setOf(TurnModifier.STRAIGHT) }
        if (lane.outline) {
            val outside = arrows.flatMap { arrow -> laneArrow(arrow, LANE_OUTLINE_STROKE).map { InkedPart(it, GlyphInk.DIM) } }
            val hollow = arrows.flatMap { arrow -> laneArrow(arrow, LANE_OUTLINE_HOLE).map { InkedPart(it, GlyphInk.HOLE) } }
            return outside + hollow
        }
        val active = lane.activeIndication.takeIf { lane.isRecommended }
        val dim = arrows.filter { it != active }.flatMap { arrow -> laneArrow(arrow, laneArrowStroke(lane, arrow)).map { InkedPart(it, GlyphInk.DIM) } }
        val lit = active?.let { arrow -> laneArrow(arrow, laneArrowStroke(lane, arrow)).map { InkedPart(it, GlyphInk.LIT) } }.orEmpty()
        return dim + lit
    }

    /**
     * Whether [lane] is one to take, and so marked by shape as well as colour: a heavier arrow and a
     * bar under it. Every lane the route can be followed from is, but a lane kept for car pools or
     * buses never is: it is drawn dim, and where it is the only lane lit it keeps its colour alone.
     * Nor is a lane drawn as an outline, which is never lit.
     */
    fun isLaneToTake(lane: Lane): Boolean = lane.isRecommended && !lane.designated && !lane.outline

    /** How heavy [arrow] of [lane] is drawn: the route's arrow in a lane to take is the heavy one; an outline's arrows have their own width. */
    fun laneArrowStroke(lane: Lane, arrow: TurnModifier): Float =
        if (lane.outline) LANE_OUTLINE_STROKE else laneStroke(active = isLaneToTake(lane) && arrow == lane.activeIndication)

    /**
     * The width of a lane arrow's line, as a share of the glyph: the route's arrow in a lane to take
     * ([active]) at [LANE_STROKE_ACTIVE], every other lane arrow at [LANE_STROKE_OTHER]; the maneuver
     * arrow's [MANEUVER_STROKE] lies between the two.
     */
    fun laneStroke(active: Boolean): Float = if (active) LANE_STROKE_ACTIVE else LANE_STROKE_OTHER

    /**
     * Direction of a roundabout's exit arm, in degrees from straight ahead, positive to the right.
     * Traffic goes round counter-clockwise, as on the right-hand side of the road, so the exit at 90
     * degrees is on the right. An exit back the way the driver came is drawn just beside the entry
     * rather than over it.
     */
    fun roundaboutArmAngle(exitDegrees: Double?, turn: TurnModifier?): Float {
        val angle = when {
            exitDegrees != null -> 180f - exitDegrees.toFloat()
            turn == TurnModifier.U_TURN -> -180f
            else -> turn.angle()
        }
        return angle.coerceIn(-MAX_ARM_ANGLE, MAX_ARM_ANGLE)
    }

    private const val MAX_ARM_ANGLE = 160f

    /** Direction of travel in degrees, 0 is straight ahead and positive turns right. */
    private fun TurnModifier?.angle(): Float = when (this) {
        TurnModifier.SHARP_RIGHT -> 135f
        TurnModifier.RIGHT -> 90f
        TurnModifier.SLIGHT_RIGHT -> 45f
        TurnModifier.SLIGHT_LEFT -> -45f
        TurnModifier.LEFT -> -90f
        TurnModifier.SHARP_LEFT -> -135f
        TurnModifier.STRAIGHT, TurnModifier.U_TURN, null -> 0f
    }

    private fun laneArrow(turn: TurnModifier, width: Float): List<GlyphPart> =
        if (turn == TurnModifier.U_TURN) uTurn(width) else turn(turn.angle(), width)

    private fun pointAlong(from: GlyphPoint, degrees: Float, length: Float): GlyphPoint {
        val radians = degrees * PI / 180.0
        return GlyphPoint(from.x + (length * sin(radians)).toFloat(), from.y - (length * cos(radians)).toFloat())
    }

    private fun head(tip: GlyphPoint, degrees: Float, width: Float): GlyphPart {
        val left = pointAlong(tip, degrees + 180f - HEAD_SPREAD, HEAD_LENGTH)
        val right = pointAlong(tip, degrees + 180f + HEAD_SPREAD, HEAD_LENGTH)
        return GlyphPart.Line(left, listOf(GlyphStep.LineTo(tip), GlyphStep.LineTo(right)), width)
    }

    private fun turn(degrees: Float, width: Float): List<GlyphPart> {
        val base = GlyphPoint(0.5f, 0.92f)
        val pivot = GlyphPoint(0.5f, 0.52f)
        val tip = pointAlong(pivot, degrees, TURN_LENGTH)
        return listOf(GlyphPart.Line(base, listOf(GlyphStep.LineTo(pivot), GlyphStep.LineTo(tip)), width), head(tip, degrees, width))
    }

    /** A left U-turn, as driven on the right-hand side of the road. */
    private fun uTurn(width: Float): List<GlyphPart> {
        val base = GlyphPoint(0.64f, 0.92f)
        val up = GlyphPoint(0.64f, 0.42f)
        val end = GlyphPoint(0.30f, 0.68f)
        val line = GlyphPart.Line(
            base,
            listOf(
                GlyphStep.LineTo(up),
                GlyphStep.ArcTo(left = 0.30f, top = 0.25f, right = 0.64f, bottom = 0.59f, startDegrees = 0f, sweepDegrees = -180f),
                GlyphStep.LineTo(end),
            ),
            width,
        )
        return listOf(line, head(end, 180f, width))
    }

    private fun roundabout(exitDegrees: Float): List<GlyphPart> {
        val center = GlyphPoint(0.5f, 0.42f)
        val radius = 0.16f
        val exitStart = pointAlong(center, exitDegrees, radius)
        val tip = pointAlong(exitStart, exitDegrees, 0.22f)
        return listOf(
            GlyphPart.Ring(center, radius, MANEUVER_STROKE),
            GlyphPart.Segment(GlyphPoint(0.5f, 0.92f), GlyphPoint(center.x, center.y + radius), MANEUVER_STROKE),
            GlyphPart.Segment(exitStart, tip, MANEUVER_STROKE),
            head(tip, exitDegrees, MANEUVER_STROKE),
        )
    }

    private fun destination(): List<GlyphPart> {
        val center = GlyphPoint(0.5f, 0.40f)
        return listOf(
            GlyphPart.Ring(center, 0.22f, MANEUVER_STROKE),
            GlyphPart.Dot(center, 0.08f),
            GlyphPart.Segment(GlyphPoint(0.5f, 0.62f), GlyphPoint(0.5f, 0.92f), MANEUVER_STROKE),
        )
    }

    /** How long an arrow's head is, and how far its two sides open from the line, in degrees. */
    private const val HEAD_LENGTH = 0.17f
    private const val HEAD_SPREAD = 34f

    /** How long a turn's arm is past the pivot. */
    private const val TURN_LENGTH = 0.34f
}
