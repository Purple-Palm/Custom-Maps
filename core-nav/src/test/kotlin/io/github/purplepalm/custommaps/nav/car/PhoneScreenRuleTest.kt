package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.car.PhoneScreenRule.Cause
import io.github.purplepalm.custommaps.nav.car.PhoneScreenRule.Change
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the phone shows during a drive while the car shows the map: its map or the list. */
class PhoneScreenRuleTest {

    private val list = PhoneScreenState(PhoneScreen.LIST)
    private val map = PhoneScreenState(PhoneScreen.MAP)
    private val mapWithListButton = PhoneScreenState(PhoneScreen.MAP, listButton = true)

    /** A drive started with the car connected: the list. */
    private fun drivingWithCar(): PhoneScreenRule = PhoneScreenRule().also {
        it.onCarMapShown(true)
        it.onGuidance(GuidanceState.PREVIEW)
        it.onGuidance(GuidanceState.ACTIVE)
    }

    @Test
    fun `without a car nothing changes, from before Start to after the arrival`() {
        val rule = PhoneScreenRule()
        for (state in listOf(GuidanceState.IDLE, GuidanceState.PREVIEW, GuidanceState.ACTIVE, GuidanceState.ARRIVED, GuidanceState.IDLE)) {
            assertNull(rule.onGuidance(state), "$state")
            assertEquals(map, rule.state)
        }
        // The buttons are not offered then, and change nothing either.
        rule.onGuidance(GuidanceState.ACTIVE)
        assertNull(rule.onMapButton())
        assertNull(rule.onListButton())
        assertEquals(PhoneScreen.MAP, rule.screen)
    }

    @Test
    fun `a drive that starts with the car showing the map shows the list, and its end the map`() {
        val rule = PhoneScreenRule()
        assertNull(rule.onCarMapShown(true))
        // Before Start and in the route preview: the map, with the car or without.
        assertNull(rule.onGuidance(GuidanceState.PREVIEW))
        assertEquals(map, rule.state)
        assertEquals(Change(PhoneScreen.LIST, Cause.DRIVE), rule.onGuidance(GuidanceState.ACTIVE))
        assertEquals(list, rule.state)
        assertEquals(Change(PhoneScreen.MAP, Cause.END), rule.onGuidance(GuidanceState.ARRIVED))
        assertEquals(map, rule.state)
        assertNull(rule.onGuidance(GuidanceState.IDLE))
    }

    @Test
    fun `End, and a start the navigator refused, end the drive too`() {
        val ended = drivingWithCar()
        assertEquals(Change(PhoneScreen.MAP, Cause.END), ended.onGuidance(GuidanceState.IDLE))
        val refused = drivingWithCar()
        assertEquals(Change(PhoneScreen.MAP, Cause.END), refused.onGuidance(GuidanceState.PREVIEW))
    }

    @Test
    fun `before Start, in the preview and at the arrival the phone shows its map while the car shows one`() {
        for (state in listOf(GuidanceState.IDLE, GuidanceState.PREVIEW, GuidanceState.ARRIVED)) {
            val rule = PhoneScreenRule()
            assertNull(rule.onGuidance(state))
            assertNull(rule.onCarMapShown(true), "$state")
            assertEquals(map, rule.state, "$state")
            // No button outside a drive.
            assertNull(rule.onMapButton())
            assertNull(rule.onListButton())
        }
    }

    @Test
    fun `the Map button shows the map for the drive, and the List button takes the choice back`() {
        val rule = drivingWithCar()
        assertEquals(Change(PhoneScreen.MAP, Cause.BUTTON), rule.onMapButton())
        assertTrue(rule.mapChosen)
        assertEquals(mapWithListButton, rule.state)
        // Pressed again: nothing new.
        assertNull(rule.onMapButton())
        assertEquals(Change(PhoneScreen.LIST, Cause.BUTTON), rule.onListButton())
        assertFalse(rule.mapChosen)
        assertEquals(list, rule.state)
        assertNull(rule.onListButton())
    }

    @Test
    fun `a car that comes mid-drive brings the list, and one that goes brings the map back`() {
        val rule = PhoneScreenRule()
        assertNull(rule.onGuidance(GuidanceState.ACTIVE))
        assertEquals(Change(PhoneScreen.LIST, Cause.CAR), rule.onCarMapShown(true))
        assertEquals(Change(PhoneScreen.MAP, Cause.CAR), rule.onCarMapShown(false))
        assertEquals(map, rule.state)
        // The same again says nothing.
        assertNull(rule.onCarMapShown(false))
    }

    @Test
    fun `the car unplugged and plugged back during a drive without a choice brings the map, then the list again`() {
        val rule = drivingWithCar()
        assertEquals(Change(PhoneScreen.MAP, Cause.CAR), rule.onCarMapShown(false))
        assertEquals(map, rule.state)
        assertEquals(Change(PhoneScreen.LIST, Cause.CAR), rule.onCarMapShown(true))
        assertEquals(list, rule.state)
    }

    @Test
    fun `the car unplugged and plugged back during a drive with the map chosen keeps the map all along`() {
        val rule = drivingWithCar()
        rule.onMapButton()
        // No car: no List button, as there is no list to go back to.
        assertNull(rule.onCarMapShown(false))
        assertEquals(map, rule.state)
        assertTrue(rule.mapChosen)
        // Back: still the map, which the driver chose, and the List button again.
        assertNull(rule.onCarMapShown(true))
        assertEquals(mapWithListButton, rule.state)
        assertEquals(Change(PhoneScreen.LIST, Cause.BUTTON), rule.onListButton())
    }

    @Test
    fun `a choice does not outlive its drive`() {
        val rule = drivingWithCar()
        rule.onMapButton()
        // The drive ends on the map the driver chose: no change, and the choice goes.
        assertNull(rule.onGuidance(GuidanceState.ARRIVED))
        assertFalse(rule.mapChosen)
        assertEquals(map, rule.state)
        assertNull(rule.onGuidance(GuidanceState.PREVIEW))
        // The next drive starts with no choice: the list.
        assertEquals(Change(PhoneScreen.LIST, Cause.DRIVE), rule.onGuidance(GuidanceState.ACTIVE))
        // The same when the car is plugged in only after the next drive started.
        val later = drivingWithCar()
        later.onMapButton()
        later.onCarMapShown(false)
        later.onGuidance(GuidanceState.IDLE)
        later.onGuidance(GuidanceState.ACTIVE)
        assertEquals(Change(PhoneScreen.LIST, Cause.CAR), later.onCarMapShown(true))
    }

    @Test
    fun `a button pressed outside a drive is no choice for the next one`() {
        val rule = PhoneScreenRule()
        rule.onCarMapShown(true)
        assertNull(rule.onMapButton())
        assertFalse(rule.mapChosen)
        assertNull(rule.onListButton())
        assertEquals(Change(PhoneScreen.LIST, Cause.DRIVE), rule.onGuidance(GuidanceState.ACTIVE))
    }

    @Test
    fun `the drive's state told again keeps the choice`() {
        val rule = drivingWithCar()
        rule.onMapButton()
        assertNull(rule.onGuidance(GuidanceState.ACTIVE))
        assertTrue(rule.mapChosen)
        assertEquals(mapWithListButton, rule.state)
        // And so does the car's.
        assertNull(rule.onCarMapShown(true))
        assertEquals(mapWithListButton, rule.state)
    }

    @Test
    fun `the line holds the screen and the cause, in lower case, and nothing else`() {
        assertEquals(mapOf("screen" to "list", "cause" to "drive"), PhoneScreenRule.fields(Change(PhoneScreen.LIST, Cause.DRIVE)))
        assertEquals(mapOf("screen" to "map", "cause" to "car"), PhoneScreenRule.fields(Change(PhoneScreen.MAP, Cause.CAR)))
        assertEquals(mapOf("screen" to "map", "cause" to "button"), PhoneScreenRule.fields(Change(PhoneScreen.MAP, Cause.BUTTON)))
        assertEquals(mapOf("screen" to "map", "cause" to "end"), PhoneScreenRule.fields(Change(PhoneScreen.MAP, Cause.END)))
        assertEquals("phone_screen", PhoneScreenRule.LINE)
    }
}
