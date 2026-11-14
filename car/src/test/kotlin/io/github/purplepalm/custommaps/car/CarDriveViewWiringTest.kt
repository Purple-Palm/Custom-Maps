package io.github.purplepalm.custommaps.car

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car's map takes the phone's drive view and Zoom in before turns as they are stored, every later change at once, as it
 * takes the camera style and the lane zoom; Recenter by itself stays the phone's (CarNavigationMap sets it to none). The
 * session needs the car's host, so the statement is pinned by its source; what the car's map does with the settings is
 * the engine's DriveCameraWiringTest's.
 */
class CarDriveViewWiringTest {

    /** The session's source with its comments taken out: what the compiler reads. */
    private val session = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarSession.kt").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private val parts = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarParts.kt").readText()

    @Test
    fun `each car map follows the stored drive camera settings with the other settings it follows`() {
        val build = session.substringAfter("private fun buildMap(").substringBefore("private fun releaseMap(")
        val follows = build.lines().map { it.trim() }.filter { it.startsWith("follow(settings.") }
        assertTrue("follow(settings.driveCamera, \"setDriveCamera\", created::setDriveCamera)" in follows, "$follows")
        // With the camera style it frames by: the same map, from the moment it is made.
        assertTrue(follows.indexOf("follow(settings.cameraStyle, \"setCameraStyle\", created::setCameraStyle)") >= 0, "$follows")
        assertTrue("val driveCamera: Flow<DriveCameraSettings>" in parts)
    }
}
