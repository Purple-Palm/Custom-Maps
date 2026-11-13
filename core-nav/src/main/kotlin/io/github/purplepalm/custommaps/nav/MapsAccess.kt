package io.github.purplepalm.custommaps.nav

/**
 * Whether maps can be shown: only once a valid token has been handed to the SDKs. The phone's screen
 * and the car's both wait for it.
 */
sealed interface MapsAccess {
    data object Checking : MapsAccess

    data object NeedsToken : MapsAccess

    /** [generation] grows every time a different token is applied. */
    data class Ready(val generation: Int) : MapsAccess
}
