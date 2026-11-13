package io.github.purplepalm.custommaps.diagnostics

import android.view.Display
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "display_state" line on a fake display: at the start, and then only when the state or the
 * refresh rate to a tenth of a hertz changes.
 */
class DisplayStateLogTest {

    private val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val log = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }
    }

    /** What the fake display says now; null while there is no display to read. */
    private var display: DisplayStateLog.Reading? = DisplayStateLog.Reading(Display.STATE_ON, 120.0f)

    private val displayState = DisplayStateLog(log) { display }

    @Test
    fun `the first reading is written at once`() {
        displayState.check()
        assertEquals(listOf("display_state" to mapOf<String, Any?>("state" to "ON", "hz" to 120.0)), lines)
    }

    @Test
    fun `the same display again writes nothing`() {
        displayState.check()
        displayState.check()
        assertEquals(1, lines.size)
    }

    @Test
    fun `a change of the state or of the refresh rate is written`() {
        displayState.check()
        display = DisplayStateLog.Reading(Display.STATE_OFF, 120.0f)
        displayState.check()
        display = DisplayStateLog.Reading(Display.STATE_OFF, 60.0f)
        displayState.check()
        assertEquals(
            listOf(mapOf<String, Any?>("state" to "ON", "hz" to 120.0), mapOf("state" to "OFF", "hz" to 120.0), mapOf("state" to "OFF", "hz" to 60.0)),
            lines.map { it.second },
        )
    }

    @Test
    fun `a refresh rate that rounds to the same tenth of a hertz is no change`() {
        display = DisplayStateLog.Reading(Display.STATE_ON, 59.94f)
        displayState.check()
        display = DisplayStateLog.Reading(Display.STATE_ON, 59.92f)
        displayState.check()
        assertEquals(listOf(59.9), lines.map { it.second["hz"] })
    }

    @Test
    fun `a display that cannot be read writes nothing, and the next one that can is written`() {
        display = null
        displayState.check()
        assertTrue(lines.isEmpty())
        display = DisplayStateLog.Reading(Display.STATE_DOZE, 60.0f)
        displayState.check()
        assertEquals(listOf("DOZE"), lines.map { it.second["state"] })
    }

    @Test
    fun `a refresh rate that is no number is written as none`() {
        display = DisplayStateLog.Reading(Display.STATE_ON, Float.NaN)
        displayState.check()
        assertEquals(mapOf<String, Any?>("state" to "ON", "hz" to null), lines.single().second)
    }

    @Test
    fun `every state has its name`() {
        val names = listOf(
            Display.STATE_ON, Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND,
            Display.STATE_VR, Display.STATE_ON_SUSPEND, Display.STATE_UNKNOWN, 42,
        ).map(SystemNames::displayStateName)
        assertEquals(listOf("ON", "OFF", "DOZE", "DOZE_SUSPEND", "VR", "ON_SUSPEND", "UNKNOWN", "STATE_42"), names)
    }
}
