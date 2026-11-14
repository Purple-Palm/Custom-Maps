package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tap guard of the two Mapbox camera styles: the SDK camera's animators protected from a tap for the camera's life, a
 * request serial raised by every request and every map taken, and the end of a request that only posts its check, which
 * asks once more only by [io.github.purplepalm.custommaps.nav.camera.RecoverRule]. The adapter needs the SDK's camera and a
 * map, so its source is pinned here ([SourceText]); the rule is RecoverRuleTest's.
 */
class NavigationCameraAdapterWiringTest {

    private val adapter = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/camera/NavigationCameraAdapter.kt").readText()

    private fun body(declaration: String) = SourceText.body(adapter, declaration).map { it.trim() }

    @Test
    fun `the SDK camera's moves are protected from a tap while the camera lives`() {
        assertTrue("gestures?.addProtectedAnimationOwner(NavigationCamera.NAVIGATION_CAMERA_OWNER)" in body("init {"))
        val destroy = body("override fun destroy()")
        assertTrue("gestures?.removeProtectedAnimationOwner(NavigationCamera.NAVIGATION_CAMERA_OWNER)" in destroy)
        // A check still posted dies with the camera.
        assertTrue(destroy.indexOf("destroyed = true") in 0 until destroy.indexOf("checks.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun `every request and every map taken raises the serial`() {
        assertTrue(body("private fun follow(recovered: Boolean)").any { "endOf(NavigationCameraState.TRANSITION_TO_FOLLOWING, ++serial, recovered)" in it })
        assertTrue(body("private fun overview(recovered: Boolean)").any { "endOf(NavigationCameraState.TRANSITION_TO_OVERVIEW, ++serial, recovered)" in it })
        assertTrue(SourceText.hasStatement(adapter, "override fun requestIdle()", "serial += 1"))
        assertTrue(SourceText.hasStatement(adapter, "override fun requestIdleFollow(puck: Point)", "serial += 1"))
        // Both ways a gesture or the compass takes the map from the camera.
        val starting = body("override fun onAnimatorStarting(type: CameraAnimatorType, animator: ValueAnimator, owner: String?)")
        val taken = starting.withIndex().filter { "listener.onCameraTaken(owner, type.name, state)" in it.value }.map { it.index }
        assertEquals(2, taken.size)
        for (at in taken) assertEquals("serial += 1", starting[at - 1])
        // Following and the overview both go through the guarded requests.
        assertTrue("override fun requestFollowing() = follow(recovered = false)" in adapter)
        assertTrue("override fun requestOverview() = overview(recovered = false)" in adapter)
    }

    @Test
    fun `the end of a request never calls the camera, and its posted check asks only by the rule`() {
        val end = body("private fun endOf(state: NavigationCameraState, asked: Long, recovered: Boolean) = TransitionEndListener { isCanceled ->")
        assertEquals(listOf("if (isCanceled && !destroyed) checks.post { recoverIfStalled(state, asked, recovered) }"), end.filter { it.isNotBlank() && it != "}" })
        val check = body("private fun recoverIfStalled(state: NavigationCameraState, asked: Long, recovered: Boolean)")
        assertEquals("if (destroyed || !RecoverRule.shouldAsk(asked, serial, navigationCamera.state == state, recovered)) return", check.first { it.isNotBlank() })
        // Asked once more as a move that was recovered: never a second time.
        assertTrue("if (mode == CameraMode.OVERVIEW) overview(recovered = true) else follow(recovered = true)" in check)
        // Nothing else in the adapter asks the SDK camera for following or the overview.
        val asks = Regex("navigationCamera\\.requestNavigationCameraTo(Following|Overview)\\(").findAll(SourceText.code(adapter)).count()
        assertEquals(3, asks, "the tuned and the standard following, and the overview")
        assertFalse("checks.postDelayed" in adapter, "one check, as soon as the SDK's call has returned")
    }
}
