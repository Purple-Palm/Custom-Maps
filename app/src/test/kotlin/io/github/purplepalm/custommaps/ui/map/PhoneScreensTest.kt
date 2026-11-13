package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.car.PhoneScreen
import io.github.purplepalm.custommaps.nav.car.PhoneScreenState
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The phone's screen for the process: the rule fed with the drive and the car's surfaces, and its lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneScreensTest {

    private val guidance = MutableStateFlow(GuidanceState.IDLE)
    private val carMapShown = MutableStateFlow(false)
    private val lines = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val log = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            lines += type to fields
        }
    }

    private fun line(screen: String, cause: String) = "phone_screen" to mapOf<String, Any?>("screen" to screen, "cause" to cause)

    @Test
    fun `without a car a whole drive changes nothing and writes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val screens = PhoneScreens(guidance, carMapShown, log)
        screens.start(backgroundScope)
        for (state in listOf(GuidanceState.PREVIEW, GuidanceState.ACTIVE, GuidanceState.ARRIVED, GuidanceState.IDLE)) {
            guidance.value = state
            assertEquals(PhoneScreenState(), screens.state.value, "$state")
        }
        assertEquals(emptyList<Pair<String, Map<String, Any?>>>(), lines)
    }

    @Test
    fun `a drive with the car shows the list, then the map and the list as the car and the buttons say, and the map at the end`() = runTest(UnconfinedTestDispatcher()) {
        val screens = PhoneScreens(guidance, carMapShown, log)
        screens.start(backgroundScope)
        carMapShown.value = true
        guidance.value = GuidanceState.PREVIEW
        assertEquals(PhoneScreenState(PhoneScreen.MAP), screens.state.value)
        guidance.value = GuidanceState.ACTIVE
        assertEquals(PhoneScreenState(PhoneScreen.LIST), screens.state.value)
        carMapShown.value = false
        assertEquals(PhoneScreenState(PhoneScreen.MAP), screens.state.value)
        carMapShown.value = true
        assertEquals(PhoneScreenState(PhoneScreen.LIST), screens.state.value)
        screens.onMapButton()
        assertEquals(PhoneScreenState(PhoneScreen.MAP, listButton = true), screens.state.value)
        // Unplugged with the map chosen: the screen stays, and the List button goes with the car.
        carMapShown.value = false
        assertEquals(PhoneScreenState(PhoneScreen.MAP, listButton = false), screens.state.value)
        carMapShown.value = true
        assertEquals(PhoneScreenState(PhoneScreen.MAP, listButton = true), screens.state.value)
        screens.onListButton()
        assertEquals(PhoneScreenState(PhoneScreen.LIST), screens.state.value)
        guidance.value = GuidanceState.ARRIVED
        assertEquals(PhoneScreenState(PhoneScreen.MAP), screens.state.value)
        assertEquals(
            listOf(
                line("list", "drive"),
                line("map", "car"),
                line("list", "car"),
                line("map", "button"),
                line("list", "button"),
                line("map", "end"),
            ),
            lines,
        )
    }

    @Test
    fun `a screen made again mid-drive finds the choice of the drive`() = runTest(UnconfinedTestDispatcher()) {
        val screens = PhoneScreens(guidance, carMapShown, log)
        screens.start(backgroundScope)
        carMapShown.value = true
        guidance.value = GuidanceState.ACTIVE
        screens.onMapButton()
        // What a new activity reads: the map the driver chose, with no line of a drive's start.
        assertEquals(PhoneScreenState(PhoneScreen.MAP, listButton = true), screens.state.value)
        assertEquals(listOf(line("list", "drive"), line("map", "button")), lines)
    }
}
