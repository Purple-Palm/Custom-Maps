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
import io.github.purplepalm.custommaps.nav.glyph.GlyphInk
import io.github.purplepalm.custommaps.nav.glyph.GlyphPart
import io.github.purplepalm.custommaps.nav.glyph.GlyphPoint
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.glyph.GlyphStep
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier

/**
 * The maneuver drawn as a single bold arrow, in the app's own style. At a roundabout the exit is
 * drawn where [roundaboutExitDegrees] puts it, falling back to the turn when that is unknown. The
 * shapes are [GlyphShapes]', which the car's routing card draws too.
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
        GlyphShapes.maneuver(type, turn, roundaboutExitDegrees).forEach { drawPart(it, color) }
    }
}

/**
 * What [ManeuverGlyph] draws, as a value: two maneuvers with equal keys get the same arrow. It
 * follows the glyph's own choice ([GlyphShapes.maneuver]), case by case, so the kind of maneuver
 * only counts for the destination and for roundabouts.
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
        GlyphShapes.lane(lane).forEach { drawPart(it.part, if (it.ink == GlyphInk.LIT) highlight else dim) }
    }
}

/**
 * Whether [lane] is one to take, and so marked by shape as well as colour: a heavier arrow and a
 * bar under it. Every lane the route can be followed from is, but a lane kept for car pools or
 * buses never is: it is drawn dim, and where it is the only lane lit it keeps its colour alone.
 */
internal fun isLaneToTake(lane: Lane): Boolean = GlyphShapes.isLaneToTake(lane)

/** How heavy [arrow] of [lane] is drawn: the route's arrow in a lane to take is the heavy one. */
internal fun laneArrowStroke(lane: Lane, arrow: TurnModifier): Float = GlyphShapes.laneArrowStroke(lane, arrow)

/**
 * The width of a lane arrow's line, as a share of the glyph: the route's arrow in a lane to take
 * ([active]) at [LANE_STROKE_ACTIVE], every other lane arrow at [LANE_STROKE_OTHER]; the maneuver
 * arrow's [MANEUVER_STROKE] lies between the two.
 */
internal fun laneStroke(active: Boolean): Float = GlyphShapes.laneStroke(active)

/** The maneuver arrow's line, as a share of the glyph. */
internal const val MANEUVER_STROKE = GlyphShapes.MANEUVER_STROKE

/** The route's arrow in a lane to take. */
internal const val LANE_STROKE_ACTIVE = GlyphShapes.LANE_STROKE_ACTIVE

/** Every other lane arrow. */
internal const val LANE_STROKE_OTHER = GlyphShapes.LANE_STROKE_OTHER

internal val ROUNDABOUTS = GlyphShapes.ROUNDABOUTS

/**
 * Direction of a roundabout's exit arm, in degrees from straight ahead, positive to the right.
 * Traffic goes round counter-clockwise, as on the right-hand side of the road, so the exit at 90
 * degrees is on the right. An exit back the way the driver came is drawn just beside the entry
 * rather than over it.
 */
internal fun roundaboutArmAngle(exitDegrees: Double?, turn: TurnModifier?): Float = GlyphShapes.roundaboutArmAngle(exitDegrees, turn)

/** Draws [part] of a glyph in the middle of the canvas, as large as its shorter side. */
private fun DrawScope.drawPart(part: GlyphPart, color: Color) {
    val side = size.minDimension
    when (part) {
        is GlyphPart.Line -> drawPath(pathOf(part), color, style = stroke(part.width))
        is GlyphPart.Segment -> drawLine(color, point(part.from), point(part.to), strokeWidth = side * part.width, cap = StrokeCap.Round)
        is GlyphPart.Ring -> drawCircle(color, side * part.radius, point(part.center), style = stroke(part.width))
        is GlyphPart.Dot -> drawCircle(color, side * part.radius, point(part.center))
    }
}

private fun DrawScope.pathOf(line: GlyphPart.Line): Path = Path().apply {
    val start = point(line.start)
    moveTo(start.x, start.y)
    line.steps.forEach { step ->
        when (step) {
            is GlyphStep.LineTo -> point(step.to).let { lineTo(it.x, it.y) }
            is GlyphStep.ArcTo -> arcTo(
                Rect(point(GlyphPoint(step.left, step.top)), point(GlyphPoint(step.right, step.bottom))),
                startAngleDegrees = step.startDegrees,
                sweepAngleDegrees = step.sweepDegrees,
                forceMoveTo = false,
            )
        }
    }
}

/** A line [share] of the glyph wide, with round ends and corners. */
private fun DrawScope.stroke(share: Float) = Stroke(width = size.minDimension * share, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.point(at: GlyphPoint) = Offset(size.width / 2 + (at.x - 0.5f) * size.minDimension, size.height / 2 + (at.y - 0.5f) * size.minDimension)
