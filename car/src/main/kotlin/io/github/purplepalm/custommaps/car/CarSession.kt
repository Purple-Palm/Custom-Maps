package io.github.purplepalm.custommaps.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.withFields

/**
 * The car's screen while the car's host, Android Auto on the phone, is connected: one session for each
 * connection, made by the app's car app service. For now it says on the car's screen how a drive
 * begins: on the phone.
 *
 * The car, the phone and the drive share one process, and car-app rethrows an exception of the app's
 * code on the main thread, which would end the phone's guidance too. So every way in catches it,
 * writes a "car_error" line with the call and the exception's class, and goes on with something safe.
 *
 * Each step of the session's lifecycle writes a "car_session" line, the first one with the host's
 * package and its Car API level. Every line of the car carries `screen=car`. Main thread only, as the
 * host calls it.
 *
 * @param displayType which of the car's displays the session is for, as the host's session info has
 *   it; the app declares only the main one
 */
class CarSession(debugLog: DebugLog, private val displayType: Int) : Session() {

    private val log = debugLog.withFields(mapOf(SCREEN to CAR))

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event -> guard("lifecycle") { onLifecycle(event) } })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        guard("onCreateScreen") { log.log(SESSION_LINE, mapOf("event" to "screen", "action" to intent.action)) }
        return CarMessageScreen(carContext, R.string.car_start_idle, log)
    }

    override fun onNewIntent(intent: Intent) {
        guard("onNewIntent") { log.log(SESSION_LINE, mapOf("event" to "intent", "action" to intent.action)) }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        guard("onCarConfigurationChanged") { log.log(SESSION_LINE, mapOf("event" to "configuration", "dark" to carContext.isDarkMode)) }
    }

    private fun onLifecycle(event: Lifecycle.Event) {
        val fields = mapOf("event" to event.name)
        log.log(SESSION_LINE, if (event == Lifecycle.Event.ON_CREATE) fields + hostFields() else fields)
    }

    /** The host that bound the app, by its package, and the Car API level it speaks; numbers and names only. */
    private fun hostFields(): Map<String, Any?> = mapOf(
        "host" to read("hostInfo") { carContext.hostInfo?.packageName },
        "api" to read("carAppApiLevel") { carContext.carAppApiLevel },
        "display" to displayType,
    )

    private inline fun <T> read(call: String, block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        failed(call, e)
        null
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
        const val SCREEN = "screen"
        const val CAR = "car"
        const val SESSION_LINE = "car_session"
        const val ERROR_LINE = "car_error"
    }
}
