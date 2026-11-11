package io.github.purplepalm.custommaps.trip

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.purplepalm.custommaps.nav.trip.TripKind
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataStoreTripLogStoreTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun close() {
        scope.cancel()
    }

    @Test
    fun `recorded starts are kept in order`() = runBlocking {
        val store = DataStoreTripLogStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "log.preferences_pb") }),
        )
        val first = TripSessionStart(Instant.parse("2026-11-03T16:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false)
        val second = TripSessionStart(Instant.parse("2026-11-03T18:30:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = true)
        store.record(first)
        store.record(second)
        assertEquals(listOf(first, second), store.entries.first())
    }

    @Test
    fun `a session's token id is kept next to the sessions without one`() = runBlocking {
        val store = DataStoreTripLogStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "log.preferences_pb") }),
        )
        val before = TripSessionStart(Instant.parse("2000-01-03T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false)
        val tagged = TripSessionStart(Instant.parse("2000-01-04T08:00:00Z"), TripKind.ACTIVE_GUIDANCE, simulated = false, tokenId = "0123456789ab")
        store.record(before)
        store.record(tagged)
        assertEquals(listOf(before, tagged), store.entries.first())
    }
}
