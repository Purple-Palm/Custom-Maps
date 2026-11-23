package io.github.purplepalm.custommaps.engine.mapbox.map

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/**
 * How the images a map takes as bitmaps grow with the car's map size
 * ([io.github.purplepalm.custommaps.nav.car.CarMapSize]). A map keeps an image's own pixels whatever its pixel ratio
 * (Maps 11.35.2: `addImage` takes a bitmap at the map's ratio), so where the map is drawn larger than its screen's density
 * those images are scaled by the size to grow with the rest of the map: the location component's puck and the car the
 * app draws itself by their layer's image sizes, the route line's origin and destination icons by the density they are
 * drawn at. The maneuver arrow's heads need nothing: the navigation SDK draws them at the map's pixel ratio. The scale is
 * 1 on the phone, where nothing changes.
 */
internal object ImageScale {

    /** The images as the map would draw them: the phone's, and the car's at its own size. */
    const val NONE = 1.0

    /**
     * The location component puck's scale expression for [scale], as JSON: the size of its three images, the same at every
     * zoom, in the form Mapbox documents for it (an interpolation over the zoom, here between two equal stops); null at
     * [NONE], the puck as it comes.
     */
    fun puckScaleJson(scale: Double): String? = if (scale == NONE) null else "[\"interpolate\",[\"linear\"],[\"zoom\"],0,$scale,22,$scale]"

    /** The density in dpi that images drawn from resources for a screen of [densityDpi] are drawn at, for [scale]. */
    fun densityDpi(densityDpi: Int, scale: Double): Int = (densityDpi * scale).roundToInt()

    /** [context] with its density times [scale], for images the map's libraries draw from resources; [context] itself at [NONE]. */
    fun context(context: Context, scale: Double): Context {
        if (scale == NONE) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.densityDpi = densityDpi(configuration.densityDpi, scale)
        return context.createConfigurationContext(configuration)
    }
}
