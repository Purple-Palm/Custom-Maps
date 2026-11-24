package io.github.purplepalm.custommaps.nav.glyph

import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Bend
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Box
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Branch
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Place
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Plan
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.PlanPoint
import io.github.purplepalm.custommaps.nav.glyph.LaneArrows.Weight
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What a part of a lane is: its tile, its stem, a branch's line or head, a hollow cut out of an outline lane, or a side
 * line of a lane kept for car pools or buses. For the checks only: the painters need no such thing.
 */
internal enum class LaneRole { TILE, STEM, LINE, HEAD, HOLLOW, SIDE_LINE }

/**
 * A part of a lane in its ink, in drawing order, with the direction it draws ([turn]; null for the stem, the tile and a
 * side line) and its [role]. The painters take its [part] and [ink] alone, as [inked]; [turn] and [role] are for the
 * checks.
 */
internal data class LaneMark(val part: GlyphPart, val ink: GlyphInk, val turn: TurnModifier? = null, val role: LaneRole = LaneRole.LINE) {
    val inked: InkedPart get() = InkedPart(part, ink)
}

/**
 * The lanes' arrow family (DECISIONS 60): lane-use arrows as the road paints and signs them, one stem per lane with a
 * square foot, every direction a branch of the stem ending in a solid triangular head. A turn leaves the stem low under
 * a straight arrow's head, a slight turn beside a straight arrow forks high as a Y, turns to both sides make a cross; a
 * sharp turn goes up, a short way across and back down at 45 degrees, angular, so it is never taken for the U-turn; the
 * U-turn and a left turn have the hook alone on top and the left arm low under it.
 *
 * All numbers are shares of the lane's square, x from the left and y from the top. A lane is first laid out in a
 * [Plan], its stem on the line x = 0 with its foot at [FOOT] and a straight arrow's tip at [TIP], then placed in the
 * square by a [Box] ([place]). Lines and heads are in the square's shares, never scaled with the box but by the fit of
 * a lane too large for it.
 */
internal object LaneArrows {

    /** How heavy an arrow is drawn, as shares of the lane's square: its line, its head's base width and its head's length. */
    data class Weight(val line: Float, val headWidth: Float, val headLength: Float) {
        /** Everything [f] times as large: a lane drawn smaller to fit its box. */
        fun times(f: Float) = Weight(line * f, headWidth * f, headLength * f)

        /** The heads [f] times as large, the line as it is: heads where a lane crowds them. */
        fun headsTimes(f: Float) = Weight(line, headWidth * f, headLength * f)

        companion object {
            /** The heaviest of [weights], part by part: what a lane is placed by, so it stands still lit, dim or hollow. */
            fun most(vararg weights: Weight) = Weight(weights.maxOf { it.line }, weights.maxOf { it.headWidth }, weights.maxOf { it.headLength })
        }
    }

    /** A point of the plan: x from the stem, y from the square's top, before the lane is placed in its square. */
    data class PlanPoint(val x: Float, val y: Float)

    /** How a branch leaves the stem. */
    enum class Bend { STRAIGHT, SLIGHT, NORMAL, SHARP, UTURN }

    /**
     * One direction of a lane, a branch of the lane's one stem. In the plan the stem stands on x = 0 from [FOOT] up; a
     * branch leaves it at [split] (from the top), bends with [radius] (a round bend of 45, 90 or 180 degrees), or for a
     * sharp turn runs [hook] straight across and comes back down at 45 degrees, then runs [reach] on to its tip. [side]
     * is -1 left, 1 right, 0 straight on; the U-turn goes left, as driven on the right.
     */
    data class Branch(val turn: TurnModifier, val side: Int, val bend: Bend, val split: Float, val radius: Float, val reach: Float, val hook: Float = 0f)

    /** A lane's branches, where its stem ends at the top ([stemTop], the lowest split) and how large its heads are. */
    data class Plan(val branches: List<Branch>, val stemTop: Float, val heads: Float)

    /** Where the arrows go in the square: x = [ox] + [k] x, y = [oy] + [k] y; weights times [fit] (a lane too large for its box). */
    data class Place(val k: Float, val ox: Float, val oy: Float, val fit: Float) {
        fun pt(p: PlanPoint) = GlyphPoint(ox + k * p.x, oy + k * p.y)
    }

    /**
     * Where the arrows go in the square: the stem's foot at [footY], a straight arrow's tip at [tipY]; no part beyond
     * [left], [right] and [top], nor more than [below] under the foot, or the lane is drawn smaller about its foot.
     */
    data class Box(val footY: Float, val tipY: Float, val left: Float, val right: Float, val top: Float, val below: Float) {
        val k: Float get() = (footY - tipY) / (FOOT - TIP)
    }

    /** Where the stem's foot and a straight arrow's tip are in the plan (the plan is the square of a 0.92 tall arrow). */
    const val FOOT = 0.96f
    const val TIP = 0.04f

    /** How far a branch's line runs into its head, so the two always meet. */
    const val OVERLAP = 0.02f

    /** How large the heads are where a lane crowds them: a U-turn with another arrow, two turns on one side, four arrows. */
    const val CROWDED = 0.85f

    const val D45 = 0.70710677f

    private val LEFT_ORDER = listOf(SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN)
    private val RIGHT_ORDER = listOf(SLIGHT_RIGHT, RIGHT, SHARP_RIGHT)

    fun sideOf(turn: TurnModifier): Int = when (turn) {
        SLIGHT_LEFT, LEFT, SHARP_LEFT, U_TURN -> -1
        SLIGHT_RIGHT, RIGHT, SHARP_RIGHT -> 1
        STRAIGHT -> 0
    }

    fun bendOf(turn: TurnModifier): Bend = when (turn) {
        STRAIGHT -> Bend.STRAIGHT
        SLIGHT_LEFT, SLIGHT_RIGHT -> Bend.SLIGHT
        LEFT, RIGHT -> Bend.NORMAL
        SHARP_LEFT, SHARP_RIGHT -> Bend.SHARP
        U_TURN -> Bend.UTURN
    }

    // ---- The layout table. ----

    /** The plan of a lane with [arrows]: its branches, its stem's top and its heads' size. */
    fun plan(arrows: Set<TurnModifier>): Plan {
        val straight = STRAIGHT in arrows
        val lefts = LEFT_ORDER.filter { it in arrows }
        val rights = RIGHT_ORDER.filter { it in arrows }
        val turns = side(lefts, straight, rights.isNotEmpty()) + side(rights, straight, lefts.isNotEmpty())
        val stemTop = turns.maxOfOrNull { it.split } ?: STRAIGHT_STEM_TOP
        val all = (if (straight) listOf(Branch(STRAIGHT, 0, Bend.STRAIGHT, stemTop, 0f, stemTop - TIP)) else emptyList()) + turns
        // A slight turn beside a straight arrow with a turn on the other side too: three heads share the top.
        val slightFork = straight && lefts.isNotEmpty() && rights.isNotEmpty() && (SLIGHT_LEFT in arrows || SLIGHT_RIGHT in arrows)
        val crowded = (U_TURN in arrows && arrows.size >= 2) || lefts.size >= 2 || rights.size >= 2 || arrows.size >= 4 || slightFork
        return Plan(all, stemTop, if (crowded) CROWDED else 1f)
    }

    /** Where a lone straight arrow's stem ends: under its head. */
    const val STRAIGHT_STEM_TOP = 0.34f

    /** The turns of one side: one alone, two as a pair roads paint or stacked, three or more stacked, every one drawn. */
    private fun side(turns: List<TurnModifier>, straight: Boolean, twoSides: Boolean): List<Branch> = when {
        turns.isEmpty() -> emptyList()
        turns.size == 1 -> listOf(single(turns.single(), straight, twoSides))
        turns.size == 2 -> pair(turns, straight) ?: stack(turns, straight, twoSides)
        else -> stack(turns, straight, twoSides)
    }

    /**
     * One turn alone on its side. Without a straight arrow it has the upper part of the square to itself; with one it
     * leaves the stem lower, so its head keeps clear of the straight head on top (a slight turn forks low and reaches
     * high, a Y; a turn and a sharp turn pass under the straight head; a U-turn hooks low).
     */
    fun single(turn: TurnModifier, straight: Boolean, twoSides: Boolean): Branch {
        val s = sideOf(turn)
        return when (bendOf(turn)) {
            Bend.SLIGHT -> when {
                !straight -> Branch(turn, s, Bend.SLIGHT, 0.56f, 0.20f, 0.50f)
                twoSides -> Branch(turn, s, Bend.SLIGHT, 0.88f, 0.09f, 0.62f)
                else -> Branch(turn, s, Bend.SLIGHT, 0.80f, 0.10f, 0.86f)
            }
            Bend.NORMAL -> when {
                !straight -> Branch(turn, s, Bend.NORMAL, 0.50f, 0.16f, if (twoSides) 0.32f else 0.44f)
                else -> Branch(turn, s, Bend.NORMAL, 0.74f, 0.13f, if (twoSides) 0.35f else 0.42f)
            }
            Bend.SHARP -> when {
                !straight -> Branch(turn, s, Bend.SHARP, SHARP_PIVOT, 0f, SHARP_REACH, SHARP_HOOK)
                else -> Branch(turn, s, Bend.SHARP, SHARP_UNDER_PIVOT, 0f, SHARP_UNDER_REACH, SHARP_UNDER_HOOK)
            }
            Bend.UTURN -> when {
                !straight -> Branch(turn, s, Bend.UTURN, 0.31f, 0.21f, 0.50f)
                else -> Branch(turn, s, Bend.UTURN, 0.64f, 0.18f, 0.32f)
            }
            Bend.STRAIGHT -> error("straight is not a turn")
        }
    }

    /** A sharp turn alone: up the stem to its pivot, a short way across, back down at 45 degrees. */
    const val SHARP_PIVOT = 0.22f
    const val SHARP_HOOK = 0.14f
    const val SHARP_REACH = 0.72f

    /** A sharp turn beside a straight arrow: its pivot under the straight head. */
    const val SHARP_UNDER_PIVOT = 0.50f
    const val SHARP_UNDER_HOOK = 0.16f
    const val SHARP_UNDER_REACH = 0.58f

    /** The U-turn and a left turn: the hook on top, the left arm low under it, its head out past the hook's. */
    const val U_LEFT_SPLIT = 0.28f
    const val U_LEFT_RADIUS = 0.17f
    const val U_LEFT_REACH = 0.29f
    const val LEFT_UNDER_U_SPLIT = 0.86f
    const val LEFT_UNDER_U_RADIUS = 0.08f
    const val LEFT_UNDER_U_REACH = 0.62f

    /**
     * Two turns on one side that roads paint together, laid out apart, or null for any other two. The U-turn and a left
     * turn: the hook alone on top, the left arm low under it, its head out past the hook's. A slight turn above a turn;
     * a turn above a sharp turn, which then leaves the stem lower; a slight turn above a sharp turn.
     */
    private fun pair(turns: List<TurnModifier>, straight: Boolean): List<Branch>? {
        val (a, b) = turns
        val s = sideOf(a)
        val ka = bendOf(a)
        val kb = bendOf(b)
        return when {
            ka == Bend.NORMAL && kb == Bend.UTURN && !straight -> listOf(
                Branch(b, s, Bend.UTURN, U_LEFT_SPLIT, U_LEFT_RADIUS, U_LEFT_REACH),
                Branch(a, s, Bend.NORMAL, LEFT_UNDER_U_SPLIT, LEFT_UNDER_U_RADIUS, LEFT_UNDER_U_REACH),
            )
            straight -> null
            ka == Bend.SLIGHT && kb == Bend.NORMAL -> listOf(Branch(a, s, Bend.SLIGHT, 0.56f, 0.20f, 0.50f), Branch(b, s, Bend.NORMAL, 0.86f, 0.12f, 0.44f))
            ka == Bend.NORMAL && kb == Bend.SHARP -> listOf(Branch(a, s, Bend.NORMAL, 0.40f, 0.12f, 0.46f), Branch(b, s, Bend.SHARP, 0.60f, 0f, 0.44f, 0.12f))
            ka == Bend.SLIGHT && kb == Bend.SHARP -> listOf(Branch(a, s, Bend.SLIGHT, 0.56f, 0.20f, 0.50f), Branch(b, s, Bend.SHARP, 0.64f, 0f, 0.40f, 0.10f))
            else -> null
        }
    }

    /**
     * Turns on one side that roads hardly ever paint together: the U-turn, else the least sharp, as if alone, each next
     * one leaving the stem lower in a tighter bend, its reach cut so its head stays above the foot. Their heads may
     * touch: these lanes are not laid out one by one (the fit keeps them in the square).
     */
    private fun stack(turns: List<TurnModifier>, straight: Boolean, twoSides: Boolean): List<Branch> {
        val ordered = if (U_TURN in turns) listOf(U_TURN) + (turns - U_TURN) else turns
        val first = single(ordered.first(), straight, twoSides)
        return listOf(first) + ordered.drop(1).mapIndexed { i, turn ->
            val alone = single(turn, straight, twoSides)
            val split = (first.split + 0.24f * (i + 1)).coerceAtMost(0.86f)
            val branch = alone.copy(split = split, radius = if (alone.bend == Bend.SHARP) 0f else 0.10f, hook = if (alone.bend == Bend.SHARP) 0.10f else 0f)
            val end = bendEnd(branch)
            val h = heading(branch)
            val most = if (h.y > 0.01f) (FOOT - end.y) / h.y else branch.reach
            branch.copy(reach = min(branch.reach, max(most, 0.34f)))
        }
    }

    // ---- Geometry of a branch, in the plan. ----

    private fun degreesOf(bend: Bend): Float = when (bend) {
        Bend.STRAIGHT -> 0f
        Bend.SLIGHT -> 45f
        Bend.NORMAL -> 90f
        Bend.SHARP -> 135f
        Bend.UTURN -> 180f
    }

    /** Which way a branch heads at its tip, a unit step on the screen (y down). */
    fun heading(b: Branch): PlanPoint = when (b.bend) {
        Bend.STRAIGHT -> PlanPoint(0f, -1f)
        Bend.SLIGHT -> PlanPoint(b.side * D45, -D45)
        Bend.NORMAL -> PlanPoint(b.side.toFloat(), 0f)
        Bend.SHARP -> PlanPoint(b.side * D45, D45)
        Bend.UTURN -> PlanPoint(0f, 1f)
    }

    /** Where a branch's bend (or a sharp turn's way across) ends, and its last straight piece begins. */
    fun bendEnd(b: Branch): PlanPoint = when (b.bend) {
        Bend.STRAIGHT -> PlanPoint(0f, b.split)
        Bend.SHARP -> PlanPoint(b.side * b.hook, b.split)
        else -> {
            val a = degreesOf(b.bend) * PI / 180.0
            PlanPoint(b.side * b.radius * (1 - cos(a)).toFloat(), b.split - b.radius * sin(a).toFloat())
        }
    }

    /** The tip of a branch's head. */
    fun tip(b: Branch): PlanPoint =
        if (b.bend == Bend.STRAIGHT) PlanPoint(0f, TIP) else bendEnd(b).let { e -> heading(b).let { h -> PlanPoint(e.x + b.reach * h.x, e.y + b.reach * h.y) } }

    // ---- Parts, in the square. ----

    /** The bend's arc as a canvas path's: clockwise on the screen to the right, the other way to the left. */
    private fun arc(place: Place, b: Branch): GlyphStep.ArcTo {
        val r = b.radius
        val y = b.split
        val deg = degreesOf(b.bend)
        return if (b.side > 0) {
            val a = place.pt(PlanPoint(0f, y - r))
            val c = place.pt(PlanPoint(2 * r, y + r))
            GlyphStep.ArcTo(a.x, a.y, c.x, c.y, 180f, deg)
        } else {
            val a = place.pt(PlanPoint(-2 * r, y - r))
            val c = place.pt(PlanPoint(0f, y + r))
            GlyphStep.ArcTo(a.x, a.y, c.x, c.y, 0f, -deg)
        }
    }

    /** The steps of a branch's line from the stem's top through its bend, without its end. */
    private fun steps(plan: Plan, place: Place, b: Branch): List<GlyphStep> {
        val out = ArrayList<GlyphStep>()
        if (b.bend != Bend.STRAIGHT && b.split < plan.stemTop - 1e-4f) out += GlyphStep.LineTo(place.pt(PlanPoint(0f, b.split)))
        when (b.bend) {
            Bend.SLIGHT, Bend.NORMAL, Bend.UTURN -> out += arc(place, b)
            Bend.SHARP -> out += GlyphStep.LineTo(place.pt(bendEnd(b)))
            Bend.STRAIGHT -> {}
        }
        return out
    }

    /**
     * [b]'s line at [w] (weights already in the square's shares), from the stem's top through its bend, ending [into]
     * inside its head, so the round end never shows.
     */
    fun line(plan: Plan, place: Place, b: Branch, w: Weight, into: Float = OVERLAP): GlyphPart.Line {
        val t = place.pt(tip(b))
        val h = heading(b)
        val back = w.headLength - into
        val end = GlyphPoint(t.x - back * h.x, t.y - back * h.y)
        return GlyphPart.Line(place.pt(PlanPoint(0f, plan.stemTop)), steps(plan, place, b) + GlyphStep.LineTo(end), w.line)
    }

    /** [b]'s head at [w]: its tip, then its base's two ends. */
    fun headCorners(place: Place, b: Branch, w: Weight): List<GlyphPoint> {
        val t = place.pt(tip(b))
        val h = heading(b)
        val bx = t.x - w.headLength * h.x
        val by = t.y - w.headLength * h.y
        val half = w.headWidth / 2
        return listOf(t, GlyphPoint(bx - half * h.y, by + half * h.x), GlyphPoint(bx + half * h.y, by - half * h.x))
    }

    /** A filled shape with straight sides through [points]. */
    fun fill(points: List<GlyphPoint>): GlyphPart.Fill = GlyphPart.Fill(points.first(), points.drop(1).map { GlyphStep.LineTo(it) })

    /** The stem from the foot up to the stem's top, square at the foot, [width] wide, its foot [footInset] higher. */
    fun stem(plan: Plan, place: Place, width: Float, footInset: Float = 0f): GlyphPart.Fill {
        val x = place.pt(PlanPoint(0f, 0f)).x
        val foot = place.pt(PlanPoint(0f, FOOT)).y - footInset
        val top = place.pt(PlanPoint(0f, plan.stemTop)).y
        val half = width / 2
        return fill(listOf(GlyphPoint(x - half, foot), GlyphPoint(x - half, top), GlyphPoint(x + half, top), GlyphPoint(x + half, foot)))
    }

    /** [b] at [w]: its line and its head. */
    fun branch(plan: Plan, place: Place, b: Branch, w: Weight): List<GlyphPart> = listOf(line(plan, place, b, w), fill(headCorners(place, b, w)))

    /** The hollow of an outline arrow's branch: its line less [rim] each side, carried into the head, the head drawn in by [rim]. */
    fun hollow(plan: Plan, place: Place, b: Branch, w: Weight, rim: Float): List<GlyphPart> {
        val line = line(plan, place, b, Weight(w.line - 2 * rim, w.headWidth, w.headLength), into = HOLLOW_INTO_HEAD)
        return listOf(line, fill(inset(headCorners(place, b, w), rim)))
    }

    /** How far an outline arrow's hollow line runs past its head's base, so the two hollows meet. */
    const val HOLLOW_INTO_HEAD = 0.10f

    /** The triangle [corners] with every side moved [by] inwards (about the middle of its inscribed circle). */
    fun inset(corners: List<GlyphPoint>, by: Float): List<GlyphPoint> {
        val (a, b, c) = corners
        val la = hypot(b.x - c.x, b.y - c.y)
        val lb = hypot(c.x - a.x, c.y - a.y)
        val lc = hypot(a.x - b.x, a.y - b.y)
        val perimeter = la + lb + lc
        val ix = (la * a.x + lb * b.x + lc * c.x) / perimeter
        val iy = (la * a.y + lb * b.y + lc * c.y) / perimeter
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2
        val inradius = 2 * area / perimeter
        val k = ((inradius - by) / inradius).coerceAtLeast(0f)
        return corners.map { GlyphPoint(ix + (it.x - ix) * k, iy + (it.y - iy) * k) }
    }

    // ---- Placing a lane in its square. ----

    /**
     * Where [plan] goes in [box], placed by its parts at [most] (the heaviest weight it is drawn at, so a lane stands in
     * the same place lit, dim or hollow): centred across, its foot on the box's foot; a lane larger than the box drawn
     * smaller about its foot, lines and heads alike.
     */
    fun place(plan: Plan, box: Box, most: Weight): Place {
        val k0 = box.k
        val probe = Place(k0, 0f, box.footY - k0 * FOOT, 1f)
        val w = most.headsTimes(plan.heads)
        val parts = plan.branches.flatMap { branch(plan, probe, it, w) } + stem(plan, probe, w.line)
        val b = Bounds.of(parts)
        val foot = box.footY
        val lowest = foot + box.below
        // Smaller only where the lane is wider or taller than the box allows; a lane reaching under the foot first
        // moves up by as much (its foot leaves the row's line only then, in lanes roads hardly paint).
        val fit = minOf(1f, (box.right - box.left) / (b.right - b.left), (lowest - box.top) / (b.bottom - b.top))
        val k = k0 * fit
        val ox = (box.left + box.right) / 2 - fit * (b.left + b.right) / 2
        var oy = foot - k * FOOT
        val bottom = foot + fit * (b.bottom - foot)
        if (bottom > lowest) oy -= bottom - lowest
        val top = foot + fit * (b.top - foot) + (oy - (foot - k * FOOT))
        if (top < box.top) oy += box.top - top
        return Place(k, ox, oy, fit)
    }

    /** The box a set of parts covers, lines with their half widths, arcs sampled. */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            fun of(parts: List<GlyphPart>): Bounds {
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                fun take(x: Float, y: Float, half: Float) {
                    l = min(l, x - half)
                    r = max(r, x + half)
                    t = min(t, y - half)
                    b = max(b, y + half)
                }
                fun path(start: GlyphPoint, steps: List<GlyphStep>, half: Float) {
                    take(start.x, start.y, half)
                    for (s in steps) {
                        when (s) {
                            is GlyphStep.LineTo -> take(s.to.x, s.to.y, half)
                            is GlyphStep.ArcTo -> {
                                val cx = (s.left + s.right) / 2
                                val cy = (s.top + s.bottom) / 2
                                val rx = (s.right - s.left) / 2
                                val ry = (s.bottom - s.top) / 2
                                for (i in 0..24) {
                                    val a = Math.toRadians((s.startDegrees + s.sweepDegrees * i / 24f).toDouble())
                                    take(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat(), half)
                                }
                            }
                        }
                    }
                }
                for (p in parts) {
                    when (p) {
                        is GlyphPart.Line -> path(p.start, p.steps, p.width / 2)
                        is GlyphPart.Fill -> path(p.start, p.steps, 0f)
                        is GlyphPart.Segment -> {
                            take(p.from.x, p.from.y, p.width / 2)
                            take(p.to.x, p.to.y, p.width / 2)
                        }
                        is GlyphPart.Ring -> {
                            take(p.center.x - p.radius, p.center.y - p.radius, p.width / 2)
                            take(p.center.x + p.radius, p.center.y + p.radius, p.width / 2)
                        }
                        is GlyphPart.Dot -> {
                            take(p.center.x - p.radius, p.center.y - p.radius, 0f)
                            take(p.center.x + p.radius, p.center.y + p.radius, 0f)
                        }
                    }
                }
                return Bounds(l, t, r, b)
            }
        }
    }
}

/**
 * The lanes of the cards, the owner's choice of 2026-11-24 (DECISIONS 60): a lane to take is an amber tile
 * ([GlyphInk.TILE]) with its arrows on it in the dark ink of icons on amber ([GlyphInk.ON_TILE]), the route's arrow
 * bold and the lane's other arrows thin; every other lane is plain grey arrows ([GlyphInk.DIM]). Every lane stands in
 * the same place of its square whatever its state, and the arrows drawn are [GlyphShapes.laneArrows], the ones the
 * car's host gets for its cluster.
 */
internal object LaneShapes {

    /** The route's arrow on a tile. */
    val BOLD = Weight(0.17f, 0.44f, 0.29f)

    /** The lane's other arrows on a tile: never thinner than 0.08, so a shared lane still says what else it allows. */
    val THIN = Weight(0.08f, 0.29f, 0.21f)

    /** Every arrow of a tile whose route arrow the data does not name. */
    val ANY = Weight(0.14f, 0.34f, 0.24f)

    /** Every arrow of a lane not to take. */
    val DIM = Weight(0.12f, 0.36f, 0.25f)

    /** An outline lane's arrows, their outside, and the rim left round their hollow. */
    val OUTLINE = Weight(0.18f, 0.40f, 0.28f)
    const val RIM = 0.05f

    /** How large an outline lane's hollow heads are in a lane of two or more arrows. */
    const val OUTLINE_CROWDED = 0.85f

    /** The tile: inset from the square on every side, round corners. */
    const val TILE_INSET = 0.02f
    const val TILE_RADIUS = 0.16f

    /** Where the arrows go: the same box on a tile and off it, so a lane's arrows stand in one place lit or not. */
    val BOX = Box(footY = 0.91f, tipY = 0.09f, left = 0.08f, right = 0.92f, top = 0.08f, below = 0.03f)

    /** The side lines of a car-pool lane: solid, the road's mark of a lane apart, inside the lane's square. */
    const val SIDE_LINE = 0.04f
    const val SIDE_X = 0.03f
    const val SIDE_TOP = 0.14f
    const val SIDE_BOTTOM = 0.86f

    /** The heaviest of the weights a lane is drawn at: what every lane is placed by. */
    private val MOST = Weight.most(BOLD, ANY, DIM, OUTLINE)

    /** The parts of [lane] in drawing order, a later one over an earlier one. */
    fun lane(lane: Lane): List<LaneMark> {
        val arrows = GlyphShapes.laneArrows(lane)
        val plan = LaneArrows.plan(arrows)
        val place = LaneArrows.place(plan, BOX, MOST)
        if (lane.outline) return outlineLane(plan, place, OUTLINE, RIM)
        // The arrows the route takes from the lane: the one it names, every one where it names none, none at all.
        val route = GlyphShapes.routeArrows(lane)
        if (GlyphShapes.isLaneToTake(lane)) {
            val tile = LaneMark(tile(), GlyphInk.TILE, null, LaneRole.TILE)
            val f = place.fit
            val routeWeight = (if (lane.activeIndication == null) ANY else BOLD).headsTimes(plan.heads).times(f)
            val thin = THIN.headsTimes(plan.heads).times(f)
            val others = plan.branches.filter { it.turn !in route }.flatMap { b -> marks(plan, place, b, thin, GlyphInk.ON_TILE) }
            val stem = LaneMark(LaneArrows.stem(plan, place, routeWeight.line), GlyphInk.ON_TILE, null, LaneRole.STEM)
            val bright = plan.branches.filter { it.turn in route }.flatMap { b -> marks(plan, place, b, routeWeight, GlyphInk.ON_TILE) }
            return listOf(tile) + others + stem + bright
        }
        // A lane not to take; a car-pool lane the route marks as the only one is lit by colour alone.
        val w = DIM.headsTimes(plan.heads).times(place.fit)
        val lines = if (lane.designated) sideLines().map { LaneMark(it, GlyphInk.DIM, null, LaneRole.SIDE_LINE) } else emptyList()
        val dim = plan.branches.filter { it.turn !in route }.flatMap { b -> marks(plan, place, b, w, GlyphInk.DIM) }
        val stem = LaneMark(LaneArrows.stem(plan, place, w.line), if (route.isEmpty()) GlyphInk.DIM else GlyphInk.LIT, null, LaneRole.STEM)
        val bright = plan.branches.filter { it.turn in route }.flatMap { b -> marks(plan, place, b, w, GlyphInk.LIT) }
        return lines + dim + stem + bright
    }

    /** The tile: a square with round corners, inset by [TILE_INSET]. */
    fun tile(): GlyphPart.Fill {
        val lo = TILE_INSET
        val hi = 1f - TILE_INSET
        val d = 2 * TILE_RADIUS
        return GlyphPart.Fill(
            GlyphPoint(lo + TILE_RADIUS, lo),
            listOf(
                GlyphStep.ArcTo(hi - d, lo, hi, lo + d, 270f, 90f),
                GlyphStep.ArcTo(hi - d, hi - d, hi, hi, 0f, 90f),
                GlyphStep.ArcTo(lo, hi - d, lo + d, hi, 90f, 90f),
                GlyphStep.ArcTo(lo, lo, lo + d, lo + d, 180f, 90f),
            ),
        )
    }

    /** The solid lines at the sides of a lane kept for car pools or buses: the road's mark of a lane apart. */
    private fun sideLines(): List<GlyphPart> =
        listOf(SIDE_X, 1f - SIDE_X).map { GlyphPart.Segment(GlyphPoint(it, SIDE_TOP), GlyphPoint(it, SIDE_BOTTOM), SIDE_LINE) }

    /** An outline lane at [outside] weight: every part in [GlyphInk.DIM], then the hollow cut out to a rim of [rim]. */
    private fun outlineLane(plan: Plan, place: Place, outside: Weight, rim: Float): List<LaneMark> {
        // Hollow heads are large: in a lane of two or more arrows they are drawn smaller, so they keep apart.
        val heads = if (plan.branches.size >= 2) minOf(plan.heads, OUTLINE_CROWDED) else plan.heads
        val w = outside.headsTimes(heads).times(place.fit)
        val r = rim * place.fit
        val out = plan.branches.flatMap { b ->
            val (line, head) = LaneArrows.branch(plan, place, b, w)
            listOf(LaneMark(line, GlyphInk.DIM, b.turn, LaneRole.LINE), LaneMark(head, GlyphInk.DIM, b.turn, LaneRole.HEAD))
        } + LaneMark(LaneArrows.stem(plan, place, w.line), GlyphInk.DIM, null, LaneRole.STEM)
        val hollow = plan.branches.flatMap { b -> LaneArrows.hollow(plan, place, b, w, r).map { LaneMark(it, GlyphInk.HOLE, b.turn, LaneRole.HOLLOW) } } +
            LaneMark(LaneArrows.stem(plan, place, w.line - 2 * r, footInset = r), GlyphInk.HOLE, null, LaneRole.HOLLOW)
        return out + hollow
    }

    /** [b]'s line and head in [ink]. */
    private fun marks(plan: Plan, place: Place, b: Branch, w: Weight, ink: GlyphInk): List<LaneMark> {
        val (line, head) = LaneArrows.branch(plan, place, b, w)
        return listOf(LaneMark(line, ink, b.turn, LaneRole.LINE), LaneMark(head, ink, b.turn, LaneRole.HEAD))
    }
}
