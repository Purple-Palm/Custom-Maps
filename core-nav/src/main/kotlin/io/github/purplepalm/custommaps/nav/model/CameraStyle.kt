package io.github.purplepalm.custommaps.nav.model

/** How the camera moves during guidance and in the route preview; the driver chooses one in Settings. */
enum class CameraStyle {
    /** The navigation SDK's camera as it comes, kept for comparison. */
    MAPBOX_STANDARD,

    /** The same camera with calmer settings: one update for each update of the navigator, and longer moves. */
    MAPBOX_TUNED,

    /** The app's own camera: it moves the map on every display frame and glides after the drawn car. */
    SMOOTH_FOLLOW,
    ;

    companion object {
        /** The style in use until the driver chooses another. */
        val DEFAULT = SMOOTH_FOLLOW
    }
}
