package io.github.purplepalm.custommaps.nav.car

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The car's lighter map while the phone is hot: when it comes, how long it holds, and its Debug switch. */
class CarLightRuleTest {

    @Test
    fun `light from the moment the status is moderate or worse`() {
        val rule = CarLightRule()
        assertFalse(rule.light)
        assertFalse(rule.onStatus(ThermalStatus.NONE, at(0)))
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(30)))
        assertFalse(rule.light)
        assertTrue(rule.onStatus(ThermalStatus.MODERATE, at(60)))
        assertTrue(rule.light)
        // Hotter still: no change.
        for (status in listOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN, ThermalStatus.MODERATE)) {
            assertFalse(rule.onStatus(status, at(90)), "$status")
            assertTrue(rule.light)
        }
        // Nothing is due while the phone is hot.
        assertEquals(CarLightRule.NONE, rule.dueAtMillis)
    }

    @Test
    fun `full again only after two minutes of light or better in a row`() {
        val rule = hotAt(0)
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(10)))
        assertTrue(rule.light)
        assertEquals(at(10) + CarLightRule.COOL_MILLIS, rule.dueAtMillis)
        // One millisecond short: still light.
        assertFalse(rule.onTime(at(10) + CarLightRule.COOL_MILLIS - 1))
        assertTrue(rule.light)
        assertTrue(rule.onTime(at(130)))
        assertFalse(rule.light)
        assertEquals(CarLightRule.NONE, rule.dueAtMillis)
        // A cooler status yet changes nothing.
        assertFalse(rule.onStatus(ThermalStatus.NONE, at(140)))
        assertFalse(rule.onTime(at(400)))
    }

    @Test
    fun `a status that comes and goes inside the two minutes changes nothing, and the count starts again`() {
        val rule = hotAt(0)
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(10)))
        // Hot again before the two minutes are over: still light, nothing due.
        assertFalse(rule.onStatus(ThermalStatus.MODERATE, at(60)))
        assertTrue(rule.light)
        assertEquals(CarLightRule.NONE, rule.dueAtMillis)
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(70)))
        // The two minutes run from the last cool status after the heat, not from the first.
        assertFalse(rule.onTime(at(130)))
        assertTrue(rule.light)
        assertEquals(at(190), rule.dueAtMillis)
        // A change between cool statuses keeps the count.
        assertFalse(rule.onStatus(ThermalStatus.NONE, at(140)))
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(150)))
        assertEquals(at(190), rule.dueAtMillis)
        assertTrue(rule.onTime(at(190)))
        assertFalse(rule.light)
    }

    @Test
    fun `the same status told again changes nothing and keeps the count`() {
        val rule = hotAt(0)
        assertFalse(rule.onStatus(ThermalStatus.MODERATE, at(5)))
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(10)))
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(100)))
        assertEquals(at(130), rule.dueAtMillis)
        assertTrue(rule.onTime(at(130)))
    }

    @Test
    fun `a phone that stays cool never gets the lighter map`() {
        val rule = CarLightRule()
        for ((seconds, status) in listOf(0 to ThermalStatus.NONE, 50 to ThermalStatus.LIGHT, 300 to ThermalStatus.NONE, 900 to ThermalStatus.LIGHT)) {
            assertFalse(rule.onStatus(status, at(seconds)))
            assertFalse(rule.onTime(at(seconds + 20)))
            assertEquals(CarLightRule.NONE, rule.dueAtMillis)
        }
        assertFalse(rule.light)
    }

    @Test
    fun `no status read counts as cool`() {
        val rule = CarLightRule()
        assertFalse(rule.onStatus(null, at(0)))
        assertFalse(rule.light)
        assertTrue(rule.onStatus(ThermalStatus.SEVERE, at(10)))
        // A status that can no longer be read starts the count as a cool one does.
        assertFalse(rule.onStatus(null, at(20)))
        assertEquals(at(140), rule.dueAtMillis)
        assertTrue(rule.onTime(at(140)))
    }

    @Test
    fun `the Debug switch off keeps the full map whatever the heat, and on again is light at once while hot`() {
        val rule = hotAt(0)
        assertTrue(rule.setEnabled(false, at(10)))
        assertFalse(rule.light)
        assertEquals(CarLightRule.NONE, rule.dueAtMillis)
        assertFalse(rule.onStatus(ThermalStatus.SEVERE, at(20)))
        assertFalse(rule.onTime(at(500)))
        assertFalse(rule.light)
        assertTrue(rule.setEnabled(true, at(600)))
        assertTrue(rule.light)
        // The same switch again changes nothing.
        assertFalse(rule.setEnabled(true, at(610)))
    }

    @Test
    fun `the Debug switch on while the phone cools keeps the full map`() {
        val rule = hotAt(0)
        assertTrue(rule.setEnabled(false, at(10)))
        assertFalse(rule.onStatus(ThermalStatus.LIGHT, at(20)))
        // On again before two minutes of cool: the phone is not hot, so the map stays full.
        assertFalse(rule.setEnabled(true, at(30)))
        assertFalse(rule.light)
        assertEquals(CarLightRule.NONE, rule.dueAtMillis)
        // And the next heat makes it light at once.
        assertTrue(rule.onStatus(ThermalStatus.MODERATE, at(40)))
    }

    @Test
    fun `the line says whether the map is the lighter one and the status by Android's name`() {
        val rule = CarLightRule()
        assertEquals(mapOf("on" to false, "thermal" to null), rule.fields())
        rule.onStatus(ThermalStatus.MODERATE, at(0))
        assertEquals(mapOf("on" to true, "thermal" to "MODERATE"), rule.fields())
        rule.onStatus(ThermalStatus.LIGHT, at(10))
        rule.onTime(at(130))
        assertEquals(mapOf("on" to false, "thermal" to "LIGHT"), rule.fields())
        assertEquals("car_light", CarLightRule.LINE)
    }

    @Test
    fun `the thermal statuses are Android's, in its order, hot from moderate on`() {
        assertEquals(listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"), ThermalStatus.entries.map { it.name })
        // PowerManager's THERMAL_STATUS_NONE to THERMAL_STATUS_SHUTDOWN.
        for ((code, status) in ThermalStatus.entries.withIndex()) {
            assertEquals(code, status.code)
            assertEquals(status, ThermalStatus.of(code))
        }
        assertEquals(listOf(false, false, true, true, true, true, true), ThermalStatus.entries.map { it.hot })
        assertNull(ThermalStatus.of(7))
        assertNull(ThermalStatus.of(-1))
    }

    /** A rule whose phone got hot at [seconds]. */
    private fun hotAt(seconds: Int): CarLightRule = CarLightRule().also {
        assertTrue(it.onStatus(ThermalStatus.MODERATE, at(seconds)))
    }

    /** [seconds] on the rule's clock, which starts somewhere after boot. */
    private fun at(seconds: Int): Long = START_MILLIS + seconds * 1_000L

    private companion object {
        const val START_MILLIS = 5_000_000L
    }
}
