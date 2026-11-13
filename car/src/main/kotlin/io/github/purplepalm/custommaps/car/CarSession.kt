package io.github.purplepalm.custommaps.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.SystemClock
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.connection.CarConnection
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import io.github.purplepalm.custommaps.car.routing.CarRouting
import io.github.purplepalm.custommaps.car.routing.CarRoutingCard
import io.github.purplepalm.custommaps.car.routing.LanesImageRenderer
import io.github.purplepalm.custommaps.car.routing.ManeuverIconRenderer
import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.car.CarLook
import io.github.purplepalm.custommaps.nav.debug.withFields
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookScreen
import io.github.purplepalm.custommaps.nav.model.CameraMode
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
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
 * - **The map** is made once the gate is ready and the app's first look is decided, again for a new
 *   token, and let go of when the session ends ([CarMapStep]). It takes the phone's camera style, lane
 *   zoom, lane details and Debug switches, pins the destination of the preview or the drive, and
 *   shows the app's one look, the phone's ([CarParts.look]): the session starts the look if no screen
 *   has yet (the host may have started the process with no activity), keeps it deciding while the
 *   session is started, as the phone's activity does, and hands the look to the map as the phone's
 *   screen does; while the phone's activity is not started the map's close view holds a switch.
 * - **The screens** are the home screen and at most one message on top of it ([CarStack]), so the
 *   host's template count stays low and every flow ends on the home screen.
 * - **The drive** goes to the host through the guidance bridge ([CarGuidanceBridge]), set up first
 *   thing at ON_CREATE, and to the home screen's routing card from the same content, with the app's
 *   own images in the host's day or night look: they sit on the host's card, not on the map. A drive
 *   running when the session is made shows at once. Opening the app on the car's screen again during
 *   a drive takes the car back. At ON_DESTROY the bridge lets go before the map does; the drive goes
 *   on on the phone.
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

    /** Keeps the app's look deciding while the session is started; null while it is not. */
    private var lookWhileStarted: Job? = null

    /** The camera of the map there is now, for the home screen's buttons; idle without a map. */
    private val cameraMode = MutableStateFlow(CameraMode.IDLE)

    private var home: CarHomeScreen? = null

    /** The drive as the host hears of it; null before ON_CREATE, or when the host's navigation manager could not be had. */
    private var bridge: CarGuidanceBridge? = null

    /** The look of the routing card's images: the host's day or night, at once. */
    private var imageLook: Look = Look.DAY

    private val words by lazy { CarDriveWords(carContext) }

    private val maneuverIcons = ManeuverIconRenderer({ carContext.resources.displayMetrics.density }, log)
    private val lanesImages = LanesImageRenderer({ carContext.resources.displayMetrics.density }, log)

    /** The routing card, the trip and the arrival, with the images in the look the car shows now. */
    private val card = CarRoutingCard(
        arrow = { parts, role -> maneuverIcons.icon(parts, role, imageLook) },
        lanesImage = { lanes -> lanesImages.image(lanes, imageLook) },
    )

    private val drive = object : CarHomeScreen.Drive {
        override fun routingInfo(): RoutingInfo = card.routingInfo(bridge?.shown)

        override fun destinationEstimate(): TravelEstimate? = card.destinationEstimate(bridge?.shown)

        override fun arrival(): MessageInfo = card.arrival(words.arrived, parts.navigation.destination.value?.name)
    }

    /** CarConnection's type while the session lives, for the log of the host's stop; null before it reports. */
    private var connectionType: Int? = null
    private var connection: LiveData<Int>? = null
    private val connectionObserver = Observer<Int> { type -> guard("connection") { onConnection(type) } }

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
            // The host asks for the first screen when the stack is empty: a message still held from an
            // earlier stack is gone with it, should the library ask again in one session.
            stack.shown?.let(stack::onGone)
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
        guard("onNewIntent") {
            log.log(SESSION_LINE, mapOf("event" to "intent", "action" to intent.action))
            // The driver opened the app on the car's screen again: during a drive, it takes the car back.
            bridge?.takeBack(CarGuidanceBridge.CAUSE_INTENT)
        }
    }

    /**
     * The host's day and night: the routing card's images take its look and the home screen's
     * template is built again for them. The map keeps the app's look ([CarLook]); a "car_look" line
     * says the host's mode beside it.
     */
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        guard("onCarConfigurationChanged") {
            val dark = carContext.isDarkMode
            log.log(SESSION_LINE, mapOf("event" to "configuration", "dark" to dark))
            log.log(LOOK_LINE, CarLook.fields(dark, CarLook.CAUSE_HOST, parts.look.lookOnMap.value))
            // The routing card's images take the host's look at once; each is drawn once in it.
            imageLook = CarLook.lookOf(dark)
            home?.refresh()
        }
    }

    private fun onLifecycle(event: Lifecycle.Event) {
        val fields = mapOf("event" to event.name, "guidance" to parts.navigation.guidanceState.value.name)
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                // The navigation manager's callback before anything else.
                guard("startGuidance") { startGuidance() }
                log.log(SESSION_LINE, fields + hostFields() + ("access" to parts.mapsAccess.value.toString()))
                guard("watchConnection") { watchConnection() }
                // The app's first look, when the car's host started the process and no activity has yet:
                // the map waits for it, so that its first tiles are drawn in it, as the phone's do.
                guard("lookStart") { parts.look.start() }
                if (!parts.look.decided.value) {
                    scope.launch {
                        parts.look.decided.first { it }
                        guard("look") { evaluate("look") }
                    }
                }
                scope.launch { parts.mapsAccess.collect { guard("access") { evaluate("access") } } }
                // The drive's state before its progress, so that a drive running now shows at once.
                scope.launch {
                    parts.navigation.guidanceState.collect { state ->
                        guard("guidanceBridge") { bridge?.onGuidance(state) }
                        guard("guidance") { evaluate("guidance") }
                    }
                }
                scope.launch { parts.navigation.routeProgress.collect { guard("progress") { bridge?.onProgress(it) } } }
            }
            Lifecycle.Event.ON_START -> {
                log.log(SESSION_LINE, fields)
                // The look decides while the car's screen shows the app, as while the phone's does; one
                // loop for both when both are started.
                lookWhileStarted?.cancel()
                lookWhileStarted = scope.launch { keepLookDeciding() }
                // Back on the car's screen: precise location may have been allowed meanwhile.
                guard("start") { evaluate("start") }
                // And during a drive another app may have been shown meanwhile: the app takes the car back.
                guard("takeBack") { bridge?.takeBack(CarGuidanceBridge.CAUSE_START) }
            }
            Lifecycle.Event.ON_STOP -> {
                log.log(SESSION_LINE, fields)
                lookWhileStarted?.cancel()
                lookWhileStarted = null
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
        // The bridge first: it is gone and lets go of the navigation manager before the map goes.
        guard("stopGuidance") { bridge?.stop() }
        guard("connection") { connection?.removeObserver(connectionObserver) }
        connection = null
        scope.cancel()
        mapJobs = null
        preciseWatch = null
        lookWhileStarted = null
        releaseMap("destroy")
        home = null
    }

    /** The gate, the map and the screens for the access to maps and the drive as they are now. */
    private fun evaluate(cause: String) {
        if (destroyed) return
        val access = parts.mapsAccess.value
        val next = CarGate.of(access, preciseLocation())
        if (access is MapsAccess.Ready) attachHost()
        val step = CarMapStep.next(mapGeneration, access, next)
        // A map waits for the app's first look; it is decided within a moment of the session's start.
        applyMap(if (step is CarMapStep.Build && !parts.look.decided.value) CarMapStep.Keep else step, cause)
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
            // Made with the app's look, so its first tiles are drawn in it.
            parts.createMap(carContext, parts.look.lookOnMap.value)
        } catch (e: RuntimeException) {
            failed("createCarMap", e)
            return
        }
        map = created
        mapGeneration = generation
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
            // Every later look of the app at once, as on the phone's map; what the map did goes into the look's line.
            launch { showLook(created) }
            // Whether the camera shows a close view, which holds a look switch while the phone's activity is not started.
            launch { feedCloseView(created) }
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

    /** Keeps the app's look deciding until cancelled, at ON_STOP; a failure is written down, not thrown. */
    private suspend fun keepLookDeciding() {
        try {
            parts.look.runWhileStarted(LookScreen.CAR)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            failed("lookWhileStarted", e)
        }
    }

    /**
     * Hands the app's look to [map] until the map goes: each request goes to it as it comes. A map
     * that fails keeps the look it had.
     */
    private suspend fun showLook(map: CarMapRenderer) {
        try {
            parts.look.showOn { request, onSettled ->
                try {
                    map.setLook(request, onSettled)
                } catch (e: RuntimeException) {
                    failed("setLook", e)
                    LookApplied(onMap = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            failed("showLook", e)
        }
    }

    /** [map]'s close view for the look until the map goes, and none after it. */
    private suspend fun feedCloseView(map: CarMapRenderer) {
        try {
            map.closeView.collect { closeView -> guard("closeView") { parts.look.onCloseView(closeView, LookScreen.CAR) } }
        } finally {
            guard("closeView") { parts.look.onCloseView(false, LookScreen.CAR) }
        }
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
        CarHomeScreen(carContext, parts.navigation.guidanceState, cameraMode.asStateFlow(), homeListener, log, drive).also { home = it }

    /**
     * The guidance bridge on the session's own navigation manager, its callback set; the routing
     * card's images in the host's look of now.
     */
    private fun startGuidance() {
        imageLook = read("isDarkMode") { CarLook.lookOf(carContext.isDarkMode) } ?: Look.DAY
        val manager = carContext.getCarService(NavigationManager::class.java)
        val bridge = CarGuidanceBridge(
            port = CarNavigationManager(manager, card::trip),
            content = { progress -> CarRouting.of(progress, parts.navigation.destination.value, ZonedDateTime.now(), words) },
            log = log,
            clock = SystemClock::elapsedRealtime,
            schedule = ::later,
            lifecycle = { lifecycle.currentState.name },
            connection = { connectionType },
            onShown = { home?.refresh() },
        )
        this.bridge = bridge
        bridge.start()
    }

    /** Runs [action] after [delayMillis] on the main thread, unless the session ends first; the result cancels it. */
    private fun later(delayMillis: Long, action: () -> Unit): () -> Unit {
        val job = scope.launch {
            delay(delayMillis)
            guard("later") { action() }
        }
        return { job.cancel() }
    }

    /**
     * Whether the phone is connected to a car, as CarConnection says, from now until the session
     * ends: the line of the host's request to stop navigating says it, so a disconnect can be told
     * from another app's start.
     */
    private fun watchConnection() {
        val type = CarConnection(carContext.applicationContext).type
        connection = type
        type.observeForever(connectionObserver)
    }

    private fun onConnection(type: Int?) {
        if (type == connectionType) return
        connectionType = type
        log.log(SESSION_LINE, mapOf("event" to "connection", "type" to CarGuidanceBridge.connectionName(type)))
    }

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
        const val LOOK_LINE = "car_look"
        const val ERROR_LINE = "car_error"

        const val MAP_BUILD = "build"
        const val MAP_REBUILD = "rebuild"
        const val MAP_RELEASE = "release"

        /** How often precise location is checked again while its message shows. */
        const val PRECISE_CHECK_MS = 3_000L
    }
}
