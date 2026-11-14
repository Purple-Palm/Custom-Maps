package io.github.purplepalm.custommaps.nav.camera

/**
 * The maneuver focus fed as the camera fed it before it had the facts of each step: from the
 * banner's maneuver type alone, a type not in [excludedTypes] a plain junction, framed at the
 * navigation SDK's own distance (no screen cap). The older camera tests keep their numbers through it.
 */
internal fun ManeuverFocus.onBanner(
    now: Double,
    stepKey: String,
    metersToManeuver: Double,
    metersOnStep: Double,
    maneuverType: String?,
    excludedTypes: Collection<String>,
    speedMps: Double?,
) {
    val kind = if (maneuverType != null && maneuverType !in excludedTypes) FocusKind.TURN else FocusKind.NONE
    onProgress(now, stepKey, metersToManeuver, metersOnStep, ManeuverFacts(stepKey, kind), Double.NaN, speedMps)
}
