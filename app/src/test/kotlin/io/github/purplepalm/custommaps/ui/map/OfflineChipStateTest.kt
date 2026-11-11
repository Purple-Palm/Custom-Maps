package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.network.OfflineReason
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** When the offline chip comes and goes, and what it says. Time is the test's own. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineChipStateTest {

    private val online = NetworkStatus("wifi", validated = true, blocked = false, metered = false, roaming = false, suspended = false)
    private val noNetwork = NetworkStatus.noNetwork()
    private val noInternet = online.copy(validated = false)
    private val blocked = online.copy(transport = "cellular", metered = true, blocked = true)

    /** Everything the chip said, from hidden at the start, as the test moves the clock. */
    private fun TestScope.chip(status: MutableStateFlow<NetworkStatus>, onScreen: Flow<Boolean> = flowOf(true)): List<OfflineReason> {
        val said = mutableListOf<OfflineReason>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { shownOfflineReason(status, onScreen).toList(said) }
        runCurrent()
        return said
    }

    private fun TestScope.wait(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `nothing shows while the app is online`() = runTest {
        val said = chip(MutableStateFlow(online))
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE), said)
    }

    @Test
    fun `a drop of 2_9 seconds never shows`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noNetwork
        wait(2_900)
        status.value = online
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE), said)
    }

    @Test
    fun `the chip comes after 3 seconds offline`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noNetwork
        wait(2_999)
        assertEquals(listOf(OfflineReason.NONE), said)
        wait(1)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_NETWORK), said)
        assertEquals(3_000L, OFFLINE_CHIP_WAIT_MILLIS)
    }

    @Test
    fun `the chip goes at once when the app is online again`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noInternet
        wait(3_000)
        assertEquals(OfflineReason.NO_INTERNET, said.last())
        status.value = online
        // No time passes.
        runCurrent()
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_INTERNET, OfflineReason.NONE), said)
    }

    @Test
    fun `a reason that changes while the chip is up changes what it says without a new wait`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noNetwork
        wait(3_000)
        status.value = blocked
        runCurrent()
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_NETWORK, OfflineReason.BLOCKED), said)
        status.value = noInternet
        runCurrent()
        assertEquals(OfflineReason.NO_INTERNET, said.last())
        // And the chip was never away in between.
        assertEquals(1, said.count { it == OfflineReason.NONE })
    }

    @Test
    fun `a reason that changes during the wait does not start the wait over`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noNetwork
        wait(2_000)
        status.value = noInternet
        wait(999)
        assertEquals(listOf(OfflineReason.NONE), said)
        wait(1)
        // 3 s after the loss, with the reason there is then.
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_INTERNET), said)
    }

    @Test
    fun `each loss of the internet waits anew`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noNetwork
        wait(3_000)
        status.value = online
        wait(1_000)
        status.value = noNetwork
        wait(2_999)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_NETWORK, OfflineReason.NONE), said)
        wait(1)
        assertEquals(OfflineReason.NO_NETWORK, said.last())
    }

    @Test
    fun `two short drops in a row never show`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        repeat(2) {
            status.value = noNetwork
            wait(2_000)
            status.value = online
            wait(100)
        }
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE), said)
    }

    @Test
    fun `nothing shows before Android has answered`() = runTest {
        val status = MutableStateFlow(NetworkStatus.NOT_REPORTED)
        val said = chip(status)
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE), said)
        // The first answer is no network at all: the wait starts with it.
        status.value = noNetwork
        wait(2_999)
        assertEquals(listOf(OfflineReason.NONE), said)
        wait(1)
        assertEquals(OfflineReason.NO_NETWORK, said.last())
    }

    @Test
    fun `a network that changes and leaves the reason as it is says nothing new`() = runTest {
        val status = MutableStateFlow(online)
        val said = chip(status)
        status.value = noInternet
        wait(3_000)
        status.value = noInternet.copy(transport = "cellular", metered = true)
        status.value = noInternet.copy(transport = "cellular", suspended = true)
        wait(10_000)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_INTERNET), said)
        // Online on another network is online all the same.
        status.value = online.copy(transport = "cellular", metered = true)
        runCurrent()
        assertEquals(OfflineReason.NONE, said.last())
    }

    @Test
    fun `a screen that opens while the app is offline waits too`() = runTest {
        val said = chip(MutableStateFlow(blocked))
        assertEquals(emptyList<OfflineReason>(), said)
        wait(3_000)
        assertEquals(listOf(OfflineReason.BLOCKED), said)
    }

    @Test
    fun `a block Android reports while the screen is stopped never shows`() = runTest {
        val status = MutableStateFlow(online)
        val onScreen = MutableStateFlow(true)
        val said = chip(status, onScreen)
        // The app goes to the background, and Android keeps it off the network a few seconds later.
        onScreen.value = false
        wait(5_000)
        status.value = blocked
        wait(60_000)
        // Back on screen: Android lifts the block a moment later.
        onScreen.value = true
        wait(50)
        status.value = online
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE), said)
    }

    @Test
    fun `back on screen while still blocked, the chip comes after the wait`() = runTest {
        val status = MutableStateFlow(online)
        val onScreen = MutableStateFlow(false)
        val said = chip(status, onScreen)
        status.value = blocked
        wait(60_000)
        onScreen.value = true
        wait(2_999)
        assertEquals(listOf(OfflineReason.NONE), said)
        wait(1)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.BLOCKED), said)
    }

    @Test
    fun `a block that was there before the screen stopped stays shown`() = runTest {
        val status = MutableStateFlow(blocked)
        val onScreen = MutableStateFlow(true)
        val said = chip(status, onScreen)
        wait(3_000)
        // The Settings button leaves the screen for the system's page, and the driver comes back.
        onScreen.value = false
        wait(60_000)
        onScreen.value = true
        wait(60_000)
        assertEquals(listOf(OfflineReason.BLOCKED), said)
        // Allowed on the system's page: the chip goes at once.
        status.value = online
        runCurrent()
        assertEquals(listOf(OfflineReason.BLOCKED, OfflineReason.NONE), said)
    }

    @Test
    fun `a block lifted while the screen is stopped and put back is left out`() = runTest {
        val status = MutableStateFlow(blocked)
        val onScreen = MutableStateFlow(true)
        val said = chip(status, onScreen)
        wait(3_000)
        onScreen.value = false
        status.value = online
        runCurrent()
        status.value = blocked
        wait(60_000)
        assertEquals(listOf(OfflineReason.BLOCKED, OfflineReason.NONE), said)
    }

    @Test
    fun `a lost network counts while the screen is stopped, so the chip is up on return`() = runTest {
        val status = MutableStateFlow(online)
        val onScreen = MutableStateFlow(true)
        val said = chip(status, onScreen)
        onScreen.value = false
        status.value = noNetwork
        wait(3_000)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_NETWORK), said)
        onScreen.value = true
        wait(60_000)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_NETWORK), said)
    }

    @Test
    fun `while the screen is stopped a blocked network is still seen for what else it is`() = runTest {
        val status = MutableStateFlow(online)
        val onScreen = MutableStateFlow(false)
        val said = chip(status, onScreen)
        // Blocked, and without internet as well: the second still counts.
        status.value = blocked.copy(validated = false)
        wait(3_000)
        assertEquals(listOf(OfflineReason.NONE, OfflineReason.NO_INTERNET), said)
    }

    @Test
    fun `every reason has its sentence, and online has none`() {
        assertNull(offlineSentenceRes(OfflineReason.NONE))
        assertEquals(R.string.offline_no_network, offlineSentenceRes(OfflineReason.NO_NETWORK))
        assertEquals(R.string.offline_no_internet, offlineSentenceRes(OfflineReason.NO_INTERNET))
        assertEquals(R.string.offline_blocked, offlineSentenceRes(OfflineReason.BLOCKED))
        val sentences = OfflineReason.entries.mapNotNull(::offlineSentenceRes)
        assertEquals(OfflineReason.entries.size - 1, sentences.toSet().size)
    }

    @Test
    fun `the sentences and the short word are the ones in the drive notes`() {
        val strings = strings()
        assertEquals("No internet. The map shows only areas saved on the phone; guidance continues.", strings["offline_no_network"])
        assertEquals("Connected, but no internet. The map shows only saved areas.", strings["offline_no_internet"])
        assertEquals("Android is not letting Custom Maps use this network.", strings["offline_blocked"])
        assertEquals("Offline", strings["offline_short"])
        assertEquals("Settings", strings["offline_settings"])
    }

    @Test
    fun `the debug line says whether the chip is up, why, and what the screen was showing`() {
        assertEquals(
            mapOf("shown" to true, "reason" to "NO_NETWORK", "guidance" to "ACTIVE"),
            offlineChipLine(OfflineReason.NO_NETWORK, GuidanceState.ACTIVE),
        )
        assertEquals(
            mapOf("shown" to true, "reason" to "BLOCKED", "guidance" to "IDLE"),
            offlineChipLine(OfflineReason.BLOCKED, GuidanceState.IDLE),
        )
        assertEquals(
            mapOf("shown" to false, "reason" to "NONE", "guidance" to "PREVIEW"),
            offlineChipLine(OfflineReason.NONE, GuidanceState.PREVIEW),
        )
    }

    @Test
    fun `the settings button opens the app's data page, or else its details page`() {
        val data = "android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS"
        val details = "android.settings.APPLICATION_DETAILS_SETTINGS"
        assertEquals(listOf(data, details), DATA_SETTINGS_ACTIONS)
        // A phone with both opens the data page and stops there.
        val tried = mutableListOf<String>()
        assertEquals(data, openFirst(DATA_SETTINGS_ACTIONS) { tried.add(it) })
        assertEquals(listOf(data), tried)
        // Without the data page the details page opens.
        assertEquals(details, openFirst(DATA_SETTINGS_ACTIONS) { it == details })
    }

    @Test
    fun `a phone with neither page opens nothing and does not fail`() {
        val tried = mutableListOf<String>()
        assertNull(
            openFirst(DATA_SETTINGS_ACTIONS) {
                tried.add(it)
                false
            },
        )
        assertEquals(DATA_SETTINGS_ACTIONS, tried)
    }

    /** The app's English strings by name. Unit tests run with the module directory as the working directory. */
    private fun strings(): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).documentElement
        return (0 until root.childNodes.length).map { root.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "string" }
            .associate { it.getAttribute("name") to it.textContent }
    }
}
