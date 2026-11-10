package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.Choreographer
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.CameraAnimationsLifecycleListener
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.maps.plugin.animation.CameraAnimatorType
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.data.ViewportData
import com.mapbox.navigation.ui.maps.camera.data.ViewportDataSourceUpdateObserver
import io.github.purplepalm.custommaps.nav.camera.Angles
import io.github.purplepalm.custommaps.nav.camera.CameraTuning
import io.github.purplepalm.custommaps.nav.camera.IdleFollow
import io.github.purplepalm.custommaps.nav.camera.ManeuverFocus
import io.github.purplepalm.custommaps.nav.camera.OncePerUpdate
import io.github.purplepalm.custommaps.nav.camera.SampleRamp
import io.github.purplepalm.custommaps.nav.camera.Spring
import io.github.purplepalm.custommaps.nav.camera.easeInOut
import io.github.purplepalm.custommaps.nav.camera.lerp
import io.github.purplepalm.custommaps.nav.debug.Motion
import io.github.purplepalm.custommaps.nav.geo.Geo
import io.github.purplepalm.custommaps.nav.model.CameraMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Smooth follow style: a camera that moves the map itself, once on every display frame.
 *
 * While it follows the car or shows the overview, nothing else sets the map's camera. It sets it
 * once per frame with [MapboxMap.setCamera], from a [Choreographer] callback on the main thread:
 * the thread and the frame the puck's own animation runs on. What the navigator sends once a second
 * only moves targets. The viewport data source still computes the bearing that looks ahead along
 * the road, the zoom, and the frames around a maneuver and of the overview; each of its frames is
 * reached over [CameraTuning.sampleHoldSeconds]. The camera goes after the targets on critically
 * damped springs. While following it goes after the puck as it is drawn, not after the latest
 * position, so it trails the car a little and comes to rest on it when the car stops. The tilt
 * before a maneuver comes from [ManeuverFocus].
 *
 * On the browsing map, before guidance, it follows the drawn puck in the same way, at a fixed zoom
 * and tilt and without the data source.
 *
 * It never starts a camera animation. Gestures, the compass and every other move of the map do, so
 * an animation that starts means someone else took the map, and the camera turns idle at once.
 *
 * While nothing moves, no frame is asked for and the camera is not set. Main thread only.
 */
internal class SmoothNavigationCamera(
    private val mapboxMap: MapboxMap,
    private val locationComponent: LocationComponentPlugin,
    private val cameraPlugin: CameraAnimationsPlugin,
    private val dataSource: MapboxNavigationViewportDataSource,
    private val tuning: CameraTuning,
    private val listener: GuidanceCamera.Listener,
) : GuidanceCamera, Choreographer.FrameCallback {

    /** What the camera is doing, [shown] to the screen as one of its camera modes. */
    private enum class Mode(val shown: CameraMode, val onPuck: Boolean) {
        IDLE(CameraMode.IDLE, onPuck = false),

        /** On the car on the browsing map, with no route; the name is [GuidanceCamera.IDLE_FOLLOW]. */
        IDLE_FOLLOW(CameraMode.IDLE, onPuck = true),
        FOLLOWING(CameraMode.FOLLOWING, onPuck = true),
        OVERVIEW(CameraMode.OVERVIEW, onPuck = false),
    }

    private val choreographer = Choreographer.getInstance()
    private var mode = Mode.IDLE

    /** True from a change of mode until the camera has reached what the new mode shows. */
    private var arriving = false
    private var visible = false
    private var framePosted = false
    private var lastFrameNanos = 0L
    private var userAnimation = false
    private var snapOnNextFrame = false

    /** After a jump of the matched position: until when a puck found far away is flown to. */
    private var teleportUntil = 0.0

    // What comes in. Times are seconds on the clock of the frames, System.nanoTime.
    private var puckLat = Double.NaN
    private var puckLng = Double.NaN

    /** Where the puck was when it last asked for a frame, and when that was. */
    private var puckWokeLat = Double.NaN
    private var puckWokeLng = Double.NaN
    private var puckSeenAt = 0.0
    private var pendingData: ViewportData? = null
    private var latestData: ViewportData? = null
    private var speedMps: Double? = null
    private var overviewPitch = 0.0

    /** Which message of an update of the navigator the data source computes its frame on. */
    private val pacing = OncePerUpdate()

    // The frames of the data source, about one a second, made continuous. Padding: top, left, bottom, right.
    private val zoomRamp = SampleRamp(tuning.sampleHoldSeconds)
    private val bearingRamp = SampleRamp(tuning.sampleHoldSeconds)
    private val latRamp = SampleRamp(tuning.sampleHoldSeconds)
    private val lngRamp = SampleRamp(tuning.sampleHoldSeconds)
    private val onPuckRamp = SampleRamp(tuning.sampleHoldSeconds)
    private val padRamp = Array(SIDES) { SampleRamp(tuning.sampleHoldSeconds) }
    private val ramps = arrayOf(zoomRamp, bearingRamp, latRamp, lngRamp, onPuckRamp, *padRamp)

    /** How close to its newest sample each of [ramps] counts as arrived. */
    private val rampRest =
        doubleArrayOf(ZOOM_REST, ANGLE_REST, CENTER_REST, CENTER_REST, ON_PUCK_REST, PAD_REST, PAD_REST, PAD_REST, PAD_REST)

    // The camera itself.
    private val lat = Spring(tuning.centerTau)
    private val lng = Spring(tuning.centerTau)
    private val zoom = Spring(tuning.zoomTau)
    private val bearing = Spring(tuning.bearingTau)
    private val pitch = Spring(tuning.pitchTau)

    /** 1 while the center is the drawn puck, 0 while it is a point of the map. */
    private val onPuck = Spring(tuning.paddingTau)
    private val pad = Array(SIDES) { Spring(tuning.paddingTau) }

    // Where the springs are going on this frame.
    private var toLat = 0.0
    private var toLng = 0.0
    private var toZoom = 0.0
    private var toBearing = 0.0
    private var toPitch = 0.0
    private val toPad = DoubleArray(SIDES)
    private var lastZoomTarget = Double.NaN
    private var lastBearingTarget = Double.NaN

    // A change of mode: how far the camera was from its new target when it began. That offset fades
    // out on an eased curve, so a moving target, the puck, is followed from the first frame and a
    // still one is reached without a jolt.
    private var transitionStart = 0.0
    private var transitionSeconds = 0.0
    private val offset = DoubleArray(VALUES)
    private var arcZoom = 0.0

    // What the map was last given, to leave it alone while nothing moves, as at a red light.
    private val written = DoubleArray(VALUES) { Double.NaN }
    private val frame = DoubleArray(VALUES)
    private var writtenPadding: EdgeInsets? = null

    // For the camera line of the debug log.
    private var statsSince = 0.0
    private var frames = 0
    private var writes = 0
    private var maxGapMs = 0.0

    /** How far the view has moved onto the next maneuver, from 0 to 1. */
    private val maneuverFocus = ManeuverFocus(tuning)

    /**
     * Every frame of the puck's glide, right after the puck was drawn at its new place. During
     * guidance the glide never ends: each update of the navigator starts it again, and while the car
     * stands the puck creeps the last millimeters for many seconds. So the puck asks for a frame
     * only when it has moved, and only while the camera looks at it.
     */
    private val puckListener = OnIndicatorPositionChangedListener { point ->
        puckLat = point.latitude()
        puckLng = point.longitude()
        val still = abs(puckLat - puckWokeLat) <= CENTER_REST && abs(puckLng - puckWokeLng) <= CENTER_REST
        if (mode.onPuck && !still) {
            puckWokeLat = puckLat
            puckWokeLng = puckLng
            puckSeenAt = now()
            requestFrame()
        }
    }

    /**
     * A new frame of the data source. The next frame of the display takes it, so two in a row count
     * once. On the browsing map the data source has no say, and its frames wait for the next route.
     */
    private val dataObserver = ViewportDataSourceUpdateObserver { data ->
        latestData = data
        if (mode != Mode.IDLE_FOLLOW) {
            pendingData = data
            requestFrame()
        }
    }

    /**
     * The camera plugin reports every camera animation that starts, also those without a duration,
     * as a drag makes them. None of them is this camera's.
     */
    private val otherAnimations = object : CameraAnimationsLifecycleListener {
        override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) {
            if (mode == Mode.IDLE) return
            listener.onCameraTaken(owner, type.name, state)
            requestIdle()
        }

        override fun onAnimatorInterrupting(
            type: CameraAnimatorType,
            runningAnimator: ValueAnimator,
            runningAnimatorOwner: String?,
            newAnimator: ValueAnimator,
            newAnimatorOwner: String?,
        ) = Unit

        override fun onAnimatorEnding(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) = Unit

        override fun onAnimatorCancelling(type: CameraAnimatorType, animator: ValueAnimator, owner: String?) = Unit
    }

    init {
        locationComponent.addOnIndicatorPositionChangedListener(puckListener)
        // Hands over the frame the data source holds at once.
        dataSource.registerUpdateObserver(dataObserver)
        cameraPlugin.addCameraAnimationsLifecycleListener(otherAnimations)
        listener.onCameraState(mode.shown, state, moving = false)
    }

    override val isIdle: Boolean get() = mode == Mode.IDLE

    override val state: String get() = if (arriving) "TRANSITION_TO_${mode.name}" else mode.name

    override fun requestFollowing() = beginTransition(Mode.FOLLOWING)

    override fun requestOverview() = beginTransition(Mode.OVERVIEW)

    override fun requestIdleFollow(puck: Point) {
        // A camera built while the car stood has not seen the puck drawn yet.
        if (puckLat.isNaN()) {
            puckLat = puck.latitude()
            puckLng = puck.longitude()
        }
        beginTransition(Mode.IDLE_FOLLOW)
    }

    // On the browsing map the camera goes after the puck as it is drawn, frame by frame.
    override fun onPuckGlide(target: Point, glideMillis: Long) = Unit

    override fun requestIdle() {
        if (mode == Mode.IDLE) return
        mode = Mode.IDLE
        arriving = false
        transitionSeconds = 0.0
        cancelFrame()
        setUserAnimation(false)
        listener.onCameraState(mode.shown, state, moving = false)
    }

    override fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        lastFrameNanos = 0L
        if (!visible) {
            cancelFrame()
            setUserAnimation(false)
            return
        }
        if (mode != Mode.IDLE) {
            // The map was not on screen, so there is nothing to glide from: the first frame drawn is right.
            syncToMap()
            snapOnNextFrame = true
            requestFrame()
        }
    }

    override fun onLocation(location: Location, isTeleport: Boolean) {
        speedMps = location.speed ?: speedMps
        if (isTeleport) teleportUntil = now() + TELEPORT_WATCH_SECONDS
        dataSource.onLocationChanged(location)
        // The route progress of this update comes right after the location and computes the frame.
        if (pacing.locationComputes(SystemClock.elapsedRealtime(), isTeleport)) dataSource.evaluate()
    }

    override fun onRouteProgress(progress: RouteProgress) {
        val now = now()
        val leg = progress.currentLegProgress
        val step = leg?.currentStepProgress
        if (leg != null && step != null) {
            maneuverFocus.onProgress(
                now = now,
                stepKey = "${progress.navigationRoute.id}/${leg.legIndex}/${step.stepIndex}",
                metersToManeuver = step.distanceRemaining.toDouble(),
                metersOnStep = step.distanceTraveled.toDouble(),
                maneuverType = progress.bannerInstructions?.primary()?.type(),
                excludedTypes = dataSource.options.followingFrameOptions.pitchNearManeuvers.excludedManeuvers,
                speedMps = speedMps,
            )
        }
        // The data source fits the zoom and the frame around a maneuver for the tilt it is told.
        // It gets the tilt the camera will have when this frame has been reached.
        dataSource.followingPitchPropertyOverride(maneuverFocus.pitchAt(now + tuning.sampleHoldSeconds))
        dataSource.onRouteProgressChanged(progress)
        dataSource.evaluate()
        pacing.onProgress(SystemClock.elapsedRealtime())
        // The tilt may have begun to move, also when the frame of the data source is the same.
        requestFrame()
    }

    override fun onGuidanceEnded() {
        maneuverFocus.reset(now())
        speedMps = null
        teleportUntil = 0.0
        dataSource.clearFollowingOverrides()
    }

    override fun diagnostics(): Map<String, Any?> {
        val now = now()
        val seconds = (now - statsSince).takeIf { statsSince > 0.0 && it > 0.0 }
        val fields = mapOf(
            "camFps" to seconds?.let { Motion.round(frames / it, 1) },
            "camMaxGapMs" to Motion.round(maxGapMs, 1),
            "camWrites" to writes,
            "focus" to Motion.round(maneuverFocus.focusAt(now), 2),
            "focusGoal" to maneuverFocus.goal,
            "zoomTarget" to Motion.round(toZoom, 2),
            "bearingError" to Motion.round(Angles.wrap180(toBearing - bearing.value), 1),
            "onPuck" to Motion.round(onPuck.value, 2),
        )
        statsSince = now
        frames = 0
        writes = 0
        maxGapMs = 0.0
        return fields
    }

    override fun destroy() {
        cancelFrame()
        setUserAnimation(false)
        // Without a word to the listener: the camera that takes this one's place speaks from here on.
        mode = Mode.IDLE
        arriving = false
        locationComponent.removeOnIndicatorPositionChangedListener(puckListener)
        dataSource.unregisterUpdateObserver(dataObserver)
        cameraPlugin.removeCameraAnimationsLifecycleListener(otherAnimations)
        dataSource.clearFollowingOverrides()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!visible || mode == Mode.IDLE || !mapboxMap.isValid()) return
        // The same clock as now().
        val now = frameTimeNanos / NANOS_PER_SECOND
        val dt = if (lastFrameNanos == 0L) {
            FIRST_FRAME_SECONDS
        } else {
            ((frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0.0, LONGEST_FRAME_SECONDS)
        }
        lastFrameNanos = frameTimeNanos
        frames += 1
        maxGapMs = maxOf(maxGapMs, dt * MILLIS_PER_SECOND)

        pendingData?.let { data ->
            pendingData = null
            applySample(data, now)
        }
        if (teleportUntil != 0.0) watchTeleport(now)
        computeTargets(now, dt)
        blendTransition(now)
        if (snapOnNextFrame) {
            snapOnNextFrame = false
            snapToTargets()
        } else {
            stepSprings(dt)
        }
        writeCamera()
        val moving = stillMoving(now)
        // Tells the map that the camera moves frame by frame, which keeps the labels steadier meanwhile.
        setUserAnimation(moving)
        if (moving) {
            requestFrame()
        } else {
            // At rest: no frame until the puck, the data source or a request asks for one.
            lastFrameNanos = 0L
        }
    }

    /**
     * The matched position jumped, and the puck with it, on this frame or one of the next. A puck
     * that is then far from the camera is flown to, not chased across the map.
     */
    private fun watchTeleport(now: Double) {
        if (mode != Mode.FOLLOWING || now > teleportUntil) {
            teleportUntil = 0.0
        } else if (!puckLat.isNaN() && Geo.meters(lat.value, lng.value, puckLat, puckLng) > tuning.teleportMeters) {
            teleportUntil = 0.0
            beginTransition(mode, seconds = tuning.teleportTransitionSeconds, again = true)
        }
    }

    private fun applySample(data: ViewportData, now: Double) {
        val target = if (mode == Mode.FOLLOWING) data.cameraForFollowing else data.cameraForOverview
        target.zoom?.let { zoomRamp.push(it, now) }
        target.bearing?.let { value ->
            // The short way round from where the bearing is.
            bearingRamp.push(Angles.nearest(value, if (bearingRamp.ready) bearingRamp.value(now) else bearing.value), now)
        }
        target.center?.let { center ->
            latRamp.push(center.latitude(), now)
            lngRamp.push(center.longitude(), now)
        }
        val padding = target.padding
        if (padding != null) {
            padRamp[TOP].push(padding.top, now)
            padRamp[LEFT].push(padding.left, now)
            padRamp[BOTTOM].push(padding.bottom, now)
            padRamp[RIGHT].push(padding.right, now)
        }
        if (mode == Mode.OVERVIEW) target.pitch?.let { overviewPitch = it }
        // On the road the data source puts the car on one point of the screen, with a padding that
        // leaves only that point: the camera then centers on the drawn puck. Near a maneuver the
        // data source frames the road around it, and the overview frames the route: a point of the map.
        val carOnFocalPoint = mode == Mode.FOLLOWING && padding != null && leavesOnePoint(padding)
        onPuckRamp.push(if (carOnFocalPoint) 1.0 else 0.0, now)
    }

    private fun computeTargets(now: Double, dt: Double) {
        onPuck.step(if (onPuckRamp.ready) onPuckRamp.value(now) else 0.0, dt)
        val weight = onPuck.value.coerceIn(0.0, 1.0)
        val mapLat = if (latRamp.ready) latRamp.value(now) else lat.value
        val mapLng = if (lngRamp.ready) lngRamp.value(now) else lng.value
        toLat = if (puckLat.isNaN()) mapLat else lerp(mapLat, puckLat, weight)
        toLng = if (puckLng.isNaN()) mapLng else lerp(mapLng, puckLng, weight)

        val zoomGoal = if (zoomRamp.ready) zoomRamp.lead(now, tuning.zoomTau) else zoom.value
        val bearingGoal =
            if (bearingRamp.ready) Angles.nearest(bearingRamp.lead(now, tuning.bearingTau), bearing.value) else bearing.value
        // A change of mode may move as fast as it needs; within a mode zoom and bearing have a top speed.
        if (transitionSeconds > 0.0 || lastZoomTarget.isNaN()) {
            toZoom = zoomGoal
            toBearing = bearingGoal
        } else {
            val zoomStep = tuning.maxZoomRate * dt
            val bearingStep = tuning.maxBearingRate * dt
            toZoom = lastZoomTarget + (zoomGoal - lastZoomTarget).coerceIn(-zoomStep, zoomStep)
            toBearing = lastBearingTarget + Angles.wrap180(bearingGoal - lastBearingTarget).coerceIn(-bearingStep, bearingStep)
        }
        lastZoomTarget = toZoom
        lastBearingTarget = toBearing
        toPitch = when (mode) {
            Mode.FOLLOWING -> maneuverFocus.pitchAt(now)
            Mode.IDLE_FOLLOW -> IdleFollow.PITCH
            else -> overviewPitch
        }
        for (side in 0 until SIDES) toPad[side] = if (padRamp[side].ready) padRamp[side].value(now) else pad[side].value
    }

    private fun blendTransition(now: Double) {
        if (transitionSeconds <= 0.0) return
        val fraction = ((now - transitionStart) / transitionSeconds).coerceIn(0.0, 1.0)
        val eased = easeInOut(fraction)
        val left = 1.0 - eased
        toLat += offset[LAT] * left
        toLng += offset[LNG] * left
        // A long move zooms out on its way and back in, as a flight does.
        toZoom += offset[ZOOM] * left - arcZoom * sin(PI * eased)
        toBearing += offset[BEARING] * left
        toPitch += offset[PITCH] * left
        for (side in 0 until SIDES) toPad[side] += offset[PAD + side] * left
        if (fraction >= 1.0) {
            transitionSeconds = 0.0
            lastZoomTarget = toZoom
            lastBearingTarget = toBearing
            if (arriving) {
                arriving = false
                listener.onCameraState(mode.shown, state, moving = false)
            }
        }
    }

    private fun stepSprings(dt: Double) {
        lat.step(toLat, dt)
        lng.step(toLng, dt)
        zoom.step(toZoom, dt)
        bearing.step(Angles.nearest(toBearing, bearing.value), dt)
        pitch.step(toPitch, dt)
        for (side in 0 until SIDES) pad[side].step(toPad[side], dt)
    }

    private fun snapToTargets() {
        lat.snap(toLat)
        lng.snap(toLng)
        zoom.snap(toZoom)
        bearing.snap(toBearing)
        pitch.snap(toPitch)
        for (side in 0 until SIDES) pad[side].snap(toPad[side])
    }

    /** One call to the map with what moved since the last one; none when nothing did. */
    private fun writeCamera() {
        frame[LAT] = lat.value
        frame[LNG] = lng.value
        frame[ZOOM] = zoom.value
        frame[BEARING] = Angles.wrap360(bearing.value)
        frame[PITCH] = pitch.value
        for (side in 0 until SIDES) frame[PAD + side] = pad[side].value

        var paddingMoved = writtenPadding == null
        for (side in 0 until SIDES) {
            if (abs(frame[PAD + side] - written[PAD + side]) > PAD_STEP) paddingMoved = true
        }
        val moved = paddingMoved || written[LAT].isNaN() ||
            abs(frame[LAT] - written[LAT]) > CENTER_STEP || abs(frame[LNG] - written[LNG]) > CENTER_STEP ||
            abs(frame[ZOOM] - written[ZOOM]) > ZOOM_STEP ||
            abs(Angles.wrap180(frame[BEARING] - written[BEARING])) > ANGLE_STEP ||
            abs(frame[PITCH] - written[PITCH]) > ANGLE_STEP
        if (!moved) return

        // The padding changes with the panels and around maneuvers; on the road the map keeps the one it has.
        val padding = if (paddingMoved) {
            EdgeInsets(frame[PAD + TOP], frame[PAD + LEFT], frame[PAD + BOTTOM], frame[PAD + RIGHT])
        } else {
            writtenPadding
        }
        mapboxMap.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(frame[LNG], frame[LAT]))
                .zoom(frame[ZOOM])
                .bearing(frame[BEARING])
                .pitch(frame[PITCH])
                .padding(padding)
                .build(),
        )
        if (paddingMoved) {
            writtenPadding = padding
            frame.copyInto(written)
        } else {
            frame.copyInto(written, endIndex = PAD)
        }
        writes += 1
    }

    /**
     * Starts the move into [target], from wherever the camera is. [again] starts it over within the
     * mode the camera is in, for a puck that jumped.
     */
    private fun beginTransition(target: Mode, seconds: Double? = null, again: Boolean = false) {
        if (target == mode && !again) return
        val now = now()
        if (mode == Mode.IDLE) {
            // An animation still on its way, the end of a fling or of an ease, would go on moving the
            // map under this camera. The move starts from where that leaves the map.
            cameraPlugin.cancelAllAnimators()
            syncToMap()
        }
        val changed = target != mode
        mode = target
        for (ramp in ramps) ramp.forget()
        pendingData = null
        if (mode == Mode.IDLE_FOLLOW) {
            // The browsing view: on the car, in the middle of the map, turned as the map is.
            zoomRamp.reset(IdleFollow.ZOOM, now)
            onPuckRamp.reset(1.0, now)
            for (side in 0 until SIDES) padRamp[side].reset(0.0, now)
        } else {
            latestData?.let { data -> applySample(data, now) }
        }
        // What the data source has not given yet stays where the camera is, and is reached when it comes.
        if (!zoomRamp.ready) zoomRamp.reset(zoom.value, now)
        if (!bearingRamp.ready) bearingRamp.reset(bearing.value, now)
        if (!latRamp.ready) {
            latRamp.reset(lat.value, now)
            lngRamp.reset(lng.value, now)
        }
        for (side in 0 until SIDES) if (!padRamp[side].ready) padRamp[side].reset(pad[side].value, now)
        onPuck.snap(if (onPuckRamp.ready) onPuckRamp.value(now) else 0.0)
        lastZoomTarget = Double.NaN
        lastBearingTarget = Double.NaN
        transitionSeconds = 0.0
        computeTargets(now, 0.0)
        offset[LAT] = lat.value - toLat
        offset[LNG] = lng.value - toLng
        offset[ZOOM] = zoom.value - toZoom
        offset[BEARING] = Angles.wrap180(bearing.value - toBearing)
        offset[PITCH] = pitch.value - toPitch
        for (side in 0 until SIDES) offset[PAD + side] = pad[side].value - toPad[side]
        val screens = screensBetween(lat.value, lng.value, toLat, toLng, zoom.value)
        arcZoom = log2(screens.coerceAtLeast(1.0)).coerceIn(0.0, tuning.maxArcZoom)
        transitionSeconds = seconds ?: when {
            // To the car on the browsing map: as quick as the ease of the other styles, unless it is far.
            mode == Mode.IDLE_FOLLOW -> if (screens > FAR_SCREENS) tuning.longTransitionSeconds else IdleFollow.ARRIVE_SECONDS
            abs(toZoom - zoom.value) > FAR_ZOOM_LEVELS || screens > FAR_SCREENS -> tuning.longTransitionSeconds
            else -> tuning.transitionSeconds
        }
        transitionStart = now
        // A map that still shows the whole world, as one does that was never moved, has nothing to
        // glide from: the camera is put on its target with the first frame, and has arrived then.
        if (zoom.value < WORLD_ZOOM && toZoom - zoom.value > FAR_ZOOM_LEVELS) {
            offset.fill(0.0)
            arcZoom = 0.0
            transitionSeconds = FIRST_FRAME_SECONDS
            snapOnNextFrame = true
        }
        if (changed) {
            // Following on the browsing map is one state from the start; the others say when they got there.
            arriving = mode != Mode.IDLE_FOLLOW
            listener.onCameraState(mode.shown, state, moving = arriving)
        }
        requestFrame()
    }

    private fun stillMoving(now: Double): Boolean {
        if (transitionSeconds > 0.0) return true
        // The puck counts only while the camera is on it, the tilt before a maneuver only during guidance.
        if (mode.onPuck && now - puckSeenAt < PUCK_AWAKE_SECONDS) return true
        if (mode == Mode.FOLLOWING && maneuverFocus.isAnimating(now)) return true
        for (index in ramps.indices) if (ramps[index].isMoving(now, rampRest[index])) return true
        for (side in 0 until SIDES) if (!pad[side].isSettled(toPad[side], PAD_REST)) return true
        return !(
            lat.isSettled(toLat, CENTER_REST) && lng.isSettled(toLng, CENTER_REST) &&
                zoom.isSettled(toZoom, ZOOM_REST) &&
                abs(Angles.wrap180(toBearing - bearing.value)) < ANGLE_REST && abs(bearing.velocity) < ANGLE_REST * 4 &&
                pitch.isSettled(toPitch, ANGLE_REST) &&
                onPuck.isSettled(if (onPuckRamp.ready) onPuckRamp.value(now) else 0.0, ON_PUCK_REST)
            )
    }

    private fun requestFrame() {
        if (framePosted || !visible || mode == Mode.IDLE) return
        framePosted = true
        choreographer.postFrameCallback(this)
    }

    private fun cancelFrame() {
        if (framePosted) choreographer.removeFrameCallback(this)
        framePosted = false
    }

    private fun setUserAnimation(on: Boolean) {
        if (on == userAnimation || !mapboxMap.isValid()) return
        userAnimation = on
        mapboxMap.setUserAnimationInProgress(on)
    }

    /** Puts the springs where the map's camera is. */
    private fun syncToMap() {
        val shown = mapboxMap.cameraState
        lat.snap(shown.center.latitude())
        lng.snap(shown.center.longitude())
        zoom.snap(shown.zoom)
        bearing.snap(shown.bearing)
        pitch.snap(shown.pitch)
        pad[TOP].snap(shown.padding.top)
        pad[LEFT].snap(shown.padding.left)
        pad[BOTTOM].snap(shown.padding.bottom)
        pad[RIGHT].snap(shown.padding.right)
        // Whoever had the map moved it: the next frame sets everything again.
        written.fill(Double.NaN)
        writtenPadding = null
        lastFrameNanos = 0L
    }

    /** Whether [padding] takes the whole map but one point, as the data source's padding for the car's place does. */
    private fun leavesOnePoint(padding: EdgeInsets): Boolean {
        // In the same pixels as the padding.
        val size = mapboxMap.getSize()
        return abs(padding.top + padding.bottom - size.height) < FOCAL_SLACK_PX &&
            abs(padding.left + padding.right - size.width) < FOCAL_SLACK_PX
    }

    /** The distance between two points, in diagonals of the screen at [atZoom]. */
    private fun screensBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double, atZoom: Double): Double {
        val size = mapboxMap.getSize()
        val pixelRatio = mapboxMap.getMapOptions().pixelRatio.toDouble()
        val metersPerPoint = METERS_PER_POINT_AT_ZOOM_0 * cos(Math.toRadians(lat1)) / 2.0.pow(atZoom)
        val diagonalMeters = hypot(size.width.toDouble(), size.height.toDouble()) / pixelRatio * metersPerPoint
        return if (diagonalMeters > 0.0) Geo.meters(lat1, lng1, lat2, lng2) / diagonalMeters else 0.0
    }

    private fun now() = System.nanoTime() / NANOS_PER_SECOND

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val MILLIS_PER_SECOND = 1_000.0

        /** The time a first frame stands for, and the most any frame counts as: a longer gap is not caught up. */
        const val FIRST_FRAME_SECONDS = 1.0 / 60.0
        const val LONGEST_FRAME_SECONDS = 0.1

        /** A puck that moved this recently is still gliding. */
        const val PUCK_AWAKE_SECONDS = 0.25

        /** How long after a jump of the matched position the puck is expected to have jumped too. */
        const val TELEPORT_WATCH_SECONDS = 0.25

        /** A move across more zoom levels or screens than this takes the long transition. */
        const val FAR_ZOOM_LEVELS = 3.0
        const val FAR_SCREENS = 2.0

        /** Below this zoom the map shows continents: the view of a map that was never moved. */
        const val WORLD_ZOOM = 3.0

        /** The width of one point of the map at zoom 0 on the equator, with 512-point tiles. */
        const val METERS_PER_POINT_AT_ZOOM_0 = 78_271.517

        /** The data source's one-point padding adds up to the map's size within this many pixels. */
        const val FOCAL_SLACK_PX = 2.0

        // The values of a frame, in the order they are kept.
        const val LAT = 0
        const val LNG = 1
        const val ZOOM = 2
        const val BEARING = 3
        const val PITCH = 4
        const val PAD = 5
        const val SIDES = 4
        const val VALUES = PAD + SIDES
        const val TOP = 0
        const val LEFT = 1
        const val BOTTOM = 2
        const val RIGHT = 3

        // A change smaller than this is not worth a call to the map: degrees of position (about a
        // tenth of a millimeter), zoom levels, degrees of angle, pixels.
        const val CENTER_STEP = 1e-9
        const val ZOOM_STEP = 1e-5
        const val ANGLE_STEP = 1e-4
        const val PAD_STEP = 0.01

        // A spring this close to its target, and this slow, is at rest.
        const val CENTER_REST = 1e-7
        const val ZOOM_REST = 1e-3
        const val ANGLE_REST = 0.05
        const val ON_PUCK_REST = 1e-3
        const val PAD_REST = 0.5
    }
}
