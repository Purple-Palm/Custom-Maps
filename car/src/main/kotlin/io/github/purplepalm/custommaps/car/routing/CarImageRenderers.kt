package io.github.purplepalm.custommaps.car.routing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.glyph.GlyphInk
import io.github.purplepalm.custommaps.nav.glyph.GlyphPart
import io.github.purplepalm.custommaps.nav.glyph.GlyphPoint
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.glyph.GlyphStep
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import kotlin.math.min

/** Which maneuver of the routing card an arrow is for, and so its colour. */
enum class ArrowRole {
    /** The maneuver to make now, and the arrival: amber. */
    NOW,

    /** The one after it, the Then step: the card's ink. */
    THEN,
}

/**
 * The maneuver arrow of the car's routing card, in the app's own style (DECISIONS 19): the shapes of
 * the phone's card ([GlyphShapes]) drawn on a transparent square of [ManeuverIconLayout.SIDE_DP] dp
 * at the car's density, in the colours of the look ([CarImageColors]).
 *
 * Each arrow is drawn once for each change of what it shows: its shapes, its role, the look and the
 * density are the key of a small cache, so a progress update that keeps the maneuver draws nothing.
 * Drawing cannot reach the caller with an exception: a failure writes a "car_error" line with its
 * class, and the card goes on without the arrow, which the host allows; an arrow that failed once is
 * not tried again. Main thread only.
 *
 * @param density the car screen's pixels to a dp, read at each call
 */
class ManeuverIconRenderer(private val density: () -> Float, private val log: DebugLog) {

    private data class Key(val parts: List<GlyphPart>, val role: ArrowRole, val look: Look, val density: Float)

    private val cache = ImageCache<Key, CarIcon>(CAPACITY)
    private val failed = BoundedSet<Key>(CAPACITY)

    /** How many arrows were drawn so far. */
    val drawn: Int get() = cache.drawn

    /** The arrow of [parts] for [role] in [look], or null when it cannot be drawn. */
    fun icon(parts: List<GlyphPart>, role: ArrowRole, look: Look): CarIcon? {
        val key = try {
            Key(parts, role, look, density())
        } catch (e: RuntimeException) {
            log.log(ERROR_LINE, CarFailure.fields("maneuverIcon", e))
            return null
        }
        if (key in failed) return null
        return try {
            cache.get(key) { draw(key) }
        } catch (e: RuntimeException) {
            failed += key
            log.log(ERROR_LINE, CarFailure.fields("maneuverIcon", e) + ("look" to look.name))
            null
        }
    }

    private fun draw(key: Key): CarIcon {
        val side = ManeuverIconLayout.side(key.density)
        val bitmap = createBitmap(side, side)
        val colors = CarImageColors.of(key.look)
        val ink = if (key.role == ArrowRole.NOW) colors.lit else colors.then
        val box = PixelBox(0, 0, side, side)
        GlyphPainter.paint(Canvas(bitmap), key.parts.map { it to ink }, box, CarImageColors.edgePx(key.density, side.toFloat()))
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private companion object {
        /** The arrows of a few steps and their Then steps, in both looks. */
        const val CAPACITY = 8
    }
}

/**
 * The lanes image of the car's routing card: the lane row the phone's card shows at that moment,
 * the same lanes from the left with the same lanes to take ([GlyphShapes.isLaneToTake]: the heavier
 * arrow and the bar under it), lanes kept for car pools and buses dim as on the phone. It is the row
 * alone, without the card's words for it or a lane choice's distance: the distance changes with every
 * update, which would draw the image again each time, and the host shows the image too small for words.
 *
 * Drawn once for each lane row, look and density on a transparent image of [LanesImageLayout.WIDTH_DP]
 * by [LanesImageLayout.HEIGHT_DP] dp ([LanesImageLayout]), and kept in a small cache, so a progress
 * update that keeps the row draws nothing. A failure writes a "car_error" line and gives no image: the
 * caller then sends no lanes at all, as the host takes a step's lanes only with their image. Main thread
 * only.
 *
 * @param density the car screen's pixels to a dp, read at each call
 */
class LanesImageRenderer(private val density: () -> Float, private val log: DebugLog) {

    private data class Key(val lanes: LaneInfo, val look: Look, val density: Float)

    private val cache = ImageCache<Key, CarIcon>(CAPACITY)
    private val failed = BoundedSet<Key>(CAPACITY)

    /** How many images were drawn so far. */
    val drawn: Int get() = cache.drawn

    /** The image of [lanes] in [look], or null when it cannot be drawn. */
    fun image(lanes: LaneInfo, look: Look): CarIcon? {
        val key = try {
            Key(lanes, look, density())
        } catch (e: RuntimeException) {
            log.log(ERROR_LINE, CarFailure.fields("lanesImage", e))
            return null
        }
        if (key in failed) return null
        return try {
            cache.get(key) { draw(key) }
        } catch (e: RuntimeException) {
            failed += key
            log.log(ERROR_LINE, CarFailure.fields("lanesImage", e) + mapOf("look" to look.name, "lanes" to lanes.lanes.size))
            null
        }
    }

    private fun draw(key: Key): CarIcon {
        val lanes = key.lanes.lanes
        val layout = LanesImageLayout.of(lanes.size, key.density)
        val bitmap = createBitmap(layout.image.width, layout.image.height)
        val canvas = Canvas(bitmap)
        val colors = CarImageColors.of(key.look)
        lanes.forEachIndexed { index, lane ->
            val box = layout.arrows(index)
            val parts = GlyphShapes.lane(lane).map { it.part to inkOf(it.ink, colors) }
            val edge = CarImageColors.edgePx(key.density, box.width.toFloat())
            GlyphPainter.paint(canvas, parts, box, edge)
            if (GlyphShapes.isLaneToTake(lane)) GlyphPainter.bar(canvas, layout.bar(index), colors.lit, edge)
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private companion object {
        /** The rows of a run-up, a lane choice and the maneuver after it, in both looks. */
        const val CAPACITY = 6

        /** The car's ink for [ink]: null for a part cut out of what is drawn under it, the hollow of an outline lane. */
        fun inkOf(ink: GlyphInk, colors: CarImageColors): CarInk? = when (ink) {
            GlyphInk.LIT -> colors.lit
            GlyphInk.DIM -> colors.dim
            GlyphInk.HOLE -> null
        }
    }
}

/** The keys that failed to draw, the newest [capacity] of them. */
private class BoundedSet<K : Any>(private val capacity: Int) {
    private val keys = LinkedHashSet<K>()

    operator fun contains(key: K): Boolean = key in keys

    operator fun plusAssign(key: K) {
        keys += key
        if (keys.size > capacity) keys.remove(keys.first())
    }
}

/**
 * Draws glyph parts on a canvas, as the phone's card draws them with Compose: lines with round ends
 * and corners, in the middle of a box and as large as its shorter side. Every part's edge is drawn
 * first and every colour after, so no edge crosses a colour drawn before it. A part without an ink
 * is cut out of what lies under it, to the image's transparent ground: the hollow of an outline
 * lane's arrows, which only that lane's own ink lies under.
 */
private object GlyphPainter {

    fun paint(canvas: Canvas, parts: List<Pair<GlyphPart, CarInk?>>, box: PixelBox, edge: Float) {
        val side = min(box.width, box.height).toFloat()
        val place = Place(box.left + box.width / 2f - side / 2f, box.top + box.height / 2f - side / 2f, side)
        for ((part, ink) in parts) if (ink != null) draw(canvas, part, ink.edge, place, grow = edge)
        for ((part, ink) in parts) if (ink != null) draw(canvas, part, ink.fill, place, grow = 0f)
        for ((part, ink) in parts) if (ink == null) draw(canvas, part, Color.TRANSPARENT, place, grow = 0f, cut = true)
    }

    /** A bar under a lane to take: a short line with round ends, with its edge. */
    fun bar(canvas: Canvas, box: PixelBox, ink: CarInk, edge: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val rect = RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
        paint.color = ink.edge
        val outer = RectF(rect.left - edge, rect.top - edge, rect.right + edge, rect.bottom + edge)
        canvas.drawRoundRect(outer, outer.height() / 2, outer.height() / 2, paint)
        paint.color = ink.fill
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
    }

    /** Where a glyph's square lies: its top left corner and its side, in pixels. */
    private class Place(val left: Float, val top: Float, val side: Float) {
        fun x(point: GlyphPoint) = left + point.x * side

        fun y(point: GlyphPoint) = top + point.y * side
    }

    /** [part] in [color], every line [grow] pixels wider on each side, for the edge under it; with [cut], cleared to transparent. */
    private fun draw(canvas: Canvas, part: GlyphPart, color: Int, place: Place, grow: Float, cut: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (cut) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val side = place.side
        when (part) {
            is GlyphPart.Line -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = part.width * side + 2 * grow
                canvas.drawPath(pathOf(part, place), paint)
            }
            is GlyphPart.Segment -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = part.width * side + 2 * grow
                canvas.drawLine(place.x(part.from), place.y(part.from), place.x(part.to), place.y(part.to), paint)
            }
            is GlyphPart.Ring -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = part.width * side + 2 * grow
                canvas.drawCircle(place.x(part.center), place.y(part.center), part.radius * side, paint)
            }
            is GlyphPart.Dot -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(place.x(part.center), place.y(part.center), part.radius * side + grow, paint)
            }
        }
    }

    private fun pathOf(line: GlyphPart.Line, place: Place): Path = Path().apply {
        moveTo(place.x(line.start), place.y(line.start))
        for (step in line.steps) {
            when (step) {
                is GlyphStep.LineTo -> lineTo(place.x(step.to), place.y(step.to))
                is GlyphStep.ArcTo -> arcTo(
                    RectF(
                        place.x(GlyphPoint(step.left, step.top)),
                        place.y(GlyphPoint(step.left, step.top)),
                        place.x(GlyphPoint(step.right, step.bottom)),
                        place.y(GlyphPoint(step.right, step.bottom)),
                    ),
                    step.startDegrees,
                    step.sweepDegrees,
                    false,
                )
            }
        }
    }
}

private const val ERROR_LINE = "car_error"
