package io.github.purplepalm.custommaps.nav.look

import kotlin.math.roundToInt

/** The two looks of the app and its map: the bright day map and today's dark night map. */
enum class Look { DAY, NIGHT }

/**
 * What a map is to show: [look] for everything but the basemap's theme, and the theme of [theme].
 * The two differ only while a theme waits for a quiet moment of the drive
 * ([LookChange.deferTheme]); a map drawing its first tiles always takes the theme of [look].
 */
data class LookOnMap(val look: Look, val theme: Look = look) {

    /** The request after [step]: a whole look, a look whose theme waits, or the theme let go. */
    fun after(step: LookStep): LookOnMap {
        val change = step.change
        return when {
            change != null && change.deferTheme -> LookOnMap(change.look, theme)
            change != null -> LookOnMap(change.look)
            step.themeRelease != null -> LookOnMap(look)
            else -> this
        }
    }
}

/** What the driver chose in Settings. Stored as the lowercase word of each entry. */
enum class LookChoice {
    /** By the sun at the phone's position: day by day, night at night. */
    AUTO,

    /** Always the day look. */
    DAY,

    /** Always the night look. */
    NIGHT,
    ;

    companion object {
        val DEFAULT = AUTO
    }
}

/** Where the position the sun is computed for came from, best first. */
enum class PlaceSource {
    /** A position this process received: the puck's or the navigator's. */
    FIX,

    /** The location service's last known position, however old. */
    LAST_KNOWN,

    /**
     * No position at all: the standard meridian of the phone's time zone. The rule takes the lowest
     * sun along it ([LookRule.GUESS_LATITUDES]), since the latitude is not known.
     */
    TIME_ZONE,
}

/**
 * A position to compute the sun for, rounded to a tenth of a degree (about 11 km), which moves the
 * sun's times by less than half a minute. Kept in memory only: never stored, never logged, and
 * [toString] names only the source.
 */
class SunPlace private constructor(val latitude: Double, val longitude: Double, val source: PlaceSource) {

    override fun equals(other: Any?): Boolean =
        other is SunPlace && other.latitude == latitude && other.longitude == longitude && other.source == source

    override fun hashCode(): Int = (latitude.hashCode() * 31 + longitude.hashCode()) * 31 + source.hashCode()

    override fun toString(): String = "SunPlace($source)"

    companion object {
        /** A position from a fix or the last known one; latitude and longitude in degrees. */
        fun of(latitude: Double, longitude: Double, source: PlaceSource): SunPlace {
            require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude out of range" }
            require(longitude.isFinite()) { "longitude must be finite" }
            return SunPlace(tenth(latitude), tenth(wrap(longitude)), source)
        }

        /**
         * The guess without any position: the meridian of the time zone's standard offset (15
         * degrees an hour). Its latitude is stored as 0 and never used: [LookRule.elevation] tries
         * several latitudes on the meridian. [standardOffsetSeconds] is the offset without summer
         * time (java.time's `ZoneRules.getStandardOffset`): summer time moves the clocks, not the
         * sun, and would put the guess an hour east.
         */
        fun ofTimeZone(standardOffsetSeconds: Int): SunPlace =
            SunPlace(0.0, tenth(wrap(standardOffsetSeconds / SECONDS_PER_DEGREE)), PlaceSource.TIME_ZONE)

        private fun tenth(degrees: Double): Double = (degrees * 10.0).roundToInt() / 10.0

        /** Into -180..180, so a zone east of +12 hours lands on the right side of the date line. */
        private fun wrap(longitude: Double): Double {
            val wrapped = ((longitude + 180.0).mod(360.0)) - 180.0
            return if (wrapped == -180.0 && longitude > 0.0) 180.0 else wrapped
        }

        /** The earth turns 15 degrees an hour: 240 seconds a degree. */
        private const val SECONDS_PER_DEGREE = 240.0
    }
}
