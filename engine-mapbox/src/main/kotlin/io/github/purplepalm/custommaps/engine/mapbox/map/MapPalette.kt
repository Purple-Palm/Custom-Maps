package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * The app's two looks on top of Mapbox Standard, and the colours of its own marks on the map.
 *
 * At night: night lighting with 3D buildings, a muted monochrome base in graphite tones, platinum
 * labels and one amber accent for the route. By day: Standard's own day light and colourful theme,
 * with every colour the night sets given back to the style. The basemap's config is one table with
 * a value for each look ([keys]), so no key can be set at night and left over by day; [MapLook]
 * writes a look's column one key at a time.
 */
internal object MapPalette {

    /**
     * The ground, the buildings and the roads at night, from the darkest to the lightest: a
     * graphite ground, the buildings one small step above it, and the roads a clear step above
     * both, so a street reads as a band on the ground also close in, where it is a plain surface.
     * Without them Mapbox's own ground was a grey only a little darker than its roads. Water and
     * parks lie a step below the ground: the monochrome theme takes their hue, so only being
     * darker tells a river or a park from the ground. These are configured colors: the night
     * light and the theme change them a little on the way to the screen, so they are a first
     * setting, to be judged on the phone.
     */
    const val LAND = "#1A1D21"
    const val BUILDINGS = "#282B31"
    const val ROADS = "#4A4F57"
    const val TRUNKS = "#5A5F67"
    const val MOTORWAYS = "#6A6F77"
    const val WATER = "#080B0E"
    const val GREENSPACE = "#090C0A"

    /**
     * The road surface where lane details are drawn, in each look: at night the street's grey, by
     * day a dark asphalt under Standard's white markings, which are faint on its own light surface.
     * Only the colour is set by the look: whether lane details are drawn at all is LaneDetails' to
     * set, and never while the style loads.
     */
    const val HD_ROADS_NIGHT = "#4A4F57"
    const val HD_ROADS_DAY = "#575C64"

    /** One key of the basemap's config, with its value in each look. */
    class Key(val name: String, val night: LookValue, val day: LookValue) {
        fun value(look: Look): LookValue = if (look == Look.NIGHT) night else day
    }

    /**
     * Every key a look writes, in the order they are written; by day every colour the night sets
     * goes back to Standard's own, except the road under lane details. Lane details' own switch
     * (showHdRoads) is in neither column: it is the driver's, kept by LaneDetails.
     */
    val keys: List<Key> = listOf(
        Key("lightPreset", LookValue.Text("night"), LookValue.Text("day")),
        // The only bright and colourful theme by day; monochrome would be pale grey.
        Key(MapLook.THEME, LookValue.Text("monochrome"), LookValue.Text("default")),
        Key("show3dObjects", LookValue.Flag(true), LookValue.Flag(true)),
        Key("colorLand", LookValue.Colour(LAND), LookValue.Restore),
        Key("colorBuildings", LookValue.Colour(BUILDINGS), LookValue.Restore),
        Key("colorMotorways", LookValue.Colour(MOTORWAYS), LookValue.Restore),
        Key("colorTrunks", LookValue.Colour(TRUNKS), LookValue.Restore),
        Key("colorRoads", LookValue.Colour(ROADS), LookValue.Restore),
        Key("colorHdRoads", LookValue.Colour(HD_ROADS_NIGHT), LookValue.Colour(HD_ROADS_DAY)),
        Key("colorWater", LookValue.Colour(WATER), LookValue.Restore),
        Key("colorGreenspace", LookValue.Colour(GREENSPACE), LookValue.Restore),
        Key("colorPlaceLabels", LookValue.Colour("#D6D7D9"), LookValue.Restore),
        Key("colorRoadLabels", LookValue.Colour("#A9ADB3"), LookValue.Restore),
        // By day the places' icons keep Standard's own colours; with one grey it would be "single"
        // and #5E646C.
        Key("colorModePointOfInterestLabels", LookValue.Text("single"), LookValue.Text("default")),
        Key("colorPointOfInterestLabels", LookValue.Colour("#8C9198"), LookValue.Restore),
        Key("densityPointOfInterestLabels", LookValue.Number(2L), LookValue.Number(2L)),
    )

    /** [look]'s column: every key with its value, in the order of [keys]. */
    fun column(look: Look): List<Pair<String, LookValue>> = keys.map { it.name to it.value(look) }

    const val AMBER = 0xFFF5A524.toInt()

    /** The route's casing: at night it melts into the dark map, by day it outlines the route. */
    const val CASING_NIGHT = 0xFF3A2800.toInt()
    const val CASING_DAY = 0xFF5C3B00.toInt()

    /**
     * Traffic on the route. Moderate is the same orange in both looks. At night heavy and severe
     * are bright, coral and pink, since a dark red would vanish on the lighter night roads; by day
     * the casing carries every level and the reds stay.
     */
    const val TRAFFIC_MODERATE = 0xFFFF7A1A.toInt()
    const val TRAFFIC_HEAVY_NIGHT = 0xFFFF6B5E.toInt()
    const val TRAFFIC_SEVERE_NIGHT = 0xFFFF5C9E.toInt()
    const val TRAFFIC_HEAVY_DAY = 0xFFE5484D.toInt()
    const val TRAFFIC_SEVERE_DAY = 0xFF9E1B32.toInt()

    /** A closed section of the route: platinum at night, a dark grey by day. */
    const val CLOSED_NIGHT = 0xFFE5E4E2.toInt()
    const val CLOSED_DAY = 0xFF3A3F46.toInt()

    /** Other routes, at every level of traffic, in both looks: a light grey with a graphite casing. */
    const val ALTERNATIVE = 0xFF9AA1AB.toInt()
    const val ALTERNATIVE_CASING = 0xFF2A2E34.toInt()

    /** The maneuver arrow and its casing, the same in both looks. */
    const val PLATINUM = 0xFFE5E4E2.toInt()
    const val GRAPHITE = 0xFF0E1013.toInt()

    /** White paint of the app's own lanes by day; at night it is [PLATINUM], never pure white on the dark map. */
    const val PAINT_WHITE_DAY = 0xFFFFFFFF.toInt()

    /** Yellow paint of the app's own lanes, the left edge line: first settings, to be judged on the phone. */
    const val PAINT_YELLOW_NIGHT = 0xFFC9BD7C.toInt()
    const val PAINT_YELLOW_DAY = 0xFFD6C886.toInt()

    /**
     * A lane that still works beside the lit one, in the app's own lanes: the route's amber laid over the drawn road at
     * [TINT_SHARE_NIGHT] at night and [TINT_SHARE_DAY] by day, as one opaque colour.
     */
    const val TINT_NIGHT = 0xFF7D6948.toInt()
    const val TINT_DAY = 0xFF93784C.toInt()
    const val TINT_SHARE_NIGHT = 0.30
    const val TINT_SHARE_DAY = 0.38

    /** A lane kept for buses, taxis and the like is the drawn road this much darker (a first setting). */
    const val KEPT_LANE_DARKER = 0.25

    /**
     * The colours of the app's own lanes in [look] (the own lanes' design 2.4). The road they are drawn on is the
     * road colour lane details have in the look ([HD_ROADS_NIGHT], [HD_ROADS_DAY]); the lit lane is the route's amber
     * with the route's casing as its edges; jams on the dark drawn road are the night's colours in both looks, since
     * the road is dark in both and the day's reds would not stand out on it.
     */
    fun lanes(look: Look): LaneColours {
        val night = look == Look.NIGHT
        val asphalt = argbOf(if (night) HD_ROADS_NIGHT else HD_ROADS_DAY)
        return LaneColours(
            asphalt = asphalt,
            keptLane = darker(asphalt, KEPT_LANE_DARKER),
            white = if (night) PLATINUM else PAINT_WHITE_DAY,
            yellow = if (night) PAINT_YELLOW_NIGHT else PAINT_YELLOW_DAY,
            lit = AMBER,
            litEdge = if (night) CASING_NIGHT else CASING_DAY,
            tint = if (night) TINT_NIGHT else TINT_DAY,
            arrowEdge = GRAPHITE,
            moderate = TRAFFIC_MODERATE,
            heavy = TRAFFIC_HEAVY_NIGHT,
            severe = TRAFFIC_SEVERE_NIGHT,
            closed = CLOSED_NIGHT,
        )
    }

    /** A colour of the basemap's config, #RRGGBB, as an opaque ARGB value. */
    fun argbOf(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)

    /** [argb], opaque, with each channel [share] darker, rounded. */
    fun darker(argb: Int, share: Double): Int {
        fun channel(shift: Int) = Math.round((argb shr shift and 0xFF) * (1.0 - share)).toInt() shl shift
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    /**
     * The colours the app gives the route line in [look], as plain ARGB values. Every colour it does
     * not name, such as restricted roads, stays the navigation SDK's own.
     */
    fun routeLine(look: Look): Map<RouteLineColor, Int> {
        val night = look == Look.NIGHT
        return mapOf(
            RouteLineColor.ROUTE_DEFAULT to AMBER,
            RouteLineColor.ROUTE_UNKNOWN to AMBER,
            RouteLineColor.ROUTE_LOW to AMBER,
            RouteLineColor.ROUTE_MODERATE to TRAFFIC_MODERATE,
            RouteLineColor.ROUTE_HEAVY to if (night) TRAFFIC_HEAVY_NIGHT else TRAFFIC_HEAVY_DAY,
            RouteLineColor.ROUTE_SEVERE to if (night) TRAFFIC_SEVERE_NIGHT else TRAFFIC_SEVERE_DAY,
            RouteLineColor.ROUTE_CLOSED to if (night) CLOSED_NIGHT else CLOSED_DAY,
            RouteLineColor.ROUTE_CASING to if (night) CASING_NIGHT else CASING_DAY,
            RouteLineColor.OTHER_DEFAULT to ALTERNATIVE,
            RouteLineColor.OTHER_UNKNOWN to ALTERNATIVE,
            RouteLineColor.OTHER_LOW to ALTERNATIVE,
            RouteLineColor.OTHER_MODERATE to ALTERNATIVE,
            RouteLineColor.OTHER_HEAVY to ALTERNATIVE,
            RouteLineColor.OTHER_SEVERE to ALTERNATIVE,
            RouteLineColor.OTHER_CASING to ALTERNATIVE_CASING,
        )
    }

    /** [routeLine] of [look] as the navigation SDK's route line takes it. */
    fun routeLineColorResources(look: Look): RouteLineColorResources {
        val builder = RouteLineColorResources.Builder()
        for ((color, argb) in routeLine(look)) {
            when (color) {
                RouteLineColor.ROUTE_DEFAULT -> builder.routeDefaultColor(argb)
                RouteLineColor.ROUTE_UNKNOWN -> builder.routeUnknownCongestionColor(argb)
                RouteLineColor.ROUTE_LOW -> builder.routeLowCongestionColor(argb)
                RouteLineColor.ROUTE_MODERATE -> builder.routeModerateCongestionColor(argb)
                RouteLineColor.ROUTE_HEAVY -> builder.routeHeavyCongestionColor(argb)
                RouteLineColor.ROUTE_SEVERE -> builder.routeSevereCongestionColor(argb)
                RouteLineColor.ROUTE_CLOSED -> builder.routeClosureColor(argb)
                RouteLineColor.ROUTE_CASING -> builder.routeCasingColor(argb)
                RouteLineColor.OTHER_DEFAULT -> builder.alternativeRouteDefaultColor(argb)
                RouteLineColor.OTHER_UNKNOWN -> builder.alternativeRouteUnknownCongestionColor(argb)
                RouteLineColor.OTHER_LOW -> builder.alternativeRouteLowCongestionColor(argb)
                RouteLineColor.OTHER_MODERATE -> builder.alternativeRouteModerateCongestionColor(argb)
                RouteLineColor.OTHER_HEAVY -> builder.alternativeRouteHeavyCongestionColor(argb)
                RouteLineColor.OTHER_SEVERE -> builder.alternativeRouteSevereCongestionColor(argb)
                RouteLineColor.OTHER_CASING -> builder.alternativeRouteCasingColor(argb)
            }
        }
        return builder.build()
    }
}

/** A value of the basemap's config in one look's column of [MapPalette.keys]. */
internal sealed interface LookValue {
    /** A word the style knows, such as a light preset or a theme. */
    data class Text(val text: String) : LookValue

    /** On or off. */
    data class Flag(val on: Boolean) : LookValue

    /** A whole number, such as a density. */
    data class Number(val value: Long) : LookValue

    /** A colour as #RRGGBB. */
    data class Colour(val hex: String) : LookValue

    /** The style's own value: the key is written with no value, which gives it back to the style. */
    data object Restore : LookValue
}

/**
 * The colours of the app's own lanes in one look ([MapPalette.lanes]), as plain ARGB values: the drawn [asphalt] (also
 * the eraser's), a [keptLane] for buses and the like, [white] and [yellow] paint (lines and lane arrows), the [lit] lane
 * and its [litEdge]s, a [tint]ed lane, the lane arrows' [arrowEdge], and the lit lane where the route has traffic or a
 * closure on it.
 */
internal data class LaneColours(
    val asphalt: Int,
    val keptLane: Int,
    val white: Int,
    val yellow: Int,
    val lit: Int,
    val litEdge: Int,
    val tint: Int,
    val arrowEdge: Int,
    val moderate: Int,
    val heavy: Int,
    val severe: Int,
    val closed: Int,
)

/** The colours of the route line the app sets, for the route and for the other routes. */
internal enum class RouteLineColor {
    ROUTE_DEFAULT,
    ROUTE_UNKNOWN,
    ROUTE_LOW,
    ROUTE_MODERATE,
    ROUTE_HEAVY,
    ROUTE_SEVERE,
    ROUTE_CLOSED,
    ROUTE_CASING,
    OTHER_DEFAULT,
    OTHER_UNKNOWN,
    OTHER_LOW,
    OTHER_MODERATE,
    OTHER_HEAVY,
    OTHER_SEVERE,
    OTHER_CASING,
}
