package io.github.purplepalm.custommaps.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog

/**
 * A message over the car's map ([CarMessage]): what the setup still needs, or how a drive starts. The
 * host draws it; the car's surface shows nothing of it. A message the driver asked for has Back; the
 * setup's messages are the app's whole screen until the phone is set up, so Back stays on them, as on
 * the home screen.
 *
 * Building the template cannot reach the host's call with an exception: a failure writes a
 * "car_template" line with its cause's class, and the screen shows a plain message of fixed words
 * instead, still a message, as a screen must send the same kind of template every time.
 *
 * @param onGone hears that the screen is gone, by Back or because the session took it away
 */
internal class CarMessageScreen(
    carContext: CarContext,
    val message: CarMessage,
    private val log: DebugLog,
    private val onGone: (CarMessage) -> Unit,
) : Screen(carContext) {

    /** Back on a setup message: nothing to go back to. */
    private val stayOnBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    init {
        try {
            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) guard("onGone") { onGone(message) }
                },
            )
            // Only while this screen is started, so only while it is on top.
            if (!message.back) carContext.onBackPressedDispatcher.addCallback(this, stayOnBack)
        } catch (e: RuntimeException) {
            failed("messageScreen", e)
        }
    }

    override fun onGetTemplate(): Template = try {
        val header = Header.Builder().setStartHeaderAction(if (message.back) Action.BACK else Action.APP_ICON)
        CarMessages.title(message)?.let { header.setTitle(carContext.getString(it)) }
        MessageTemplate.Builder(carContext.getString(CarMessages.text(message)))
            .setHeader(header.build())
            .setLoading(message.loading)
            .build()
    } catch (e: RuntimeException) {
        log.log(TEMPLATE_LINE, CarFailure.fields("onGetTemplate", e) + mapOf("template" to "message", "message" to message.name))
        MessageTemplate.Builder(FALLBACK_TEXT).build()
    }

    private inline fun guard(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            failed(call, e)
        }
    }

    private fun failed(call: String, e: RuntimeException) {
        log.log(ERROR_LINE, CarFailure.fields(call, e))
    }

    private companion object {
        const val TEMPLATE_LINE = "car_template"
        const val ERROR_LINE = "car_error"

        /** Words that need no resource, for the message that must not fail. */
        const val FALLBACK_TEXT = "Use Custom Maps on the phone when you are parked."
    }
}
