package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.ui.maps.camera.data.FollowingFrameOptions
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.model.CameraStyle
import io.github.purplepalm.custommaps.nav.model.LaneZoom
import kotlin.math.max
import kotlin.math.min

/**
 * What the Lane zoom setting changes on the viewport data source, for each camera style. The
 * navigation SDK still fits every following frame. With the Large lane zoom it may fit the frame
 * of a maneuver closer than its own limit allows, and that frame holds less road after the
 * maneuver, so the junction fills it.
 *
 * - Smooth follow: the limit of the frames is raised for good. The camera keeps the open road at
 *   the zoom it always had and goes closer only toward a maneuver and near a lane choice.
 * - Mapbox tuned has no camera of its own that could hold the road back, so its limits move with
 *   the drive, before each frame is computed ([beforeTunedEvaluate]).
 * - Mapbox standard keeps the SDK's values whatever the setting says.
 *
 * The map calls [apply] when the lane zoom or the camera style changes. What a camera changes while
 * it runs it takes back with [resetLimits] when it ends. Main thread only.
 */
internal object LaneZoomFrames {

    // The navigation SDK's own values, from FollowingFrameOptions.
    private const val SDK_MAX_ZOOM = 16.35
    private const val SDK_MIN_ZOOM = 10.5
    private const val SDK_METERS_AFTER_MANEUVER = 100.0

    /** How far the SDK lets the view turn from the car's heading toward the road ahead, in degrees. */
    const val SDK_BEARING_LEAD = 45.0

    /**
     * Sets up the following frames of [style] for [mode]. The caller computes a new frame after it.
     *
     * @param route the route the data source has, if any
     * @param progress the latest progress of the drive, if any
     */
    fun apply(
        dataSource: MapboxNavigationViewportDataSource,
        style: CameraStyle,
        mode: LaneZoom,
        tuning: CameraTuning,
        route: NavigationRoute?,
        progress: RouteProgress?,
    ) {
        when (style) {
            CameraStyle.SMOOTH_FOLLOW -> applySmoothFollow(dataSource, mode, tuning, route, progress)
            CameraStyle.MAPBOX_TUNED -> applyTuned(dataSource, mode, tuning, route, progress)
            CameraStyle.MAPBOX_STANDARD -> applyStandard(dataSource, route, progress)
        }
    }

    /**
     * Smooth follow: with Large the frames may be fitted as close as [CameraTuning.maxZoom], and
     * hold [CameraTuning.largeMetersAfterManeuver] of road after a maneuver.
     */
    fun applySmoothFollow(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        route: NavigationRoute?,
        progress: RouteProgress?,
    ) {
        val large = mode == LaneZoom.LARGE
        val options = dataSource.options.followingFrameOptions
        options.maxZoom = if (large) tuning.maxZoom else tuning.defaultMaxZoom
        options.minZoom = SDK_MIN_ZOOM
        options.bearingSmoothing.maxBearingAngleDiff = SDK_BEARING_LEAD
        setMetersAfterManeuver(dataSource, metersAfterManeuver(large, tuning), route, progress)
    }

    /**
     * Mapbox tuned: the limits start at the Default values and are moved before each frame of the
     * drive; the road kept after a maneuver is set here, as for Smooth follow.
     */
    fun applyTuned(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        route: NavigationRoute?,
        progress: RouteProgress?,
    ) {
        resetLimits(dataSource, tuning)
        setMetersAfterManeuver(dataSource, metersAfterManeuver(mode == LaneZoom.LARGE, tuning), route, progress)
    }

    /** Mapbox standard: the SDK's own values, whatever the lane zoom is. */
    fun applyStandard(dataSource: MapboxNavigationViewportDataSource, route: NavigationRoute?, progress: RouteProgress?) {
        val options = dataSource.options.followingFrameOptions
        options.maxZoom = SDK_MAX_ZOOM
        options.minZoom = SDK_MIN_ZOOM
        options.bearingSmoothing.maxBearingAngleDiff = SDK_BEARING_LEAD
        setMetersAfterManeuver(dataSource, SDK_METERS_AFTER_MANEUVER, route, progress)
    }

    /**
     * Mapbox tuned, before the frame of each progress update is computed. With Large:
     * - while the SDK frames a maneuver, the frame may be fitted until a lane is
     *   [CameraTuning.junctionLaneDp] wide; elsewhere the limit is the Default one;
     * - near a lane choice the frame is no farther out than a lane of
     *   [CameraTuning.freewayLaneDp], and the view turns less toward the road ahead.
     * The camera's own animations turn these steps into glides. With Default the limits are the
     * Default ones on every update.
     *
     * @param laneWindow the view is close for a lane choice now
     * @param latitude where the car is; a lane's width on the screen depends on it
     */
    fun beforeTunedEvaluate(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        progress: RouteProgress,
        laneWindow: Boolean,
        latitude: Double,
    ) {
        if (mode != LaneZoom.LARGE) {
            resetLimits(dataSource, tuning)
            return
        }
        val options = dataSource.options.followingFrameOptions
        val minZoom = if (laneWindow) max(SDK_MIN_ZOOM, closest(tuning.freewayLaneDp, latitude, tuning)) else SDK_MIN_ZOOM
        val maxZoom = if (framesManeuver(progress, options)) {
            max(tuning.defaultMaxZoom, closest(tuning.junctionLaneDp, latitude, tuning))
        } else {
            tuning.defaultMaxZoom
        }
        options.minZoom = minZoom
        // The SDK takes the lower limit last, so the upper one may not be under it.
        options.maxZoom = max(maxZoom, minZoom)
        options.bearingSmoothing.maxBearingAngleDiff = if (laneWindow) tuning.laneMaxBearingLead else SDK_BEARING_LEAD
    }

    /**
     * The limits a camera moves during a drive, back at the values the drive starts with. The road
     * kept after a maneuver is not among them: it belongs to the style and the lane zoom, and
     * [apply] sets it.
     */
    fun resetLimits(dataSource: MapboxNavigationViewportDataSource, tuning: CameraTuning) {
        val options = dataSource.options.followingFrameOptions
        options.maxZoom = tuning.defaultMaxZoom
        options.minZoom = SDK_MIN_ZOOM
        options.bearingSmoothing.maxBearingAngleDiff = SDK_BEARING_LEAD
    }

    private fun metersAfterManeuver(large: Boolean, tuning: CameraTuning): Double =
        if (large) tuning.largeMetersAfterManeuver else SDK_METERS_AFTER_MANEUVER

    /**
     * The data source works out the road to show after each maneuver when it is given a route, and
     * it ignores a route it already has. So after a change the route is taken out and given again,
     * and the progress with it. All of that happens within this call, on the main thread: no frame
     * is computed in between, so none shows a data source without its route.
     */
    private fun setMetersAfterManeuver(
        dataSource: MapboxNavigationViewportDataSource,
        meters: Double,
        route: NavigationRoute?,
        progress: RouteProgress?,
    ) {
        val after = dataSource.options.followingFrameOptions.frameGeometryAfterManeuver
        if (after.distanceToFrameAfterManeuver == meters) return
        after.distanceToFrameAfterManeuver = meters
        if (route == null) return
        dataSource.clearRouteData()
        dataSource.onRouteChanged(route)
        // A progress of the route before a reroute is of no use to the new one.
        if (progress != null && progress.navigationRoute.id == route.id) dataSource.onRouteProgressChanged(progress)
    }

    /** Whether the SDK frames the next maneuver at this [progress]: its own rule, with its own options. */
    private fun framesManeuver(progress: RouteProgress, options: FollowingFrameOptions): Boolean {
        val step = progress.currentLegProgress?.currentStepProgress ?: return false
        val type = progress.bannerInstructions?.primary()?.type() ?: return false
        val near = options.pitchNearManeuvers
        return near.enabled && type !in near.excludedManeuvers && step.distanceRemaining <= near.triggerDistanceFromManeuver
    }

    /**
     * The zoom at which a lane is [laneDp] wide at [latitude], no closer than Large ever goes.
     * Where the latitude is not a number no zoom is close enough to ask for.
     */
    private fun closest(laneDp: Double, latitude: Double, tuning: CameraTuning): Double {
        val zoom = MapScale.zoomForLaneDp(laneDp, latitude)
        return if (zoom.isNaN()) Double.NEGATIVE_INFINITY else min(zoom, tuning.maxZoom)
    }
}
