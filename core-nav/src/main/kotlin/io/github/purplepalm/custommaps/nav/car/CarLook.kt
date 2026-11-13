package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookOnMap

/** How the car map follows the host's day and night. A first setting, to be tuned with drives. */
data class CarLookTuning(
    /**
     * How long the host has to hold a mode before the basemap's theme follows it, in milliseconds.
     * The theme is the one write that redraws the basemap's tiles; a tunnel is usually shorter.
     */
    val themeHoldMillis: Long = 60_000L,
)

/** What the car map is to show after a step of [CarLook], and the fields of its "car_look" line. */
data class CarLookStep(val request: LookOnMap, val fields: Map<String, Any?>)

/**
 * The car map's look, from the day or night mode the car's host asks for (`CarContext.isDarkMode`),
 * in two stages, so that a flip at a tunnel's entrance and back at its exit redraws no tiles:
 *
 * - A new mode writes the look's light and colours at once ([onHost]): dark at once when the host
 *   says so, with no tile rebuilt. The basemap's theme stays as it is for now.
 * - The theme follows once the host has held the mode for [CarLookTuning.themeHoldMillis] ([onTime]).
 *   A flip back within that time cancels it: the theme already is that look's.
 * - A map that loads its style anew draws its first tiles in the whole look ([onNewMap]): no theme
 *   waits after that.
 *
 * The first look ([start]) is the host's mode when the car map is made. The phone keeps its own look;
 * the two may differ for a while at dusk, by design. Times are milliseconds on the caller's monotonic
 * clock. Plain state, no clock and no timer of its own: the caller asks [onTime] at [themeDueAtMillis].
 */
class CarLook(dark: Boolean, private val tuning: CarLookTuning = CarLookTuning()) {

    /** The host's mode as last reported. */
    var dark: Boolean = dark
        private set

    /** What the map is to show now: the host's look, with the theme shown so far while one waits. */
    var request: LookOnMap = LookOnMap(lookOf(dark))
        private set

    /** When the waiting theme follows; null while none waits. */
    var themeDueAtMillis: Long? = null
        private set

    /** When the host took the mode it has now; the theme's line says how long it held. */
    private var heldSinceMillis = 0L

    /** The fields of the "car_look" line of the first look. */
    fun start(): Map<String, Any?> = mapOf("dark" to dark, "cause" to CAUSE_START)

    /**
     * The host reports [dark] at [nowMillis]. The look's light and colours go at once and the theme
     * waits; after a flip back while it waited, the theme already is the look's and nothing waits.
     * Null when that is the mode the car map has already.
     */
    fun onHost(dark: Boolean, nowMillis: Long): CarLookStep? {
        if (dark == this.dark) return null
        this.dark = dark
        heldSinceMillis = nowMillis
        val look = lookOf(dark)
        request = LookOnMap(look, theme = request.theme)
        themeDueAtMillis = if (request.theme == look) null else nowMillis + tuning.themeHoldMillis
        return CarLookStep(request, mapOf("dark" to dark, "cause" to CAUSE_HOST))
    }

    /**
     * The time is [nowMillis]: the waiting theme follows when the host has held its mode long enough.
     * Null when no theme waits or it is not due yet.
     */
    fun onTime(nowMillis: Long): CarLookStep? {
        val due = themeDueAtMillis ?: return null
        if (nowMillis < due) return null
        themeDueAtMillis = null
        request = LookOnMap(request.look)
        val heldSeconds = (nowMillis - heldSinceMillis) / MILLIS_PER_SECOND
        return CarLookStep(request, mapOf("dark" to dark, "cause" to CAUSE_HOST, "themeHeldS" to heldSeconds))
    }

    /** A map loads its style anew: its first tiles are drawn in the whole look, so no theme waits any more. */
    fun onNewMap() {
        request = LookOnMap(request.look)
        themeDueAtMillis = null
    }

    companion object {
        /** The first look, when the car map is made. */
        const val CAUSE_START = "START"

        /** A change of the host's day and night mode. */
        const val CAUSE_HOST = "HOST"

        private const val MILLIS_PER_SECOND = 1_000L

        /** The look of the host's mode. */
        fun lookOf(dark: Boolean): Look = if (dark) Look.NIGHT else Look.DAY
    }
}
