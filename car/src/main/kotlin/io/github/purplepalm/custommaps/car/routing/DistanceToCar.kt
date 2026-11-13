package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.format.ImperialUnit
import io.github.purplepalm.custommaps.nav.model.Distance
import androidx.car.app.model.Distance as HostDistance

/** A distance as the car's host takes it: the number it shows and one of `Distance.UNIT_*`. */
data class CarDistance(val value: Double, val unit: Int)

/**
 * Distances as the phone shows them ([ImperialFormat.rounded]): feet below a tenth of a mile, miles
 * with their one decimal below 10 miles ([HostDistance.UNIT_MILES_P1], which always shows the decimal,
 * so "1.0 mi" stays "1.0 mi"), whole miles above. The host cuts what it shows rather than rounding it
 * (its own example: 1.46 km shows "1.4 km"), so it gets the number already rounded, the one the phone
 * shows; a tenth is the double nearest to it, which prints as the tenth itself.
 */
object DistanceToCar {

    fun of(distance: Distance): CarDistance {
        val rounded = ImperialFormat.rounded(distance)
        return when (rounded.unit) {
            ImperialUnit.FEET -> CarDistance(rounded.amount.toDouble(), HostDistance.UNIT_FEET)
            ImperialUnit.TENTHS_OF_MILE -> CarDistance(rounded.amount / TENTHS, HostDistance.UNIT_MILES_P1)
            ImperialUnit.MILES -> CarDistance(rounded.amount.toDouble(), HostDistance.UNIT_MILES)
        }
    }

    private const val TENTHS = 10.0
}

/** The library's distance for [this]. */
internal fun CarDistance.toHost(): HostDistance = HostDistance.create(value, unit)
