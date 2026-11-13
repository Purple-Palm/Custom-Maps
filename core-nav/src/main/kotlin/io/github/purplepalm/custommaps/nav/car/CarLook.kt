package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap

/**
 * Day and night in the car. The car's map shows the app's one look, the phone's
 * ([io.github.purplepalm.custommaps.nav.look.SharedLook]): the owner's choice, so the car is dark
 * when the phone is. The host's own day or night mode (`CarContext.isDarkMode`) colours only the
 * images the app hands the host for its routing card, the maneuver icon and the lanes image, which
 * sit on the host's card and not on the map ([lookOf]). A "car_look" line says the host's mode and
 * the look the map shows ([fields]).
 */
object CarLook {

    /** The car map is made, with the look it shows from its first tile on. */
    const val CAUSE_START = "START"

    /** The host's day or night mode changed: only the card's images follow it. */
    const val CAUSE_HOST = "HOST"

    /** The app's look changed, and the car's map took it. */
    const val CAUSE_APP = "APP"

    /** The look of the images on the host's card for the host's mode. */
    fun lookOf(dark: Boolean): Look = if (dark) Look.NIGHT else Look.DAY

    /**
     * The fields of a "car_look" line: [dark], the host's mode, null when the car's context could not
     * say; [cause]; the look the map shows, from [request]; and `themeDeferred` true while the
     * basemap keeps the theme of the look before, for a quiet moment of the drive.
     */
    fun fields(dark: Boolean?, cause: String, request: LookOnMap): Map<String, Any?> = mapOf(
        "dark" to dark,
        "cause" to cause,
        "look" to request.look,
        "themeDeferred" to true.takeIf { request.theme != request.look },
    ).filterValues { it != null }
}
