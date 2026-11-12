package io.github.purplepalm.custommaps.look

import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.look.LookTuning
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.look.SunPosition
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.hours

/**
 * When Auto changes the look next, and to which: for the note under Auto in Settings, on the
 * screen only. Never logged or stored: the time is the local dusk or dawn.
 */
data class NextLook(val look: Look, val at: LocalTime) {

    companion object {
        /**
         * The moment the sun would change [shown] under Auto, as a time of day in [zone] to the
         * minute, the holds of a drive aside: with the day look shown, the evening's crossing of
         * [LookTuning.nightFrom] on the way down; with the night look shown, the morning's crossing
         * of [LookTuning.dayFrom] on the way up; searched a day ahead. None with Day or Night by
         * hand, under the time zone's guess (its place is only a meridian), when the sun has crossed
         * already and the change waits for the next minute, and in polar day or night.
         */
        fun of(choice: LookChoice, shown: Look, place: SunPlace, now: Instant, zone: ZoneId, tuning: LookTuning = LookTuning()): NextLook? {
            if (choice != LookChoice.AUTO || place.source == PlaceSource.TIME_ZONE) return null
            val elevation = SunPosition.elevation(now, place.latitude, place.longitude)
            val next = when (shown) {
                Look.DAY -> Crossing(Look.NIGHT, tuning.nightFrom, SunPosition.Way.DOWN, due = elevation <= tuning.nightFrom)
                Look.NIGHT -> Crossing(Look.DAY, tuning.dayFrom, SunPosition.Way.UP, due = elevation >= tuning.dayFrom)
            }
            if (next.due) return null
            val at = SunPosition.nextCrossing(now, SEARCH, place.latitude, place.longitude, next.degrees, next.way) ?: return null
            return NextLook(next.look, toTheMinute(at).atZone(zone).toLocalTime())
        }

        /** [at] to the nearest minute. */
        internal fun toTheMinute(at: Instant): Instant = at.plusSeconds(HALF_MINUTE_SECONDS).truncatedTo(ChronoUnit.MINUTES)

        private class Crossing(val look: Look, val degrees: Double, val way: SunPosition.Way, val due: Boolean)

        private val SEARCH = 24.hours
        private const val HALF_MINUTE_SECONDS = 30L
    }
}
