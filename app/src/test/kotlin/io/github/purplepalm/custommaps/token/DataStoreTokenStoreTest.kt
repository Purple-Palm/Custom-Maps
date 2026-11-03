package io.github.purplepalm.custommaps.token

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DataStoreTokenStoreTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStoreTokenStore

    // Shaped like a Mapbox token but clearly fake, so secret scanners do not flag it.
    private val publicToken = "pk.dGVzdC1wYXlsb2Fk.dGVzdC1zaWduYXR1cmU"

    @BeforeEach
    fun createStore() {
        store = DataStoreTokenStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(directory, "credentials.preferences_pb") },
            ),
        )
    }

    @AfterEach
    fun closeStore() {
        scope.cancel()
    }

    @Test
    fun `starts without a token`() = runBlocking {
        assertNull(store.token.first())
    }

    @Test
    fun `stores a public token without surrounding whitespace`() = runBlocking {
        store.save("  $publicToken\n")
        assertEquals(publicToken, store.token.first())
    }

    @Test
    fun `replacing keeps only the newest token`() = runBlocking {
        val newer = publicToken.dropLast(4) + "AbCd"
        store.save(publicToken)
        store.save(newer)
        assertEquals(newer, store.token.first())
    }

    @Test
    fun `clear removes the token`() = runBlocking {
        store.save(publicToken)
        store.clear()
        assertNull(store.token.first())
    }

    @Test
    fun `refuses a secret token and keeps nothing`() = runBlocking {
        assertThrows<IllegalArgumentException> { runBlocking { store.save("sk.dGVzdA.c2VjcmV0") } }
        assertNull(store.token.first())
    }
}
