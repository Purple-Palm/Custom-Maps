package io.github.purplepalm.custommaps.nav.model

import kotlin.time.Duration

/** Guidance state at one moment, as the UI and the camera logic need it. */
data class RouteProgressSnapshot(
    val nextManeuver: Maneuver,
    val distanceToNextManeuver: Distance,
    val lanes: LaneInfo?,
    val distanceRemaining: Distance,
    val durationRemaining: Duration,
)
