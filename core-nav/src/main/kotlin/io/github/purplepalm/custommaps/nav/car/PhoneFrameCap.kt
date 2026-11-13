package io.github.purplepalm.custommaps.nav.car

/**
 * The car's surfaces that show the map now, counted from the car maps' attach and detach: one count
 * for the process, kept by the engine. A car map counts its surface once as it comes and once as it
 * goes; while one session's map is let go of and the next one's surface comes, two may show the map
 * at once. A surface that goes without having come counts for nothing. Main thread only.
 */
class CarSurfaces {

    /** How many car surfaces show the map now. */
    var count = 0
        private set

    /** A car's surface came ([shown]) or went. Returns whether any car surface shows the map now. */
    fun onCarSurface(shown: Boolean): Boolean {
        count = if (shown) count + 1 else (count - 1).coerceAtLeast(0)
        return count > 0
    }
}

/**
 * The frame rate cap of one phone map while a car's surface shows the map too: two maps at once, the
 * phone's at its display's full rate, make the phone hot quickly, so the phone's map then draws at
 * most [CAPPED_FPS] frames a second, and as before once no car surface shows the map ([UNCAPPED_FPS]).
 * Nothing is set on a map before a car's surface shows the map, so the phone's map with no car draws
 * as it always has; a map made while one does is capped at once. Main thread only.
 */
class PhoneFrameCap {

    /** Whether the cap is set on the map now. */
    var capped = false
        private set

    /**
     * The frame rate to set on the map now that a car's surface shows the map ([carShown]) or none
     * does: [CAPPED_FPS], [UNCAPPED_FPS], or null when what is set is right already.
     */
    fun capFor(carShown: Boolean): Int? = when {
        carShown == capped -> null
        carShown -> CAPPED_FPS
        else -> UNCAPPED_FPS
    }

    /** The frame rate [capFor] gave for [carShown] is set on the map. */
    fun onSet(carShown: Boolean) {
        capped = carShown
    }

    companion object {
        /** The most frames a second the phone's map draws while a car's surface shows the map too. */
        const val CAPPED_FPS = 60

        /**
         * A cap above any display's rate, which draws at the display's own rate as with no cap: the
         * map library's way back to none is for its own libraries only.
         */
        const val UNCAPPED_FPS = Int.MAX_VALUE
    }
}
