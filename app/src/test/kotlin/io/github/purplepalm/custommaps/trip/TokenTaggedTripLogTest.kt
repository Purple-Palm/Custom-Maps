package io.github.purplepalm.custommaps.trip

import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import io.github.purplepalm.custommaps.token.DataStoreTokenStore
import io.github.purplepalm.custommaps.token.TokenList
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenTaggedTripLogTest {

    private val log = object : TripLogStore {
        val log = MutableStateFlow<List<TripSessionStart>>(emptyList())
        override val entries = log

        override suspend fun record(start: TripSessionStart) {
            log.value = log.value + start
        }
    }

    private fun start(day: Int, tokenId: String? = null) =
        TripSessionStart(Instant.parse("2000-01-0${day}T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false, tokenId = tokenId)

    @Test
    fun `each session gets the id of the token the maps had when it began`() = runBlocking {
        var applied: String? = "t1"
        val tagged = TokenTaggedTripLog(log) { applied }

        tagged.record(start(1))
        applied = "t2"
        tagged.record(start(2))

        assertEquals(listOf(start(1, "t1"), start(2, "t2")), log.log.value)
        assertEquals(log.log, tagged.entries)
    }

    @Test
    fun `with no token handed over a session has no id, and one that names its token keeps it`() = runBlocking {
        val tagged = TokenTaggedTripLog(log) { null }

        tagged.record(start(1))
        tagged.record(start(2, "t9"))

        assertEquals(listOf(start(1), start(2, "t9")), log.log.value)
    }

    @Test
    fun `every id a saved token can get fits in a line of the trip log`() {
        // A 12-digit hex id as the store makes them, the id of the token moved from the old key,
        // and the longest one the list allows.
        for (id in listOf("0123456789ab", DataStoreTokenStore.MIGRATED_ID, "a".repeat(32), "t-2_x")) {
            assertTrue(TokenList.isId(id))
            assertTrue(TripLog.isTokenId(id))
            assertEquals(listOf(start(1, id)), TripLog.decode(TripLog.encode(listOf(start(1, id)))))
        }
    }
}
