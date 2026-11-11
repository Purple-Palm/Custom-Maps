package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.StepIntersection
import org.junit.jupiter.api.Assertions.assertEquals
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
