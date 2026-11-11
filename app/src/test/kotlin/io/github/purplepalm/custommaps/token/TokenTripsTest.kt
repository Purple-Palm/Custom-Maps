package io.github.purplepalm.custommaps.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TokenTripsTest {

    /** Main (t1, active), Test (t2) and Spare (t3), with clearly fake tokens. */
    private val list = TokenList.EMPTY
        .add(null, "pk.dGVzdA.c2ln1", "t1")
        .add("Test", "pk.dGVzdA.c2ln2", "t2")
        .add("Spare", "pk.dGVzdA.c2ln3", "t3")

    @Test
    fun `each saved token has a line in the list's order, also with no trip`() {
        val rows = TokenTripCounts.rows(mapOf("t2" to 3, "t1" to 1), list)

        assertEquals(
            listOf(TokenTrips(TripsOf.Token("Main"), 1), TokenTrips(TripsOf.Token("Test"), 3), TokenTrips(TripsOf.Token("Spare"), 0)),
            rows,
        )
    }

    @Test
    fun `deleted tokens count together, and sessions without an id count apart`() {
        val rows = TokenTripCounts.rows(mapOf("t1" to 1, "gone1" to 2, "gone2" to 1, null to 4), list)

        assertEquals(listOf(TokenTrips(TripsOf.Deleted, 3), TokenTrips(TripsOf.BeforeNames, 4)), rows.takeLast(2))
        assertEquals(1 + 3 + 4, rows.sumOf { it.count })
    }

    @Test
    fun `with no token saved only the special lines are left`() {
        assertEquals(listOf(TokenTrips(TripsOf.Deleted, 2)), TokenTripCounts.rows(mapOf("t1" to 2), TokenList.EMPTY))
        assertEquals(emptyList<TokenTrips>(), TokenTripCounts.rows(emptyMap(), TokenList.EMPTY))
    }

    @Test
    fun `a renamed token keeps its trips under its new name`() {
        val rows = TokenTripCounts.rows(mapOf("t2" to 2), list.rename("t2", "Office"))

        assertEquals(TokenTrips(TripsOf.Token("Office"), 2), rows[1])
    }

    @Test
    fun `one session names its token, a deleted one, or none`() {
        assertEquals(TripsOf.Token("Test"), TokenTripCounts.of("t2", list))
        assertEquals(TripsOf.Deleted, TokenTripCounts.of("t2", list.remove("t2")))
        assertEquals(TripsOf.BeforeNames, TokenTripCounts.of(null, list))
    }
}
