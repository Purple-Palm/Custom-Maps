package io.github.purplepalm.custommaps.ui.token

import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.token.NameProblem
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenProblem
import io.github.purplepalm.custommaps.token.TokenStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Shaped like a Mapbox public token and clearly fake: "test" and "sig" in base64, and a number. */
    private fun fake(n: Int) = "pk.dGVzdA.c2ln$n"

    /** The list kept in memory, with the rules the real store applies. */
    private val store = object : TokenStore {
        val stored = MutableStateFlow(TokenList.EMPTY)
        var storageFull = false
        private var ids = 0

        override val list = stored

        override suspend fun add(name: String?, token: String) = change { list ->
            list.problemAdding(name, token) ?: null.also { stored.value = list.add(name, token, "id${++ids}") }
        }

        override suspend fun rename(id: String, name: String) = change { list ->
            list.problemRenaming(id, name) ?: null.also { stored.value = list.rename(id, name) }
        }

        override suspend fun remove(id: String) = change { list -> null.also { stored.value = list.remove(id) } }

        override suspend fun activate(id: String) = change { list -> null.also { stored.value = list.activate(id) } }

        private fun change(block: (TokenList) -> TokenProblem?): TokenProblem? {
            if (storageFull) throw IOException("No space left on device")
            return block(stored.value)
        }
    }

    private val guidance = MutableStateFlow(GuidanceState.IDLE)

    private val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val debugLog = object : DebugLog {
        override fun log(type: String, fields: Map<String, Any?>) {
            logged += type to fields
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = TokenViewModel(store, guidance, debugLog).also {
        it.state.launchIn(backgroundScope)
        runCurrent()
    }

    /** [count] saved tokens, ids id1, id2..., the first one active. */
    private fun TestScope.saved(count: Int): TokenViewModel {
        val viewModel = viewModel()
        repeat(count) { n ->
            assertNull(viewModel.add(null, fake(n + 1)))
            runCurrent()
        }
        return viewModel
    }

    private fun TokenViewModel.ready() = state.value as TokenState.Ready

    @Test
    fun `lists each token masked, the active one marked`() = runTest(dispatcher) {
        val viewModel = saved(2)

        val rows = viewModel.ready().rows
        assertEquals(listOf("Main", "Token 2"), rows.map { it.name })
        assertEquals(listOf(true, false), rows.map { it.active })
        assertEquals(listOf("pk.dGVz…2ln1", "pk.dGVz…2ln2"), rows.map { it.masked })
        // The whole state, as it could be printed or kept, never holds a token.
        val text = viewModel.state.value.toString()
        assertFalse(text.contains(fake(1)) || text.contains(fake(2)), text)
    }

    @Test
    fun `the first token from the setup screen becomes Main and active`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertEquals(TokenState.Ready(emptyList(), locked = false, full = false, defaultName = "Main"), viewModel.state.value)

        assertNull(viewModel.add(null, "  ${fake(1)} "))
        runCurrent()

        assertEquals("Main", store.stored.value.active?.name)
        assertEquals(fake(1), store.stored.value.active?.token)
    }

    @Test
    fun `a token that cannot be added says why and stores nothing`() = runTest(dispatcher) {
        val viewModel = saved(1)

        assertEquals(TokenProblem.BadToken(AccessTokenCheck.SECRET_TOKEN), viewModel.add(null, "sk.dGVzdA.c2Vj"))
        assertEquals(TokenProblem.Duplicate("Main"), viewModel.add("Test", fake(1)))
        assertEquals(TokenProblem.BadName(NameProblem.TAKEN), viewModel.add("main", fake(2)))
        runCurrent()

        assertEquals(1, store.stored.value.entries.size)
    }

    @Test
    fun `Add goes at five tokens`() = runTest(dispatcher) {
        val viewModel = saved(TokenList.MAX_ENTRIES)

        assertTrue(viewModel.ready().full)
        assertFalse(viewModel.ready().mayAdd)
        assertEquals(TokenProblem.Full, viewModel.add("Spare", fake(6)))
        assertEquals(5, store.stored.value.entries.size)
    }

    @Test
    fun `parked, a tap on another token makes it the active one`() = runTest(dispatcher) {
        val viewModel = saved(2)
        assertTrue(viewModel.ready().rows[1].mayUse)
        assertFalse(viewModel.ready().rows[0].mayUse, "the active one is in use already")

        viewModel.use("id2")
        runCurrent()

        assertEquals("id2", store.stored.value.activeId)
        assertEquals(listOf(false, true), viewModel.ready().rows.map { it.active })
    }

    @Test
    fun `while guidance runs the token cannot be switched, the active one not deleted and none added`() = runTest(dispatcher) {
        val viewModel = saved(3)
        guidance.value = GuidanceState.ACTIVE
        runCurrent()
        val before = store.stored.value

        val ready = viewModel.ready()
        assertTrue(ready.locked)
        assertFalse(ready.mayAdd)
        assertEquals(listOf(false, false, false), ready.rows.map { it.mayUse })
        assertEquals(listOf(false, true, true), ready.rows.map { it.mayDelete })

        viewModel.use("id2")
        viewModel.delete("id1")
        assertEquals(TokenProblem.DuringTrip, viewModel.add("Spare", fake(4)))
        runCurrent()

        assertEquals(before, store.stored.value)
        assertEquals(listOf("during_trip", "during_trip", "during_trip"), logged.filter { it.first == "token_list" }.mapNotNull { it.second["refused"] }.takeLast(3))
    }

    @Test
    fun `while guidance runs the other tokens can be renamed and deleted`() = runTest(dispatcher) {
        val viewModel = saved(3)
        guidance.value = GuidanceState.ACTIVE
        runCurrent()

        assertNull(viewModel.rename("id1", "Office"))
        viewModel.delete("id3")
        runCurrent()

        assertEquals(listOf("Office", "Token 2"), store.stored.value.entries.map { it.name })
        assertEquals("id1", store.stored.value.activeId)
    }

    @Test
    fun `right after the trip ends the token can be switched again`() = runTest(dispatcher) {
        val viewModel = saved(2)
        guidance.value = GuidanceState.ACTIVE
        runCurrent()
        viewModel.use("id2")
        runCurrent()
        assertEquals("id1", store.stored.value.activeId)

        guidance.value = GuidanceState.IDLE
        runCurrent()
        assertFalse(viewModel.ready().locked)
        viewModel.use("id2")
        runCurrent()

        assertEquals("id2", store.stored.value.activeId)
    }

    @Test
    fun `deleting the active token parked makes the first one left active`() = runTest(dispatcher) {
        val viewModel = saved(3)
        viewModel.use("id2")
        runCurrent()

        viewModel.delete("id2")
        runCurrent()

        assertEquals("id1", store.stored.value.activeId)
        assertEquals(listOf("Main", "Token 3"), viewModel.ready().rows.map { it.name })
    }

    @Test
    fun `deleting the last token leaves none`() = runTest(dispatcher) {
        val viewModel = saved(1)

        viewModel.delete("id1")
        runCurrent()

        assertEquals(TokenList.EMPTY, store.stored.value)
        assertEquals(emptyList<TokenRow>(), viewModel.ready().rows)
        assertEquals("Main", viewModel.ready().defaultName)
    }

    @Test
    fun `renaming keeps the active token and checks the name`() = runTest(dispatcher) {
        val viewModel = saved(2)
        viewModel.use("id2")
        runCurrent()

        assertNull(viewModel.rename("id2", "Test"))
        assertEquals(TokenProblem.BadName(NameProblem.EMPTY), viewModel.rename("id1", "  "))
        assertEquals(TokenProblem.BadName(NameProblem.LOOKS_LIKE_TOKEN), viewModel.rename("id1", fake(9)))
        assertEquals(TokenProblem.NotFound, viewModel.rename("id9", "Gym"))
        runCurrent()

        assertEquals("id2", store.stored.value.activeId)
        assertEquals(listOf("Main", "Test"), store.stored.value.entries.map { it.name })
    }

    @Test
    fun `a change that cannot be stored leaves the list as it was`() = runTest(dispatcher) {
        val viewModel = saved(2)
        store.storageFull = true

        viewModel.use("id2")
        runCurrent()

        assertEquals("id1", store.stored.value.activeId)
        assertEquals("Main", viewModel.ready().rows.first { it.active }.name)
        assertEquals("error", logged.last().first)
        assertEquals("tokenStore.use", logged.last().second["where"])
    }

    @Test
    fun `the log names the action and the place in the list, never a token or a name`() = runTest(dispatcher) {
        val viewModel = saved(2)
        viewModel.rename("id2", "Test")
        viewModel.use("id2")
        runCurrent()

        val lines = logged.filter { it.first == "token_list" }.map { it.second }
        assertEquals(listOf(mapOf("action" to "add", "slot" to 1), mapOf("action" to "add", "slot" to 2), mapOf("action" to "rename", "slot" to 2), mapOf("action" to "use", "slot" to 2)), lines)
        assertFalse(logged.toString().contains("Test") || logged.toString().contains("pk."), logged.toString())
    }

    @Test
    fun `the message for a long name gives the limit`() {
        // Unit tests run with the module directory as the working directory.
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(Regex("""name="token_error_name_long">[^<]*\b${TokenList.MAX_NAME_LENGTH}\b""").containsMatchIn(strings))
    }

    @Test
    fun `a secret token is refused with the message it had before`() {
        assertEquals(R.string.token_error_secret, TokenProblem.BadToken(AccessTokenCheck.SECRET_TOKEN).messageRes())
        assertEquals(R.string.token_error_malformed, TokenProblem.BadToken(AccessTokenCheck.MALFORMED).messageRes())
        assertEquals(R.string.token_locked_during_guidance, TokenProblem.DuringTrip.messageRes())
        assertEquals(R.string.token_error_name_token, TokenProblem.BadName(NameProblem.LOOKS_LIKE_TOKEN).messageRes())
    }
}
