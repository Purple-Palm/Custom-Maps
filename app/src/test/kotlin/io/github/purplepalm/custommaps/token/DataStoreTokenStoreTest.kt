package io.github.purplepalm.custommaps.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DataStoreTokenStoreTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The token file itself, as the store sees it. */
    private lateinit var file: DataStore<Preferences>

    private val ids = AtomicInteger()

    /** Shaped like a Mapbox public token and clearly fake: "test" and "sig" in base64, and a number. */
    private fun fake(n: Int) = "pk.dGVzdA.c2ln$n"

    /** Where the versions before the list kept their one token. */
    private val oldKey = stringPreferencesKey("mapbox_access_token")

    @BeforeEach
    fun createFile() {
        file = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "credentials.preferences_pb") },
        )
    }

    @AfterEach
    fun closeFile() {
        scope.cancel()
    }

    /** A store on [on], with the ids id1, id2... in the order entries are made. */
    private fun store(on: DataStore<Preferences> = file) = DataStoreTokenStore(on, newId = { "id${ids.incrementAndGet()}" }, readRetryMillis = 10)

    /** What the file holds, key by key. */
    private suspend fun raw(): Map<String, Any> = file.data.first().asMap().mapKeys { it.key.name }

    private suspend fun writeRaw(vararg values: Pair<String, String>) {
        file.edit { preferences -> values.forEach { (key, value) -> preferences[stringPreferencesKey(key)] = value } }
    }

    private fun TokenList.names() = entries.map { it.name }

    @Test
    fun `starts without a token`() = runBlocking {
        assertEquals(TokenList.EMPTY, store().list.first())
        assertNull(store().token.first())
    }

    @Test
    fun `stores a public token without surrounding whitespace as Main`() = runBlocking {
        val store = store()

        assertNull(store.add(null, "  ${fake(1)}\n"))

        assertEquals(fake(1), store.token.first())
        assertEquals(TokenList(listOf(TokenEntry("id1", "Main", fake(1))), "id1"), store.list.first())
    }

    @Test
    fun `keeps up to five tokens in the order they were added, the first one active`() = runBlocking {
        val store = store()
        for (n in 1..5) assertNull(store.add(if (n == 2) "Test" else null, fake(n)))

        assertEquals(TokenProblem.Full, store.add("Spare", fake(6)))

        val list = store.list.first()
        assertEquals(listOf("Main", "Test", "Token 2", "Token 3", "Token 4"), list.names())
        assertEquals("id1", list.activeId)
        assertEquals(fake(1), store.token.first())
    }

    @Test
    fun `refuses a secret token, the same token twice and a taken name`() = runBlocking {
        val store = store()

        assertEquals(TokenProblem.BadToken(AccessTokenCheck.SECRET_TOKEN), store.add(null, "sk.dGVzdA.c2VjcmV0"))
        assertNull(store.token.first())
        assertTrue(raw().isEmpty())

        store.add(null, fake(1))
        assertEquals(TokenProblem.Duplicate("Main"), store.add("Test", fake(1)))
        assertEquals(TokenProblem.BadName(NameProblem.TAKEN), store.add("MAIN", fake(2)))
        assertEquals(1, store.list.first().entries.size)
    }

    @Test
    fun `switching changes the token, renaming does not`() = runBlocking {
        val store = store()
        store.add(null, fake(1))
        store.add("Test", fake(2))

        assertNull(store.activate("id2"))
        assertEquals(fake(2), store.token.first())

        assertNull(store.rename("id2", "Spare"))
        assertEquals(fake(2), store.token.first())
        assertEquals(TokenEntry("id2", "Spare", fake(2)), store.active.first())
        assertEquals(TokenProblem.NotFound, store.activate("id9"))
        assertEquals(TokenProblem.NotFound, store.rename("id9", "Gym"))
        assertEquals(TokenProblem.BadName(NameProblem.TAKEN), store.rename("id2", "main"))
    }

    @Test
    fun `deleting the active token makes the first one left active, and the last one leaves none`() = runBlocking {
        val store = store()
        for (n in 1..3) store.add(null, fake(n))
        store.activate("id2")

        assertNull(store.remove("id2"))
        assertEquals("id1", store.list.first().activeId)
        assertEquals(fake(1), store.token.first())

        store.remove("id1")
        store.remove("id3")
        assertEquals(TokenList.EMPTY, store.list.first())
        assertNull(store.token.first())
        assertTrue(raw().isEmpty(), "no key is left behind")
        assertEquals(TokenProblem.NotFound, store.remove("id3"))
    }

    @Test
    fun `the token of a version before the list becomes Main and active, once`() = runBlocking {
        writeRaw(oldKey.name to fake(1))

        val list = store().list.first()

        assertEquals(TokenList(listOf(TokenEntry("id1", "Main", fake(1))), "id1"), list)
        assertEquals(setOf("mapbox_tokens", "mapbox_active_token"), raw().keys, "the old key is gone")
        // Read again, by a new store as after the next start: the same entry with the same id.
        assertEquals(list, store().list.first())
        assertEquals(1, ids.get(), "no second id was made")
    }

    @Test
    fun `the migration writes the list first and removes the old key last`() = runBlocking {
        writeRaw(oldKey.name to fake(1))
        val probe = Probe(file)

        store(probe).list.first()

        assertEquals(
            listOf(
                setOf("mapbox_access_token", "mapbox_tokens", "mapbox_active_token"),
                setOf("mapbox_tokens", "mapbox_active_token"),
            ),
            probe.written,
        )
    }

    @Test
    fun `a process that dies between the two writes loses nothing and adds nothing twice`() = runBlocking {
        writeRaw(oldKey.name to fake(1))

        // The second write, the one that removes the old key, never happens.
        val first = store(Probe(file, failingWrite = 2)).list.first()
        assertEquals(TokenList(listOf(TokenEntry("id1", "Main", fake(1))), "id1"), first)
        assertEquals(fake(1), raw()["mapbox_access_token"], "both keys are there, as after a crash")

        // The next start reads both keys: the token is in the list already, so only the old key goes.
        val next = store().list.first()
        assertEquals(first, next)
        assertFalse("mapbox_access_token" in raw().keys)
    }

    @Test
    fun `two readers at once migrate the token once`() = runBlocking {
        writeRaw(oldKey.name to fake(1))

        val one = async { store().list.first() }
        val two = async { store().list.first() }

        assertEquals(one.await(), two.await())
        assertEquals(1, one.await().entries.size)
        assertEquals(1, TokenList.decode(raw()["mapbox_tokens"] as String, null).entries.size)
    }

    @Test
    fun `a token deleted after a crash in the migration does not come back`() = runBlocking {
        // The state a process that died between the migration's two writes leaves.
        writeRaw(oldKey.name to fake(1), "mapbox_tokens" to "m1\t${fake(1)}\tMain", "mapbox_active_token" to "m1")

        assertNull(store().remove("m1"))

        assertEquals(TokenList.EMPTY, store().list.first())
        assertTrue(raw().isEmpty())
    }

    @Test
    fun `a token an older version wrote again joins the list without becoming active`() = runBlocking {
        writeRaw("mapbox_tokens" to "m1\t${fake(1)}\tMain", "mapbox_active_token" to "m1", oldKey.name to fake(2))

        val list = store().list.first()

        assertEquals(listOf("Main", "Token 2"), list.names())
        assertEquals(fake(2), list.entries.last().token)
        assertEquals("m1", list.activeId)
        assertFalse("mapbox_access_token" in raw().keys)
    }

    @Test
    fun `a damaged list keeps every token that can be read, with one active`() = runBlocking {
        val text = listOf("m1\t${fake(1)}\tMain", "garbage", "m2\tsk.dGVzdA.c2Vj\tSecret", "m3\t${fake(3)}\tTest", "m4\t${fake(4)}").joinToString("\n")
        writeRaw("mapbox_tokens" to text, "mapbox_active_token" to "gone")

        val list = store().list.first()

        assertEquals(listOf("m1", "m3"), list.entries.map { it.id })
        assertEquals("m1", list.activeId)
        assertEquals(fake(1), store().token.first())
    }

    @Test
    fun `every key the store writes is one of its own`() = runBlocking {
        writeRaw(oldKey.name to fake(1))
        val store = store()
        store.list.first()
        store.add("Test", fake(2))
        store.rename("id2", "Spare")
        store.activate("id2")

        assertTrue(DataStoreTokenStore.KEY_NAMES.containsAll(raw().keys), raw().keys.toString())
        assertEquals(setOf("mapbox_access_token", "mapbox_tokens", "mapbox_active_token"), DataStoreTokenStore.KEY_NAMES)
    }

    @Test
    fun `a read error counts as no token for the moment and is tried again`() = runBlocking {
        store().add(null, fake(1))

        val seen = withTimeout(5_000) { store(Probe(file, failingReads = 1)).token.take(2).toList() }

        assertEquals(listOf(null, fake(1)), seen)
    }

    @Test
    fun `replacing keeps only the newest token, under the same name`() = runBlocking {
        val store = store()
        store.save(fake(1))
        store.save(" ${fake(2)} ")

        assertEquals(fake(2), store.token.first())
        assertEquals(listOf("Main"), store.list.first().names())
    }

    @Test
    fun `clear removes every token`() = runBlocking {
        writeRaw(oldKey.name to fake(9))
        val store = store()
        store.save(fake(1))
        store.add(null, fake(2))

        store.clear()

        assertNull(store.token.first())
        assertTrue(raw().isEmpty())
    }

    @Test
    fun `save refuses a secret token and keeps nothing`() = runBlocking {
        assertThrows<IllegalArgumentException> { runBlocking { store().save("sk.dGVzdA.c2VjcmV0") } }
        assertNull(store().token.first())
    }

    /**
     * The token file with a write that fails, as a process that dies there leaves it, reads that
     * fail, and the keys held after each write.
     */
    private class Probe(
        private val file: DataStore<Preferences>,
        private val failingWrite: Int? = null,
        private val failingReads: Int = 0,
    ) : DataStore<Preferences> {
        private val writes = AtomicInteger()
        private val reads = AtomicInteger()
        val written = CopyOnWriteArrayList<Set<String>>()

        override val data: Flow<Preferences> = flow {
            if (reads.incrementAndGet() <= failingReads) throw IOException("The file cannot be read")
            emitAll(file.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (writes.incrementAndGet() == failingWrite) throw IOException("The process died here")
            return file.updateData(transform).also { written += it.asMap().keys.map { key -> key.name }.toSet() }
        }
    }
}
