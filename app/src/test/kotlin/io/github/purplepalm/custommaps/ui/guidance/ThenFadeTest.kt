package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What stays put in a Then chip that is kept across a step change: the arrow and the label each
 * fade only when their key changes. Invented roads.
 */
class ThenFadeTest {

    private fun Maneuver.arrowKey() = glyphKey(type, modifier, roundaboutExitDegrees)

    private fun roundabout(type: ManeuverType = ManeuverType.ROUNDABOUT, turn: TurnModifier? = null, degrees: Double? = null) =
        Maneuver(type, turn, "Elm Street", roundaboutExitNumber = 2, roundaboutExitDegrees = degrees)

    @Test
    fun `the same turn onto another road keeps its arrow and changes its label`() {
        val ontoPine = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street")
        val ontoElm = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Elm Street")
        assertEquals(ontoPine.arrowKey(), ontoElm.arrowKey())
        assertNotEquals(ontoPine.labelKey(), ontoElm.labelKey())
    }

    @Test
    fun `another turn onto the same road changes its arrow and keeps its label`() {
        val right = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street")
        val left = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Pine Street")
        assertNotEquals(right.arrowKey(), left.arrowKey())
        assertEquals(right.labelKey(), left.labelKey())
    }

    @Test
    fun `every direction is an arrow of its own`() {
        val arrows = TurnModifier.entries.map { Maneuver(ManeuverType.TURN, it, "Pine Street").arrowKey() }
        assertEquals(TurnModifier.entries.size, arrows.toSet().size)
    }

    @Test
    fun `the kind of maneuver does not change a plain arrow`() {
        fun arrow(type: ManeuverType, turn: TurnModifier?) = Maneuver(type, turn, "Pine Street").arrowKey()
        assertEquals(arrow(ManeuverType.TURN, TurnModifier.RIGHT), arrow(ManeuverType.END_OF_ROAD, TurnModifier.RIGHT))
        assertEquals(arrow(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT), arrow(ManeuverType.FORK, TurnModifier.SLIGHT_RIGHT))
        // No direction is drawn as straight on.
        assertEquals(arrow(ManeuverType.CONTINUE, TurnModifier.STRAIGHT), arrow(ManeuverType.FORK, null))
    }

    @Test
    fun `a roundabout's arrow is where its exit is`() {
        assertEquals(roundabout(degrees = 180.0).arrowKey(), roundabout(ManeuverType.ROTARY, degrees = 180.0).arrowKey())
        assertNotEquals(roundabout(degrees = 90.0).arrowKey(), roundabout(degrees = 270.0).arrowKey())
        // Without an angle the turn stands in, as in the glyph.
        assertEquals(roundabout(degrees = 90.0).arrowKey(), roundabout(turn = TurnModifier.RIGHT).arrowKey())
        // And it is never the arrow of a plain turn, even one that points the same way.
        assertNotEquals(roundabout(turn = TurnModifier.RIGHT).arrowKey(), Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Elm Street").arrowKey())
    }

    @Test
    fun `the destination is one mark, and its label is its side`() {
        val onRight = Maneuver(ManeuverType.ARRIVE, TurnModifier.RIGHT, "Pine Street")
        val onLeft = Maneuver(ManeuverType.ARRIVE, TurnModifier.LEFT, "Pine Street")
        assertEquals(onRight.arrowKey(), onLeft.arrowKey())
        assertNotEquals(onRight.arrowKey(), Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street").arrowKey())
        assertEquals(R.string.guidance_then_on_right, onRight.labelKey())
        assertEquals(R.string.guidance_then_on_left, onLeft.labelKey())
        // The road of the arrival is not shown, so it does not count.
        assertEquals(onRight.labelKey(), Maneuver(ManeuverType.ARRIVE, TurnModifier.SLIGHT_RIGHT, "Elm Street").labelKey())
    }

    @Test
    fun `a road without a name has no label`() {
        assertNull(Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "").labelKey())
        assertNull(Maneuver(ManeuverType.FORK, TurnModifier.SLIGHT_LEFT, " ").labelKey())
        assertEquals("Pine Street", Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street").labelKey())
    }
}
