package io.github.purplepalm.custommaps.nav.look

import java.time.Instant
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Where the sun stands for a place at a moment, from the equations of NOAA's solar calculator
 * (after Meeus, Astronomical Algorithms). NOAA gives its sunrise and sunset times as accurate to
 * about a minute between 72 degrees north and south, and to about ten minutes beyond.
 *
 * The elevation is geometric: the centre of the sun against a level horizon, without refraction.
 * Almanacs put sunrise and sunset at [RISE_SET] and the ends of civil twilight at
 * [CIVIL_TWILIGHT], both geometric elevations of the centre.
 *
 * Pure arithmetic on an instant and a place, a few dozen operations: no clock, no time zone and no
 * calendar day, so summer time, a change of time zone and the date line cannot change the answer,
 * and polar day and polar night need no special case.
 */
object SunPosition {

    /** Sunrise and sunset: 34' of refraction plus 16' for the sun's radius below the horizon. */
    const val RISE_SET = -0.833

    /** Civil twilight begins in the morning and ends in the evening at 6 degrees below the horizon. */
    const val CIVIL_TWILIGHT = -6.0

    /** Which way the sun passes an elevation. */
    enum class Way { UP, DOWN, EITHER }

    /**
     * The sun's elevation in degrees, from -90 to 90, at [at], seen from [latitude] and
     * [longitude] in degrees (north and east positive).
     */
    fun elevation(at: Instant, latitude: Double, longitude: Double): Double {
        val julianDay = at.epochSecond / SECONDS_PER_DAY + at.nano / NANOS_PER_DAY + UNIX_EPOCH_JULIAN_DAY
        val t = (julianDay - J2000) / DAYS_PER_CENTURY

        // The sun's mean longitude and anomaly, the orbit's eccentricity and the equation of centre.
        val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = radians(meanAnomaly)
        val centre = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * m) * (0.019993 - 0.000101 * t) +
            sin(3.0 * m) * 0.000289

        // Apparent longitude (nutation and aberration) and the corrected obliquity of the ecliptic.
        val omega = radians(125.04 - 1934.136 * t)
        val apparentLongitude = radians(meanLongitude + centre - 0.00569 - 0.00478 * sin(omega))
        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = radians(meanObliquity + 0.00256 * cos(omega))
        val declination = asin(sin(obliquity) * sin(apparentLongitude))

        // The equation of time, in minutes: how far the sundial runs ahead of mean solar time.
        val y = tan(obliquity / 2.0).let { it * it }
        val l0 = radians(meanLongitude)
        val equationOfTime = 4.0 * degrees(
            y * sin(2.0 * l0) - 2.0 * eccentricity * sin(m) +
                4.0 * eccentricity * y * sin(m) * cos(2.0 * l0) -
                0.5 * y * y * sin(4.0 * l0) - 1.25 * eccentricity * eccentricity * sin(2.0 * m),
        )

        // True solar time at the place, then the hour angle: 0 at local solar noon.
        val minutesOfDay = at.epochSecond.mod(SECONDS_PER_DAY_LONG) / SECONDS_PER_MINUTE + at.nano / NANOS_PER_MINUTE
        val trueSolarTime = (minutesOfDay + equationOfTime + 4.0 * longitude).mod(MINUTES_PER_DAY)
        val hourAngle = radians(trueSolarTime / 4.0 - 180.0)

        val phi = radians(latitude)
        val cosZenith = (sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        return 90.0 - degrees(acos(cosZenith))
    }

    /**
     * The first moment after [from] and no later than [within] after it at which the sun passes
     * [degrees] the [way] asked, to within a second; null when it does not in that time, as in
     * polar day and polar night. It looks every [step] and halves the step in which the sun
     * passed, so a dip shorter than [step], which only happens close to the poles, can be missed.
     * For tests, diagnostics and an "about 5:12 PM" hint on the phone's own screen; the switch
     * itself compares elevations and needs no times.
     */
    fun nextCrossing(
        from: Instant,
        within: Duration,
        latitude: Double,
        longitude: Double,
        degrees: Double,
        way: Way = Way.EITHER,
        step: Duration = 5.minutes,
    ): Instant? {
        require(step.isPositive()) { "step must be positive: $step" }
        val stepMillis = step.inWholeMilliseconds
        val endMillis = from.toEpochMilli() + within.inWholeMilliseconds
        var startMillis = from.toEpochMilli()
        var startAbove = elevation(from, latitude, longitude) >= degrees
        while (startMillis < endMillis) {
            val nextMillis = minOf(startMillis + stepMillis, endMillis)
            val nextAbove = elevation(Instant.ofEpochMilli(nextMillis), latitude, longitude) >= degrees
            val passed = startAbove != nextAbove
            val wanted = when (way) {
                Way.UP -> !startAbove
                Way.DOWN -> startAbove
                Way.EITHER -> true
            }
            if (passed && wanted) return halve(startMillis, nextMillis, startAbove, latitude, longitude, degrees)
            startMillis = nextMillis
            startAbove = nextAbove
        }
        return null
    }

    /** Narrows [lowMillis, highMillis], where the sun passes [degrees], to a second. */
    private fun halve(lowMillis: Long, highMillis: Long, lowAbove: Boolean, latitude: Double, longitude: Double, degrees: Double): Instant {
        var low = lowMillis
        var high = highMillis
        while (high - low > MILLIS_PER_SECOND) {
            val middle = low + (high - low) / 2
            val above = elevation(Instant.ofEpochMilli(middle), latitude, longitude) >= degrees
            if (above == lowAbove) low = middle else high = middle
        }
        return Instant.ofEpochMilli(high)
    }

    private fun radians(degrees: Double): Double = degrees * PI / 180.0

    private fun degrees(radians: Double): Double = radians * 180.0 / PI

    private const val SECONDS_PER_DAY = 86_400.0
    private const val SECONDS_PER_DAY_LONG = 86_400L
    private const val NANOS_PER_DAY = 86_400.0e9
    private const val SECONDS_PER_MINUTE = 60.0
    private const val NANOS_PER_MINUTE = 60.0e9
    private const val MINUTES_PER_DAY = 1_440.0
    private const val MILLIS_PER_SECOND = 1_000L
    private const val UNIX_EPOCH_JULIAN_DAY = 2_440_587.5
    private const val J2000 = 2_451_545.0
    private const val DAYS_PER_CENTURY = 36_525.0
}
