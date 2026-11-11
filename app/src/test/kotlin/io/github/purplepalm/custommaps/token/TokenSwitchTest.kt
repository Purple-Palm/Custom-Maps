package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.token.TokenSwitch.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TokenSwitchTest {

    // Clearly fake public tokens.
    private val main = "pk.dGVzdA.c2ln1"
    private val test = "pk.dGVzdA.c2ln2"

    @Test
    fun `the first read hands over whatever is saved`() {
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = true, applied = null, wanted = main, guidance = GuidanceState.IDLE))
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = true, applied = null, wanted = null, guidance = GuidanceState.IDLE))
    }

    @Test
    fun `the same token again changes nothing, a rename for example`() {
        for (guidance in GuidanceState.entries) {
            assertEquals(Step.Keep, TokenSwitch.step(first = false, applied = main, wanted = main, guidance = guidance), guidance.name)
        }
        assertEquals(Step.Keep, TokenSwitch.step(first = false, applied = null, wanted = null, guidance = GuidanceState.IDLE))
    }

    @Test
    fun `a switch while guidance runs waits for the trip to end`() {
        assertEquals(Step.Hold, TokenSwitch.step(first = false, applied = main, wanted = test, guidance = GuidanceState.ACTIVE))
        // Deleting every token is held as well: the trip keeps the token it began with.
        assertEquals(Step.Hold, TokenSwitch.step(first = false, applied = main, wanted = null, guidance = GuidanceState.ACTIVE))
        // Once the trip has ended, the held choice is handed over.
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = false, applied = main, wanted = test, guidance = GuidanceState.IDLE))
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = false, applied = main, wanted = test, guidance = GuidanceState.ARRIVED))
    }

    @Test
    fun `a switch while routes are previewed closes the preview`() {
        assertEquals(Step.Apply(closePreview = true), TokenSwitch.step(first = false, applied = main, wanted = test, guidance = GuidanceState.PREVIEW))
        assertEquals(Step.Apply(closePreview = true), TokenSwitch.step(first = false, applied = main, wanted = null, guidance = GuidanceState.PREVIEW))
    }

    @Test
    fun `a switch parked hands the token over at once`() {
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = false, applied = main, wanted = test, guidance = GuidanceState.IDLE))
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = false, applied = null, wanted = main, guidance = GuidanceState.IDLE))
        assertEquals(Step.Apply(closePreview = false), TokenSwitch.step(first = false, applied = main, wanted = null, guidance = GuidanceState.IDLE))
    }
}
