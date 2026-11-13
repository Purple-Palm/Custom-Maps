package io.github.purplepalm.custommaps.nav.lanes

/**
 * Who draws the lanes on the map, as Settings' Lanes on the map says (the own lanes' design 7): nobody ([OFF]), Mapbox
 * ([MAPBOX], its lane details, as the old switch Lane details on the map drew them, the route line thin close in), or the
 * app ([APP]: during guidance its own drawing of the freeway lanes, its own route line in the lane the plan names, and
 * over the drawn lanes in a close view the car as a bar across the road). The card's lane row, the run-up and its close
 * view are the same with every choice: they read the lane plan whatever is drawn.
 */
enum class LanesOnMap {
    OFF,
    MAPBOX,
    APP,
    ;

    /** Whether Mapbox's lane details are on the map: with [MAPBOX], and with [APP] only where they are kept under the app's drawing ([DETAILS_UNDER_DRAWING]). */
    val mapboxDetails: Boolean get() = this == MAPBOX || (this == APP && DETAILS_UNDER_DRAWING)

    /** Whether the app draws the lanes: its drawing, its own route line and the car's bar. */
    val appDraws: Boolean get() = this == APP

    /**
     * What the stored settings of a version before the choice still ask of the file, one write at a time: first the
     * choice is written while the old keys stay, then the old keys go. A process that dies between the two leaves both,
     * and the next read finds the choice written and only takes the old keys away, so nothing is lost or written twice.
     */
    enum class Migration {
        /** Nothing to do: no choice to carry over, and no old key next to a choice. */
        NONE,

        /** The old switch's choice is written ([fromLaneDetails]) while the old keys stay. */
        WRITE_CHOICE,

        /** A choice is stored: the old keys go, and the choice stays whatever they said. */
        REMOVE_OLD_KEYS,
        ;

        companion object {
            /**
             * The next step for the stored [choice] (its word, null when there is none), the old switch's [laneDetails]
             * and the old Debug switch [drawOwnLanes] (each null when the file has none). The Debug switch is never
             * carried over: it goes with the old switch, or with the next choice made.
             */
            fun of(choice: String?, laneDetails: Boolean?, drawOwnLanes: Boolean?): Migration = when {
                choice == null && laneDetails != null -> WRITE_CHOICE
                choice != null && (laneDetails != null || drawOwnLanes != null) -> REMOVE_OLD_KEYS
                else -> NONE
            }
        }
    }

    companion object {
        /**
         * What a phone that never chose shows, also once the choice is offered: an absent choice reads as this and is
         * never written, so a later default reaches every phone that never chose.
         */
        val DEFAULT = OFF

        /**
         * Whether Mapbox's lane details stay on under the app's drawing with [APP]. Off until a drive with the measuring
         * switch has shown that they draw below the app's asphalt where it draws and show where it does not (the own lanes'
         * design 7, D12, T6): two lane truths side by side would disagree by up to a lane.
         */
        const val DETAILS_UNDER_DRAWING = false

        /** The choice the old switch Lane details on the map stands for: on is [MAPBOX], off is [OFF]. */
        fun fromLaneDetails(on: Boolean): LanesOnMap = if (on) MAPBOX else OFF
    }
}
