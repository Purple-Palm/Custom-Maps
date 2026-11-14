package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the engine listens to the electronic horizon and stops: once the trip session runs, at the switch, at the session's
 * stop outside the app, at the drive's end and when the navigator goes; and where its reading reaches the debug log. The
 * engine needs the navigator, so these statements are pinned by its source ([SourceText]), as the plan's lookup is; what
 * they do is HorizonReaderTest's and HorizonCountsTest's.
 */
class HorizonWiringTest {

    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()
    private val reader = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/HorizonReader.kt").readText()

    private fun body(declaration: String) = SourceText.body(engine, declaration).map { it.trim() }

    @Test
    fun `it listens only with the switch on, during guidance, while the trip session runs`() {
        val update = body("private fun updateHorizon()")
        assertTrue(
            "val wanted = HorizonListening.wanted(laneCountsHorizon, _guidanceState.value == GuidanceState.ACTIVE, tripSessionRunning)" in update,
        )
        assertTrue("horizon.listen(navigation.takeIf { wanted })" in update)
        // The switch's state before the setting is read is none, which is off.
        assertTrue("private var laneCountsHorizon: Boolean? = null" in engine.lines().map { it.trim() })
    }

    @Test
    fun `the trip session's start, the switch and a navigator that comes ask again, after the session runs`() {
        val start = body("is GuidanceCommand.StartTripSession -> {")
        val running = start.indexOf("tripSessionRunning = true")
        assertTrue(running >= 0)
        assertTrue(start.indexOf("updateHorizon()") > running, "after the trip session runs")
        assertTrue(SourceText.hasStatement(engine, "internal fun setLaneCountsHorizon(on: Boolean)", "updateHorizon()"))
        assertTrue(SourceText.hasStatement(engine, "override fun onAttached(mapboxNavigation: MapboxNavigation)", "updateHorizon()"))
        assertTrue(SourceText.hasStatement(engine, "private fun onTripSessionStoppedOutsideApp()", "updateHorizon()"))
        val outside = body("private fun onTripSessionStoppedOutsideApp()")
        assertTrue(outside.indexOf("tripSessionRunning = false") in 0 until outside.indexOf("updateHorizon()"))
    }

    @Test
    fun `the switch is stored as it comes before the engine asks again, and what was held goes once it stops listening`() {
        // The switch's own value, unconditionally, then the question whether to listen.
        val set = "internal fun setLaneCountsHorizon(on: Boolean)"
        assertTrue(SourceText.hasStatement(engine, set, "laneCountsHorizon = on"))
        val setBody = body(set)
        assertTrue(setBody.indexOf("laneCountsHorizon = on") in 0 until setBody.indexOf("updateHorizon()"), "stored before it is read")
        // Once not listening, by the switch or any other condition, the stretches held go with it.
        val update = "private fun updateHorizon()"
        assertTrue(SourceText.hasStatement(engine, update, "if (!horizon.listening) routeHorizon.clear()"))
        val updateBody = body(update)
        assertTrue(updateBody.indexOf("horizon.listen(navigation.takeIf { wanted })") in 0 until updateBody.indexOf("if (!horizon.listening) routeHorizon.clear()"))
    }

    @Test
    fun `each update the reader lays out goes to the route's holder, and the car's station carries its moment`() {
        // The reader's result reaches what the map's own line asks for its next window.
        val result = "override fun onResult(result: HorizonResult) {"
        assertTrue(SourceText.hasStatement(engine, result, "routeHorizon.take(result.ahead)"))
        val resultBody = body(result)
        assertTrue(resultBody.indexOf("if (_guidanceState.value != GuidanceState.ACTIVE) return") in 0 until resultBody.indexOf("routeHorizon.take(result.ahead)"))
        // The plan's lookup takes the station's time with the station: a horizon update moves it on from there.
        val look = "private fun lookUpPlan(progress: RouteProgress)"
        assertTrue(SourceText.hasStatement(engine, look, "lanePlanStation = car"))
        assertTrue(SourceText.hasStatement(engine, look, "lanePlanStationNanos = SystemClock.elapsedRealtimeNanos()"))
        val lookBody = body(look)
        assertEquals(lookBody.indexOf("lanePlanStation = car") + 1, lookBody.indexOf("lanePlanStationNanos = SystemClock.elapsedRealtimeNanos()"))
        assertTrue(
            "val age = (SystemClock.elapsedRealtimeNanos() - lanePlanStationNanos) / NANOS_PER_SECOND" in body("private fun horizonCar(): HorizonCar?"),
        )
    }

    @Test
    fun `the drive's end stops it before its summary is added up, and the navigator's going stops it too`() {
        val stop = body("GuidanceCommand.StopTripSession -> {")
        val end = stop.indexOf("endHorizon()")
        assertTrue(end >= 0)
        assertTrue(end < stop.indexOf("endTripTally()"), "before the summary")
        assertTrue(end < stop.indexOf("navigation?.stopTripSession()"), "before the session stops")
        assertTrue("horizon.endDrive()" in body("private fun endHorizon()"))
        val detached = body("override fun onDetached(mapboxNavigation: MapboxNavigation)")
        assertTrue(detached.indexOf("horizon.listen(null)") in 0 until detached.indexOf("navigation = null"))
    }

    @Test
    fun `the motion object gains ehLanes and the progress line the observer's time, and nothing else of the horizon`() {
        assertTrue("if (window == null) emptyMap() else mapOf(\"motion\" to window + horizon.ehLanesField())" in body("private fun motionField(progress: RouteProgress, active: Boolean): Map<String, Any?> = try {"))
        assertTrue(") + speedLimitChange(active) + motion + horizonTime()," in body("private val routeProgressObserver = RouteProgressObserver { progress ->"))
        assertTrue("private fun horizonTime(): Map<String, Any?> = horizon.takeObserverTime()?.let { mapOf(\"horizon\" to it) } ?: emptyMap()" in engine.lines().map { it.trim() })
    }

    @Test
    fun `the horizon's reading sends nothing, starts and stops no trip session and makes no sound`() {
        val code = SourceText.code(reader)
        for (call in listOf(
            "requestRoutes", "setNavigationRoutes", "startTripSession", "stopTripSession", "resetTripSession",
            "TextToSpeech", "AudioTrack", "MediaPlayer", "SoundPool", "ToneGenerator", "AudioFocus", "VoiceInstructionsObserver",
            "getEdgeShape", "getPathShape", "getGraphPositionCoordinate", "getAdasisEdgeAttributes", "names", "countryCode", "stateCode",
        )) {
            assertFalse(call in code, call)
        }
        // The edges' metadata only, read on the worker: the engine hands the reader nothing else of the graph.
        assertTrue("metadata = { navigator, id -> navigator.graphAccessor.getEdgeMetadata(id)?.toHorizonEdge() }," in engine.lines().map { it.trim() })
        assertTrue("worker = routeLines," in engine.lines().map { it.trim() })
    }
}
