package io.github.purplepalm.custommaps.nav.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LaneInfoTest {

    @Test
    fun `counts recommended lanes`() {
        val lanes = LaneInfo(
            listOf(
                Lane(setOf(TurnModifier.LEFT), isRecommended = false),
                Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), isRecommended = true, TurnModifier.RIGHT),
                Lane(setOf(TurnModifier.RIGHT), isRecommended = true, TurnModifier.RIGHT),
            ),
        )
        assertEquals(2, lanes.recommendedLaneCount)
    }

    @Test
    fun `lane guidance needs at least one lane`() {
        assertThrows<IllegalArgumentException> { LaneInfo(emptyList()) }
    }

    @Test
    fun `only a recommended lane can have an active arrow`() {
        assertThrows<IllegalArgumentException> {
            Lane(setOf(TurnModifier.LEFT), isRecommended = false, activeIndication = TurnModifier.LEFT)
        }
    }
}
