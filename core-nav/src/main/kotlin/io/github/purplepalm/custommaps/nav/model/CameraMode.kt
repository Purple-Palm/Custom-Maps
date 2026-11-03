package io.github.purplepalm.custommaps.nav.model

enum class CameraMode {
    /** Shows the whole remaining route and its alternatives. */
    OVERVIEW,

    /** Close view behind the vehicle, used when a maneuver is near. */
    FOLLOWING,

    /** The driver moved the map; automatic camera changes are paused. */
    IDLE,
}
