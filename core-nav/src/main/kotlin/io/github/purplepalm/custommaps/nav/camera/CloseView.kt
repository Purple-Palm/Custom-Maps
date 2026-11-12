package io.github.purplepalm.custommaps.nav.camera

/**
 * Whether the guidance camera shows a close view: the view going down onto a junction or in for a
 * lane choice, held there, or coming back. A switch between the day and the night look waits until
 * it ends, so the basemap is never drawn anew under a close-up of a turn or of the lanes.
 *
 * Read from the schedules the cameras keep anyway ([ManeuverFocus], [LaneFocus], the frames of the
 * navigation SDK's data source), once per progress update and never per frame. The schedules run in
 * every camera mode, so the answer is right again the moment the camera follows the car.
 */
object CloseView {

    /**
     * Smooth follow: while its maneuver focus has goal 1 or still moves, with either lane zoom (the
     * tilt goes down onto the junction with both), and with Large also while its lane focus has goal
     * 1 or still moves (Large alone goes close for a lane choice, and holds it for a run-up).
     *
     * @param maneuverGoal [ManeuverFocus.goal], 0 or 1
     * @param maneuverMoving [ManeuverFocus.isAnimating] now
     * @param laneGoal [LaneFocus.goal], 0 or 1
     * @param laneMoving [LaneFocus.isAnimating] now
     * @param large the Large lane zoom is set
     */
    fun smoothFollow(maneuverGoal: Double, maneuverMoving: Boolean, laneGoal: Double, laneMoving: Boolean, large: Boolean): Boolean =
        maneuverGoal > 0.0 || maneuverMoving || (large && (laneGoal > 0.0 || laneMoving))

    /**
     * Mapbox tuned: with Large only, while its lane window is in or the navigation SDK frames a
     * maneuver at Large's zoom. With Default its frames are the SDK's own, which the look's window
     * before a maneuver covers, as for Mapbox standard, which never shows one.
     *
     * @param framesManeuver the SDK frames the next maneuver now, not the road
     * @param laneWindow the lane choices' schedule is in for one now ([LaneFocus.goal] 1)
     * @param large the Large lane zoom is set
     */
    fun mapboxTuned(framesManeuver: Boolean, laneWindow: Boolean, large: Boolean): Boolean =
        large && (framesManeuver || laneWindow)
}
