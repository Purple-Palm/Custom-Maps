package io.github.purplepalm.custommaps.diagnostics

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.Motion

/**
 * The phone's own screen, for the debug log: whether its default display is on, off or dozing, and
 * how fast it refreshes. Android's screen broadcasts did not come during the first car session, while
 * the phone was locked and unlocked; the display's own state does, and the frames the app's main
 * thread gets depend on it. A "display_state" line when the app starts and whenever the state, or the
 * refresh rate to a tenth of a hertz, changes: `state` (ON, OFF, DOZE, DOZE_SUSPEND, VR, ON_SUSPEND or
 * UNKNOWN) and `hz`. No position and no time of day.
 *
 * [read] asks the system for the display, null while there is none: call [check] off the main
 * thread, one call at a time.
 */
internal class DisplayStateLog(
    private val debugLog: DebugLog,
    private val read: () -> Reading?,
) {

    /** What the default display says: its power state, as `Display.getState()` gives it, and its refresh rate in hertz. */
    data class Reading(val state: Int, val refreshHz: Float)

    private var lastState: String? = null
    private var lastHz: Double? = null

    /** Reads the display and writes a line when it says something else than the last line did. */
    fun check() {
        val reading = read() ?: return
        val state = SystemNames.displayStateName(reading.state)
        val hz = reading.refreshHz.toDouble().takeIf { it.isFinite() }?.let { Motion.round(it, 1) }
        if (state == lastState && hz == lastHz) return
        lastState = state
        lastHz = hz
        debugLog.log(LINE, mapOf("state" to state, "hz" to hz))
    }

    companion object {
        const val LINE = "display_state"
    }
}
