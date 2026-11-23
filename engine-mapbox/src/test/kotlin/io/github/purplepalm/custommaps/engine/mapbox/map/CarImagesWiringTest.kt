package io.github.purplepalm.custommaps.engine.mapbox.map

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the car map's size reaches the images a map takes as bitmaps ([ImageScale]): each scene is made with the size of
 * its surface's map, its puck takes the size as its scale expression, the car the app draws itself as its three image
 * sizes, and the route line's end icons are drawn at the density times the size; all of it as the map is made, never as
 * a location-component setting during a drive (CarLayerTest scans for those). The maneuver arrow's heads keep the
 * navigation SDK's own scale, drawn at the map's pixel ratio. The scale itself is ImageScaleTest's; the scene needs a
 * map, so these statements are pinned by its source ([SourceText]).
 */
class CarImagesWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
    private val layer = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/CarLayer.kt").readText()
    private val car = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/car/CarNavigationMap.kt").readText()

    private val sceneCode = SourceText.code(scene).lines().map { it.trim() }

    @Test
    fun `each car scene is made with the size of its surface's map, and the phone's with none`() {
        val attach = SourceText.body(car, "private fun attach(surface: MapboxCarMapSurface)").map { it.trim() }
        assertTrue("imageScale = size.factor," in attach, "$attach")
        assertTrue("imageScale: Double = ImageScale.NONE," in sceneCode)
        val phone = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/PhoneMap.kt").readText()
        assertTrue("imageScale" !in SourceText.code(phone), "the phone's map keeps its images as they come")
    }

    @Test
    fun `the puck takes the size as its scale, and the car layer its dot's three sizes`() {
        assertTrue("scaleExpression = ImageScale.puckScaleJson(imageScale)," in sceneCode)
        assertTrue(
            "private val carLayer = CarLayer(CarLayer.on(context, mapboxMap, plugins.location, puck, imageScale), debugLog, later = { block -> scope.launch { block() } })" in
                sceneCode,
        )
        val add = SourceText.body(layer, "override fun addLayer()").map { it.trim() }
        for (size in listOf("\"top-image-size\" to dotSize,", "\"bearing-image-size\" to dotSize,", "\"shadow-image-size\" to dotSize,")) {
            assertTrue(size in add, "$size: $add")
        }
        assertTrue("set(\"top-image-size\", dotSize)" in SourceText.body(layer, "override fun showDot()").map { it.trim() })
        assertTrue("private val dotSize = Value.valueOf(imageScale)" in SourceText.code(layer).lines().map { it.trim() })
    }

    @Test
    fun `the route line's end icons come from a context at the density times the size, the arrow's heads from the SDK's own`() {
        assertTrue("MapboxRouteLineViewOptions.Builder(ImageScale.context(context, imageScale))" in sceneCode)
        // The arrow heads are drawn at the map's pixel ratio by the navigation SDK itself (RouteArrowUtils, ui-maps 3.35.2).
        assertTrue("RouteArrowOptions.Builder(context)" in sceneCode)
    }
}
