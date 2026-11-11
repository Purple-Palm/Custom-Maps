package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.BannerComponents
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.api.directions.v5.models.BannerText
import com.mapbox.api.directions.v5.models.StepIntersection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the route's step fields and the progress line add for the next drive. Invented intersections near 0, 0. */
class StepCountsTest {

    private fun intersection(signal: Boolean? = null, stop: Boolean? = null, yieldSign: Boolean? = null, railway: Boolean? = null): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(0.001, 0.0))
            .trafficSignal(signal)
            .stopSign(stop)
            .yieldSign(yieldSign)
            .railwayCrossing(railway)
            .build()

    @Test
    fun `a step counts its signals, signs and crossings`() {
        val fields = stepSignFields(
            listOf(
                intersection(signal = true, stop = false),
                intersection(signal = true, railway = true),
                intersection(signal = false, yieldSign = true),
            ),
        )

        assertEquals(mapOf("trafficSignals" to 2, "stopSigns" to 0, "yieldSigns" to 1, "railwayCrossings" to 1), fields)
    }

    @Test
    fun `a step whose intersections say nothing has no counts`() {
        val fields = stepSignFields(listOf(intersection(), null))

        assertEquals(mapOf("trafficSignals" to null, "stopSigns" to null, "yieldSigns" to null, "railwayCrossings" to null), fields)
    }

    private fun laneComponent(directions: List<String>, active: Boolean, activeDirection: String? = null): BannerComponents =
        BannerComponents.builder().text("").type("lane").directions(directions).active(active).activeDirection(activeDirection).build()

    private fun banner(atMeters: Double, sub: List<BannerComponents>? = null): BannerInstructions =
        BannerInstructions.builder()
            .distanceAlongGeometry(atMeters)
            .primary(BannerText.builder().text("Exit 24").type("off ramp").modifier("slight right").build())
            .sub(sub?.let { BannerText.builder().text("").components(it).build() })
            .build()

    @Test
    fun `the step's lanes come from its last banner that has them`() {
        val lanes = listOf(
            laneComponent(listOf("straight"), active = false),
            laneComponent(listOf("straight", "slight right"), active = true, activeDirection = "slight right"),
        )
        // A step's banner far from the exit has no lanes; the one near it does.
        val far = banner(2_000.0)
        val near = banner(400.0, sub = lanes + BannerComponents.builder().text("Oak Street").type("text").build())
        val expected = listOf(
            mapOf("directions" to listOf("straight"), "active" to false, "activeDirection" to null),
            mapOf("directions" to listOf("straight", "slight right"), "active" to true, "activeDirection" to "slight right"),
        )
        assertEquals(expected, stepLaneFields(listOf(far, near)))
        // A last banner without lanes does not hide those of the one before it.
        assertEquals(expected, stepLaneFields(listOf(near, banner(100.0))))
        assertNull(stepLaneFields(listOf(far)))
        assertNull(stepLaneFields(null))
        assertNull(stepLaneFields(emptyList()))
    }

    @Test
    fun `the speed limit is a value, a unit and a sign`() {
        assertEquals(
            mapOf("value" to 45, "unit" to "MILES_PER_HOUR", "sign" to "MUTCD"),
            speedLimitFields(45, "MILES_PER_HOUR", "MUTCD"),
        )
        // A road without a limit.
        assertEquals(mapOf("value" to null, "unit" to "MILES_PER_HOUR", "sign" to "MUTCD"), speedLimitFields(null, "MILES_PER_HOUR", "MUTCD"))
    }
}
