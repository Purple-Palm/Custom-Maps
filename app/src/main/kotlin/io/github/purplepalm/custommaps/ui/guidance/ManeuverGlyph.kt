package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The maneuver drawn as a single bold arrow, in the app's own style. At a roundabout the exit is
 * drawn where [roundaboutExitDegrees] puts it, falling back to the turn when that is unknown.
 */
@Composable
fun ManeuverGlyph(
    type: ManeuverType,
    turn: TurnModifier?,
    color: Color,
    modifier: Modifier = Modifier,
    roundaboutExitDegrees: Double? = null,
) {
    Canvas(modifier) {
        when {
            type == ManeuverType.ARRIVE -> drawDestination(color)
            type in ROUNDABOUTS -> drawRoundabout(roundaboutArmAngle(roundaboutExitDegrees, turn), color)
            turn == TurnModifier.U_TURN -> drawUTurn(color, MANEUVER_STROKE)
            else -> drawTurn(turn.angle(), color, MANEUVER_STROKE)
        }
    }
}

/**
 * What [ManeuverGlyph] draws, as a value: two maneuvers with equal keys get the same arrow. It
 * follows the glyph's own choice above, case by case, so the kind of maneuver only counts for the
 * destination and for roundabouts.
 */
internal fun glyphKey(type: ManeuverType, turn: TurnModifier?, roundaboutExitDegrees: Double?): Any = when {
    type == ManeuverType.ARRIVE -> ManeuverType.ARRIVE
    type in ROUNDABOUTS -> roundaboutArmAngle(roundaboutExitDegrees, turn)
    else -> turn ?: TurnModifier.STRAIGHT
}

/**
 * One lane: every arrow painted on it, the one the route takes in [highlight] and the others in
 * [dim]. In a lane to take ([isLaneToTake]) the route's arrow is also drawn heavier than any other
 * lane arrow ([laneStroke]), since the two colours alone differ too little in lightness to tell
 * at a glance in the sun: 1.91:1 by day, 2.31:1 at night.
 */
@Composable
fun LaneGlyph(lane: Lane, highlight: Color, dim: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arrows = lane.indications.ifEmpty { setOf(TurnModifier.STRAIGHT) }
        val active = lane.activeIndication.takeIf { lane.isRecommended }
        arrows.filter { it != active }.forEach { drawLaneArrow(it, dim, laneArrowStroke(lane, it)) }
        active?.let { drawLaneArrow(it, highlight, laneArrowStroke(lane, it)) }
    }
}

/**
 * Whether [lane] is one to take, and so marked by shape as well as colour: a heavier arrow and a
 * bar under it. Every lane the route can be followed from is, but a lane kept for car pools or
 * buses never is: it is drawn dim, and where it is the only lane lit it keeps its colour alone.
 */
internal fun isLaneToTake(lane: Lane): Boolean = lane.isRecommended && !lane.designated

/** How heavy [arrow] of [lane] is drawn: the route's arrow in a lane to take is the heavy one. */
internal fun laneArrowStroke(lane: Lane, arrow: TurnModifier): Float =
    laneStroke(active = isLaneToTake(lane) && arrow == lane.activeIndication)

/**
 * The width of a lane arrow's line, as a share of the glyph: the route's arrow in a lane to take
 * ([active]) at [LANE_STROKE_ACTIVE], every other lane arrow at [LANE_STROKE_OTHER]; the maneuver
 * arrow's [MANEUVER_STROKE] lies between the two.
 */
internal fun laneStroke(active: Boolean): Float = if (active) LANE_STROKE_ACTIVE else LANE_STROKE_OTHER

/** The maneuver arrow's line, as a share of the glyph. */
internal const val MANEUVER_STROKE = 0.11f

/** The route's arrow in a lane to take. */
internal const val LANE_STROKE_ACTIVE = 0.15f

/** Every other lane arrow. */
internal const val LANE_STROKE_OTHER = 0.09f

internal val ROUNDABOUTS = setOf(
    ManeuverType.ROUNDABOUT,
    ManeuverType.ROUNDABOUT_TURN,
    ManeuverType.EXIT_ROUNDABOUT,
    ManeuverType.ROTARY,
    ManeuverType.EXIT_ROTARY,
)

/**
 * Direction of a roundabout's exit arm, in the same terms as [angle]. Traffic goes round
 * counter-clockwise, as on the right-hand side of the road, so the exit at 90 degrees is on the
 * right. An exit back the way the driver came is drawn just beside the entry rather than over it.
 */
internal fun roundaboutArmAngle(exitDegrees: Double?, turn: TurnModifier?): Float {
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

private fun DrawScope.drawLaneArrow(turn: TurnModifier, color: Color, share: Float) {
    if (turn == TurnModifier.U_TURN) drawUTurn(color, share) else drawTurn(turn.angle(), color, share)
}

/** A line [share] of the glyph wide, with round ends and corners. */
private fun DrawScope.stroke(share: Float = MANEUVER_STROKE) = Stroke(width = size.minDimension * share, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.point(x: Float, y: Float) = Offset(size.width / 2 + (x - 0.5f) * size.minDimension, size.height / 2 + (y - 0.5f) * size.minDimension)

private fun pointAlong(from: Offset, degrees: Float, length: Float): Offset {
    val radians = degrees * PI / 180.0
    return Offset(from.x + (length * sin(radians)).toFloat(), from.y - (length * cos(radians)).toFloat())
}

private fun DrawScope.drawHead(tip: Offset, degrees: Float, color: Color, share: Float) {
    val length = size.minDimension * 0.17f
    val left = pointAlong(tip, degrees + 180f - 34f, length)
    val right = pointAlong(tip, degrees + 180f + 34f, length)
    drawPath(Path().apply { moveTo(left.x, left.y); lineTo(tip.x, tip.y); lineTo(right.x, right.y) }, color, style = stroke(share))
}

private fun DrawScope.drawTurn(degrees: Float, color: Color, share: Float) {
    val base = point(0.5f, 0.92f)
    val pivot = point(0.5f, 0.52f)
    val tip = pointAlong(pivot, degrees, size.minDimension * 0.34f)
    drawPath(Path().apply { moveTo(base.x, base.y); lineTo(pivot.x, pivot.y); lineTo(tip.x, tip.y) }, color, style = stroke(share))
    drawHead(tip, degrees, color, share)
}

/** A left U-turn, as driven on the right-hand side of the road. */
private fun DrawScope.drawUTurn(color: Color, share: Float) {
    val base = point(0.64f, 0.92f)
    val up = point(0.64f, 0.42f)
    val topLeft = point(0.30f, 0.25f)
    val bottomRight = point(0.64f, 0.59f)
    val end = point(0.30f, 0.68f)
    val path = Path().apply {
        moveTo(base.x, base.y)
        lineTo(up.x, up.y)
        arcTo(Rect(topLeft, bottomRight), startAngleDegrees = 0f, sweepAngleDegrees = -180f, forceMoveTo = false)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = stroke(share))
    drawHead(end, 180f, color, share)
}

private fun DrawScope.drawRoundabout(exitDegrees: Float, color: Color) {
    val center = point(0.5f, 0.42f)
    val radius = size.minDimension * 0.16f
    drawCircle(color, radius, center, style = stroke())
    val entry = point(0.5f, 0.92f)
    drawLine(color, entry, Offset(center.x, center.y + radius), strokeWidth = size.minDimension * MANEUVER_STROKE, cap = StrokeCap.Round)
    val exitStart = pointAlong(center, exitDegrees, radius)
    val tip = pointAlong(exitStart, exitDegrees, size.minDimension * 0.22f)
    drawLine(color, exitStart, tip, strokeWidth = size.minDimension * MANEUVER_STROKE, cap = StrokeCap.Round)
    drawHead(tip, exitDegrees, color, MANEUVER_STROKE)
}

private fun DrawScope.drawDestination(color: Color) {
    val center = point(0.5f, 0.40f)
    drawCircle(color, size.minDimension * 0.22f, center, style = stroke())
    drawCircle(color, size.minDimension * 0.08f, center)
    drawLine(color, point(0.5f, 0.62f), point(0.5f, 0.92f), strokeWidth = size.minDimension * MANEUVER_STROKE, cap = StrokeCap.Round)
}
