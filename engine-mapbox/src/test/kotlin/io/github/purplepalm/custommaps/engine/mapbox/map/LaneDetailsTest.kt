package io.github.purplepalm.custommaps.engine.mapbox.map

import io.github.purplepalm.custommaps.nav.debug.DebugLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaneDetailsTest {

    /** The style's switch, with every write recorded. It starts off, as a style comes. */
    private class Style(var value: Boolean? = false) : LaneDetails.Setting {
        override var usable = true
        val writes = ArrayList<Boolean>()
        var error: String? = null

        override fun read(): Boolean? = value

        override fun write(on: Boolean): String? {
            writes += on
            if (error == null) value = on
            return error
        }
    }

    private val style = Style()
    private val lines = ArrayList<Map<String, Any?>>()
    private val log = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            assertEquals("lane_details", type)
            lines += fields
        }
    }

    /** What was left for later, with its delay; the test runs it when the time has come. */
    private val later = ArrayList<Pair<Long, () -> Unit>>()
    private val details = LaneDetails(style, log) { delayMillis, task -> later += delayMillis to task }

    private fun runLater(delayMillis: Long) {
        val due = later.filter { it.first == delayMillis }
        later.removeAll(due)
        due.forEach { it.second() }
    }

    /** What the navigation SDK does when the style is ready and at every style load. */
    private fun sdkHides() {
        if (style.value == true) style.value = false
    }

    @Test
    fun `off as it starts, a style load sets nothing and leaves nothing for later`() {
        assertFalse(details.enabled)
        details.onStyleLoaded()
        assertTrue(later.isEmpty())
        assertTrue(style.writes.isEmpty())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `wanted before the style is there, it is set only after everything that hears the style load`() {
        details.setEnabled(true)
        assertTrue(style.writes.isEmpty(), "no style yet")
        details.onStyleLoaded()
        // Still within the style load: the SDK looks now, and finds nothing to turn off.
        assertTrue(style.writes.isEmpty(), "never within the callback of the style load")
        sdkHides()
        runLater(0L)
        assertEquals(listOf(true), style.writes)
        assertEquals(true, style.value)
        assertEquals(listOf(mapOf("reason" to "style_loaded", "wanted" to true, "read" to true)), lines)
        // The look a second later finds it as it should be.
        runLater(LaneDetails.RECHECK_MILLIS)
        assertEquals(listOf(true), style.writes)
        assertEquals(mapOf("laneDetails" to true, "hdRoads" to true, "hdRoadsReapplied" to 0), details.diagnostics())
        assertTrue(later.isEmpty())
    }

    @Test
    fun `every style load sets it again the same way`() {
        details.setEnabled(true)
        details.onStyleLoaded()
        runLater(0L)
        runLater(LaneDetails.RECHECK_MILLIS)
        // A new style comes with lane details off.
        style.value = false
        details.onStyleLoaded()
        assertEquals(listOf(true), style.writes, "not within the callback")
        runLater(0L)
        assertEquals(listOf(true, true), style.writes)
        assertEquals(true, style.value)
    }

    @Test
    fun `the switch applies at once when the style is there, on and off`() {
        details.onStyleLoaded()
        details.setEnabled(true)
        assertEquals(listOf(true), style.writes)
        details.setEnabled(true)
        assertEquals(listOf(true), style.writes, "the same again is nothing new")
        details.setEnabled(false)
        assertEquals(listOf(true, false), style.writes)
        assertEquals(
            listOf(
                mapOf("reason" to "setting", "wanted" to true, "read" to true),
                mapOf("reason" to "setting", "wanted" to false, "read" to false),
            ),
            lines,
        )
        assertTrue(later.isEmpty())
    }

    @Test
    fun `turned off by something else, it is set again and counted`() {
        details.onStyleLoaded()
        details.setEnabled(true)
        details.check()
        assertEquals(0, details.diagnostics()["hdRoadsReapplied"])
        sdkHides()
        details.check()
        assertEquals(listOf(true, true), style.writes)
        assertEquals(mapOf("laneDetails" to true, "hdRoads" to true, "hdRoadsReapplied" to 1), details.diagnostics())
        assertEquals(mapOf("reason" to "reapplied", "wanted" to true, "read" to true), lines.last())
        sdkHides()
        details.check()
        assertEquals(2, details.diagnostics()["hdRoadsReapplied"])
    }

    @Test
    fun `turned off within a second of the style load, the look after it sets it again`() {
        details.setEnabled(true)
        details.onStyleLoaded()
        runLater(0L)
        // Should the SDK ever look after the setting was made.
        sdkHides()
        runLater(LaneDetails.RECHECK_MILLIS)
        assertEquals(true, style.value)
        assertEquals(1, details.diagnostics()["hdRoadsReapplied"])
    }

    @Test
    fun `turned on by something else while the switch is off, it is turned off`() {
        details.onStyleLoaded()
        style.value = true
        details.check()
        assertEquals(listOf(false), style.writes)
        assertEquals(mapOf("laneDetails" to false, "hdRoads" to false, "hdRoadsReapplied" to 1), details.diagnostics())
    }

    @Test
    fun `a style without the setting is left alone by the check`() {
        details.onStyleLoaded()
        details.setEnabled(true)
        style.value = null
        details.check()
        details.check()
        assertEquals(listOf(true), style.writes, "only the switch itself")
        assertEquals(mapOf("laneDetails" to true, "hdRoads" to null, "hdRoadsReapplied" to 0), details.diagnostics())
    }

    @Test
    fun `before a style is there the check does nothing and says nothing`() {
        details.setEnabled(true)
        details.check()
        assertTrue(style.writes.isEmpty())
        assertEquals(mapOf("laneDetails" to true, "hdRoads" to null, "hdRoadsReapplied" to 0), details.diagnostics())
    }

    @Test
    fun `a setting the style refuses is logged with what it said`() {
        details.onStyleLoaded()
        style.error = "no such config"
        details.setEnabled(true)
        assertEquals(listOf(mapOf("reason" to "setting", "wanted" to true, "read" to false, "error" to "no such config")), lines)
    }

    @Test
    fun `once the map goes away nothing is set, also not what was left for later`() {
        details.setEnabled(true)
        details.onStyleLoaded()
        details.release()
        runLater(0L)
        runLater(LaneDetails.RECHECK_MILLIS)
        details.check()
        details.setEnabled(false)
        assertTrue(style.writes.isEmpty())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a map that is not valid any more is not asked`() {
        details.onStyleLoaded()
        style.usable = false
        details.setEnabled(true)
        details.check()
        assertTrue(style.writes.isEmpty())
    }
}
