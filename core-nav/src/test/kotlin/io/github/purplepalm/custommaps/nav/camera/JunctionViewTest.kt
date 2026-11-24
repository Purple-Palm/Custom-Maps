package io.github.purplepalm.custommaps.nav.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How Smooth follow shows its junctions: tilted only on the car with Turns from above off, from above everywhere else. */
class JunctionViewTest {

    @Test
    fun `only the car's camera with the switch off shows its junctions tilted`() {
        assertEquals(JunctionView.TILTED, JunctionView.of(anchored = true, turnsFromAbove = false))
        assertEquals(JunctionView.ABOVE, JunctionView.of(anchored = true, turnsFromAbove = true))
        // The phone's junction views are from above whatever the car's switch says.
        assertEquals(JunctionView.ABOVE, JunctionView.of(anchored = false, turnsFromAbove = false))
        assertEquals(JunctionView.ABOVE, JunctionView.of(anchored = false, turnsFromAbove = true))
        assertTrue(CameraTuning.CAR.anchored)
        assertFalse(CameraTuning.PHONE.anchored)
        // As the camera line names them.
        assertEquals(listOf("above", "tilted"), JunctionView.entries.map { it.logName })
    }

    @Test
    fun `from above the car looks straight down onto the junction as the phone does, tilted it keeps its one tilt`() {
        val car = CameraTuning.CAR
        val phone = CameraTuning.PHONE
        assertEquals(0.0, car.junctionPitch(JunctionView.ABOVE))
        assertEquals(phone.maneuverPitch, car.junctionPitch(JunctionView.ABOVE))
        assertEquals(55.0, car.junctionPitch(JunctionView.TILTED))
        // The car's numbers stay the ones of lanes step S1: the switch is the camera's state, not a tuning.
        assertEquals(listOf(55.0, 55.0, 55.0), listOf(car.cruisePitch, car.maneuverPitch, car.lanePitch))
        // The phone's tilt over a junction is its own either way.
        for (view in JunctionView.entries) assertEquals(phone.maneuverPitch, phone.junctionPitch(view), "$view")
        // Along the focus: from the car's tilt on the road down to straight above, and the lanes' share wins nothing over it.
        for (lanes in listOf(0.0, 1.0)) {
            assertEquals(55.0, guidancePitch(car, lanes, 0.0, car.junctionPitch(JunctionView.ABOVE)), 1e-12)
            assertEquals(27.5, guidancePitch(car, lanes, 0.5, car.junctionPitch(JunctionView.ABOVE)), 1e-12)
            assertEquals(0.0, guidancePitch(car, lanes, 1.0, car.junctionPitch(JunctionView.ABOVE)), 1e-12)
            assertEquals(55.0, guidancePitch(car, lanes, 1.0, car.junctionPitch(JunctionView.TILTED)), 1e-12)
        }
        // Without a tilt of its own the guidance tilt is the tuning's, as before.
        assertEquals(guidancePitch(phone, 0.3, 0.6), guidancePitch(phone, 0.3, 0.6, phone.junctionPitch(JunctionView.ABOVE)), 0.0)
        assertEquals(guidancePitch(car, 0.3, 0.6), guidancePitch(car, 0.3, 0.6, car.junctionPitch(JunctionView.TILTED)), 0.0)
    }
}
