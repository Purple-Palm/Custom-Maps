package io.github.purplepalm.custommaps.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The car's home screen and the root of its stack: the app's map on the whole of the car's surface,
 * under a navigation template whose buttons the host draws ([CarHome]). It stays a navigation template
 * in every state, so the screens of a later step can be pushed on it and every flow ends on it, which
 * starts the host's template count over.
 *
 * During a drive the template carries the routing card, as the phone's maneuver card: the maneuver
 * with its arrow, distance and words, the lane row, the Then step while the phone shows it, and the
 * arrival time, time and distance left; it loads while the route is worked out again. At the arrival
 * it says so, as the phone's arrival card, until the phone's card goes. The host draws all of it; the
 * car's surface shows only the map.
 *
 * The template follows the drive's state and the camera's, and the drive's progress as the guidance
 * bridge passes it on ([refresh]): each change asks the host for a new one, which for a navigation
 * template is a refresh and counts nothing. Building it cannot reach the host's call with an exception:
 * a failure writes a "car_template" line with its cause's class, and the screen sends a plain navigation
 * template with the one action the state allows instead. A click that fails writes a "car_error" line.
 *
 * @param camera the camera of the car's map as it is now; idle while there is no map
 * @param drive what the template shows of the drive
 */
internal class CarHomeScreen(
    carContext: CarContext,
    private val guidance: StateFlow<GuidanceState>,
    private val camera: StateFlow<CameraMode>,
    private val listener: Listener,
    private val log: DebugLog,
    private val drive: Drive,
) : Screen(carContext) {

    /** What the template shows of the drive, made by the session from the guidance bridge's content. */
    interface Drive {
        /** The routing card during a drive; loading before its first progress and while the route is worked out again. */
        fun routingInfo(): RoutingInfo

        /** The arrival time, time and distance left; null before the first progress. */
        fun destinationEstimate(): TravelEstimate?

        /** The arrival, as the phone's arrival card shows it. */
        fun arrival(): MessageInfo
    }

    /** What the screen's buttons do. Main thread, as the host calls them. */
    interface Listener {
        fun onStartOnPhone()

        fun onEnd()

        fun onMapButton(button: CarMapButton)

        /** A zoom button: [delta] levels in, or out when below zero. */
        fun onZoom(delta: Double)
    }

    init {
        // From the screen's creation, when the session pushes it, to its end: a screen never pushed
        // follows nothing, and nothing holds on to it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    combine(guidance, camera) { state, mode -> CarHome.of(state, mode) }
                        .distinctUntilChanged()
                        .collect { refresh() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    failed("homeState", e)
                }
            }
        }
    }

    /** Asks the host for the template again; it does nothing while the screen is not on the car's screen. */
    fun refresh() {
        try {
            invalidate()
        } catch (e: RuntimeException) {
            failed("invalidate", e)
        }
    }

    override fun onGetTemplate(): Template {
        val state = guidance.value
        val home = CarHome.of(state, camera.value)
        return try {
            val template = NavigationTemplate.Builder()
                .setActionStrip(ActionStrip.Builder().addAction(action(home.action)).build())
                .setMapActionStrip(mapStrip(home.mapButton))
            when (state) {
                GuidanceState.ACTIVE -> {
                    template.setNavigationInfo(drive.routingInfo())
                    drive.destinationEstimate()?.let(template::setDestinationTravelEstimate)
                }
                GuidanceState.ARRIVED -> template.setNavigationInfo(drive.arrival())
                GuidanceState.IDLE, GuidanceState.PREVIEW -> Unit
            }
            template.build()
        } catch (e: RuntimeException) {
            log.log(TEMPLATE_LINE, CarFailure.fields("onGetTemplate", e) + mapOf("template" to "home", "action" to home.action.name, "guidance" to state.name))
            fallback(home.action)
        }
    }

    private fun action(action: CarHomeAction): Action = when (action) {
        CarHomeAction.START_ON_PHONE -> Action.Builder()
            .setTitle(carContext.getString(R.string.car_start_on_phone))
            .setIcon(icon(R.drawable.car_ic_phone))
            .setOnClickListener(click("startOnPhone", listener::onStartOnPhone))
            .build()
        CarHomeAction.END -> Action.Builder()
            .setTitle(carContext.getString(R.string.car_end))
            .setOnClickListener(click("end", listener::onEnd))
            .build()
    }

    /** Pan, zoom in, zoom out and [button]; the host passes the car's pan and zoom gestures on only with Pan here. */
    private fun mapStrip(button: CarMapButton?): ActionStrip {
        val strip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(iconAction(R.drawable.car_ic_zoom_in, "zoomIn") { listener.onZoom(CarHome.ZOOM_STEP) })
            .addAction(iconAction(R.drawable.car_ic_zoom_out, "zoomOut") { listener.onZoom(-CarHome.ZOOM_STEP) })
        if (button != null) strip.addAction(iconAction(iconOf(button), "mapButton") { listener.onMapButton(button) })
        return strip.build()
    }

    /**
     * A navigation template with the state's one action and nothing that could fail again: fixed words,
     * no resource, no icon, no map buttons. The map stays on the surface below it.
     */
    private fun fallback(action: CarHomeAction): Template = try {
        val (title, onClick) = when (action) {
            CarHomeAction.START_ON_PHONE -> FALLBACK_START to listener::onStartOnPhone
            CarHomeAction.END -> FALLBACK_END to listener::onEnd
        }
        val button = Action.Builder().setTitle(title).setOnClickListener(click("fallback", onClick)).build()
        NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(button).build()).build()
    } catch (e: RuntimeException) {
        log.log(TEMPLATE_LINE, CarFailure.fields("fallback", e) + ("template" to "home"))
        NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build()).build()
    }

    private fun iconAction(@DrawableRes res: Int, call: String, block: () -> Unit): Action =
        Action.Builder().setIcon(icon(res)).setOnClickListener(click(call, block)).build()

    /** The app's own vector, tinted by the host to fit its buttons by day and by night. */
    private fun icon(@DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(CarColor.DEFAULT).build()

    @DrawableRes
    private fun iconOf(button: CarMapButton): Int = when (button) {
        CarMapButton.RECENTER, CarMapButton.RECENTER_ROUTES -> R.drawable.car_ic_recenter
        CarMapButton.OVERVIEW -> R.drawable.car_ic_overview
    }

    private fun click(call: String, block: () -> Unit) = OnClickListener {
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

        // Words that need no resource, for the template that must not fail.
        const val FALLBACK_START = "Start on phone"
        const val FALLBACK_END = "End"
    }
}
