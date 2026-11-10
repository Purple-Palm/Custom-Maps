package io.github.purplepalm.custommaps.nav.camera

/**
 * The top inset of the map: how far down from the top the camera keeps the route and the car clear.
 *
 * During guidance the maneuver panel changes its height as it goes, by a row when lanes appear and
 * by a line when an instruction wraps, and every change of the inset makes the camera frame the
 * road again. So during guidance the inset only grows: it follows the panel down and stays there
 * when the panel gets shorter. Outside guidance it starts over.
 *
 * It is measured from the maneuver panel alone. What else the top of the screen shows, the search
 * bar, its suggestions or any list, is no input here, so none of it moves the camera's frame.
 *
 * All values are pixels from the top of the map. One per map; not safe for more than one thread.
 */
class GuidancePadding {

    /** The lowest the panel has reached in this guidance. */
    private var grown = 0

    /**
     * The inset for what the screen shows now. Call it whenever one of the inputs changes.
     *
     * @param guiding whether guidance is running
     * @param panelBottom the bottom edge of the maneuver panel; 0 while it is not shown
     * @param restTop the inset without a panel: what the system bars and the screen's margin take
     * @param band a strip of constant height under the panel, kept free during the whole of guidance
     *   for what slides in and out there, so that it never moves the frame either
     */
    fun topInset(guiding: Boolean, panelBottom: Int, restTop: Int, band: Int = 0): Int {
        if (!guiding) {
            grown = 0
            return restTop
        }
        grown = maxOf(grown, panelBottom, restTop)
        return grown + band
    }
}
