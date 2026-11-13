package io.github.purplepalm.custommaps.car

import android.content.Context
import io.github.purplepalm.custommaps.car.routing.ArrivalSide
import io.github.purplepalm.custommaps.car.routing.CarWords

/** The routing card's words from the car module's resources, the phone's words for the same things. */
internal class CarDriveWords(private val context: Context) : CarWords {

    override fun roundaboutExit(number: Int): String = context.getString(R.string.car_roundabout_exit, number)

    override fun arrivalSide(side: ArrivalSide): String = context.getString(
        when (side) {
            ArrivalSide.LEFT -> R.string.car_then_on_left
            ArrivalSide.RIGHT -> R.string.car_then_on_right
            ArrivalSide.AHEAD -> R.string.car_destination
        },
    )

    override val destination: String get() = context.getString(R.string.car_destination)

    /** The title of the arrival. */
    val arrived: String get() = context.getString(R.string.car_arrived)
}
