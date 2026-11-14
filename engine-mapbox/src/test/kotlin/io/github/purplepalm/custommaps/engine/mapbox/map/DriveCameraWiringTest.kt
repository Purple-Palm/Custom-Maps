package io.github.purplepalm.custommaps.engine.mapbox.map

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the map carries out what its drive views want ([io.github.purplepalm.custommaps.nav.camera.DriveCamera]): the start,
 * each progress update after the camera's own, the seek, the map put away and back, the hold of the map and its clock. A map
 * needs a MapView, the navigation app and a style, so the scene is pinned here by its source ([SourceText]); what the drive
 * camera decides is DriveCameraTest's and DriveViewPlanTest's.
 */
class DriveCameraWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
    private val phone = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/PhoneMap.kt").readText()

    private fun body(declaration: String) = SourceText.body(scene, declaration).map { it.trim() }

    @Test
    fun `guidance starts with the drive views' first answer, handed on always, and render forces no following`() {
        val render = body("private fun render(state: GuidanceState)")
        val start = "driveCamera.onGuidanceStarted(driveNow(), fromPreview = before == GuidanceState.PREVIEW)?.let { wish ->"
        assertTrue(start in render)
        assertEquals("carryOut(wish, BY_START, WHY_START, always = true)", render[render.indexOf(start) + 1])
        assertFalse(render.any { "camera.requestFollowing()" in it }, "the start asks the drive views, not the camera")
        // The render before is kept before anything of this one runs.
        assertTrue(render.indexOf("val before = rendered") in 0 until render.indexOf("rendered = state"))
        assertTrue(render.indexOf("driveCamera.onGuidanceEnded(driveNow())") >= 0)
    }

    @Test
    fun `each progress update decides after the camera's own and behind the guard of an old position`() {
        val progress = body("private fun onRouteProgress(progress: RouteProgress)")
        val guard = progress.indexOf("if (guidanceState != GuidanceState.ACTIVE || oldPosition) return")
        val camera = progress.indexOfFirst { it.startsWith("camera.onRouteProgress(") }
        val plan = progress.indexOf("if (!away) carryOut(driveCamera.onProgress(engine.viewAhead(progress)), BY_PLAN, WHY_PLAN)")
        val close = progress.indexOf("_closeView.value = camera.closeView")
        assertTrue(guard == 0, "an update the guard holds reaches neither")
        assertTrue(camera in 1 until plan, "the camera has its frame of the update first")
        assertTrue(plan in 1 until close, "the close view is read after what the drive views asked")
    }

    @Test
    fun `a seek decides afresh, after the guard of an old position, and a camera is asked only for a new wish`() {
        val located = body("override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult)")
        val guard = located.indexOf("if (oldPosition) return")
        val jump = located.indexOf("driveCamera.onJump(driveNow())")
        assertTrue(guard in 0 until jump)
        val carry = body("private fun carryOut(wish: CameraWish?, by: String, why: String, always: Boolean = false, extra: Map<String, Any?> = emptyMap())")
        assertTrue("if (wish != null && (always || wish != handedWish)) {" in carry)
        // The driver's gesture took the map already: leaving it asks nothing.
        assertTrue("CameraWish.LEAVE -> Unit" in carry)
    }

    @Test
    fun `the map put away does no work for the drive views, and back it decides afresh and asks anew`() {
        val away = body("fun setAway(away: Boolean)")
        assertTrue("driveCamera.onAway(driveNow())" in away)
        val back = away.indexOf("driveCamera.onJump(driveNow())")
        assertTrue(back >= 0, "$away")
        // Back, the plan decides on the update the map heard last, none the guard of an old position holds, before the
        // camera is asked: else it would be asked for the wish of before, and for the car's only at the next update.
        assertEquals("if (!oldPosition) lastProgress?.let { driveCamera.onProgress(engine.viewAhead(it)) }", away[back + 1])
        assertEquals("carryOut(driveCamera.wish, BY_START, WHY_BACK, always = true)", away[back + 2])
        // While away the map still keeps each update the guard lets through, for that return.
        val progress = body("private fun onRouteProgress(progress: RouteProgress)")
        assertTrue(progress.indexOf("lastProgress = progress") in 1 until progress.indexOf("if (!away) carryOut(driveCamera.onProgress(engine.viewAhead(progress)), BY_PLAN, WHY_PLAN)"))
        // The hold's clock stops while the map is away, and with the hold.
        val ticker = body("private fun startHoldTicker()")
        assertTrue("if (away || guidanceState != GuidanceState.ACTIVE) break" in ticker)
        assertTrue("if (control?.secondsLeft == null) break" in ticker)
        assertTrue("delay(HOLD_TICK_MS)" in ticker)
    }

    @Test
    fun `settings change the drive views at once, and a map put away asks its camera for nothing`() {
        val settings = body("override fun setDriveCamera(settings: DriveCameraSettings)")
        assertEquals("val wish = driveCamera.setSettings(settings, driveNow())", settings.first())
        assertTrue(
            "if (guidanceState == GuidanceState.ACTIVE && !away) carryOut(wish, BY_DRIVER, WHY_SETTINGS) else publishDriveControls()" in settings,
            "$settings",
        )
    }

    @Test
    fun `a map button during a drive switches the drive view only from the other one, else it recenters`() {
        // The car's strip: every map button during guidance goes through the drive views.
        val mode = body("override fun setCameraMode(mode: CameraMode)")
        assertEquals(listOf("if (guidanceState == GuidanceState.ACTIVE) {", "chooseDriveView(mode)", "return"), mode.subList(0, 3))
        val choose = body("private fun chooseDriveView(mode: CameraMode)")
        assertTrue("val view = driveCamera.driveViewControl().driveView" in choose)
        // The overview switch from Normal only: in the Overview already, as while it is down for a turn, it changes nothing,
        // and the view goes out by itself after the turn.
        assertTrue("CameraMode.OVERVIEW -> if (view == DriveView.NORMAL) toggle(now)" in choose, "$choose")
        // Following leaves the Overview drive view; in Normal it is Recenter, which ends a hold with the car at once.
        assertTrue(
            "CameraMode.FOLLOWING -> if (view == DriveView.OVERVIEW) toggle(now) else carryOut(driveCamera.onRecenter(now), BY_DRIVER, WHY_RECENTER_TAP)" in choose,
            "$choose",
        )
        assertEquals(2, choose.count { "toggle(now)" in it }, "$choose")
    }

    @Test
    fun `a camera made anew during a drive is asked for what the drive views want`() {
        val style = body("override fun setCameraStyle(style: CameraStyle)")
        val ask = style.indexOf("carryOut(driveCamera.wish, BY_STYLE, WHY_STYLE, always = true)")
        assertTrue(style.indexOf("handedWish = null") in 0 until ask)
        assertTrue(style.indexOf("camera = createCamera(style)") in 0 until ask)
    }

    @Test
    fun `the phone's finger listener only looks`() {
        val fingers = SourceText.body(phone, "private fun watchFingers()").map { it.trim() }
        assertTrue("MotionEvent.ACTION_DOWN -> scene.onFinger(true)" in fingers)
        assertTrue("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scene.onFinger(false)" in fingers)
        assertEquals("false", fingers.last { it.isNotBlank() && it != "}" })
    }

    @Test
    fun `the drive views move only the camera, ask for no route and touch no trip`() {
        val code = SourceText.code(scene)
        for (call in listOf("requestRoutes(", "switchToAlternativeRoute(", "startTripSession(", "stopTripSession(", "resetTripSession(")) {
            assertFalse(call in code, call)
        }
        // The route line draws the navigator's routes; nothing sets them on the navigator.
        val sets = Regex("([A-Za-z]+)\\??\\.setNavigationRoutes\\(").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("routeLineApi"), sets)
    }
}
