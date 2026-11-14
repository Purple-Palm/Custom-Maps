package io.github.purplepalm.custommaps.nav.model

/** What the map shows during a drive while nobody holds it: the driver's choice, kept for the next drive. */
enum class DriveView {
    /** The view behind the car, all the time: guidance as it always was. */
    NORMAL,

    /**
     * The whole rest of the route from straight above, north up. With Zoom in before turns on, it
     * comes down to the view behind the car before each turn, exit, fork and freeway lane choice, and
     * goes back out after it when the next one is far enough away; with it off, it stays out.
     */
    OVERVIEW,
}

/** The settings of the drive camera, read together. */
data class DriveCameraSettings(
    val driveView: DriveView = DriveView.NORMAL,
    /** Recenter by itself: how many seconds after the driver's last touch the map goes back; 0 is off. */
    val recenterSeconds: Int = 10,
    /** Zoom in before turns: read only in the Overview drive view; off gives the plain Overview. */
    val turnZoom: Boolean = true,
)

/** What the Overview toggle shows: it changes only when the drive view or Zoom in before turns changes. */
data class DriveViewControl(val driveView: DriveView, val turnZoom: Boolean)

/**
 * What Recenter shows while the driver holds the map; there is none while nothing is held.
 * [secondsLeft]: whole seconds until the map goes back by itself; null while Recenter by itself is off.
 */
data class RecenterControl(val secondsLeft: Int?)
