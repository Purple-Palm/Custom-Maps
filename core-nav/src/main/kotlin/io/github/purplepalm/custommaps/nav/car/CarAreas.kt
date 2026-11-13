package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.ViewportPadding
import kotlin.math.roundToInt

/**
 * A rectangle of the car's surface in pixels, as the host reports its visible and its stable area:
 * left and top inside it, right and bottom just past it, as Android's `Rect` has them. An empty one
 * means that the host does not know the area.
 */
data class CarArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/** How far an area lies from each edge of the surface, in pixels, never below zero. */
data class CarInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = CarInsets(0, 0, 0, 0)
    }
}

/** The car camera's two paddings, in pixels: one while it follows the car, one for the overview. */
data class CarPaddings(val following: ViewportPadding, val overview: ViewportPadding)

/**
 * Where the Mapbox logo and the attribution sit on the car's surface, in pixels: each one's left edge
 * from the surface's left edge, and its bottom edge from the surface's bottom edge.
 */
data class OrnamentPlace(val logoX: Int, val logoBottom: Int, val attributionX: Int, val attributionBottom: Int)

/**
 * The car map's room on the surface, worked out from the two areas the host reports and the size of
 * the surface the map draws on now. The visible area is what nothing covers at this moment; the
 * stable area is what stays free while the host's strips come and go ("as if they were always
 * present"). The insets are worked out here from the areas and the surface, on every surface, rather
 * than taken from the map library, which keeps them only for the surface they came with.
 *
 * - While the camera follows the car it frames the stable area, so the car does not move when the
 *   action strip comes or goes: the area's insets plus [MARGIN_DP], but at the bottom the inset plus
 *   a third of the area's height ([FOLLOWING_BOTTOM_SHARE]), which puts the car low in the area. The
 *   visible area stands in until a stable one arrives.
 * - The overview frames the visible area: its insets plus [MARGIN_DP].
 * - The Mapbox logo and the attribution sit in the bottom left corner of the stable area, so no strip
 *   covers them and they do not move ([ornaments]).
 *
 * Without either area the whole surface counts. Pure arithmetic on pixels; the caller gives the
 * density of the car's screen.
 */
object CarAreas {

    /** Around the framed area on every side but the following frame's bottom, as Mapbox's own car camera has it. */
    const val MARGIN_DP = 5.0

    /** How high the car sits in the area while the camera follows it: its bottom padding is this share of the area's height. */
    const val FOLLOWING_BOTTOM_SHARE = 1.0 / 3.0

    /** Between the stable area's edges and the logo and attribution. */
    const val ORNAMENT_MARGIN_DP = 8.0

    /** Between the logo and the attribution. */
    const val ORNAMENT_GAP_DP = 4.0

    /**
     * The least share of the surface's width and of its height that the padding leaves free, whatever
     * the host reports: a camera framed into nothing would have no answer.
     */
    const val MIN_FREE_SHARE = 0.25

    /**
     * The insets of [area] inside a surface of [widthPx] by [heightPx]; null when there is no area, it
     * is empty, or it lies outside the surface. An area that reaches past an edge is cut at it.
     */
    fun insets(widthPx: Int, heightPx: Int, area: CarArea?): CarInsets? {
        if (area == null || area.isEmpty || widthPx <= 0 || heightPx <= 0) return null
        val left = area.left.coerceIn(0, widthPx)
        val top = area.top.coerceIn(0, heightPx)
        val right = area.right.coerceIn(0, widthPx)
        val bottom = area.bottom.coerceIn(0, heightPx)
        if (right <= left || bottom <= top) return null
        return CarInsets(left = left, top = top, right = widthPx - right, bottom = heightPx - bottom)
    }

    /** The camera's paddings on a surface of [widthPx] by [heightPx] at [density] pixels a dp. */
    fun paddings(widthPx: Int, heightPx: Int, density: Float, stable: CarArea?, visible: CarArea?): CarPaddings {
        val margin = px(MARGIN_DP, density)
        val stableInsets = insets(widthPx, heightPx, stable)
        val visibleInsets = insets(widthPx, heightPx, visible)
        val follow = stableInsets ?: visibleInsets ?: CarInsets.NONE
        val areaHeight = heightPx - follow.top - follow.bottom
        val following = fit(
            widthPx,
            heightPx,
            left = follow.left + margin,
            top = follow.top + margin,
            right = follow.right + margin,
            bottom = follow.bottom + (areaHeight * FOLLOWING_BOTTOM_SHARE).roundToInt(),
        )
        val view = visibleInsets ?: stableInsets ?: CarInsets.NONE
        val overview = fit(
            widthPx,
            heightPx,
            left = view.left + margin,
            top = view.top + margin,
            right = view.right + margin,
            bottom = view.bottom + margin,
        )
        return CarPaddings(following, overview)
    }

    /**
     * Where the logo ([logoWidthPx] by [logoHeightPx], as the map library draws it) and the
     * attribution ([attributionHeightPx] high) go: the logo in the bottom left corner of the stable
     * area, [ORNAMENT_MARGIN_DP] in from its edges, and the attribution right of it, centred on the
     * logo's height. The visible area stands in until a stable one arrives, the whole surface without
     * either.
     */
    fun ornaments(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        stable: CarArea?,
        visible: CarArea?,
        logoWidthPx: Int,
        logoHeightPx: Int,
        attributionHeightPx: Int,
    ): OrnamentPlace {
        val area = insets(widthPx, heightPx, stable) ?: insets(widthPx, heightPx, visible) ?: CarInsets.NONE
        val margin = px(ORNAMENT_MARGIN_DP, density)
        val logoX = area.left + margin
        val logoBottom = area.bottom + margin
        return OrnamentPlace(
            logoX = logoX,
            logoBottom = logoBottom,
            attributionX = logoX + logoWidthPx + px(ORNAMENT_GAP_DP, density),
            attributionBottom = logoBottom + (logoHeightPx - attributionHeightPx) / 2,
        )
    }

    /** [dp] in whole pixels at [density]. */
    fun px(dp: Double, density: Float): Int = (dp * density).roundToInt()

    /**
     * The padding with at least [MIN_FREE_SHARE] of each dimension left free: two sides that would
     * take more are made smaller together, each in proportion to its size.
     */
    private fun fit(widthPx: Int, heightPx: Int, left: Int, top: Int, right: Int, bottom: Int): ViewportPadding {
        val (l, r) = share(widthPx, left, right)
        val (t, b) = share(heightPx, top, bottom)
        return ViewportPadding(left = l, top = t, right = r, bottom = b)
    }

    private fun share(size: Int, first: Int, second: Int): Pair<Int, Int> {
        val most = (size * (1.0 - MIN_FREE_SHARE)).toInt().coerceAtLeast(0)
        val sum = first + second
        if (sum <= most) return first to second
        val a = (first.toLong() * most / sum).toInt()
        return a to (most - a)
    }
}
