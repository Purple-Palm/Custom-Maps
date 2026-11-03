package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RoundaboutArmTest {

    @ParameterizedTest(name = "exit at {0} degrees -> arm at {1}")
    @CsvSource(
        "45, 135",
        "90, 90",
        "135, 45",
        "180, 0",
        "270, -90",
        "315, -135",
        // Back the way the driver came: beside the entry, not over it.
        "360, -160",
    )
    fun `the exit angle decides where the arm points`(degrees: Double, expected: Float) {
        // The modifier is ignored when the exit angle is known.
        assertEquals(expected, roundaboutArmAngle(degrees, TurnModifier.SLIGHT_RIGHT))
    }

    @ParameterizedTest(name = "{0} without an exit angle -> arm at {1}")
    @CsvSource(
        "RIGHT, 90",
        "STRAIGHT, 0",
        "U_TURN, -160",
    )
    fun `without an exit angle the turn is used`(turn: TurnModifier, expected: Float) {
        assertEquals(expected, roundaboutArmAngle(null, turn))
    }
}
