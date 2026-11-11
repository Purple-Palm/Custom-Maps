package io.github.purplepalm.custommaps.nav.guidance

/** What the maneuver card does with one progress update of a drive; [logName] is how the debug log names it. */
enum class CardUpdate(val logName: String) {
    /** The update brings the next maneuver: the card shows it. */
    FRESH("fresh"),

    /** The update has none, as happens for a second now and then, and the card is of its route: it stays as it is. */
    KEEP("kept"),

    /**
     * The update is of a route the card is not of yet and has no maneuver for it, as in the first
     * second or so after a reroute or after Start: the card must not keep a maneuver of the route
     * before. It shows the new route's next maneuver as the route has it, dimmed and without a
     * distance, until the first update that brings it.
     */
    PENDING("pending"),
}

/**
 * Keeps the maneuver card true to the route on the map. The navigator can send the first updates of
 * a new route before it has an instruction for it; a card that kept the last one would then show a
 * maneuver of a route no longer driven, next to the new route's line and arrow.
 *
 * It remembers which route the card on screen is of, from one update of a drive to the next, and
 * forgets it when there is no card any more ([reset]). Main thread only, like the updates.
 */
class CardContinuity {

    /** The route of the card on screen; null before the first card of a drive. */
    private var shownRouteId: String? = null

    /**
     * What the card does with an update of the route [progressRouteId]; the card is of that route
     * from then on, whatever it does.
     *
     * @param hasManeuver whether this update brings the next maneuver
     */
    fun next(progressRouteId: String, hasManeuver: Boolean): CardUpdate {
        val update = when {
            hasManeuver -> CardUpdate.FRESH
            shownRouteId == progressRouteId -> CardUpdate.KEEP
            else -> CardUpdate.PENDING
        }
        shownRouteId = progressRouteId
        return update
    }

    /** No card any more, as at the end of a drive: the next update finds none to keep. */
    fun reset() {
        shownRouteId = null
    }
}
