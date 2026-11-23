package io.github.purplepalm.custommaps.car

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each car map takes Settings' car map size as stored before its first surface, which the host sends with a later
 * call, and every later change at once, as it follows the Debug switches. The session needs the car's host, so the
 * statements are pinned by its source; what the car's map does with the size is the engine's tests'.
 */
class CarMapSizeWiringTest {

    /** The session's source with its comments taken out: what the compiler reads. */
    private val session = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarSession.kt").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

    private val parts = File("src/main/kotlin/io/github/purplepalm/custommaps/car/CarParts.kt").readText()

    @Test
    fun `a new car map takes the stored size at once, then follows every change`() {
        val build = session.substringAfter("private fun buildMap(").substringBefore("private fun releaseMap(").lines().map { it.trim() }
        val made = build.indexOfFirst { it.startsWith("parts.createMap(carContext, look.lookOnMap)") }
        val stored = build.indexOf("guard(\"setMapSize\") { created.setMapSize(settings.mapSize.value) }")
        val follows = build.indexOf("follow(settings.mapSize, \"setMapSize\", created::setMapSize)")
        assertTrue(made >= 0, "the map is made")
        assertTrue(stored > made, "the stored size right after the map is made: $build")
        assertTrue(follows > stored, "then every change: $build")
        // Before the follows start: nothing on the main thread comes in between, the host's first surface included.
        assertTrue(build.subList(made, stored).none { "launch" in it }, "$build")
        // Read now, as a map is made: a value, not only a flow.
        assertTrue("val mapSize: StateFlow<CarMapSize>," in parts)
    }
}
