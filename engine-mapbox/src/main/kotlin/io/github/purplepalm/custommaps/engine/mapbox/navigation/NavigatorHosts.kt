package io.github.purplepalm.custommaps.engine.mapbox.navigation

import androidx.lifecycle.LifecycleOwner
import io.github.purplepalm.custommaps.nav.car.CarFailure
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState

/**
 * The screens that keep the navigator alive while they are there. The navigator exists while at least
 * one attached lifecycle is created and goes when none is left; creating it starts nothing that Mapbox
 * bills. Three kinds keep it:
 *
 * - the phone's activity ([attachPhone]), while its map screen is composed, detached when it goes, as
 *   before;
 * - each car session ([attachCar]), from the session's creation once maps may be shown, and never
 *   detached by hand: the navigator lets a lifecycle go by itself when it is destroyed. A detach that
 *   ran before the navigator's own observer saw the session's end would leave the dead session
 *   counted, and the navigator would never be let go again in this process;
 * - the drive itself, which the engine attaches at its start and detaches after its trip session
 *   stopped ([GuidanceLifecycleOwner]).
 *
 * So screens coming and going never stop or restart a trip session. The two kinds of screen are kept
 * apart for the start rule of a later step, which needs to know whether the phone's screen is there.
 * A car session's attach writes a "host_attached" line; the phone's writes none, as before. Main
 * thread only, like the navigator's own lifecycle bookkeeping.
 */
internal class NavigatorHosts(
    private val attach: (LifecycleOwner) -> Unit,
    private val detach: (LifecycleOwner) -> Unit,
    private val debugLog: DebugLog,
    /** Where guidance is at the moment of an attach, for its line. */
    private val guidance: () -> GuidanceState,
) {

    /** The phone's screen keeps the navigator alive from now on, until [detachPhone]. */
    fun attachPhone(owner: LifecycleOwner) = attach(owner)

    /** The phone's screen goes; the navigator goes too if nothing else keeps it. */
    fun detachPhone(owner: LifecycleOwner) = detach(owner)

    /**
     * A car session keeps the navigator alive until it is destroyed; nothing detaches it. Attaching the
     * same session again changes nothing. A failure is written to the debug log and the session goes
     * on without the navigator; it never reaches the car's code, which runs in the phone's process.
     */
    fun attachCar(owner: LifecycleOwner) {
        try {
            attach(owner)
        } catch (e: RuntimeException) {
            debugLog.log(CAR_ERROR, CarFailure.fields("attachCarHost", e))
            return
        }
        debugLog.log(
            "host_attached",
            mapOf("kind" to KIND_CAR, "state" to owner.lifecycle.currentState.name, "guidance" to guidance()),
        )
    }

    companion object {
        const val KIND_CAR = "car"

        /** The line of an exception caught in the car's code outside the car's navigation calls. */
        const val CAR_ERROR = "car_error"
    }
}
