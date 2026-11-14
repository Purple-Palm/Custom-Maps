package io.github.purplepalm.custommaps.engine.mapbox.car

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The car's map keeps Recenter by itself at Off: the car's host owns its pan mode and its map buttons carry no count, so
 * after Pan, a scroll or a zoom the map stays where the driver put it until Recenter, with no move by itself. The drive views'
 * settings come from the phone with its count; the car map keeps them without it, from the start and for every surface after.
 * What a hold does with Recenter by itself at Off is MapHoldTest's and DriveCameraTest's; the car map needs a car's surface,
 * so these statements are pinned by their source ([SourceText]), as its light and its frame cap are.
 */
class CarDriveCameraWiringTest {

    private val car = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/car/CarNavigationMap.kt").readText()
    private val lines = SourceText.code(car).lines().map { it.trim() }

    @Test
    fun `the car's map never counts down, whatever the phone's Recenter by itself is`() {
        // Before the phone's settings come, and the phone's kept without their count.
        assertTrue("private var driveSettings = DriveCameraSettings(recenterSeconds = 0)" in lines)
        val set = "override fun setDriveCamera(settings: DriveCameraSettings)"
        assertTrue(SourceText.hasStatement(car, set, "driveSettings = settings.copy(recenterSeconds = 0)"))
        assertEquals(listOf("driveSettings = settings.copy(recenterSeconds = 0)"), lines.filter { it.startsWith("driveSettings =") })
        // Every scene gets the settings as kept: the one there is, and each new surface's before its drive goes on.
        assertTrue(SourceText.hasStatement(car, set, "guard(\"setDriveCamera\") { scene?.setDriveCamera(driveSettings) }"))
        assertTrue(SourceText.hasStatement(car, "private fun attach(surface: MapboxCarMapSurface)", "created.setDriveCamera(driveSettings)"))
        val handed = lines.filter { ".setDriveCamera(" in it }
        assertEquals(2, handed.size, "$handed")
        assertTrue(handed.all { "setDriveCamera(driveSettings)" in it }, "$handed")
        // And no count on the car's screen.
        assertTrue("override val recenterControl: StateFlow<RecenterControl?> = MutableStateFlow<RecenterControl?>(null).asStateFlow()" in lines)
    }
}
