package io.github.purplepalm.custommaps.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.car.CarLook
import io.github.purplepalm.custommaps.nav.debug.withFields
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.model.CameraMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The car's screen while the car's host, Android Auto on the phone, is connected: one session for each
 * connection, made by the app's car app service. It shows the app's map on the car's surface, the same
 * scene as the phone's, under the home screen's buttons; a drive is started on the phone.
 *
 * - **The setup gate.** The session waits for the token as the phone's screen does ([CarGate]): a
 *   loading message while the token store is read (the host may have started the process with the
 *   phone locked), a message that the setup is done on the phone while the token or precise location
 *   is missing, the map once both are there. Precise location is checked again every few seconds while
 *   its message shows, as no broadcast says it was allowed.
 * - **The navigator** is kept alive by the session from the first token on ([CarParts.attachHost]) and
 *   let go by itself when the session is destroyed, never by hand. That starts nothing that is billed;
 *   the drive belongs to the engine and outlives the session.
 * - **The map** is made once the gate is ready, again for a new token, and let go of when the session
 *   ends ([CarMapStep]). It takes the phone's camera style, lane zoom, lane details and Debug switches,
 *   pins the destination of the preview or the drive, and follows the host's day and night.
 * - **The screens** are the home screen and at most one message on top of it ([CarStack]), so the
 *   host's template count stays low and every flow ends on the home screen.
 *
 * The car, the phone and the drive share one process, and car-app rethrows an exception of the app's
 * code on the main thread, which would end the phone's guidance too. So every way in catches it,
 * writes a "car_error" line with the call and the exception's class, and goes on with something safe.
 *
 * Each step of the session's lifecycle writes a "car_session" line, the first one with the host's
 * package and its Car API level, and so does each change of the gate, the map and the screens. Every
 * line of the car carries `screen=car`; none has a position, a road or a token. Main thread only, as
 * the host calls it.
 *
 * @param displayType which of the car's displays the session is for, as the host's session info has
 *   it; the app declares only the main one
 */
class CarSession(private val parts: CarParts, private val displayType: Int) : Session() {

    private val log = parts.debugLog.withFields(mapOf(SCREEN to CAR))

    /** The session's own work, on the main thread; a failure that gets this far is written down, never thrown. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> log.log(ERROR_LINE, CarFailure.fields("coroutine", e)) },
    )

    private val stack = CarStack()
    private var gate = CarGate.LOADING
    private var gateLogged = false
    private var screensMade = false
    private var hostAttached = false
    private var destroyed = false

    private var map: CarMapRenderer? = null

    /** The token generation the map was made for; null without a map. */
    private var mapGeneration: Int? = null

    /** What the map follows: the settings, the destination and its camera. */
    private var mapJobs: Job? = null

    private var preciseWatch: Job? = null

    /** The camera of the map there is now, for the home screen's buttons; idle without a map. */
    private val cameraMode = MutableStateFlow(CameraMode.IDLE)

    private var home: CarHomeScreen? = null

    private val homeListener = object : CarHomeScreen.Listener {
        override fun onStartOnPhone() {
            // A click the host delivers late, after a message came over the map, asks nothing.
            if (gate != CarGate.READY || stack.shown != null) return
            stack.onStartAsked(parts.navigation.guidanceState.value)
            applyStack("startOnPhone")
        }

        // As the phone's End: the stop sequence of the drive, which ends both screens.
        override fun onEnd() {
            log.log("ui_end")
            parts.navigation.stopGuidance(StopReason.USER_CANCELLED)
        }

        override fun onMapButton(button: CarMapButton) {
            map?.setCameraMode(button.target)
        }

        override fun onZoom(delta: Double) {
            map?.zoomBy(delta)
        }
    }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event -> guard("lifecycle") { onLifecycle(event) } })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        try {
            log.log(SESSION_LINE, mapOf("event" to "screen", "action" to intent.action))
            evaluate("screen")
            val root = homeScreen()
            screensMade = true
            val first = stack.step(gate, parts.navigation.guidanceState.value)
            if (first is CarStackStep.Push) {
                // The home screen below the message, so that the stack never runs empty under it.
                val message = messageScreen(first.message)
                screenManager().push(root)
                logStep(first, "screen")
                return message
            }
            return root
        } catch (e: RuntimeException) {
            failed("onCreateScreen", e)
            screensMade = true
            // The home screen alone; the next change of the gate puts its message on it again.
            stack.shown?.let(stack::onGone)
            return home ?: homeScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        guard("onNewIntent") { log.log(SESSION_LINE, mapOf("event" to "intent", "action" to intent.action)) }
    }

    /**
     * The host's day and night: the map takes the light and the colours at once and the basemap's theme
     * once the mode has held ([CarLook]), and the home screen's template is built again for it.
     */
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        guard("onCarConfigurationChanged") {
            val dark = carContext.isDarkMode
            log.log(SESSION_LINE, mapOf("event" to "configuration", "dark" to dark))
            map?.setLook(LookOnMap(CarLook.lookOf(dark)))
            home?.refresh()
        }
    }

    private fun onLifecycle(event: Lifecycle.Event) {
        val fields = mapOf("event" to event.name, "guidance" to parts.navigation.guidanceState.value.name)
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                log.log(SESSION_LINE, fields + hostFields() + ("access" to parts.mapsAccess.value.toString()))
                scope.launch { parts.mapsAccess.collect { guard("access") { evaluate("access") } } }
                scope.launch { parts.navigation.guidanceState.collect { guard("guidance") { evaluate("guidance") } } }
            }
            Lifecycle.Event.ON_START -> {
                log.log(SESSION_LINE, fields)
                // Back on the car's screen: precise location may have been allowed meanwhile.
                evaluate("start")
            }
            Lifecycle.Event.ON_DESTROY -> {
                log.log(SESSION_LINE, fields)
                stop()
            }
            else -> log.log(SESSION_LINE, fields)
        }
    }

    /**
     * The car disconnected or the host let the app go. The map lets go of the surface; the navigator
     * lets the session go by itself. Nothing here touches the drive, which goes on on the phone.
     */
    private fun stop() {
        destroyed = true
        scope.cancel()
        mapJobs = null
        preciseWatch = null
        releaseMap("destroy")
        home = null
    }

    /** The gate, the map and the screens for the access to maps and the drive as they are now. */
    private fun evaluate(cause: String) {
        if (destroyed) return
        val access = parts.mapsAccess.value
        val next = CarGate.of(access, preciseLocation())
        if (access is MapsAccess.Ready) attachHost()
        applyMap(CarMapStep.next(mapGeneration, access, next), cause)
        if (next != gate || !gateLogged) {
            gate = next
            gateLogged = true
            log.log(SESSION_LINE, mapOf("event" to "gate", "gate" to next.name, "cause" to cause))
        }
        watchPreciseLocation(next == CarGate.NO_PRECISE_LOCATION)
        applyStack(cause)
    }

    /** The navigator lives while this session does, from the first token on; once per session. */
    private fun attachHost() {
        if (hostAttached) return
        hostAttached = true
        guard("attachCarHost") { parts.attachHost(this) }
    }

    private fun applyMap(step: CarMapStep, cause: String) {
        when (step) {
            CarMapStep.Keep -> Unit
            is CarMapStep.Build -> buildMap(step.generation, MAP_BUILD, cause)
            is CarMapStep.Rebuild -> {
                releaseMap(cause)
                buildMap(step.generation, MAP_REBUILD, cause)
            }
            CarMapStep.Release -> releaseMap(cause)
        }
    }

    private fun buildMap(generation: Int, action: String, cause: String) {
        val created = try {
            parts.createMap(carContext)
        } catch (e: RuntimeException) {
            failed("createCarMap", e)
            return
        }
        map = created
        mapGeneration = generation
        guard("setLook") { created.setLook(LookOnMap(CarLook.lookOf(carContext.isDarkMode))) }
        val settings = parts.mapSettings
        mapJobs = scope.launch {
            follow(settings.cameraStyle, "setCameraStyle", created::setCameraStyle)
            follow(settings.laneZoom, "setLaneZoom", created::setLaneZoom)
            follow(settings.laneDetails, "setLaneDetails", created::setLaneDetails)
            follow(settings.ownRouteLine, "setOwnRouteLine", created::setOwnRouteLine)
            follow(settings.drawOwnLanes, "setDrawOwnLanes", created::setDrawOwnLanes)
            // The place of the preview, the drive and the arrival, as the phone pins it.
            follow(parts.navigation.destination, "showPlace", created::showPlace)
            follow(created.cameraMode, "cameraMode") { cameraMode.value = it }
        }
        log.log(SESSION_LINE, mapOf("event" to "map", "action" to action, "generation" to generation, "cause" to cause))
    }

    private fun releaseMap(cause: String) {
        val current = map ?: return
        mapJobs?.cancel()
        mapJobs = null
        map = null
        mapGeneration = null
        cameraMode.value = CameraMode.IDLE
        guard("releaseCarMap") { current.release() }
        log.log(SESSION_LINE, mapOf("event" to "map", "action" to MAP_RELEASE, "cause" to cause))
    }

    private fun <T> CoroutineScope.follow(values: Flow<T>, call: String, apply: (T) -> Unit) {
        launch { values.collect { guard(call) { apply(it) } } }
    }

    /** One step of the screens towards what the gate and the drive ask for. */
    private fun applyStack(cause: String) {
        if (!screensMade || destroyed) return
        val step = stack.step(gate, parts.navigation.guidanceState.value)
        if (step == CarStackStep.None) return
        val manager = screenManager()
        try {
            when (step) {
                CarStackStep.None -> Unit
                // Always from the home screen, so that it holds one message at most.
                is CarStackStep.Push -> {
                    manager.popToRoot()
                    manager.push(messageScreen(step.message))
                }
                CarStackStep.Pop -> manager.popToRoot()
                is CarStackStep.Replace -> {
                    manager.popToRoot()
                    manager.push(messageScreen(step.message))
                }
            }
        } catch (e: RuntimeException) {
            failed("stack", e)
            // The next step starts from what is on top, not from what was meant to be.
            val onTop = (runCatching { manager.top }.getOrNull() as? CarMessageScreen)?.message
            stack.shown?.takeIf { it != onTop }?.let(stack::onGone)
        }
        logStep(step, cause)
    }

    private fun logStep(step: CarStackStep, cause: String) {
        val (name, message) = when (step) {
            CarStackStep.None -> return
            is CarStackStep.Push -> "push" to step.message
            CarStackStep.Pop -> "pop" to null
            is CarStackStep.Replace -> "replace" to step.message
        }
        log.log(SESSION_LINE, mapOf("event" to "stack", "step" to name, "message" to message?.name, "cause" to cause).filterValues { it != null })
    }

    /**
     * While precise location is missing, whether it has been allowed on the phone, every few seconds:
     * Android sends no word of it.
     */
    private fun watchPreciseLocation(on: Boolean) {
        if (!on) {
            preciseWatch?.cancel()
            preciseWatch = null
            return
        }
        if (preciseWatch != null) return
        preciseWatch = scope.launch {
            while (!preciseLocation()) delay(PRECISE_CHECK_MS)
            preciseWatch = null
            guard("location") { evaluate("location") }
        }
    }

    private fun preciseLocation(): Boolean = try {
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } catch (e: RuntimeException) {
        failed("checkSelfPermission", e)
        false
    }

    private fun homeScreen(): CarHomeScreen =
        CarHomeScreen(carContext, parts.navigation.guidanceState, cameraMode.asStateFlow(), homeListener, log).also { home = it }

    private fun messageScreen(message: CarMessage) = CarMessageScreen(carContext, message, log, stack::onGone)

    private fun screenManager(): ScreenManager = carContext.getCarService(ScreenManager::class.java)

    /** The host that bound the app, by its package, and the Car API level it speaks; names and numbers only. */
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

        const val MAP_BUILD = "build"
        const val MAP_REBUILD = "rebuild"
        const val MAP_RELEASE = "release"

        /** How often precise location is checked again while its message shows. */
        const val PRECISE_CHECK_MS = 3_000L
    }
}
