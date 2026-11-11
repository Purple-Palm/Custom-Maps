package io.github.purplepalm.custommaps.token

import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.GuidanceState.ACTIVE
import io.github.purplepalm.custommaps.nav.model.GuidanceState.ARRIVED
import io.github.purplepalm.custommaps.nav.model.GuidanceState.IDLE
import io.github.purplepalm.custommaps.nav.model.GuidanceState.PREVIEW
import io.github.purplepalm.custommaps.token.TokenHandOver.Effect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TokenHandOverTest {

    /** Main (t1, active), Test (t2) and Spare (t3), with clearly fake tokens. */
    private val saved = TokenList.EMPTY
        .add(null, "pk.dGVzdA.c2ln1", "t1")
        .add("Test", "pk.dGVzdA.c2ln2", "t2")
        .add("Spare", "pk.dGVzdA.c2ln3", "t3")

    private val main = saved.entries[0]
    private val test = saved.entries[1]
    private val spare = saved.entries[2]

    /** Main handed over at the app's start, parked. */
    private fun started() = TokenHandOver().also { it.next(saved, IDLE) }

    /** Main handed over at the start, and a trip running on it since. */
    private fun inTrip() = started().also { assertEquals(Effect.Keep, it.next(saved, ACTIVE)) }

    @Test
    fun `the first read hands over whatever is saved`() {
        for (guidance in GuidanceState.entries) {
            val handOver = TokenHandOver()
            assertNull(handOver.appliedTokenId)

            assertEquals(Effect.Apply(main, closePreview = false), handOver.next(saved, guidance), guidance.name)
            assertEquals("t1", handOver.appliedTokenId)
        }
    }

    @Test
    fun `with no token at the start the screens hear so, and the first one entered is handed over`() {
        val handOver = TokenHandOver()
        assertEquals(Effect.Apply(null, closePreview = false), handOver.next(TokenList.EMPTY, IDLE))
        assertNull(handOver.appliedTokenId)

        val first = TokenList.EMPTY.add(null, main.token, "t1")
        assertEquals(Effect.Apply(first.active, closePreview = false), handOver.next(first, IDLE))
        assertEquals("t1", handOver.appliedTokenId)
    }

    @Test
    fun `a switch parked hands the token over at once, and the next trips count under it`() {
        for (parked in listOf(IDLE, ARRIVED)) {
            val handOver = started()

            assertEquals(Effect.Apply(test, closePreview = false), handOver.next(saved.activate("t2"), parked), parked.name)
            assertEquals("t2", handOver.appliedTokenId)
        }
    }

    @Test
    fun `a switch while routes are previewed closes the preview first`() {
        val handOver = started()

        assertEquals(Effect.Apply(test, closePreview = true), handOver.next(saved.activate("t2"), PREVIEW))
        assertEquals("t2", handOver.appliedTokenId)
    }

    @Test
    fun `a switch during a trip waits, and the trip keeps counting under its own token`() {
        val handOver = inTrip()

        assertEquals(Effect.Hold(test, log = true), handOver.next(saved.activate("t2"), ACTIVE))

        assertEquals("t1", handOver.appliedTokenId)
    }

    @Test
    fun `a change that waited is handed over when the trip ends, with or without arrival`() {
        for (end in listOf(IDLE, ARRIVED)) {
            val handOver = inTrip()
            val switched = saved.activate("t2")
            handOver.next(switched, ACTIVE)

            assertEquals(Effect.Apply(test, closePreview = false), handOver.next(switched, end), end.name)
            assertEquals("t2", handOver.appliedTokenId)
            // Handed over once: the next read changes nothing.
            assertEquals(Effect.Keep, handOver.next(switched, end))
        }
    }

    @Test
    fun `a change back to the trip's own token before the end ends the wait`() {
        val handOver = inTrip()
        handOver.next(saved.activate("t2"), ACTIVE)

        assertEquals(Effect.Keep, handOver.next(saved, ACTIVE))
        assertEquals(Effect.Keep, handOver.next(saved, IDLE))
        assertEquals("t1", handOver.appliedTokenId)
    }

    @Test
    fun `a change that waits is logged once, and again when another token waits`() {
        val handOver = inTrip()
        val toTest = saved.activate("t2")

        assertEquals(Effect.Hold(test, log = true), handOver.next(toTest, ACTIVE))
        // Renaming the token that waits, or another one, is no new change.
        val renamed = toTest.rename("t2", "Gym")
        assertEquals(Effect.Hold(renamed.active, log = false), handOver.next(renamed, ACTIVE))
        assertEquals(Effect.Hold(renamed.active, log = false), handOver.next(renamed.rename("t1", "Office"), ACTIVE))
        // Another token waits instead.
        assertEquals(Effect.Hold(spare, log = true), handOver.next(saved.activate("t3"), ACTIVE))
        assertEquals("t1", handOver.appliedTokenId)
    }

    @Test
    fun `no token left during a trip waits too, logged once`() {
        val handOver = inTrip()

        // As a read that failed during the drive gives it: no token for the moment.
        assertEquals(Effect.Hold(null, log = true), handOver.next(TokenList.EMPTY, ACTIVE))
        assertEquals(Effect.Hold(null, log = false), handOver.next(TokenList.EMPTY, ACTIVE))
        assertEquals("t1", handOver.appliedTokenId)

        // Read again, the trip's own token ends the wait; a later one is logged again.
        assertEquals(Effect.Keep, handOver.next(saved, ACTIVE))
        assertEquals(Effect.Hold(null, log = true), handOver.next(TokenList.EMPTY, ACTIVE))

        // Still none when the trip ends: the screens hear that there is no token.
        assertEquals(Effect.Apply(null, closePreview = false), handOver.next(TokenList.EMPTY, IDLE))
        assertNull(handOver.appliedTokenId)
    }

    @Test
    fun `the same token under a new name or entry is kept, and trips count under the entry that holds it`() {
        val handOver = started()

        assertEquals(Effect.Keep, handOver.next(saved.rename("t1", "Office"), IDLE))
        assertEquals("t1", handOver.appliedTokenId)

        // Deleted and added again faster than the list was read: the same token, another entry.
        val again = TokenList(listOf(TokenEntry("t9", "Main", main.token)), "t9")
        assertEquals(Effect.Keep, handOver.next(again, ACTIVE))
        assertEquals("t9", handOver.appliedTokenId)
    }
}
