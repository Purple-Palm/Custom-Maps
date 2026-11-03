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

/** The maneuver drawn as a single bold arrow, in the app's own style. */
@Composable
fun ManeuverGlyph(type: ManeuverType, turn: TurnModifier?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when {
            type == ManeuverType.ARRIVE -> drawDestination(color)
            type in ROUNDABOUTS -> drawRoundabout(turn.angle(), color)
            turn == TurnModifier.U_TURN -> drawUTurn(color)
            else -> drawTurn(turn.angle(), color)
        }
    }
}

/** One lane: every arrow painted on it, the one the route takes highlighted. */
@Composable
fun LaneGlyph(lane: Lane, highlight: Color, dim: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arrows = lane.indications.ifEmpty { setOf(TurnModifier.STRAIGHT) }
        val active = lane.activeIndication.takeIf { lane.isRecommended }
        arrows.filter { it != active }.forEach { drawLaneArrow(it, dim) }
        active?.let { drawLaneArrow(it, highlight) }
    }
}

private val ROUNDABOUTS = setOf(
    ManeuverType.ROUNDABOUT,
    ManeuverType.ROUNDABOUT_TURN,
    ManeuverType.EXIT_ROUNDABOUT,
    ManeuverType.ROTARY,
    ManeuverType.EXIT_ROTARY,
)

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

private fun DrawScope.drawLaneArrow(turn: TurnModifier, color: Color) {
    if (turn == TurnModifier.U_TURN) drawUTurn(color) else drawTurn(turn.angle(), color)
}

private fun DrawScope.stroke() = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.point(x: Float, y: Float) = Offset(size.width / 2 + (x - 0.5f) * size.minDimension, size.height / 2 + (y - 0.5f) * size.minDimension)

private fun pointAlong(from: Offset, degrees: Float, length: Float): Offset {
    val radians = degrees * PI / 180.0
    return Offset(from.x + (length * sin(radians)).toFloat(), from.y - (length * cos(radians)).toFloat())
}

private fun DrawScope.drawHead(tip: Offset, degrees: Float, color: Color) {
    val length = size.minDimension * 0.17f
    val left = pointAlong(tip, degrees + 180f - 34f, length)
    val right = pointAlong(tip, degrees + 180f + 34f, length)
    drawPath(Path().apply { moveTo(left.x, left.y); lineTo(tip.x, tip.y); lineTo(right.x, right.y) }, color, style = stroke())
}

private fun DrawScope.drawTurn(degrees: Float, color: Color) {
    val base = point(0.5f, 0.92f)
    val pivot = point(0.5f, 0.52f)
    val tip = pointAlong(pivot, degrees, size.minDimension * 0.34f)
    drawPath(Path().apply { moveTo(base.x, base.y); lineTo(pivot.x, pivot.y); lineTo(tip.x, tip.y) }, color, style = stroke())
    drawHead(tip, degrees, color)
}

/** A left U-turn, as driven on the right-hand side of the road. */
private fun DrawScope.drawUTurn(color: Color) {
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
    drawPath(path, color, style = stroke())
    drawHead(end, 180f, color)
}

private fun DrawScope.drawRoundabout(exitDegrees: Float, color: Color) {
    val center = point(0.5f, 0.42f)
    val radius = size.minDimension * 0.16f
    drawCircle(color, radius, center, style = stroke())
    val entry = point(0.5f, 0.92f)
    drawLine(color, entry, Offset(center.x, center.y + radius), strokeWidth = size.minDimension * 0.11f, cap = StrokeCap.Round)
    val exitStart = pointAlong(center, exitDegrees, radius)
    val tip = pointAlong(exitStart, exitDegrees, size.minDimension * 0.22f)
    drawLine(color, exitStart, tip, strokeWidth = size.minDimension * 0.11f, cap = StrokeCap.Round)
    drawHead(tip, exitDegrees, color)
}

private fun DrawScope.drawDestination(color: Color) {
    val center = point(0.5f, 0.40f)
    drawCircle(color, size.minDimension * 0.22f, center, style = stroke())
    drawCircle(color, size.minDimension * 0.08f, center)
    drawLine(color, point(0.5f, 0.62f), point(0.5f, 0.92f), strokeWidth = size.minDimension * 0.11f, cap = StrokeCap.Round)
}
