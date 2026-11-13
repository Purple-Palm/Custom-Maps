package io.github.purplepalm.custommaps.car

import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * A message on the car's screen, under the app's icon. The host draws it; the car's surface shows
 * nothing of it.
 *
 * Building the template cannot reach the host's call with an exception: a failure writes a
 * "car_template" line with its cause's class, and the screen shows a plain message of fixed words
 * instead, still a message, as a screen must send the same kind of template every time.
 */
internal class CarMessageScreen(
    carContext: CarContext,
    @param:StringRes private val text: Int,
    private val log: DebugLog,
) : Screen(carContext) {

    override fun onGetTemplate(): Template = try {
        MessageTemplate.Builder(carContext.getString(text))
            .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).build())
            .build()
    } catch (e: RuntimeException) {
        log.log(TEMPLATE_LINE, CarFailure.fields("onGetTemplate", e) + ("template" to "message"))
        MessageTemplate.Builder(FALLBACK_TEXT).build()
    }

    private companion object {
        const val TEMPLATE_LINE = "car_template"

        /** Words that need no resource, for the message that must not fail. */
        const val FALLBACK_TEXT = "Use Custom Maps on the phone when you are parked."
    }
}
