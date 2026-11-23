package io.github.purplepalm.custommaps.engine.mapbox.map.camera

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smooth follow anchored on the car ([io.github.purplepalm.custommaps.nav.camera.CameraTuning.anchored]): the data source
 * frames no maneuver and gets no junction's points, the camera works out the road view and the junction's fit itself once
 * per update, and the phone's path is left as it was. The camera needs a map, so it is pinned here by its source
 * ([SourceText]); the numbers are core-nav's RoadViewTest, JunctionFitTest, LaneZoomTest and CameraMotionTest, and the
 * junction's points CarJunctionTest's.
 */
class AnchoredCameraWiringTest {

    private val dir = "src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox"
    private val camera = File("$dir/map/camera/SmoothNavigationCamera.kt").readText()
    private val frames = File("$dir/map/camera/LaneZoomFrames.kt").readText()
    private val scene = File("$dir/map/MapScene.kt").readText()
    private val car = File("$dir/car/CarNavigationMap.kt").readText()

    /** The trimmed lines of the body of [declaration] in [text], also for a declaration whose parameters span lines. */
    private fun body(text: String, declaration: String): List<String> {
        val lines = SourceText.code(text).lines()
        val at = lines.indexOfFirst { it.trim().startsWith(declaration) }
        require(at >= 0) { "No $declaration" }
        val indent = lines[at].takeWhile { it == ' ' }.length
        val open = if (lines[at].trimEnd().endsWith("{")) {
            at
        } else {
            (at + 1 until lines.size).first { lines[it].takeWhile { c -> c == ' ' }.length == indent && lines[it].trim().startsWith(")") }
        }
        return lines.drop(open + 1).takeWhile { it.isBlank() || it.takeWhile { c -> c == ' ' }.length > indent }.map { it.trim() }
    }

    @Test
    fun `the data source frames a maneuver only as the camera decided, never for the anchored camera`() {
        val progress = body(camera, "override fun onRouteProgress(")
        assertTrue("dataSource.isFramingManeuverPropertyOverride(LaneZoomFrames.sdkFramesManeuver(tuning, maneuverFocus.framesManeuver))" in progress)
        // Its limit and the junction's points follow the same rule, at the map's setup and before each frame.
        val setup = body(frames, "fun applySmoothFollow(")
        assertTrue("val maneuver = large && sdkFramesManeuver(tuning, framesManeuver(framed, options))" in setup)
        val before = body(frames, "fun beforeSmoothEvaluate(")
        assertTrue("val maneuver = mode == LaneZoom.LARGE && sdkFramesManeuver(tuning, framesManeuver)" in before)
        assertTrue("junction.update(progress, mode, maneuver, coalesceMeters(options), tuning)" in before)
        // The Mapbox tuned style frames by the SDK's own rule as before.
        val tuned = body(frames, "fun applyTuned(")
        assertFalse(tuned.any { "sdkFramesManeuver" in it })
    }

    @Test
    fun `a camera made anew on the car takes back a maneuver frame before it hears its first frame`() {
        val init = body(camera, "init {")
        val anchor = init.indexOf("if (tuning.anchored) anchorFrames()")
        val observe = init.indexOf("dataSource.registerUpdateObserver(dataObserver)")
        assertTrue(anchor in 0 until observe, "$init")
        val anchorFrames = body(camera, "private fun anchorFrames()")
        assertEquals(listOf("dataSource.isFramingManeuverPropertyOverride(false)", "dataSource.evaluate()"), anchorFrames.filter { it.isNotBlank() })
    }

    @Test
    fun `each frame of the data source gives the anchored camera the road view and the junction's fit, the phone its fit`() {
        val sample = body(camera, "private fun applySample(")
        val anchored = sample.indexOf("if (mode == Mode.FOLLOWING && tuning.anchored) {")
        val phone = sample.indexOf("zoomRamp.push(if (carOnFocalPoint && laneZoom == LaneZoom.LARGE) min(fitted, tuning.defaultMaxZoom) else fitted, now)")
        assertTrue(anchored in 0 until phone, "the phone's zoom is the data source's fit, after the anchored branch")
        val branch = sample.subList(anchored, phone)
        assertTrue("roadZoom = roadViewZoom(latitude)" in branch)
        assertTrue("pushJunctionFit(target, carOnFocalPoint, now)" in branch)
        assertTrue("return" in branch)
        // Before the drive's first position nothing is pushed: a camera made anew during a drive stays where it starts.
        assertEquals("if (!located) return", branch[1])
        // The road view at the car's tilt for the highest speed of the last few seconds, once per update, with its position.
        assertTrue("private val roadPeak = PeakSpeed(tuning.roadPeakSeconds)" in camera)
        val located = body(camera, "override fun onLocation(location: Location, isTeleport: Boolean)")
        val speed = located.indexOf("speedMps = location.speed ?: speedMps")
        assertTrue(speed in 0 until located.indexOf("roadSpeed = roadPeak.onSpeed(now(), speedMps)"))
        assertTrue("located = true" in located)
        assertTrue("located = false" in body(camera, "override fun onGuidanceEnded()"))
        val progress = body(camera, "override fun onRouteProgress(")
        assertTrue("if (laneZoom == LaneZoom.LARGE) carJunction.update(progress, maneuverFocus.latchedJunction, tuning) else carJunction.clear()" in progress)
    }

    @Test
    fun `the road view and the junction's fit take the following box, the frame's tilt and the map's own pixel ratio`() {
        // The road view: the box from the car's row up to the top padding of this map, at the map's own ratio (the
        // host's density times the car map's size), never the host's density alone.
        val road = body(camera, "private fun roadViewZoom(latitude: Double): Double")
        assertEquals(
            listOf(
                "if (!mapboxMap.isValid()) return Double.NaN",
                "val padding = dataSource.followingPadding",
                "val height = mapboxMap.getSize().height.toDouble()",
                "return RoadView.zoom(tuning, roadSpeed, height, height - padding.bottom, padding.top, mapboxMap.getMapOptions().pixelRatio.toDouble(), latitude)",
            ),
            road.filter { it.isNotBlank() },
        )
        // The junction's fit: the same box and ratio, the frame's bearing and tilt, the box's half-width between the side
        // paddings (the routing card's included), and each point ahead as far farther as the camera may trail the car.
        val push = body(camera, "private fun pushJunctionFit(")
        assertTrue("val heading = target.bearing ?: if (bearingRamp.ready) bearingRamp.value(now) else bearing.value" in push, "$push")
        val padding = push.indexOf("val padding = dataSource.followingPadding")
        assertTrue(padding >= 0, "$push")
        assertEquals(listOf("val size = mapboxMap.getSize()", "val height = size.height.toDouble()"), push.subList(padding + 1, padding + 3), "$push")
        val call = push.indexOf("val fit = carJunction.zoomAt(")
        val end = push.subList(call + 1, push.size).indexOf(")")
        assertTrue(call > padding && end >= 0, "$push")
        assertEquals(
            listOf(
                "latitude = center.latitude(),",
                "longitude = center.longitude(),",
                "bearing = heading,",
                "pitch = target.pitch ?: tuning.cruisePitch,",
                "mapHeightPx = height,",
                "carRowPx = height - padding.bottom,",
                "rowPx = padding.top,",
                "halfWidthPx = (size.width.toDouble() - padding.left - padding.right) / 2.0,",
                "pixelRatio = mapboxMap.getMapOptions().pixelRatio.toDouble(),",
                "lagMeters = tuning.maxPuckLagDp * MapScale.metersPerDp(zoom.value, center.latitude()),",
            ),
            push.subList(call + 1, call + 1 + end),
        )
    }

    @Test
    fun `the anchored camera plans its zoom with Large only, and Default is the road view`() {
        val targets = body(camera, "private fun computeTargets(")
        val anchored = targets.indexOf("val zoomGoal = if (largeFollowing && tuning.anchored) {")
        val phone = targets.indexOf("} else if (largeFollowing) {")
        assertTrue(anchored in 0 until phone)
        assertTrue(targets.subList(anchored, phone).any { it.startsWith("zoomPlanner.anchoredTarget(") })
        assertTrue("val fit = if (fitRamp.ready && maneuverFocus.framesItself(now)) {" in targets)
        // With Default the zoom is the frame's, which on the car is the road view.
        val otherwise = phone + targets.subList(phone, targets.size).indexOf("} else {")
        assertEquals("frameZoom", targets[otherwise + 1])
    }

    @Test
    fun `a seek, the end of guidance and Default forget the junction and the road view's speeds`() {
        for (declaration in listOf("override fun onJump()", "override fun onGuidanceEnded()")) {
            val lines = body(camera, declaration)
            assertTrue("carJunction.clear()" in lines, declaration)
            assertTrue("forgetJunctionFit()" in lines, declaration)
            assertTrue("forgetSpeeds()" in lines, declaration)
        }
        val forget = body(camera, "private fun forgetSpeeds()")
        assertTrue("roadPeak.reset()" in forget && "roadSpeed = null" in forget)
        val laneZoom = body(camera, "override fun setLaneZoom(mode: LaneZoom)")
        assertTrue("carJunction.clear()" in laneZoom && "forgetJunctionFit()" in laneZoom)
    }

    @Test
    fun `the car's camera line has the road view and the junction's zoom, the phone's line nothing new`() {
        val diagnostics = body(camera, "override fun diagnostics()")
        assertTrue(") + if (tuning.anchored) anchoredFields() else emptyMap()" in diagnostics)
        val fields = SourceText.code(camera).substringAfter("private fun anchoredFields()").substringBefore("override fun settling()")
        for (key in listOf("\"roadAheadM\"", "\"roadSpeed\"", "\"roadZoom\"", "\"junctionZoom\"")) assertTrue(key in fields, key)
        // The ratio is the car's own field of the line.
        assertTrue("\"pixelRatio\" to carSurface?.mapSurface?.mapboxMap?.pixelRatio?.let { Motion.round(it.toDouble(), 3) }," in car)
    }

    @Test
    fun `the car's map takes the car's camera and the Mapbox styles the numbers they had`() {
        assertTrue("tuning = CameraTuning.CAR," in car)
        val create = body(scene, "private fun createCamera(style: CameraStyle): GuidanceCamera")
        assertTrue("tuning.forMapboxStyles()," in create)
        // Smooth follow takes the screen's own.
        val smooth = create.indexOf("SmoothNavigationCamera(")
        assertEquals("tuning,", create[smooth + 5])
    }
}
