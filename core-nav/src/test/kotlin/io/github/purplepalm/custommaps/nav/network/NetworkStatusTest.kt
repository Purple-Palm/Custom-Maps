package io.github.purplepalm.custommaps.nav.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NetworkStatusTest {

    private val wifi = NetworkStatus("wifi", validated = true, blocked = false, metered = false, roaming = false, suspended = false)

    @Test
    fun `a validated network that is not blocked is online`() {
        assertEquals(OfflineReason.NONE, wifi.offlineReason())
        assertEquals(OfflineReason.NONE, wifi.copy(transport = "cellular", metered = true, roaming = true).offlineReason())
    }

    @Test
    fun `no default network is no network, even with other networks in sight`() {
        assertEquals(OfflineReason.NO_NETWORK, NetworkStatus.noNetwork().offlineReason())
        assertEquals(OfflineReason.NO_NETWORK, NetworkStatus.noNetwork(available = listOf("cellular")).offlineReason())
    }

    @Test
    fun `a network that is not validated or is suspended has no internet`() {
        assertEquals(OfflineReason.NO_INTERNET, wifi.copy(validated = false).offlineReason())
        assertEquals(OfflineReason.NO_INTERNET, wifi.copy(transport = "cellular", suspended = true).offlineReason())
    }

    @Test
    fun `blocked wins over validated`() {
        assertEquals(OfflineReason.BLOCKED, wifi.copy(blocked = true).offlineReason())
        assertEquals(OfflineReason.BLOCKED, wifi.copy(blocked = true, validated = false).offlineReason())
    }

    @Test
    fun `a VPN default network with validation counts as online`() {
        assertEquals(OfflineReason.NONE, wifi.copy(transport = "vpn").offlineReason())
        assertEquals(OfflineReason.NO_INTERNET, wifi.copy(transport = "vpn", validated = false).offlineReason())
    }

    @Test
    fun `before Android has answered the app is not called offline`() {
        assertEquals(OfflineReason.NONE, NetworkStatus.NOT_REPORTED.offlineReason())
    }
}
