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

/** One step of a glyph's line or filled shape after its start ([GlyphPart.Line], [GlyphPart.Fill]). */
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

    /**
     * A closed shape, from [start] through [steps] and straight back to [start], filled by the non-zero rule, with no
     * line along it. Its corners are sharp; an [GlyphStep.ArcTo] rounds one. The car draws its edge as the shape filled
     * and outlined with a line twice the edge wide, round at the corners, so the edge stands out of the shape by the
     * edge's width on every side, as it does round every other part.
     */
    data class Fill(val start: GlyphPoint, val steps: List<GlyphStep>) : GlyphPart
}

/**
 * How a part of a lane is coloured: in the route's amber, dim as every other arrow, as an amber tile or a dark mark on
 * one, or cut out of what is drawn under it.
 */
enum class GlyphInk {
    /** The route's amber as a line or an arrow: the car's lit ink, the phone's laneOn. */
    LIT,

    /** The look's grey of every other lane arrow. */
    DIM,

    /**
     * No colour: the part takes away the lane's own ink drawn under it, so the background shows through, as the inside
     * of an outline does. Drawn after every coloured part of its lane, on a layer of the lane's own.
     */
    HOLE,

    /** Amber as a fill, a tile with marks on it: the car's lit ink with its dark edge, the phone's accent. */
    TILE,

    /**
     * A mark on a [TILE], in the dark ink of icons on amber, so it reads the same on any card the tile lies on: the car's
     * onTile ink, the phone's onAccent.
     */
    ON_TILE,
}

/** A part of a lane's glyph with its colour, in drawing order: a later part lies over an earlier one. */
data class InkedPart(val part: GlyphPart, val ink: GlyphInk)

/**
 * The shapes of the app's own maneuver and lane arrows (DECISIONS 19), as plain data in one place:
 * the phone's card draws them with Compose and the car's routing card draws them on a canvas, so the
 * two screens always show the same arrows, and an arrow added here reaches both.
 *
 * A maneuver is a single bold arrow. At a roundabout the exit is drawn where its degrees put it,
 * falling back to the turn when they are unknown; the arrival is the destination's mark. A sharp turn
 * goes up the stem past where the other turns bend and comes back down at its angle, its head clear
 * of the stem, so it reads at a glance as a turn back, also at the card's smaller sizes.
 *
 * A lane has arrows of its own family, as the road paints them (DECISIONS 60): one stem with a
 * square foot, every arrow a branch of it with a solid head, and a lane to take an amber tile ([lane]).
 */
object GlyphShapes {

    /** The maneuver arrow's line, as a share of the glyph. */
    const val MANEUVER_STROKE = 0.11f

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
        else -> arrow(turn, MANEUVER_STROKE)
    }

    /**
     * One lane, in drawing order: every arrow of [laneArrows] a branch of one stem with a square foot,
     * each ending in a solid head, standing in the same place of the lane's square whatever the lane's
     * state ([LaneShapes], DECISIONS 60).
     *
     * A lane to take ([isLaneToTake]) is an amber tile ([GlyphInk.TILE]) with its arrows on it in the
     * dark ink of icons on amber ([GlyphInk.ON_TILE]): the route's arrow bold over the stem, the lane's
     * other arrows thin, and every arrow at one weight between the two where the route names none of
     * them. The tile tells the lane to take by its shape, at a glance in the sun, where the two colours
     * alone differ too little in lightness. Every other lane is plain arrows in [GlyphInk.DIM]. A lane
     * kept for car pools or buses has two short solid lines at its sides, inside its square, and where
     * the route marks it as the only lane its route arrows are [GlyphInk.LIT], lit by colour alone.
     *
     * A lane drawn as an outline ([Lane.outline]) is never lit: its arrows are hollow, [GlyphInk.DIM]
     * with their middle cut out ([GlyphInk.HOLE]), so it differs from a lane not to take by its
     * shape, not by a colour.
     */
    fun lane(lane: Lane): List<InkedPart> = LaneShapes.lane(lane).map { it.inked }

    /**
     * The arrows [lane] is drawn with: those painted on it, a straight one for a lane with none, and
     * the route's arrow should the data not list it on the lane. The car's host gets the same arrows
     * for its cluster (`LanesToCar`), so the card and the cluster never disagree.
     */
    fun laneArrows(lane: Lane): Set<TurnModifier> {
        val named = lane.activeIndication.takeIf { lane.isRecommended && !lane.outline }
        return lane.indications.ifEmpty { setOf(TurnModifier.STRAIGHT) } + listOfNotNull(named)
    }

    /**
     * The arrows of [lane] drawn as the route's: in a lane the route can be followed from, the arrow
     * the route names, or every arrow of [laneArrows] where it names none; none in any other lane or
     * in an outline lane. On a lane to take's tile they are the arrows drawn heavier; in a lane kept
     * for car pools or buses that is the only lane the route marks, the lit ones. The host's
     * recommended arrows are the same (`LanesToCar`).
     */
    fun routeArrows(lane: Lane): Set<TurnModifier> = when {
        !lane.isRecommended || lane.outline -> emptySet()
        else -> lane.activeIndication?.let { setOf(it) } ?: laneArrows(lane)
    }

    /**
     * Whether [lane] is one to take, and so drawn on a tile, marked by shape as well as colour. Every
     * lane the route can be followed from is, but a lane kept for car pools or buses never is: it is
     * drawn dim, and where it is the only lane lit it keeps its colour alone. Nor is a lane drawn as
     * an outline, which is never lit.
     */
    fun isLaneToTake(lane: Lane): Boolean = lane.isRecommended && !lane.designated && !lane.outline

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

    /** The arrow of [turn] in lines [width] wide: the U-turn's own, a sharp turn's higher bend, or a plain bend. */
    private fun arrow(turn: TurnModifier?, width: Float): List<GlyphPart> = when (turn) {
        TurnModifier.U_TURN -> uTurn(width)
        TurnModifier.SHARP_RIGHT, TurnModifier.SHARP_LEFT -> bend(turn.angle(), width, SHARP_PIVOT_Y, SHARP_LENGTH)
        else -> bend(turn.angle(), width, PIVOT_Y, TURN_LENGTH)
    }

    private fun pointAlong(from: GlyphPoint, degrees: Float, length: Float): GlyphPoint {
        val radians = degrees * PI / 180.0
        return GlyphPoint(from.x + (length * sin(radians)).toFloat(), from.y - (length * cos(radians)).toFloat())
    }

    private fun head(tip: GlyphPoint, degrees: Float, width: Float): GlyphPart {
        val left = pointAlong(tip, degrees + 180f - HEAD_SPREAD, HEAD_LENGTH)
        val right = pointAlong(tip, degrees + 180f + HEAD_SPREAD, HEAD_LENGTH)
        return GlyphPart.Line(left, listOf(GlyphStep.LineTo(tip), GlyphStep.LineTo(right)), width)
    }

    /**
     * A stem from the bottom up to [pivotY], an arm of [length] from there at [degrees], and the head at its tip. Every bend
     * stands on the same stem, so a lane's arrows meet at its foot.
     */
    private fun bend(degrees: Float, width: Float, pivotY: Float, length: Float): List<GlyphPart> {
        val base = GlyphPoint(0.5f, BASE_Y)
        val pivot = GlyphPoint(0.5f, pivotY)
        val tip = pointAlong(pivot, degrees, length)
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

    /** Where every turn's stem stands, from the top. */
    private const val BASE_Y = 0.92f

    /** Where a turn's arm leaves the stem, from the top. */
    const val PIVOT_Y = 0.52f

    /** How long a turn's arm is past the pivot. */
    const val TURN_LENGTH = 0.34f

    /**
     * Where a sharp turn's arm leaves the stem: higher than the other turns' [PIVOT_Y], so the arm has room to come back
     * down beside the stem with its head clear of it.
     */
    const val SHARP_PIVOT_Y = 0.28f

    /** How long a sharp turn's arm is past its pivot: long enough to carry the head clear of the stem. */
    const val SHARP_LENGTH = 0.52f
}
