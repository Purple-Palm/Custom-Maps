package io.github.purplepalm.custommaps.engine.mapbox.car

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where Settings' Turns from above reaches the car's camera: the car map keeps the switch, on until told otherwise, hands
 * it to the scene of the surface there is and to every later one, and the scene keeps it for the camera there is and every
 * camera it makes. The car map needs a car's surface and the scene a map, so these statements are pinned by their source
 * ([SourceText]).
 */
class CarTurnsFromAboveWiringTest {

    private val dir = "src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox"
    private val car = File("$dir/car/CarNavigationMap.kt").readText()
    private val scene = File("$dir/map/MapScene.kt").readText()

    private fun body(text: String, declaration: String) = SourceText.body(text, declaration).map { it.trim() }

    @Test
    fun `the car map keeps the switch for every surface and hands it to the scene there is`() {
        assertTrue("private var turnsFromAbove = true" in SourceText.code(car).lines().map { it.trim() }, "on until told otherwise")
        assertEquals(
            listOf("turnsFromAbove = enabled", "guard(\"setTurnsFromAbove\") { scene?.setTurnsFromAbove(enabled) }"),
            body(car, "override fun setTurnsFromAbove(enabled: Boolean)").filter { it.isNotBlank() },
        )
        // A new surface's scene takes it with the camera style and the lane zoom, before anything of the drive.
        val attach = body(car, "private fun attach(surface: MapboxCarMapSurface)")
        val laneZoom = attach.indexOf("created.setLaneZoom(laneZoom)")
        assertTrue(laneZoom >= 0 && attach[laneZoom + 1] == "created.setTurnsFromAbove(turnsFromAbove)", "$attach")
    }

    @Test
    fun `the scene keeps the switch for the camera there is and every camera it makes`() {
        assertTrue("private var turnsFromAbove = true" in SourceText.code(scene).lines().map { it.trim() }, "on until told otherwise")
        assertEquals(
            listOf("if (enabled == turnsFromAbove) return", "turnsFromAbove = enabled", "camera.setTurnsFromAbove(enabled)"),
            body(scene, "fun setTurnsFromAbove(enabled: Boolean)").filter { it.isNotBlank() },
        )
        val create = body(scene, "private fun createCamera(style: CameraStyle): GuidanceCamera")
        val laneZoom = create.indexOf("created.setLaneZoom(laneZoom)")
        assertTrue(laneZoom >= 0 && create[laneZoom + 1] == "created.setTurnsFromAbove(turnsFromAbove)", "$create")
        // The property is set before the scene's first camera is made.
        val code = SourceText.code(scene)
        assertTrue(code.indexOf("private var turnsFromAbove = true") < code.indexOf("camera = createCamera(cameraStyle)"))
    }
}
