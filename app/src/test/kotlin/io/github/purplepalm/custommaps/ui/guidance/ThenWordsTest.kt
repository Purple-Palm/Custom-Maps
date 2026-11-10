package io.github.purplepalm.custommaps.ui.guidance

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThenWordsTest {

    @Test
    fun `every turn has its own words`() {
        val words = TurnModifier.entries.map { thenDirectionRes(ManeuverType.TURN, it) }
        assertEquals(TurnModifier.entries.size, words.toSet().size)
        assertEquals(R.string.then_straight, thenDirectionRes(ManeuverType.FORK, null))
    }

    @Test
    fun `a roundabout is named as one whatever the turn`() {
        assertEquals(R.string.then_roundabout, thenDirectionRes(ManeuverType.ROUNDABOUT, TurnModifier.LEFT))
        assertEquals(R.string.then_roundabout, thenDirectionRes(ManeuverType.EXIT_ROTARY, null))
    }

    @Test
    fun `the destination names its side`() {
        assertEquals(R.string.then_destination_right, thenDirectionRes(ManeuverType.ARRIVE, TurnModifier.SLIGHT_RIGHT))
        assertEquals(R.string.then_destination_left, thenDirectionRes(ManeuverType.ARRIVE, TurnModifier.LEFT))
        assertEquals(R.string.then_destination, thenDirectionRes(ManeuverType.ARRIVE, null))
        assertEquals(R.string.guidance_then_on_right, arrivalSideRes(TurnModifier.SHARP_RIGHT))
        assertEquals(R.string.guidance_then_on_left, arrivalSideRes(TurnModifier.SLIGHT_LEFT))
        assertEquals(R.string.guidance_then_destination, arrivalSideRes(TurnModifier.STRAIGHT))
    }
}
