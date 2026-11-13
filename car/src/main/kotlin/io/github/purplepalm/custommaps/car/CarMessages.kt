package io.github.purplepalm.custommaps.car

import androidx.annotation.StringRes

/** The words of each car message, from the car module's resources. */
internal object CarMessages {

    @StringRes
    fun text(message: CarMessage): Int = when (message) {
        CarMessage.LOADING -> R.string.car_loading
        CarMessage.NO_TOKEN -> R.string.car_setup_token
        CarMessage.NO_PRECISE_LOCATION -> R.string.car_setup_location
        CarMessage.START_IDLE -> R.string.car_start_idle
        CarMessage.START_PREVIEW -> R.string.car_start_preview
    }

    /** The title in the message's header; none while loading, where the app's icon is enough. */
    @StringRes
    fun title(message: CarMessage): Int? = when (message) {
        CarMessage.LOADING -> null
        CarMessage.NO_TOKEN, CarMessage.NO_PRECISE_LOCATION -> R.string.car_setup_title
        CarMessage.START_IDLE, CarMessage.START_PREVIEW -> R.string.car_start_on_phone
    }
}
