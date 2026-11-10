package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThenWordsTest {

    @Test
    fun `every turn has its own words`() {
        val words = mapOf(
            TurnModifier.U_TURN to R.string.then_u_turn,
            TurnModifier.SHARP_RIGHT to R.string.then_sharp_right,
            TurnModifier.RIGHT to R.string.then_right,
            TurnModifier.SLIGHT_RIGHT to R.string.then_slight_right,
            TurnModifier.STRAIGHT to R.string.then_straight,
            TurnModifier.SLIGHT_LEFT to R.string.then_slight_left,
            TurnModifier.LEFT to R.string.then_left,
            TurnModifier.SHARP_LEFT to R.string.then_sharp_left,
        )
        assertEquals(words, TurnModifier.entries.associateWith { thenDirectionRes(ManeuverType.TURN, it) })
        assertEquals(R.string.then_straight, thenDirectionRes(ManeuverType.FORK, null))
    }

    @Test
    fun `a roundabout is named as one whatever the turn`() {
        assertEquals(R.string.then_roundabout, thenDirectionRes(ManeuverType.ROUNDABOUT, TurnModifier.LEFT))
        assertEquals(R.string.then_roundabout, thenDirectionRes(ManeuverType.EXIT_ROTARY, null))
    }

    @Test
    fun `the destination names its side`() {
        val right = setOf(TurnModifier.SLIGHT_RIGHT, TurnModifier.RIGHT, TurnModifier.SHARP_RIGHT)
        val left = setOf(TurnModifier.SLIGHT_LEFT, TurnModifier.LEFT, TurnModifier.SHARP_LEFT)
        for (turn in TurnModifier.entries) {
            // What the screen reader says, and what the chip shows.
            val (spoken, shown) = when (turn) {
                in right -> R.string.then_destination_right to R.string.guidance_then_on_right
                in left -> R.string.then_destination_left to R.string.guidance_then_on_left
                else -> R.string.then_destination to R.string.guidance_then_destination
            }
            assertEquals(spoken, thenDirectionRes(ManeuverType.ARRIVE, turn), "$turn")
            assertEquals(shown, arrivalSideRes(turn), "$turn")
        }
        // An arrival without a side, as the route sends it when the side is unknown.
        assertEquals(R.string.then_destination, thenDirectionRes(ManeuverType.ARRIVE, null))
        assertEquals(R.string.guidance_then_destination, arrivalSideRes(null))
    }
}
