package io.github.purplepalm.custommaps.nav.camera

/**
 * How Smooth follow shows the junction it looks down onto ([ManeuverFocus]): from above, flat, the junction framed by
 * the navigation SDK through its framing override, or tilted, at the car's one tilt with the car on its row
 * ([CameraTuning.anchored], [ZoomPlanner.anchoredTarget]). The car's Settings > Car screen > Turns from above chooses for
 * the car's camera; the phone's junction views are from above whatever it says. The camera line names it by [logName].
 */
enum class JunctionView(val logName: String) {
    /** Straight down onto the junction, framed by the navigation SDK, the car free to leave its row in that frame. */
    ABOVE("above"),

    /** At the car's one tilt, anchored on the car: its own fit of the junction, never farther out than the road view. */
    TILTED("tilted"),
    ;

    companion object {
        /**
         * The junction view of a camera anchored on the car or not ([CameraTuning.anchored]) with Turns from above on or
         * off: tilted only on the car with it off.
         */
        fun of(anchored: Boolean, turnsFromAbove: Boolean): JunctionView = if (anchored && !turnsFromAbove) TILTED else ABOVE
    }
}
