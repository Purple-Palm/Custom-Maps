package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.navigation.model.TravelEstimate
import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A travel estimate as the car's host takes it: how far, when the car gets there, and in how many
 * seconds, [UNKNOWN_SECONDS] when the route does not say.
 */
data class CarEstimate(val distance: CarDistance, val arrival: ZonedDateTime, val remainingSeconds: Long) {
    companion object {
        /** The library's word for a time not known, [TravelEstimate.REMAINING_TIME_UNKNOWN]. */
        const val UNKNOWN_SECONDS = TravelEstimate.REMAINING_TIME_UNKNOWN
    }
}

/**
 * The time and distance to a step of the drive or to its destination, as the phone's guidance bar has
 * them: the arrival is now plus the whole seconds left, as the phone's arrival time is
 * ([io.github.purplepalm.custommaps.nav.format.ImperialFormat.arrivalTime]), and the host shows it in
 * the car's own time format. A time below zero, without end or more than [MAX_AHEAD] ahead is not
 * known: the host then shows no time, and the arrival is now, which it never refuses.
 */
object EstimateToCar {

    /** The farthest ahead an estimate goes; beyond it the route's time is no estimate. */
    val MAX_AHEAD: Duration = 7.days

    /** To the destination, [distance] and [remaining] away; no time left is the arrival. */
    fun destination(distance: Distance, remaining: Duration, now: ZonedDateTime): CarEstimate = of(distance, remaining, now, zeroKnown = true)

    /**
     * To the next step or the one after it, [distance] and [remaining] away. The route gives a step's
     * time as zero where it does not know it, so zero is not known here.
     */
    fun step(distance: Distance, remaining: Duration, now: ZonedDateTime): CarEstimate = of(distance, remaining, now, zeroKnown = false)

    private fun of(distance: Distance, remaining: Duration, now: ZonedDateTime, zeroKnown: Boolean): CarEstimate {
        val known = remaining.isFinite() && !remaining.isNegative() && remaining <= MAX_AHEAD && (zeroKnown || remaining.isPositive())
        val seconds = if (known) remaining.inWholeSeconds else CarEstimate.UNKNOWN_SECONDS
        return CarEstimate(DistanceToCar.of(distance), if (known) now.plusSeconds(seconds) else now, seconds)
    }
}

/** The library's travel estimate for [this]. */
internal fun CarEstimate.toHost(): TravelEstimate =
    TravelEstimate.Builder(distance.toHost(), arrival).setRemainingTimeSeconds(remainingSeconds).build()
