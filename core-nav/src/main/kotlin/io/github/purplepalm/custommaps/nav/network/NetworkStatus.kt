package io.github.purplepalm.custommaps.nav.network

/**
 * This app's default network as Android reports it to this app, not to the phone as a whole: the
 * network the map, the router and search use.
 */
data class NetworkStatus(
    /** "wifi", "cellular", "vpn", "ethernet", "bluetooth", "other", [NONE] or [UNKNOWN]. */
    val transport: String,
    /** Android reached the internet through it; false behind a captive portal or with no service. */
    val validated: Boolean,
    /** Android keeps this app off it: Data Saver, a background data or battery limit, a firewall rule. */
    val blocked: Boolean,
    val metered: Boolean,
    val roaming: Boolean,
    /** Up but passing no data for now, as in a tunnel or during some calls. */
    val suspended: Boolean,
    /**
     * Every network with internet the app can see, default or not, by transport, sorted. Tells "the
     * phone has no mobile data" apart from "mobile data exists but is not this app's network".
     */
    val available: List<String> = emptyList(),
) {
    companion object {
        /** No default network at all. */
        const val NONE = "none"

        /** Android has not answered yet. */
        const val UNKNOWN = "unknown"

        /** Before Android has answered: not counted as offline. */
        val NOT_REPORTED = NetworkStatus(UNKNOWN, validated = false, blocked = false, metered = false, roaming = false, suspended = false)

        /** No default network; [available] are the networks the app can see all the same. */
        fun noNetwork(available: List<String> = emptyList()) =
            NetworkStatus(NONE, validated = false, blocked = false, metered = false, roaming = false, suspended = false, available = available)
    }
}

/** Why this app cannot reach the internet, or [NONE] when it can. */
enum class OfflineReason {
    /** Online, or Android has not said yet. */
    NONE,

    /** No default network at all: no Wi-Fi, no mobile data for this app. */
    NO_NETWORK,

    /** A network that does not reach the internet now: not validated, or suspended. */
    NO_INTERNET,

    /** A network Android does not let this app use. */
    BLOCKED,
}

fun NetworkStatus.offlineReason(): OfflineReason = when {
    transport == NetworkStatus.UNKNOWN -> OfflineReason.NONE
    transport == NetworkStatus.NONE -> OfflineReason.NO_NETWORK
    // A validated network is still of no use to an app that Android keeps off it.
    blocked -> OfflineReason.BLOCKED
    !validated || suspended -> OfflineReason.NO_INTERNET
    else -> OfflineReason.NONE
}
