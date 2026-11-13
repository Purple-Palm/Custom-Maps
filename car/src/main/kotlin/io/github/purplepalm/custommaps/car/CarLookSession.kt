package io.github.purplepalm.custommaps.car

import io.github.purplepalm.custommaps.nav.CarMapRenderer
import io.github.purplepalm.custommaps.nav.LookApplied
import io.github.purplepalm.custommaps.nav.look.LookOnMap
import io.github.purplepalm.custommaps.nav.look.LookScreen
import io.github.purplepalm.custommaps.nav.look.SharedLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The car session's part in the app's one look, the phone's ([SharedLook]), on the session's
 * lifecycle:
 * - **Created** ([onCreate]): the first look of the process is started, when the car's host started
 *   the process and no screen has yet, and the session hears when it is decided: the car's map waits
 *   for it ([CarMapStep]), so that its first tiles are drawn in it.
 * - **Started** ([onStart] to [onStop]): the look keeps deciding as the car's screen, in one loop with
 *   the phone's activity when both are started.
 * - **A map** ([follow]): every look goes to it, the first one included, as the phone's screen hands
 *   the look to its map, and its close view goes to the look as the car's.
 *
 * The host's own day and night touch none of this: they colour only the images on its routing card
 * ([io.github.purplepalm.custommaps.nav.car.CarLook]). Every call into the look and the map catches
 * what is thrown and reports it to [failed] with the call: the car, the phone and the drive share one
 * process. Main thread only, in the session's [scope].
 */
class CarLookSession(
    private val look: SharedLook,
    private val scope: CoroutineScope,
    private val failed: (call: String, e: RuntimeException) -> Unit,
) {

    /** Keeps the look deciding while the session is started; null while it is not. */
    private var whileStarted: Job? = null

    /** Whether the first look of the process is decided: the car's map is made only then. */
    val decided: Boolean get() = look.decided.value

    /** What the car's map is to show now; a map made now is made with it. */
    val lookOnMap: LookOnMap get() = look.lookOnMap.value

    /**
     * The session is created: starts the first look if no screen has, and calls [onDecided] once it is
     * decided, when it is not yet.
     */
    fun onCreate(onDecided: () -> Unit) {
        try {
            look.start()
        } catch (e: RuntimeException) {
            failed("lookStart", e)
        }
        if (decided) return
        scope.launch {
            look.decided.first { it }
            try {
                onDecided()
            } catch (e: RuntimeException) {
                failed("look", e)
            }
        }
    }

    /** The session is started: the look decides while it is, until [onStop]. */
    fun onStart() {
        whileStarted?.cancel()
        whileStarted = scope.launch {
            try {
                look.runWhileStarted(LookScreen.CAR)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                failed("lookWhileStarted", e)
            }
        }
    }

    /** The session is stopped, or destroyed: the car's screen keeps the look deciding no more. */
    fun onStop() {
        whileStarted?.cancel()
        whileStarted = null
    }

    /**
     * Hands every look to [map] while [mapScope] lives, and the map's close view to the look; when the
     * map goes, its close view goes with it.
     */
    fun follow(map: CarMapRenderer, mapScope: CoroutineScope) {
        mapScope.launch { showLook(map) }
        mapScope.launch { feedCloseView(map) }
    }

    /** Each request of the look goes to [map] as it comes; a map that fails keeps the look it had. */
    private suspend fun showLook(map: CarMapRenderer) {
        try {
            look.showOn { request, onSettled ->
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
            map.closeView.collect { closeView -> closeView(closeView) }
        } finally {
            closeView(false)
        }
    }

    private fun closeView(closeView: Boolean) {
        try {
            look.onCloseView(closeView, LookScreen.CAR)
        } catch (e: RuntimeException) {
            failed("closeView", e)
        }
    }
}
