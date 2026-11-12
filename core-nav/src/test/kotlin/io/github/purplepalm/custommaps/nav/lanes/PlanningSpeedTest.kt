package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Speeds in miles an hour as signs post them, compared in metres a second. */
class PlanningSpeedTest {

    private val mph = PlanningSpeed.MPS_PER_MPH

    @Test
    fun `a posted speed is planned 5 mph faster`() {
        assertEquals(75.0 * mph, PlanningSpeed.mps(70.0 * mph, RoadKind.FREEWAY), 1e-9)
        assertEquals(30.0 * mph, PlanningSpeed.mps(25.0 * mph, RoadKind.STREET), 1e-9)
        assertEquals(5.0, PlanningSpeed.MARGIN_MPH)
    }

    @Test
    fun `without a posted speed each kind of road has its own setting, plus 5 mph`() {
        assertEquals(70.0 * mph, PlanningSpeed.mps(null, RoadKind.FREEWAY), 1e-9)
        assertEquals(50.0 * mph, PlanningSpeed.mps(null, RoadKind.RAMP), 1e-9)
        assertEquals(35.0 * mph, PlanningSpeed.mps(null, RoadKind.STREET), 1e-9)
        assertEquals(65.0 * mph, PlanningSpeed.postedFor(RoadKind.FREEWAY), 1e-9)
        assertEquals(45.0 * mph, PlanningSpeed.postedFor(RoadKind.RAMP), 1e-9)
        assertEquals(30.0 * mph, PlanningSpeed.postedFor(RoadKind.STREET), 1e-9)
    }

    @Test
    fun `a posted speed that is no number, zero or less counts as none`() {
        for (odd in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -10.0)) {
            assertEquals(70.0 * mph, PlanningSpeed.mps(odd, RoadKind.FREEWAY), 1e-9, "$odd")
        }
        assertEquals(1.0 + 5.0 * mph, PlanningSpeed.mps(1.0, RoadKind.FREEWAY), 1e-9, "any speed above zero is one")
    }

    @Test
    fun `a node is planned by the road that leaves it`() {
        assertEquals(50.0 * mph, PlanningSpeed.of(RouteNode(0.0, freeway = true, ramp = true)), 1e-9, "a ramp, whatever its class")
        assertEquals(70.0 * mph, PlanningSpeed.of(RouteNode(0.0, freeway = true)), 1e-9)
        assertEquals(35.0 * mph, PlanningSpeed.of(RouteNode(0.0)), 1e-9)
        assertEquals(45.0 * mph, PlanningSpeed.of(RouteNode(0.0, freeway = true, postedMps = 40.0 * mph)), 1e-9)
    }
}
