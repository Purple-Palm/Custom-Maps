package io.github.purplepalm.custommaps.nav.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteCountsTest {

    @Test
    fun `intersections with a flag are counted`() {
        assertEquals(2, RouteCounts.flagged(listOf(true, false, null, true)))
        assertEquals(0, RouteCounts.flagged(listOf(false, null)))
    }

    @Test
    fun `a step whose intersections say nothing has no count`() {
        assertNull(RouteCounts.flagged(listOf(null, null)))
        assertNull(RouteCounts.flagged(emptyList()))
    }

    @Test
    fun `runs of a level are told apart by anything between them`() {
        val levels = listOf("low", "heavy", "heavy", "severe", "heavy", null, "heavy")
        val meters = listOf(50.0, 20.0, 30.0, 40.0, 10.0, 5.0, 7.0)

        assertEquals(listOf(50.0, 10.0, 7.0), RouteCounts.runs(levels, meters, "heavy"))
        assertEquals(listOf(40.0), RouteCounts.runs(levels, meters, "severe"))
        assertEquals(emptyList<Double>(), RouteCounts.runs(levels, meters, "moderate"))
    }

    @Test
    fun `a length that is no number counts as none`() {
        assertEquals(listOf(12.0), RouteCounts.runs(listOf("heavy", "heavy", "heavy"), listOf(12.0, Double.NaN), "heavy"))
    }

    @Test
    fun `a route counts its runs over all legs, each leg ending its own`() {
        val first = listOf("heavy", "heavy") to listOf(100.4, 200.2)
        val second = listOf("heavy", "severe", "severe") to listOf(50.0, 60.6, 70.0)

        assertEquals(
            mapOf(
                "heavy" to mapOf("count" to 2, "meters" to listOf(301.0, 50.0)),
                "severe" to mapOf("count" to 1, "meters" to listOf(131.0)),
            ),
            RouteCounts.congestionRuns(listOf(first, second)),
        )
    }
}
