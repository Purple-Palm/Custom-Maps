package io.github.purplepalm.custommaps.nav.look

import io.github.purplepalm.custommaps.nav.guidance.Reach
import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The sun's elevation in degrees for a moment and a place; [NOAA] in the app, a fake in tests. */
fun interface SunElevation {
    fun degrees(at: Instant, latitude: Double, longitude: Double): Double

    companion object {
        val NOAA = SunElevation(SunPosition::elevation)
    }
}

/**
 * The numbers of the look rule and the switch; first settings, to be tuned on drives. No row in
 * Settings for any of them.
 */
data class LookTuning(
    /**
     * The sun's centre at or above this elevation, in degrees, brings the day look. Between sunset
     * (-0.833) and the end of civil twilight (-6): about 10 to 20 minutes from sunrise or sunset
     * between 30 and 50 degrees of latitude.
     */
    val dayFrom: Double = -2.5,
    /** At or below this the night look; in between, the look stays as it is, so it cannot flicker. */
    val nightFrom: Double = -3.5,
    /** After the sun switched the look, it does not switch it back for this long. */
    val dwell: Duration = 10.minutes,
    /** During guidance a switch waits while the next maneuver is this close... */
    val quietBefore: Reach = Reach(Distance.meters(300.0), 20.seconds),
    /** ...and for this long after a maneuver, counted from the step change. */
    val quietAfter: Duration = 6.seconds,
    /**
     * Once a switch has waited this long, counted from the start of its wait, the window around
     * maneuvers narrows to [cappedBefore] and [cappedAfter], so a drive with a turn every few
     * hundred meters still gets its switch, never right at a turn. A tunnel and a close view hold it
     * for as long as they last.
     */
    val maxHold: Duration = 10.minutes,
    /** The window before a maneuver once [maxHold] has passed. */
    val cappedBefore: Reach = Reach(Distance.meters(150.0), 10.seconds),
    /** The window after a maneuver once [maxHold] has passed. */
    val cappedAfter: Duration = 3.seconds,
    /** Below this speed, in meters a second, the car stands, and a waiting switch goes. */
    val standingBelowMps: Double = 0.5,
) {
    init {
        require(nightFrom < dayFrom) { "nightFrom must be below dayFrom" }
    }

    /** With no look shown yet, the middle of the two decides. */
    val middle: Double get() = (dayFrom + nightFrom) / 2.0
}

/** Which look the driver's choice asks for at a moment. Pure: the same inputs, the same answer. */
object LookRule {

    /**
     * The look [choice] asks for at [at] for [place]. [shown] is the look on screen now, or null
     * before the first one: between [LookTuning.nightFrom] and [LookTuning.dayFrom] the shown look
     * stays, and without one [LookTuning.middle] decides.
     */
    fun wanted(
        choice: LookChoice,
        place: SunPlace,
        at: Instant,
        shown: Look?,
        tuning: LookTuning = LookTuning(),
        sun: SunElevation = SunElevation.NOAA,
    ): Look = when (choice) {
        LookChoice.DAY -> Look.DAY
        LookChoice.NIGHT -> Look.NIGHT
        LookChoice.AUTO -> bySun(elevation(place, at, sun), shown, tuning)
    }

    /**
     * The sun's elevation the rule decides by. For a position, the sun there. For the time zone's
     * guess, which knows the meridian but not the latitude, the lowest sun of [GUESS_LATITUDES] on
     * that meridian: a dark screen by day can be read, a light one in a dark car blinds.
     */
    fun elevation(place: SunPlace, at: Instant, sun: SunElevation = SunElevation.NOAA): Double =
        if (place.source == PlaceSource.TIME_ZONE) {
            GUESS_LATITUDES.minOf { latitude -> sun.degrees(at, latitude, place.longitude) }
        } else {
            sun.degrees(at, place.latitude, place.longitude)
        }

    /** The look for a sun at [elevation] degrees; see [wanted]. */
    fun bySun(elevation: Double, shown: Look?, tuning: LookTuning = LookTuning()): Look = when {
        elevation >= tuning.dayFrom -> Look.DAY
        elevation <= tuning.nightFrom -> Look.NIGHT
        shown != null -> shown
        elevation >= tuning.middle -> Look.DAY
        else -> Look.NIGHT
    }

    /** The latitudes the time zone's guess tries on its meridian. */
    val GUESS_LATITUDES: List<Double> = listOf(0.0, 45.0, -45.0, 60.0, -60.0)
}
