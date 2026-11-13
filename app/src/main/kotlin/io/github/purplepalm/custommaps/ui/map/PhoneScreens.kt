package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.car.PhoneScreenRule
import io.github.purplepalm.custommaps.nav.car.PhoneScreenState
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the phone shows during a drive, its map or the list of the maneuvers ahead ([PhoneScreenRule]),
 * fed with the drive's state, whether a car's surface shows the map, and the driver's Map and List
 * buttons; a "phone_screen" line at each change of the screen. One for the process, in AppGraph, like
 * the drive itself: a phone screen made again mid-drive finds the driver's choice of this drive.
 * Without a car showing the map it never changes and writes nothing. Main thread only.
 */
class PhoneScreens(
    /** The navigator's guidance state. */
    private val guidance: StateFlow<GuidanceState>,
    /** Whether a car's surface shows the map now (the engine's own count). */
    private val carMapShown: StateFlow<Boolean>,
    private val debugLog: DebugLog,
) {

    private val rule = PhoneScreenRule()

    private val _state = MutableStateFlow(PhoneScreenState())

    /** What the phone shows now, and whether its map offers the List button. */
    val state: StateFlow<PhoneScreenState> = _state.asStateFlow()

    /** Follows the drive and the car's surfaces for as long as [scope] lives: the process's. */
    fun start(scope: CoroutineScope) {
        scope.launch { guidance.collect { state -> update("guidance") { rule.onGuidance(state) } } }
        scope.launch { carMapShown.collect { shown -> update("carMapShown") { rule.onCarMapShown(shown) } } }
    }

    /** The Map button on the list: the phone's map for the rest of this drive. */
    fun onMapButton() = update("mapButton") { rule.onMapButton() }

    /** The List button on the phone's map: back to the list. */
    fun onListButton() = update("listButton") { rule.onListButton() }

    /**
     * One step of the rule, its state published and a change written. The car's surfaces drive one of
     * the inputs: whatever goes wrong here costs an error line, never the drive the process holds.
     */
    private inline fun update(where: String, step: () -> PhoneScreenRule.Change?) {
        try {
            val change = step()
            _state.value = rule.state
            if (change != null) debugLog.log(PhoneScreenRule.LINE, PhoneScreenRule.fields(change))
        } catch (e: RuntimeException) {
            debugLog.error("phoneScreen.$where", e)
        }
    }
}
