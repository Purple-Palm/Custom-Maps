package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.car.app.navigation.model.Maneuver as HostManeuver

/**
 * The app's maneuvers as the car's host names them (docs/STAGE-2.md, The routing card), and that the
 * library's own builder takes every one: it throws for a roundabout without its number or angle and for
 * a number or an angle where the type has none, and the host never sees what it would refuse.
 */
class ManeuverToCarTest {

    private fun type(type: ManeuverType, turn: TurnModifier?) = ManeuverToCar.of(type, turn, exitNumber = null, exitDegrees = null).type

    @Test
    fun `the library names 49 kinds of maneuver, and every one the app sends is one of them`() {
        assertEquals(49, HOST_TYPES.size)
        for (input in everyInput()) {
            val car = ManeuverToCar.of(input.type, input.turn, input.exitNumber, input.exitDegrees)
            assertTrue(car.type in HOST_TYPES, "$input -> ${car.type}")
        }
    }

    @Test
    fun `turns take the kind of their turn, a U-turn to the left as traffic is on the right`() {
        val expected = mapOf(
            TurnModifier.U_TURN to HostManeuver.TYPE_U_TURN_LEFT,
            TurnModifier.SHARP_RIGHT to HostManeuver.TYPE_TURN_SHARP_RIGHT,
            TurnModifier.RIGHT to HostManeuver.TYPE_TURN_NORMAL_RIGHT,
            TurnModifier.SLIGHT_RIGHT to HostManeuver.TYPE_TURN_SLIGHT_RIGHT,
            TurnModifier.STRAIGHT to HostManeuver.TYPE_STRAIGHT,
            TurnModifier.SLIGHT_LEFT to HostManeuver.TYPE_TURN_SLIGHT_LEFT,
            TurnModifier.LEFT to HostManeuver.TYPE_TURN_NORMAL_LEFT,
            TurnModifier.SHARP_LEFT to HostManeuver.TYPE_TURN_SHARP_LEFT,
            null to HostManeuver.TYPE_STRAIGHT,
        )
        for (kind in listOf(ManeuverType.TURN, ManeuverType.NEW_NAME, ManeuverType.NOTIFICATION, ManeuverType.UNKNOWN)) {
            for ((turn, host) in expected) assertEquals(host, type(kind, turn), "$kind $turn")
        }
        // The end of the road names its turn; without one it is of no known kind.
        for ((turn, host) in expected - null) assertEquals(host, type(ManeuverType.END_OF_ROAD, turn), "end of road $turn")
        assertEquals(HostManeuver.TYPE_UNKNOWN, type(ManeuverType.END_OF_ROAD, null))
    }

    @Test
    fun `going on along the road is straight on whatever its bend, unless it turns back`() {
        for (turn in TURNS - TurnModifier.U_TURN) assertEquals(HostManeuver.TYPE_STRAIGHT, type(ManeuverType.CONTINUE, turn), "$turn")
        assertEquals(HostManeuver.TYPE_U_TURN_LEFT, type(ManeuverType.CONTINUE, TurnModifier.U_TURN))
        assertEquals(HostManeuver.TYPE_DEPART, type(ManeuverType.DEPART, TurnModifier.LEFT))
    }

    @Test
    fun `an off-ramp or a fork without a side leaves on the near side, an on-ramp goes straight on`() {
        val noSide = listOf(null, TurnModifier.STRAIGHT, TurnModifier.U_TURN)
        for (turn in noSide) {
            assertEquals(HostManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT, type(ManeuverType.OFF_RAMP, turn), "off-ramp $turn")
            assertEquals(HostManeuver.TYPE_FORK_RIGHT, type(ManeuverType.FORK, turn), "fork $turn")
            assertEquals(HostManeuver.TYPE_STRAIGHT, type(ManeuverType.ON_RAMP, turn), "on-ramp $turn")
            assertEquals(HostManeuver.TYPE_MERGE_SIDE_UNSPECIFIED, type(ManeuverType.MERGE, turn), "merge $turn")
        }
        assertEquals(HostManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, type(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT))
        assertEquals(HostManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT, type(ManeuverType.OFF_RAMP, TurnModifier.RIGHT))
        assertEquals(HostManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT, type(ManeuverType.OFF_RAMP, TurnModifier.SHARP_RIGHT))
        assertEquals(HostManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT, type(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_LEFT))
        assertEquals(HostManeuver.TYPE_OFF_RAMP_NORMAL_LEFT, type(ManeuverType.OFF_RAMP, TurnModifier.LEFT))
        assertEquals(HostManeuver.TYPE_OFF_RAMP_NORMAL_LEFT, type(ManeuverType.OFF_RAMP, TurnModifier.SHARP_LEFT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT, type(ManeuverType.ON_RAMP, TurnModifier.SLIGHT_RIGHT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_NORMAL_RIGHT, type(ManeuverType.ON_RAMP, TurnModifier.RIGHT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_SHARP_RIGHT, type(ManeuverType.ON_RAMP, TurnModifier.SHARP_RIGHT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_SLIGHT_LEFT, type(ManeuverType.ON_RAMP, TurnModifier.SLIGHT_LEFT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_NORMAL_LEFT, type(ManeuverType.ON_RAMP, TurnModifier.LEFT))
        assertEquals(HostManeuver.TYPE_ON_RAMP_SHARP_LEFT, type(ManeuverType.ON_RAMP, TurnModifier.SHARP_LEFT))
        for (turn in listOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)) {
            assertEquals(HostManeuver.TYPE_FORK_RIGHT, type(ManeuverType.FORK, turn), "fork $turn")
            assertEquals(HostManeuver.TYPE_MERGE_RIGHT, type(ManeuverType.MERGE, turn), "merge $turn")
        }
        for (turn in listOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)) {
            assertEquals(HostManeuver.TYPE_FORK_LEFT, type(ManeuverType.FORK, turn), "fork $turn")
            assertEquals(HostManeuver.TYPE_MERGE_LEFT, type(ManeuverType.MERGE, turn), "merge $turn")
        }
    }

    @Test
    fun `the arrival names the side the destination is on`() {
        assertEquals(HostManeuver.TYPE_DESTINATION, type(ManeuverType.ARRIVE, null))
        assertEquals(HostManeuver.TYPE_DESTINATION, type(ManeuverType.ARRIVE, TurnModifier.U_TURN))
        assertEquals(HostManeuver.TYPE_DESTINATION_STRAIGHT, type(ManeuverType.ARRIVE, TurnModifier.STRAIGHT))
        for (turn in listOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)) {
            assertEquals(HostManeuver.TYPE_DESTINATION_RIGHT, type(ManeuverType.ARRIVE, turn), "$turn")
        }
        for (turn in listOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)) {
            assertEquals(HostManeuver.TYPE_DESTINATION_LEFT, type(ManeuverType.ARRIVE, turn), "$turn")
        }
    }

    @Test
    fun `a roundabout has its exit number, 1 when unknown, and its angle where it is one the host takes`() {
        for (kind in listOf(ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_TURN, ManeuverType.ROTARY)) {
            for (turn in TURNS + null) {
                assertEquals(CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, exitNumber = 1), ManeuverToCar.of(kind, turn, null, null), "$kind $turn")
            }
            assertEquals(CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, exitNumber = 3), ManeuverToCar.of(kind, TurnModifier.LEFT, 3, null))
            assertEquals(
                CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, exitNumber = 2, exitAngle = 180),
                ManeuverToCar.of(kind, TurnModifier.STRAIGHT, 2, 180.0),
            )
            assertEquals(
                CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, exitNumber = 1, exitAngle = 90),
                ManeuverToCar.of(kind, TurnModifier.RIGHT, null, 90.0),
            )
        }
        // Leaving one is its exit, with no number: the host refuses a number there.
        for (kind in listOf(ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)) {
            assertEquals(CarManeuver(HostManeuver.TYPE_ROUNDABOUT_EXIT_CCW), ManeuverToCar.of(kind, TurnModifier.RIGHT, 2, 90.0))
        }
    }

    @Test
    fun `an angle is rounded and kept only from 1 to 360 degrees`() {
        assertEquals(1, ManeuverToCar.exitAngle(0.6))
        assertEquals(1, ManeuverToCar.exitAngle(1.0))
        assertEquals(360, ManeuverToCar.exitAngle(360.0))
        assertEquals(360, ManeuverToCar.exitAngle(360.4))
        assertEquals(135, ManeuverToCar.exitAngle(135.2))
        for (degrees in listOf(null, 0.0, 0.4, -10.0, 360.6, 400.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(ManeuverToCar.exitAngle(degrees), "$degrees")
        }
    }

    @Test
    fun `the library builds every maneuver the app sends`() {
        for (input in everyInput()) {
            val car = ManeuverToCar.of(input.type, input.turn, input.exitNumber, input.exitDegrees)
            val built = assertDoesNotThrow<HostManeuver>({ car.toHost(icon = null) }, "$input -> $car")
            assertEquals(car.type, built.type, "$input")
            if (car.exitNumber != null) assertEquals(car.exitNumber, built.roundaboutExitNumber, "$input")
            if (car.exitAngle != null) assertEquals(car.exitAngle, built.roundaboutExitAngle, "$input")
        }
    }

    @Test
    fun `the library refuses what the mapper never sends`() {
        // A roundabout without its number, a number or an angle where the type takes none, and an angle out of range.
        assertThrows(IllegalArgumentException::class.java) { HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW).build() }
        assertThrows(IllegalArgumentException::class.java) {
            HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE).setRoundaboutExitNumber(1).build()
        }
        assertThrows(IllegalArgumentException::class.java) { HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_EXIT_CCW).setRoundaboutExitNumber(1) }
        assertThrows(IllegalArgumentException::class.java) { HostManeuver.Builder(HostManeuver.TYPE_TURN_NORMAL_LEFT).setRoundaboutExitNumber(1) }
        assertThrows(IllegalArgumentException::class.java) {
            HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE).setRoundaboutExitAngle(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE).setRoundaboutExitAngle(361)
        }
        assertThrows(IllegalArgumentException::class.java) { HostManeuver.Builder(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW).setRoundaboutExitNumber(0) }
    }

    @Test
    fun `a domain maneuver maps by its kind, turn, exit number and angle`() {
        val maneuver = Maneuver(ManeuverType.ROTARY, TurnModifier.LEFT, "Oak Street", roundaboutExitNumber = 4, roundaboutExitDegrees = 270.0)
        assertEquals(CarManeuver(HostManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 4, 270), ManeuverToCar.of(maneuver))
    }

    private data class Input(val type: ManeuverType, val turn: TurnModifier?, val exitNumber: Int?, val exitDegrees: Double?)

    /** Every kind and turn, with the exit numbers and angles a route can carry, odd ones included. */
    private fun everyInput(): List<Input> = buildList {
        for (type in ManeuverType.entries) {
            for (turn in TURNS + null) {
                for (number in listOf(null, 0, 1, 2, 7)) {
                    for (degrees in DEGREES) add(Input(type, turn, number, degrees))
                }
            }
        }
    }

    private companion object {
        val TURNS: List<TurnModifier> = TurnModifier.entries

        val DEGREES = listOf(null, 0.0, 0.4, 0.6, 45.0, 90.0, 180.0, 270.0, 359.6, 360.0, 360.4, 361.0, -15.0, Double.NaN, Double.POSITIVE_INFINITY)

        /** Every `TYPE_*` the library names, read from the class itself. */
        val HOST_TYPES: Set<Int> = HostManeuver::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TYPE_") && it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }
            .toSet()
    }
}
