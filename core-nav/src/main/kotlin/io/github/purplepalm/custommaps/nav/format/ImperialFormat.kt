package io.github.purplepalm.custommaps.nav.format

import io.github.purplepalm.custommaps.nav.model.Distance
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration

/** A distance ready for display, split so the number can be drawn large and the unit small. */
data class DisplayDistance(val value: String, val unit: String) {
    override fun toString(): String = "$value $unit"
}

/** What a [RoundedDistance] counts. */
enum class ImperialUnit {
    /** Feet, in steps of 10 below 100 ft and of 50 above. */
    FEET,

    /** Tenths of a mile, shown with their one decimal, below 10 miles. */
    TENTHS_OF_MILE,

    /** Whole miles, from 10 miles on. */
    MILES,
}

/**
 * A distance rounded as the app shows it, as numbers: [amount] of [unit]. The phone's text
 * ([ImperialFormat.distance]) and the car's distances are both made from it, so they always say the
 * same.
 */
data class RoundedDistance(val amount: Long, val unit: ImperialUnit)

/** US customary units, as used on the road in California. */
object ImperialFormat {

    /** Below a tenth of a mile, distances are given in feet. */
    private const val FEET_LIMIT = 528.0
    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "40 ft", "350 ft", "0.2 mi", "4.5 mi", "12 mi". */
    fun distance(distance: Distance): DisplayDistance {
        val rounded = rounded(distance)
        val amount = rounded.amount
        return when (rounded.unit) {
            ImperialUnit.FEET -> DisplayDistance(amount.toString(), "ft")
            ImperialUnit.TENTHS_OF_MILE -> DisplayDistance("${amount / 10}.${amount % 10}", "mi")
            ImperialUnit.MILES -> DisplayDistance(amount.toString(), "mi")
        }
    }

    /**
     * [distance] rounded as the text of [ImperialFormat.distance] shows it, as numbers: feet below a
     * tenth of a mile, tenths of a mile below 10 miles, whole miles above.
     */
    fun rounded(distance: Distance): RoundedDistance {
        val feet = distance.feet
        if (feet < FEET_LIMIT) {
            val step = if (feet < 100.0) 10L else 50L
            val rounded = minOf((feet / step).roundToLong() * step, 500L)
            return RoundedDistance(rounded, ImperialUnit.FEET)
        }
        val tenths = (distance.miles * 10).roundToLong()
        return if (tenths < 100) {
            RoundedDistance(tenths, ImperialUnit.TENTHS_OF_MILE)
        } else {
            RoundedDistance(distance.miles.roundToLong(), ImperialUnit.MILES)
        }
    }

    /** "1 min", "25 min", "1 hr", "2 hr 5 min". Rounded to the nearest minute, never below one. */
    fun duration(duration: Duration): String {
        val minutes = maxOf(1L, (duration.inWholeSeconds + 30) / 60)
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours hr" else "$hours hr $rest min"
    }

    /** Clock time of arrival, for example "3:45 PM". */
    fun arrivalTime(now: ZonedDateTime, remaining: Duration): String =
        now.plusSeconds(remaining.inWholeSeconds).format(clockFormat)
}
