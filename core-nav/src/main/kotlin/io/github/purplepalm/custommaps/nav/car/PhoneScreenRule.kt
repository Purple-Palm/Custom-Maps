package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.model.GuidanceState

/** What the phone shows during a drive: its map, or the list of the maneuvers ahead in its place. */
enum class PhoneScreen { MAP, LIST }

/**
 * What the phone shows now, and whether its map offers the List button: during a drive, while a car's
 * surface shows the map and the driver chose the phone's map, which the button takes back.
 */
data class PhoneScreenState(val screen: PhoneScreen = PhoneScreen.MAP, val listButton: Boolean = false)

/**
 * Which screen the phone shows, the owner's request: no phone map while the car shows one during a
 * drive, the list of the maneuvers ahead instead, and the map one tap away.
 *
 * The phone shows the [PhoneScreen.LIST] in place of its map while guidance is active and a car's
 * surface shows the map (the engine's `carMapShown`), unless the driver chose the map in this drive
 * (the Map button on the list). A new drive starts with no choice, and the driver's List button on the
 * map takes the choice back. A car that goes mid-drive brings the map back; a car that comes mid-drive
 * brings the list, unless the map was chosen. Before Start, in the route preview and at the arrival
 * the phone shows its map, with a car or without.
 *
 * Each call returns the change it made, with its [Cause], for a "phone_screen" line, or null when
 * the screen stays. With no car showing the map nothing ever changes. Pure; main thread only.
 */
class PhoneScreenRule {

    /** Why the screen changed: a drive started, a car's surface came or went, a button, or the drive ended. */
    enum class Cause { DRIVE, CAR, BUTTON, END }

    /** A change of the screen, and why. */
    data class Change(val screen: PhoneScreen, val cause: Cause)

    /** What the phone shows now. */
    var screen: PhoneScreen = PhoneScreen.MAP
        private set

    /** Whether the driver chose the map in this drive: the Map button on the list, until the List button or the drive's end. */
    var mapChosen: Boolean = false
        private set

    /** Whether guidance is active: a drive. */
    private var driving = false

    /** Whether a car's surface shows the map. */
    private var carShown = false

    /** What the phone shows now, and whether its map offers the List button. */
    val state: PhoneScreenState get() = PhoneScreenState(screen, listButton = driving && carShown && mapChosen)

    /** The drive's state: a change into ACTIVE starts a drive, one out of it ends the drive. */
    fun onGuidance(state: GuidanceState): Change? {
        val active = state == GuidanceState.ACTIVE
        if (active == driving) return null
        driving = active
        // A new drive starts with no choice, and one that ended leaves none.
        mapChosen = false
        return settle(if (active) Cause.DRIVE else Cause.END)
    }

    /** Whether a car's surface shows the map now. */
    fun onCarMapShown(shown: Boolean): Change? {
        carShown = shown
        return settle(Cause.CAR)
    }

    /** The Map button on the list: the phone's map for the rest of this drive. Outside a drive, nothing. */
    fun onMapButton(): Change? {
        if (!driving) return null
        mapChosen = true
        return settle(Cause.BUTTON)
    }

    /** The List button on the phone's map: the choice taken back. Outside a drive there is none to take. */
    fun onListButton(): Change? {
        mapChosen = false
        return settle(Cause.BUTTON)
    }

    private fun settle(cause: Cause): Change? {
        val next = if (driving && carShown && !mapChosen) PhoneScreen.LIST else PhoneScreen.MAP
        if (next == screen) return null
        screen = next
        return Change(next, cause)
    }

    companion object {
        /** The debug log line at each change of the phone's screen. */
        const val LINE = "phone_screen"

        /** The line's fields: the screen and the cause, in lower case, and nothing else. */
        fun fields(change: Change): Map<String, Any?> = mapOf(
            "screen" to change.screen.name.lowercase(),
            "cause" to change.cause.name.lowercase(),
        )
    }
}
