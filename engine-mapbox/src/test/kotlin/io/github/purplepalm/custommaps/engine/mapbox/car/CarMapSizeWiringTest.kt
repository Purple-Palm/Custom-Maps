package io.github.purplepalm.custommaps.engine.mapbox.car

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the car map's size ([io.github.purplepalm.custommaps.nav.car.CarMapSize]) reaches the map on the car's surface:
 * the one options object the map library keeps, at the host's density times the size; a new size through the car's
 * resize ([io.github.purplepalm.custommaps.nav.car.CarMapResize]), which makes the map of the surface there is again by
 * a replay of the host's surface; and the detach and the attach in between, which keep the old map's camera and the
 * engine's count of a shown surface. The resize's rules are CarMapResizeTest's and the replay GuardedSurfaceCallbackTest's;
 * the car map needs a car's surface, so these statements are pinned by its source ([SourceText]).
 */
class CarMapSizeWiringTest {

    private val car = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/car/CarNavigationMap.kt").readText()

    private fun body(declaration: String) = SourceText.body(car, declaration).map { it.trim() }

    @Test
    fun `every surface's map is made from the one options object, at the host's density times the size`() {
        assertTrue("private val mapInitOptions = initOptions(CarMapSize.DEFAULT)" in SourceText.code(car).lines().map { it.trim() })
        assertTrue("val callback = carMap.prepareSurfaceCallback(carContext, mapInitOptions)" in body("init {"))
        assertTrue("mapOptions = mapOptions(size)," in body("private fun initOptions(size: CarMapSize)"))
        val options = SourceText.code(car).substringAfter("private fun mapOptions(size: CarMapSize): MapOptions =").substringBefore("\n\n").trim()
        assertEquals("MapOptions.Builder().applyDefaultParams(size.pixelRatio(hostDensity())).contextMode(ContextMode.SHARED).build()", options)
        // A new size changes the options the library reads at its next surface; never a second surface callback.
        assertTrue("mapInitOptions.mapOptions = mapOptions(size)" in body("override fun useSize(size: CarMapSize)"))
        assertEquals(1, Regex("prepareSurfaceCallback\\(").findAll(SourceText.code(car)).count())
    }

    @Test
    fun `a new size goes through the resize, whose remake replays the host's surface, and says what became of it`() {
        val set = "override fun setMapSize(size: CarMapSize)"
        assertTrue(SourceText.hasStatement(car, set, "if (released) return"))
        val inside = body(set)
        assertTrue("val outcome = resize.request(size) ?: return@guard" in inside, "$inside")
        assertTrue("log.log(SURFACE_LINE, surfaceFields(EVENT_SIZE) + (\"outcome\" to outcome.logName))" in inside, "$inside")
        // A remake that leaves no map on the surface gives the phone its map back.
        assertTrue("if (carSurface == null) showsSurface(false)" in inside, "$inside")
        assertTrue("val handed = surfaceCallback?.replay { renewed(it) } ?: false" in body("override fun remake(): Boolean"))
        assertTrue("surfaceCallback = guarded" in body("init {"))
    }

    @Test
    fun `a remake's detach keeps the old map's camera and the engine's count, and its attach records the size`() {
        val detach = body("private fun detach(surface: MapboxCarMapSurface)")
        val kept = detach.indexOf("if (resize.remaking) leftCamera = resize.keptCamera")
        assertTrue(kept > detach.indexOf("leftCamera = null"), "after the host's own case: $detach")
        assertTrue("if (!resize.remaking) showsSurface(false)" in detach, "$detach")
        assertTrue("showsSurface(false)" !in detach, "never told false through a remake: $detach")
        val attach = body("private fun attach(surface: MapboxCarMapSurface)")
        val size = attach.indexOf("val size = resize.size")
        assertTrue(size >= 0 && attach[size + 1] == "surfaceSize = size", "$attach")
        assertTrue(size < attach.indexOfFirst { it.startsWith("val created = MapScene(") }, "$attach")
        // The camera the new map starts from: the one the detach kept.
        assertTrue("(leftCamera ?: lastPositionCamera())?.let(mapSurface.mapboxMap::setCamera)" in attach)
    }

    @Test
    fun `the surface lines carry the size and the map's pixel ratio, and so does the car's camera line`() {
        val fields = body("private fun surfaceFields(event: String): Map<String, Any?>")
        assertTrue("\"mapSize\" to (surfaceSize ?: resize.size).factor," in fields, "$fields")
        assertTrue(
            "\"pixelRatio\" to Motion.round((carSurface?.mapSurface?.mapboxMap?.pixelRatio ?: resize.size.pixelRatio(hostDensity())).toDouble(), 3)," in fields,
            "$fields",
        )
        val camera = body("override fun cameraFields(): Map<String, Any?>")
        assertTrue("\"pixelRatio\" to carSurface?.mapSurface?.mapboxMap?.pixelRatio?.let { Motion.round(it.toDouble(), 3) }," in camera, "$camera")
    }
}
