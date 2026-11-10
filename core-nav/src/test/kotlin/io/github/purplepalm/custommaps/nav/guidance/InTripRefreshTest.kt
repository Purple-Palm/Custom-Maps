package io.github.purplepalm.custommaps.nav.guidance

import io.github.purplepalm.custommaps.nav.guidance.InTripAction.NONE
import io.github.purplepalm.custommaps.nav.guidance.InTripAction.REFRESH
import io.github.purplepalm.custommaps.nav.guidance.InTripAction.SET_ROUTES
import io.github.purplepalm.custommaps.nav.guidance.InTripRefresh.Obstacle
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.AS_PREVIEWED
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REFRESH_IN_TRIP
import io.github.purplepalm.custommaps.nav.guidance.StartPlan.REPLAN_IN_TRIP
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Every guard of the decision, with invented values. */
class InTripRefreshTest {

    /** The case in which everything is allowed; each test changes one value of it. */
    private data class Case(
        val plan: StartPlan = REPLAN_IN_TRIP,
        val progressSeen: Boolean = true,
        val guidanceActive: Boolean = true,
        val rerouteSeen: Boolean = false,
        val routeCount: Int = 2,
        val destinationShiftMeters: Double? = 4.0,
    ) {
        fun decide(): InTripAction =
            InTripRefresh.decide(plan, progressSeen, guidanceActive, rerouteSeen, routeCount, destinationShiftMeters)

        fun obstacle(): Obstacle? =
            InTripRefresh.obstacle(plan, progressSeen, guidanceActive, rerouteSeen, routeCount, destinationShiftMeters)
    }

    private val plans = StartPlan.entries

    @Test
    fun `the limit for the same destination is a hundred meters`() {
        assertEquals(100.0, InTripRefresh.MAX_DESTINATION_SHIFT_METERS)
    }

    @Test
    fun `routes to the same destination are set once the trip has a progress`() {
        assertEquals(SET_ROUTES, Case().decide())
        assertNull(Case().obstacle())
        assertEquals(SET_ROUTES, Case(routeCount = 1, destinationShiftMeters = 0.0).decide())
    }

    @Test
    fun `at the first progress the plan says what to begin`() {
        // Asked with the routes the navigator has and nothing moved yet.
        assertEquals(NONE, Case(plan = AS_PREVIEWED, routeCount = 1, destinationShiftMeters = 0.0).decide())
        assertEquals(REFRESH, Case(plan = REFRESH_IN_TRIP, routeCount = 1, destinationShiftMeters = 0.0).decide())
        assertEquals(SET_ROUTES, Case(plan = REPLAN_IN_TRIP, routeCount = 1, destinationShiftMeters = 0.0).decide())
    }

    @Test
    fun `a start as previewed does nothing inside the trip`() {
        assertEquals(NONE, Case(plan = AS_PREVIEWED).decide())
        assertEquals(Obstacle.NOTHING_PLANNED, Case(plan = AS_PREVIEWED).obstacle())
    }

    @Test
    fun `nothing happens before the first progress of the trip`() {
        for (plan in plans) {
            assertEquals(NONE, Case(plan = plan, progressSeen = false).decide(), "$plan")
        }
        assertEquals(Obstacle.NO_PROGRESS, Case(plan = REFRESH_IN_TRIP, progressSeen = false).obstacle())
        assertEquals(Obstacle.NO_PROGRESS, Case(plan = REPLAN_IN_TRIP, progressSeen = false).obstacle())
    }

    @Test
    fun `nothing happens once guidance has ended`() {
        for (plan in plans) {
            assertEquals(NONE, Case(plan = plan, guidanceActive = false).decide(), "$plan")
            assertEquals(Obstacle.GUIDANCE_ENDED, Case(plan = plan, guidanceActive = false).obstacle(), "$plan")
        }
    }

    @Test
    fun `nothing happens once the navigator has rerouted`() {
        for (plan in plans) {
            assertEquals(NONE, Case(plan = plan, rerouteSeen = true).decide(), "$plan")
        }
        assertEquals(Obstacle.REROUTED, Case(plan = REFRESH_IN_TRIP, rerouteSeen = true).obstacle())
        assertEquals(Obstacle.REROUTED, Case(plan = REPLAN_IN_TRIP, rerouteSeen = true).obstacle())
    }

    @Test
    fun `an empty response never sets routes`() {
        assertEquals(NONE, Case(routeCount = 0).decide())
        assertEquals(Obstacle.NO_ROUTES, Case(routeCount = 0).obstacle())
        assertEquals(NONE, Case(routeCount = 0, destinationShiftMeters = 0.0).decide())
        assertEquals(NONE, Case(routeCount = -1).decide())
    }

    @Test
    fun `a navigator without routes is not asked to refresh`() {
        assertEquals(NONE, Case(plan = REFRESH_IN_TRIP, routeCount = 0).decide())
        assertEquals(Obstacle.NO_ROUTES, Case(plan = REFRESH_IN_TRIP, routeCount = 0).obstacle())
    }

    @Test
    fun `a destination moved more than a hundred meters never sets routes`() {
        assertEquals(NONE, Case(destinationShiftMeters = 100.01).decide())
        assertEquals(Obstacle.DESTINATION_MOVED, Case(destinationShiftMeters = 100.01).obstacle())
        assertEquals(NONE, Case(destinationShiftMeters = 101.0).decide())
        assertEquals(NONE, Case(destinationShiftMeters = 5_000.0).decide())
        assertEquals(NONE, Case(destinationShiftMeters = Double.POSITIVE_INFINITY).decide())
        assertEquals(Obstacle.DESTINATION_MOVED, Case(destinationShiftMeters = Double.POSITIVE_INFINITY).obstacle())
    }

    @Test
    fun `a destination exactly a hundred meters away is still the same`() {
        assertEquals(SET_ROUTES, Case(destinationShiftMeters = 100.0).decide())
        assertEquals(SET_ROUTES, Case(destinationShiftMeters = 99.99).decide())
        assertNull(Case(destinationShiftMeters = 100.0).obstacle())
    }

    @Test
    fun `a destination that cannot be measured never sets routes`() {
        assertEquals(NONE, Case(destinationShiftMeters = null).decide())
        assertEquals(Obstacle.DESTINATION_UNKNOWN, Case(destinationShiftMeters = null).obstacle())
        assertEquals(NONE, Case(destinationShiftMeters = Double.NaN).decide())
        assertEquals(Obstacle.DESTINATION_UNKNOWN, Case(destinationShiftMeters = Double.NaN).obstacle())
        assertEquals(NONE, Case(destinationShiftMeters = -1.0).decide())
        assertEquals(Obstacle.DESTINATION_UNKNOWN, Case(destinationShiftMeters = -1.0).obstacle())
    }

    @Test
    fun `a refresh does not depend on a destination, which it keeps`() {
        assertEquals(REFRESH, Case(plan = REFRESH_IN_TRIP, destinationShiftMeters = null).decide())
        assertEquals(REFRESH, Case(plan = REFRESH_IN_TRIP, destinationShiftMeters = 5_000.0).decide())
    }

    @Test
    fun `no other guard can make up for one that fails`() {
        // Every mix of the six values: something is done only when every guard passes.
        val shifts = listOf(null, 0.0, 100.0, 100.5, Double.NaN)
        for (plan in plans) for (progress in listOf(true, false)) for (active in listOf(true, false)) {
            for (reroute in listOf(true, false)) for (count in listOf(0, 1, 3)) for (shift in shifts) {
                val case = Case(plan, progress, active, reroute, count, shift)
                val open = active && progress && !reroute && count > 0
                val expected = when {
                    !open -> NONE
                    plan == AS_PREVIEWED -> NONE
                    plan == REFRESH_IN_TRIP -> REFRESH
                    shift != null && shift <= 100.0 -> SET_ROUTES
                    else -> NONE
                }
                assertEquals(expected, case.decide(), "$case")
                // The reason is there exactly when nothing is done.
                assertEquals(expected == NONE, case.obstacle() != null, "$case")
            }
        }
    }

    @Test
    fun `the reason names the first guard that fails`() {
        // Guidance that ended comes before everything else.
        assertEquals(
            Obstacle.GUIDANCE_ENDED,
            Case(guidanceActive = false, progressSeen = false, rerouteSeen = true, routeCount = 0).obstacle(),
        )
        assertEquals(Obstacle.NO_PROGRESS, Case(progressSeen = false, rerouteSeen = true, routeCount = 0).obstacle())
        assertEquals(Obstacle.REROUTED, Case(rerouteSeen = true, routeCount = 0, destinationShiftMeters = 500.0).obstacle())
        assertEquals(Obstacle.NO_ROUTES, Case(routeCount = 0, destinationShiftMeters = 500.0).obstacle())
    }
}
