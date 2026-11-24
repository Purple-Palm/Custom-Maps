package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's junction views from above (Turns from above, [io.github.purplepalm.custommaps.nav.camera.JunctionView.ABOVE])
 * in Smooth follow: the tilt goes down to straight above along the focus, the data source frames the maneuver as the
 * camera decided with the lane zoom's limits, the road view and the junction's own fit go down with the tilt until then,
 * and a change of the switch tells the data source at once and moves the camera only during a junction view. The camera
 * needs a map, so it is pinned here by its source ([SourceText]); the rules are core-nav's CameraMotionTest,
 * JunctionViewTest, RoadViewTest and LaneZoomTest, the limits LaneZoomFramesTest's.
 */
class JunctionsFromAboveWiringTest {

    private val camera = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/camera/SmoothNavigationCamera.kt").readText()

    private fun body(declaration: String): List<String> = SourceText.body(camera, declaration).map { it.trim() }

    private val code = SourceText.code(camera).lines().map { it.trim() }

    @Test
    fun `the view from above is the car's with the switch on, its tilt straight down over the junction`() {
        assertTrue("private var turnsFromAbove = true" in code, "on until told otherwise")
        assertTrue("private val junctionView: JunctionView get() = JunctionView.of(tuning.anchored, turnsFromAbove)" in code)
        assertTrue("private val above: Boolean get() = tuning.anchored && turnsFromAbove" in code)
        assertTrue("private val junctionPitch: Double get() = tuning.junctionPitch(junctionView)" in code)
        // The tilt the data source fits its frames for, the tilt of every frame, and the tilt folded into a move.
        val progress = body("override fun onRouteProgress(")
        assertTrue("dataSource.followingPitchPropertyOverride(guidancePitch(tuning, laneShare, maneuverFocus.focusAt(later), junctionPitch))" in progress)
        assertTrue("Mode.FOLLOWING -> guidancePitch(tuning, laneShare, junctionFocus, junctionPitch)" in body("private fun computeTargets("))
        val fold = body("private fun foldTiltIntoMove(now: Double)")
        assertTrue("val before = guidancePitch(tuning, laneShare, maneuverFocus.focusAt(now), junctionPitch)" in fold)
        assertTrue("offset[PITCH] += (before - guidancePitch(tuning, laneShare, maneuverFocus.focusAt(now), junctionPitch)) / left" in fold)
        // No guidance tilt anywhere without the junction's own.
        val pitches = code.filter { "guidancePitch(" in it }
        // The data source's at each progress and at a change of the switch, the move's two, and every frame's.
        assertEquals(5, pitches.size, "$pitches")
        assertTrue(pitches.all { "junctionPitch)" in it }, "$pitches")
    }

    @Test
    fun `from above the data source's frame of the maneuver takes the junction fit's place on its ramp, with either lane zoom`() {
        val push = body("private fun pushJunctionFit(")
        assertTrue("if ((laneZoom != LaneZoom.LARGE && !above) || key == null || center == null || !mapboxMap.isValid()) {" in push, "$push")
        val maneuver = push.indexOf("if (!carOnFocalPoint) {")
        assertEquals(
            listOf("val framed = target.zoom", "if (above && framed != null) pushFit(key, min(framed, tuning.maxZoom), now) else forgetJunctionFit()", "return"),
            push.subList(maneuver + 1, maneuver + 4),
            "$push",
        )
        // Before the junction's own fit, which needs the car on its row.
        assertTrue(maneuver < push.indexOf("val fit = carJunction.zoomAt("), "$push")
        assertTrue("pushFit(key, min(fit, tuning.maxZoom), now)" in push)
        // The plan from above, the road view and the junction's frame from their ramps, from the latch on.
        val targets = body("private fun computeTargets(")
        val above = targets.indexOf("val zoomGoal = if (mode == Mode.FOLLOWING && above) {")
        val tilted = targets.indexOf("} else if (largeFollowing && tuning.anchored) {")
        val branch = targets.subList(above, tilted)
        assertTrue("val frame = if (fitRamp.ready && maneuverFocus.framesItself(now)) {" in branch, "$branch")
        assertTrue("zoomPlanner.aboveTarget(" in branch, "$branch")
        for (argument in listOf("mode = laneZoom,", "roadZoom = frameZoom,", "junctionFrame = frame,", "junctionFocus = junctionFocus,", "junctionKeep = maneuverFocus.keepAt(now),")) {
            assertTrue(argument in branch, "$argument: $branch")
        }
        // The junction is read with either lane zoom from above, and Default keeps it.
        assertTrue("if (!above) {" in body("override fun setLaneZoom(mode: LaneZoom)"))
    }

    @Test
    fun `from above the tilt waits for the zoom while the junction it went down for would leave the screen`() {
        // Set at a latch that tilts the view down, not at one made while it was down already; let go at a seek and the end.
        val progress = body("override fun onRouteProgress(")
        val latch = progress.indexOf("if (goalBefore < 1.0 && maneuverFocus.goal == 1.0) {")
        assertEquals(listOf("foldTiltIntoMove(now)", "descendingFor = maneuverFocus.latchedJunction"), progress.subList(latch + 1, latch + 3), "$progress")
        for (declaration in listOf("override fun onJump()", "override fun onGuidanceEnded()")) assertTrue("descendingFor = null" in body(declaration), declaration)
        // Within the mode only, after the focus's tilt.
        val targets = body("private fun computeTargets(")
        val floor = targets.indexOf("if (mode == Mode.FOLLOWING && above && steady) toPitch = max(toPitch, tiltFloor())")
        assertTrue(floor > targets.indexOf("Mode.FOLLOWING -> guidancePitch(tuning, laneShare, junctionFocus, junctionPitch)"), "$targets")
        assertTrue(floor < targets.indexOf("for (side in 0 until SIDES) toPad[side] = if (padRamp[side].ready) padRamp[side].value(now) else pad[side].value"))
        // The junction it went down for, while latched, at the camera's own centre, bearing, zoom and padding.
        val tilt = body("private fun tiltFloor(): Double").filter { it.isNotBlank() }
        assertEquals("val key = descendingFor ?: return 0.0", tilt[0])
        assertEquals("if (maneuverFocus.latchedJunction != key || maneuverFocus.goal < 1.0) return 0.0", tilt[1])
        assertTrue("val ahead = carJunction.metersAhead(lat.value, lng.value, bearing.value)" in tilt, "$tilt")
        assertTrue("val centerRow = pad[TOP].value + (height - pad[TOP].value - pad[BOTTOM].value) / 2.0" in tilt, "$tilt")
        assertTrue("val floor = LookAhead.pitchFor(ahead, zoom.value, height, centerRow, TILT_FLOOR_ROW_PX, mapPixelRatio, lat.value)" in tilt, "$tilt")
        // The map's height and ratio of the last progress: nothing asked of the map on a frame.
        assertTrue("val height = mapHeightPx" in tilt, "$tilt")
        val cap = body("private fun frameCapMeters(facts: ManeuverFacts): Double")
        assertTrue("mapHeightPx = size.height.toDouble()" in cap && "mapPixelRatio = mapboxMap.getMapOptions().pixelRatio.toDouble()" in cap, "$cap")
    }

    @Test
    fun `a change of the switch tells the data source at once and moves the camera only during a junction view`() {
        val set = body("override fun setTurnsFromAbove(enabled: Boolean)").filter { it.isNotBlank() }
        assertEquals(listOf("if (enabled == turnsFromAbove) return", "turnsFromAbove = enabled", "if (!tuning.anchored) return"), set.subList(0, 3))
        // What the planner and the fit held was the other kind's.
        assertTrue("zoomPlanner.reset()" in set && "forgetJunctionFit()" in set, "$set")
        val told = set.indexOf("lastProgress?.let { progress ->")
        val evaluate = set.indexOf("dataSource.evaluate()")
        assertTrue(told >= 0 && evaluate > told, "$set")
        val tellings = set.subList(told + 1, evaluate)
        for (call in listOf(
            "if (laneZoom == LaneZoom.LARGE || above) carJunction.update(progress, maneuverFocus.latchedJunction, tuning) else carJunction.clear()",
            "dataSource.followingPitchPropertyOverride(guidancePitch(tuning, laneShare, maneuverFocus.focusAt(later), junctionPitch))",
            "dataSource.isFramingManeuverPropertyOverride(LaneZoomFrames.sdkFramesManeuver(tuning, maneuverFocus.framesManeuver, junctionView))",
            "LaneZoomFrames.beforeSmoothEvaluate(dataSource, laneZoom, tuning, progress, junction, maneuverFocus.framesManeuver, junctionView)",
        )) {
            assertTrue(call in tellings, "$call: $set")
        }
        // One eased move, from the frame the data source computed again, only while a junction view is on.
        val move = set.indexOf("if (mode == Mode.FOLLOWING && maneuverFocus.inView(now)) beginTransition(mode, again = true)")
        assertTrue(move > evaluate, "$set")
        assertEquals("requestFrame()", set.last())
        // The move starts from the frame the data source has now.
        assertTrue("latestData?.let { data -> applySample(data, now) }" in body("private fun beginTransition("))
    }

    @Test
    fun `the car's line says how a latched junction view shows the junction`() {
        val fields = SourceText.code(camera).substringAfter("private fun anchoredFields()").substringBefore("override fun settling()")
        assertTrue("\"junctionView\" to junctionView.logName.takeIf { maneuverFocus.latchedJunction != null }," in fields.lines().map { it.trim() })
        assertTrue("(largeFollowing || (above && mode == Mode.FOLLOWING))" in fields, "from above the junction's zoom with Default too")
    }
}
