package io.github.purplepalm.custommaps.car

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each car map follows Settings' Turns from above, every change at once, as it follows the car map's size and the Debug
 * switches. The session needs the car's host, so the statements are pinned by its source; what the car's map does with
 * the switch is the engine's tests'.
 */
class CarTurnsFromAboveWiringTest {

    /** The session's source with its comments taken out: what the compiler reads. */
    private val session = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarSession.kt").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private val parts = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarParts.kt").readText()

    @Test
    fun `a car map follows the stored switch from the map's making on`() {
        val build = session.substringAfter("private fun buildMap(").substringBefore("private fun releaseMap(").lines().map { it.trim() }
        val jobs = build.indexOf("mapJobs = scope.launch {")
        val follows = build.indexOf("follow(settings.turnsFromAbove, \"setTurnsFromAbove\", created::setTurnsFromAbove)")
        assertTrue(jobs >= 0 && follows > jobs, "followed with the map's other settings: $build")
        assertTrue("val turnsFromAbove: Flow<Boolean>," in parts)
    }
}
