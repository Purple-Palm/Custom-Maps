package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.ui.maps.camera.data.FollowingFrameOptions
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.LookAhead
import io.github.purplepalm.custommaps.nav.camera.MapScale
import io.github.purplepalm.custommaps.nav.camera.TurnAhead
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
 * - Smooth follow: the upper limit is raised only while the SDK frames a maneuver, before each
 *   frame of the drive is computed ([beforeSmoothEvaluate]). A frame of the road is fitted from
 *   the Default limit, as it always was ([smoothMaxZoom] says why). Near a lane choice the camera
 *   goes closer by itself.
 * - Mapbox tuned has no camera of its own that could hold the road back, so all its limits move
 *   with the drive, before each frame is computed ([beforeTunedEvaluate]).
 * - In both, while the SDK frames a maneuver, the frame also holds the road to where it bends past
 *   the maneuver point ([JunctionPoints]), and no point of it at any other time.
 * - Mapbox standard keeps the SDK's values whatever the setting says.
 *
 * The map calls [apply] when the lane zoom or the camera style changes. What a camera changes while
 * it runs it takes back with [resetLimits] when it ends. Main thread only.
 */
internal object LaneZoomFrames {

    // The navigation SDK's own values, from FollowingFrameOptions.
    private const val SDK_MAX_ZOOM = 16.35
    const val SDK_MIN_ZOOM = 10.5
    private const val SDK_METERS_AFTER_MANEUVER = 100.0

    /** How far the SDK lets the view turn from the car's heading toward the road ahead, in degrees. */
    const val SDK_BEARING_LEAD = 45.0

    /**
     * Sets up the following frames of [style] for [mode]. The caller computes a new frame after it.
     *
     * @param route the route the data source has, if any
     * @param progress the latest progress of the drive, if any
     * @param junction the road past the maneuver point that the data source frames besides its own
     */
    fun apply(
        dataSource: MapboxNavigationViewportDataSource,
        style: CameraStyle,
        mode: LaneZoom,
        tuning: CameraTuning,
        route: NavigationRoute?,
        progress: RouteProgress?,
        junction: JunctionPoints,
    ) {
        when (style) {
            CameraStyle.SMOOTH_FOLLOW -> applySmoothFollow(dataSource, mode, tuning, route, progress, junction)
            CameraStyle.MAPBOX_TUNED -> applyTuned(dataSource, mode, tuning, route, progress, junction)
            CameraStyle.MAPBOX_STANDARD -> applyStandard(dataSource, route, progress, junction)
        }
    }

    /**
     * Smooth follow: with Large the frame of a maneuver holds
     * [CameraTuning.largeMetersAfterManeuver] of road after it. The upper limit is the one of
     * [smoothMaxZoom] for the progress the data source frames by after this call, and the camera
     * moves it with every progress that follows ([beforeSmoothEvaluate]).
     */
    fun applySmoothFollow(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        route: NavigationRoute?,
        progress: RouteProgress?,
        junction: JunctionPoints,
    ) {
        val large = mode == LaneZoom.LARGE
        val options = dataSource.options.followingFrameOptions
        val framed = progressOf(route, progress)
        val maneuver = large && framesManeuver(framed, options)
        options.maxZoom = smoothMaxZoom(mode, tuning, framesManeuver = maneuver)
        options.minZoom = SDK_MIN_ZOOM
        options.bearingSmoothing.maxBearingAngleDiff = SDK_BEARING_LEAD
        setMetersAfterManeuver(dataSource, metersAfterManeuver(large, tuning), route, progress)
        junction.update(framed, mode, maneuver, coalesceMeters(options), tuning)
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
        junction: JunctionPoints,
    ) {
        resetLimits(dataSource, tuning)
        val large = mode == LaneZoom.LARGE
        setMetersAfterManeuver(dataSource, metersAfterManeuver(large, tuning), route, progress)
        val options = dataSource.options.followingFrameOptions
        val framed = progressOf(route, progress)
        junction.update(framed, mode, large && framesManeuver(framed, options), coalesceMeters(options), tuning)
    }

    /** Mapbox standard: the SDK's own values, whatever the lane zoom is. */
    fun applyStandard(
        dataSource: MapboxNavigationViewportDataSource,
        route: NavigationRoute?,
        progress: RouteProgress?,
        junction: JunctionPoints,
    ) {
        val options = dataSource.options.followingFrameOptions
        options.maxZoom = SDK_MAX_ZOOM
        options.minZoom = SDK_MIN_ZOOM
        options.bearingSmoothing.maxBearingAngleDiff = SDK_BEARING_LEAD
        setMetersAfterManeuver(dataSource, SDK_METERS_AFTER_MANEUVER, route, progress)
        junction.clear()
    }

    /**
     * Smooth follow, before the frame of each progress update is computed and after the data
     * source was given that [progress]: the upper limit is the one of [smoothMaxZoom], and while
     * the SDK frames a maneuver with Large, the frame holds the road to where it bends past the
     * maneuver point ([junction]). Nothing else is set here. How far the view turns toward the road
     * ahead the camera sets itself, and the lower limit stays the SDK's.
     */
    fun beforeSmoothEvaluate(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        progress: RouteProgress,
        junction: JunctionPoints,
    ) {
        val options = dataSource.options.followingFrameOptions
        // With Default nothing of the drive is asked: the limit is the same on every update,
        // nothing is written, and the junction frame gets none of the bend's points.
        val maneuver = mode == LaneZoom.LARGE && framesManeuver(progress, options)
        val maxZoom = smoothMaxZoom(mode, tuning, framesManeuver = maneuver)
        if (options.maxZoom != maxZoom) options.maxZoom = maxZoom
        junction.update(progress, mode, maneuver, coalesceMeters(options), tuning)
    }

    /**
     * The upper limit of one following frame of Smooth follow: [CameraTuning.maxZoom] while Large
     * has the SDK frame a maneuver, so that the junction can fill that frame, and
     * [CameraTuning.defaultMaxZoom] for every other frame.
     *
     * A frame of the road must not get the raised limit. The SDK fits such a frame with a search
     * that starts at this limit, and where the search ends depends on where it starts. At zoom 20
     * the tilted camera stands only some 80 to 115 m behind the car. A corner of the box around
     * the framed road that lies farther back, as on an S-shaped stretch, then has no place on
     * the screen. The search takes it for an outermost point and runs out until the SDK stops it
     * at its lowest zoom ([SDK_MIN_ZOOM]), a map some 20 km wide. Started at the Default limit
     * the search passes over the same corner and ends at the zoom the road needs. This is read
     * from the native map library (11.34.1) and from two models of its fit, in which about 1 in
     * 100 curved road frames went wrong. It was not seen on a device. The frame of a maneuver is
     * fitted by another call, which is given no zoom to start from, and the limit only caps its
     * result.
     *
     * @param framesManeuver the SDK frames the next maneuver now, not the road
     */
    fun smoothMaxZoom(mode: LaneZoom, tuning: CameraTuning, framesManeuver: Boolean): Double =
        if (mode == LaneZoom.LARGE && framesManeuver) tuning.maxZoom else tuning.defaultMaxZoom

    /**
     * Mapbox tuned, before the frame of each progress update is computed: the data source gets
     * the limits of [tunedLimits]. The camera's own animations turn these steps into glides.
     *
     * @param laneWindow the lane choices' schedule is in for one now; it moves the limits only with Large
     * @param latitude where the car is; a lane's width on the screen depends on it
     * @param junction the road past the maneuver point that the data source frames besides its own
     * @param lanesCap the closest zoom the lanes may go to and still show enough road ahead ([lanesCap])
     */
    fun beforeTunedEvaluate(
        dataSource: MapboxNavigationViewportDataSource,
        mode: LaneZoom,
        tuning: CameraTuning,
        progress: RouteProgress,
        laneWindow: Boolean,
        latitude: Double,
        junction: JunctionPoints,
        lanesCap: Double = Double.POSITIVE_INFINITY,
    ) {
        val options = dataSource.options.followingFrameOptions
        // With Default nothing of the drive is asked: the limits are the same on every update,
        // and the junction frame gets none of the bend's points.
        val framesManeuver = mode == LaneZoom.LARGE && framesManeuver(progress, options)
        val limits = tunedLimits(mode, tuning, framesManeuver, laneWindow, latitude, lanesCap)
        options.minZoom = limits.minZoom
        options.maxZoom = limits.maxZoom
        options.bearingSmoothing.maxBearingAngleDiff = limits.bearingLead
        junction.update(progress, mode, framesManeuver, coalesceMeters(options), tuning)
    }

    /**
     * How far Smooth follow lets the view turn toward the road ahead, in degrees. With Large it
     * turns less near a lane choice ([laneWindow]), so the lanes at the car stay close to upright,
     * and while the view looks down onto a turn ([junctionFocus] above 0), so a bend past the turn
     * is not swung to the top of the screen while the car still drives straight, close in. Every
     * other time, and always with Default, the SDK's own 45 degrees.
     *
     * @param laneWindow the lane choices' schedule is in for one now
     * @param junctionFocus how far the view has moved onto the next maneuver, from 0 to 1
     */
    fun smoothBearingLead(mode: LaneZoom, tuning: CameraTuning, laneWindow: Boolean, junctionFocus: Double): Double {
        if (mode != LaneZoom.LARGE) return SDK_BEARING_LEAD
        var lead = SDK_BEARING_LEAD
        if (laneWindow) lead = min(lead, tuning.laneMaxBearingLead)
        if (junctionFocus > 0.0) lead = min(lead, tuning.junctionMaxBearingLead)
        return lead
    }

    /**
     * How far Smooth follow's tilt goes toward the lanes' tilt (`guidancePitch`): the lane focus,
     * with Large while the camera follows the car; nothing with Default and in any other mode, where
     * the tilt is the one it always had. The focus is read only where it counts.
     *
     * @param following the camera follows the car, or the frame is the one that follows it
     * @param focus the lane focus at the time the tilt is for, from 0 to 1
     */
    inline fun laneShare(mode: LaneZoom, following: Boolean, focus: () -> Double): Double =
        if (mode == LaneZoom.LARGE && following) focus() else 0.0

    /** The zooms a following frame is fitted within, and how far the view may turn toward the road ahead, in degrees. */
    class Limits(val minZoom: Double, val maxZoom: Double, val bearingLead: Double)

    /**
     * The limits of Mapbox tuned for one frame. With Default they are the Default ones on every
     * update. With Large:
     * - while the SDK frames a maneuver, the frame may be fitted until a lane is
     *   [CameraTuning.junctionLaneDp] wide; elsewhere the upper limit is the Default one;
     * - near a lane choice the frame of the road is no farther out than a lane of
     *   [CameraTuning.freewayLaneDp], or than [lanesCap] where that is farther out, so the road
     *   ahead still shows; and the view turns less toward the road ahead. The frame of a maneuver
     *   keeps the SDK's lower limit also then: the SDK applies the lower limit to that frame too,
     *   and it is fitted around the car and the junction, so a limit above its fit would push both
     *   off the map.
     *
     * @param framesManeuver the SDK frames the next maneuver now, not the road
     * @param laneWindow the lane choices' schedule is in for one now
     * @param latitude where the car is; a lane's width on the screen depends on it
     * @param lanesCap the closest zoom the lanes may go to and still show enough road ahead
     *   ([lanesCap]); infinity, or not a number, for none. It never takes the limit under the SDK's
     */
    fun tunedLimits(
        mode: LaneZoom,
        tuning: CameraTuning,
        framesManeuver: Boolean,
        laneWindow: Boolean,
        latitude: Double,
        lanesCap: Double = Double.POSITIVE_INFINITY,
    ): Limits {
        if (mode != LaneZoom.LARGE) return Limits(SDK_MIN_ZOOM, tuning.defaultMaxZoom, SDK_BEARING_LEAD)
        val lanes = closest(tuning.freewayLaneDp, latitude, tuning)
        // The lower of the two; a cap that is not a number fails the test and caps nothing.
        val lanesMin = if (lanesCap < lanes) lanesCap else lanes
        val minZoom = if (laneWindow && !framesManeuver) max(SDK_MIN_ZOOM, lanesMin) else SDK_MIN_ZOOM
        val maxZoom = if (framesManeuver) max(tuning.defaultMaxZoom, closest(tuning.junctionLaneDp, latitude, tuning)) else tuning.defaultMaxZoom
        // The SDK takes the lower limit last, so the upper one may not be under it.
        return Limits(minZoom, max(maxZoom, minZoom), if (laneWindow) tuning.laneMaxBearingLead else SDK_BEARING_LEAD)
    }

    /**
     * The closest zoom the lanes' close view may take and still show
     * [CameraTuning.laneLookAheadSeconds] of driving at [capSpeed] above the car, seen at a tilt of
     * [pitch] ([LookAhead.zoomFor]), on a map [mapHeightPx] high whose following padding has
     * [paddingTop] and [paddingBottom], in pixels: the navigation SDK puts the car on the padding's
     * bottom edge, and the padding's top is where the map is clear of the card. Worked out once per
     * progress update. Infinity, no cap, without a speed, before the padding is set, and wherever
     * the model has no answer.
     *
     * @param capSpeed the highest speed of the last [CameraTuning.laneCapSpeedSeconds] (`PeakSpeed`)
     * @param pixelRatio pixels per dp
     */
    fun lanesCap(
        tuning: CameraTuning,
        capSpeed: Double?,
        pitch: Double,
        mapHeightPx: Double,
        paddingTop: Double,
        paddingBottom: Double,
        pixelRatio: Double,
        latitude: Double,
    ): Double {
        if (capSpeed == null || (paddingTop <= 0.0 && paddingBottom <= 0.0)) return Double.POSITIVE_INFINITY
        return LookAhead.zoomFor(capSpeed * tuning.laneLookAheadSeconds, pitch, mapHeightPx, mapHeightPx - paddingBottom, paddingTop, pixelRatio, latitude)
    }

    /**
     * The maneuver that ends the step of [progress] where the camera frames it as a junction: a
     * maneuver of a kind the camera's own rule [counts], with the meters to where that framing
     * begins, [framingMeters] before it. Null for a maneuver the view stays up for, without a
     * banner and without a step. The lane focus keeps a close view for lanes until then when the
     * turn comes right after them.
     */
    fun turnAhead(progress: RouteProgress, counts: (String) -> Boolean, framingMeters: Double): TurnAhead? {
        val leg = progress.currentLegProgress ?: return null
        val step = leg.currentStepProgress ?: return null
        val type = progress.bannerInstructions?.primary()?.type() ?: return null
        if (!counts(type)) return null
        return TurnAhead("${progress.navigationRoute.id}/${leg.legIndex}/${step.stepIndex}", step.distanceRemaining.toDouble() - framingMeters)
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

    /** The SDK frames a step after a maneuver whole up to this length (its own option, 150 m). */
    private fun coalesceMeters(options: FollowingFrameOptions): Double =
        options.frameGeometryAfterManeuver.distanceToCoalesceCompoundManeuvers

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
        val framed = progressOf(route, progress)
        if (framed != null) dataSource.onRouteProgressChanged(framed)
    }

    /**
     * The progress the data source frames by while it has [route]: the latest one, if it is of
     * that route. A progress of the route before a reroute is of no use to the new one, and
     * without a route there is none.
     */
    private fun progressOf(route: NavigationRoute?, progress: RouteProgress?): RouteProgress? =
        progress?.takeIf { route != null && it.navigationRoute.id == route.id }

    /**
     * Whether the SDK frames the next maneuver at this [progress], for both styles that carry
     * Large. Without a progress it frames none.
     */
    fun framesManeuver(progress: RouteProgress?, options: FollowingFrameOptions): Boolean {
        val step = progress?.currentLegProgress?.currentStepProgress ?: return false
        return framesManeuver(progress.bannerInstructions?.primary()?.type(), step.distanceRemaining, options.pitchNearManeuvers)
    }

    /**
     * Whether the SDK frames the next maneuver, not the road: its own rule, with its own options.
     * It decides this when it is given a route progress and keeps it until the next one.
     *
     * @param maneuverType the type of the maneuver in the banner; null while there is no banner
     * @param metersToManeuver what is left of the current step
     */
    fun framesManeuver(maneuverType: String?, metersToManeuver: Float, near: FollowingFrameOptions.PitchNearManeuvers): Boolean =
        maneuverType != null && near.enabled && maneuverType !in near.excludedManeuvers &&
            metersToManeuver <= near.triggerDistanceFromManeuver

    /**
     * The zoom at which a lane is [laneDp] wide at [latitude], no closer than Large ever goes.
     * Where the latitude is not a number no zoom is close enough to ask for.
     */
    private fun closest(laneDp: Double, latitude: Double, tuning: CameraTuning): Double {
        val zoom = MapScale.zoomForLaneDp(laneDp, latitude)
        return if (zoom.isNaN()) Double.NEGATIVE_INFINITY else min(zoom, tuning.maxZoom)
    }
}
