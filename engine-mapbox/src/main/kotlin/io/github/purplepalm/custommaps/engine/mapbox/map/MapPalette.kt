package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import com.mapbox.maps.MapboxMap
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources

/**
 * The app's look on top of Mapbox Standard: night lighting with 3D buildings, a muted monochrome
 * base in graphite tones, platinum labels and one amber accent for the route.
 */
internal object MapPalette {

    /** Import id of the Standard basemap when it is loaded as the style itself. */
    private const val BASEMAP = "basemap"

    /**
     * The ground, the buildings and the roads at night, from the darkest to the lightest: a
     * graphite ground, the buildings one small step above it, and the roads a clear step above
     * both, so a street reads as a band on the ground also close in, where it is a plain surface.
     * Without them Mapbox's own ground was a grey only a little darker than its roads. These are
     * configured colors: the night light and the monochrome theme change them a little on the
     * way to the screen, so they are a first setting, to be judged on the phone.
     */
    const val LAND = "#16181B"
    const val BUILDINGS = "#282B31"
    const val ROADS = "#4A4F57"
    const val TRUNKS = "#5A5F67"
    const val MOTORWAYS = "#6A6F77"

    private val nightConfig: Map<String, Value> = mapOf(
        "lightPreset" to Value.valueOf("night"),
        "theme" to Value.valueOf("monochrome"),
        "show3dObjects" to Value.valueOf(true),
        "colorLand" to Value.valueOf(LAND),
        "colorBuildings" to Value.valueOf(BUILDINGS),
        "colorMotorways" to Value.valueOf(MOTORWAYS),
        "colorTrunks" to Value.valueOf(TRUNKS),
        "colorRoads" to Value.valueOf(ROADS),
        // The road surface where lane details are drawn. Only the color is set here: whether they
        // are drawn at all is LaneDetails' to set, and never while the style loads.
        "colorHdRoads" to Value.valueOf("#4A4F57"),
        "colorWater" to Value.valueOf("#0B151D"),
        "colorGreenspace" to Value.valueOf("#101A15"),
        "colorPlaceLabels" to Value.valueOf("#D6D7D9"),
        "colorRoadLabels" to Value.valueOf("#A9ADB3"),
        "colorModePointOfInterestLabels" to Value.valueOf("single"),
        "colorPointOfInterestLabels" to Value.valueOf("#8C9198"),
        "densityPointOfInterestLabels" to Value.valueOf(2L),
    )

    /** Call when the style JSON is parsed, before tiles render, so no daylight frame appears. */
    fun applyNight(map: MapboxMap) {
        nightConfig.forEach { (name, value) -> map.setStyleImportConfigProperty(BASEMAP, name, value) }
    }

    const val AMBER = 0xFFF5A524.toInt()
    private const val AMBER_CASING = 0xFF3A2800.toInt()
    private const val TRAFFIC_MODERATE = 0xFFFF7A1A.toInt()
    private const val TRAFFIC_HEAVY = 0xFFE5484D.toInt()
    private const val TRAFFIC_SEVERE = 0xFF9E1B32.toInt()
    private const val ALTERNATIVE = 0xFF6F747C.toInt()
    private const val ALTERNATIVE_CASING = 0xFF2A2E34.toInt()
    const val PLATINUM = 0xFFE5E4E2.toInt()
    const val GRAPHITE = 0xFF0E1013.toInt()

    /** Amber where traffic is light or unknown, warmer to red as it gets heavier; alternatives in grey. */
    val routeLineColors: RouteLineColorResources = RouteLineColorResources.Builder()
        .routeDefaultColor(AMBER)
        .routeUnknownCongestionColor(AMBER)
        .routeLowCongestionColor(AMBER)
        .routeModerateCongestionColor(TRAFFIC_MODERATE)
        .routeHeavyCongestionColor(TRAFFIC_HEAVY)
        .routeSevereCongestionColor(TRAFFIC_SEVERE)
        .routeCasingColor(AMBER_CASING)
        .alternativeRouteDefaultColor(ALTERNATIVE)
        .alternativeRouteUnknownCongestionColor(ALTERNATIVE)
        .alternativeRouteLowCongestionColor(ALTERNATIVE)
        .alternativeRouteModerateCongestionColor(ALTERNATIVE)
        .alternativeRouteHeavyCongestionColor(ALTERNATIVE)
        .alternativeRouteSevereCongestionColor(ALTERNATIVE)
        .alternativeRouteCasingColor(ALTERNATIVE_CASING)
        .build()
}
