package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.CameraAnimationsPlugin
import com.mapbox.maps.plugin.animation.CameraAnimatorOptions
import com.mapbox.maps.plugin.animation.CameraAnimatorOptions.Companion.cameraAnimatorOptions
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.transition.MapboxNavigationCameraStateTransition
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraStateTransition
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions

/**
 * How the Mapbox tuned style moves the camera from one following frame to the next.
 *
 * The navigation SDK's own transition plays five linear animations of one second, and every update
 * of the navigator starts them again from where the camera is. So each change of zoom or tilt is over
 * within a second, and the camera stands still whenever an update comes late. Here the animations are
 * still linear and still started again by every update, but longer:
 * - center and bearing last a little longer than the updates are apart, so they are still moving
 *   when the next update comes;
 * - zoom, tilt and padding last two to three updates. Started again each second from where they
 *   are, they settle over a few seconds, the way a low-pass filter does.
 *
 * The flights between following, overview and idle, and the frames of the overview, stay the SDK's.
 *
 * Two rules of [NavigationCamera] hold here. Every animator carries its owner name, or the gestures
 * handler takes the animation for a gesture and turns the camera idle. And no animation is longer
 * than the transition options allow: the camera asks for a frame without animation with a limit of 0.
 */
internal class FollowingTransition(
    mapboxMap: MapboxMap,
    private val cameraPlugin: CameraAnimationsPlugin,
) : NavigationCameraStateTransition {

    private val stock = MapboxNavigationCameraStateTransition(mapboxMap, cameraPlugin)

    override fun transitionToFollowing(
        cameraOptions: CameraOptions,
        transitionOptions: NavigationCameraTransitionOptions,
    ): AnimatorSet = stock.transitionToFollowing(cameraOptions, transitionOptions)

    override fun transitionToOverview(
        cameraOptions: CameraOptions,
        transitionOptions: NavigationCameraTransitionOptions,
    ): AnimatorSet = stock.transitionToOverview(cameraOptions, transitionOptions)

    override fun updateFrameForOverview(
        cameraOptions: CameraOptions,
        transitionOptions: NavigationCameraTransitionOptions,
    ): AnimatorSet = stock.updateFrameForOverview(cameraOptions, transitionOptions)

    override fun updateFrameForFollowing(
        cameraOptions: CameraOptions,
        transitionOptions: NavigationCameraTransitionOptions,
    ): AnimatorSet {
        val limit = transitionOptions.maxDuration
        val animators = ArrayList<Animator>(5)
        cameraOptions.center?.let { center ->
            animators += cameraPlugin.createCenterAnimator(owned(center), false) { linear(CENTER_MS, limit) }
        }
        cameraOptions.bearing?.let { bearing ->
            animators += cameraPlugin.createBearingAnimator(owned(bearing)) { linear(BEARING_MS, limit) }
        }
        cameraOptions.zoom?.let { zoom ->
            animators += cameraPlugin.createZoomAnimator(owned(zoom)) { linear(ZOOM_MS, limit) }
        }
        cameraOptions.pitch?.let { pitch ->
            animators += cameraPlugin.createPitchAnimator(owned(pitch)) { linear(PITCH_MS, limit) }
        }
        cameraOptions.padding?.let { padding ->
            animators += cameraPlugin.createPaddingAnimator(owned(padding)) { linear(PADDING_MS, limit) }
        }
        return AnimatorSet().apply { playTogether(animators) }
    }

    private fun <T> owned(target: T): CameraAnimatorOptions<T> =
        cameraAnimatorOptions(target) { owner(NavigationCamera.NAVIGATION_CAMERA_OWNER) }

    private fun ValueAnimator.linear(millis: Long, limit: Long) {
        duration = minOf(millis, limit)
        interpolator = LINEAR
    }

    companion object {
        private const val CENTER_MS = 1_100L
        private const val BEARING_MS = 1_300L
        private const val ZOOM_MS = 2_500L
        private const val PITCH_MS = 2_000L
        private const val PADDING_MS = 1_500L
        private val LINEAR = LinearInterpolator()

        /**
         * The limit for the frames of following, as long as the longest animation above. The camera
         * takes it with every request to follow; without it the limit is one second.
         */
        val FRAME_OPTIONS: NavigationCameraTransitionOptions =
            NavigationCameraTransitionOptions.Builder().maxDuration(ZOOM_MS).build()
    }
}
