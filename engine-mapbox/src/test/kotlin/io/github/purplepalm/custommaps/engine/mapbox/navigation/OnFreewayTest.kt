package io.github.purplepalm.custommaps.engine.mapbox.navigation

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.api.directions.v5.models.MapboxStreetsV8
import com.mapbox.api.directions.v5.models.StepIntersection
import com.mapbox.api.directions.v5.models.StepManeuver
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the phone's motion is logged: while the car is on a freeway, by the intersection it passed
 * last on the step under way, as the navigator counts them. A made-up step near 0, 0.
 */
class OnFreewayTest {

    private fun intersection(at: Int, classes: List<String>? = null, streetsClass: String? = null): StepIntersection =
        StepIntersection.builder()
            .rawLocation(doubleArrayOf(0.0, at * 0.0009))
            .classes(classes)
            .mapboxStreetsV8(streetsClass?.let { MapboxStreetsV8.builder().roadClass(it).build() })
            .build()

    /** From a street onto a freeway and off it: the class of the road each intersection leads onto. */
    private val step: LegStep = LegStep.builder()
        .maneuver(StepManeuver.builder().rawLocation(doubleArrayOf(0.0, 0.0)).type("on ramp").modifier("slight right").build())
        .distance(0.0)
        .duration(0.0)
        .weight(0.0)
        .mode("driving")
        .intersections(
            listOf(
                intersection(0, streetsClass = "primary"),
                intersection(1, streetsClass = "motorway_link"),
                intersection(2, classes = listOf("motorway"), streetsClass = "motorway"),
                intersection(3, classes = listOf("motorway", "toll")),
                intersection(4, streetsClass = "trunk"),
                intersection(5, classes = listOf("toll"), streetsClass = "secondary"),
            ),
        )
        .build()

    @Test
    fun `the car is on a freeway where the intersection it passed last leads onto one`() {
        assertFalse(onFreeway(step, 0), "a street")
        assertFalse(onFreeway(step, 1), "a ramp by its class alone")
        assertTrue(onFreeway(step, 2))
        assertTrue(onFreeway(step, 3), "the motorway class, with a toll")
        assertTrue(onFreeway(step, 4), "a trunk road counts as a freeway, as for the lane choices")
        assertFalse(onFreeway(step, 5))
    }

    @Test
    fun `without the step or the intersection the car is on no freeway`() {
        assertFalse(onFreeway(null, 2))
        assertFalse(onFreeway(step, 6))
        assertFalse(onFreeway(step, -1))
        val bare = step.toBuilder().intersections(emptyList()).build()
        assertFalse(onFreeway(bare, 0))
    }
}
