package io.github.purplepalm.custommaps.ui.token

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenProblem
import io.github.purplepalm.custommaps.token.TokenStore
import java.io.IOException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One saved token as Settings lists it. Only its masked form ever reaches the UI. */
data class TokenRow(
    val id: String,
    val name: String,
    val masked: String,
    val active: Boolean,
    /** A tap may make it the active one: another token than the active one, and no trip running. */
    val mayUse: Boolean,
    /** Any token may be deleted, except the active one while a trip runs. */
    val mayDelete: Boolean,
)

sealed interface TokenState {
    data object Loading : TokenState

    /**
     * The saved tokens in the driver's order, none when [rows] is empty. [locked] while guidance
     * runs: the active token can then be neither switched nor deleted, and no token added. [full]
     * at [TokenList.MAX_ENTRIES], when Add goes. [defaultName] is what Add offers as the name.
     */
    data class Ready(
        val rows: List<TokenRow>,
        val locked: Boolean,
        val full: Boolean,
        val defaultName: String,
    ) : TokenState {
        val mayAdd: Boolean get() = !locked && !full
    }
}

/**
 * The saved tokens for Settings and the first-launch screen. Every change is checked here against
 * [TokenList]'s rules and guidance's state before it is stored, and the store checks it again
 * against what it holds. While guidance runs ([guidance] ACTIVE) the active token stays as it is,
 * so a trip session always ends on the token it began with; AppGraph also holds back a change that
 * slipped through until the trip ends.
 */
class TokenViewModel(
    private val store: TokenStore,
    private val guidance: StateFlow<GuidanceState>,
    private val debugLog: DebugLog,
) : ViewModel() {

    /** The stored list as last read; null before the first read. Kept current while the view model lives, so a change is checked at once. */
    private val list: StateFlow<TokenList?> = store.list.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<TokenState> = combine(list, guidance) { list, guidance ->
        if (list == null) {
            TokenState.Loading
        } else {
            val locked = guidance == GuidanceState.ACTIVE
            TokenState.Ready(
                rows = list.entries.map { entry ->
                    TokenRow(
                        id = entry.id,
                        name = entry.name,
                        masked = MapboxAccessTokens.mask(entry.token),
                        active = entry.id == list.activeId,
                        mayUse = list.mayActivate(entry.id, locked),
                        mayDelete = list.mayRemove(entry.id, locked),
                    )
                },
                locked = locked,
                full = list.full,
                defaultName = list.defaultName(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TokenState.Loading)

    private fun tripRunning(): Boolean = guidance.value == GuidanceState.ACTIVE

    private fun current(): TokenList = list.value ?: TokenList.EMPTY

    /**
     * Adds [input] under [name], the default name when null or blank; the first token becomes the
     * active one. Returns why it cannot be added, for the screen to say, or null when it is being stored.
     */
    fun add(name: String?, input: String): TokenProblem? {
        val list = current()
        val problem = if (tripRunning()) TokenProblem.DuringTrip else list.problemAdding(name, input)
        val slot = list.entries.size + 1
        if (problem != null) {
            logAction("add", slot, problem)
            return problem
        }
        viewModelScope.launch { change("add", slot) { store.add(name, input) } }
        return null
    }

    /** Calls the entry [id] [name]; allowed during a trip too, as the active token stays as it is. */
    fun rename(id: String, name: String): TokenProblem? {
        val list = current()
        list.problemRenaming(id, name)?.let { problem ->
            logAction("rename", list.slotOf(id), problem)
            return problem
        }
        viewModelScope.launch { change("rename", list.slotOf(id)) { store.rename(id, name) } }
        return null
    }

    /** Makes [id] the active token, after the driver confirmed it; the map then reloads with it. Refused while guidance runs. */
    fun use(id: String) {
        val list = current()
        if (!list.mayActivate(id, tripRunning())) {
            logAction("use", list.slotOf(id), if (tripRunning()) TokenProblem.DuringTrip else TokenProblem.NotFound)
            return
        }
        viewModelScope.launch { change("use", list.slotOf(id)) { store.activate(id) } }
    }

    /** Deletes [id], after the driver confirmed it. The active token is refused while guidance runs. */
    fun delete(id: String) {
        val list = current()
        if (!list.mayRemove(id, tripRunning())) {
            logAction("delete", list.slotOf(id), if (tripRunning()) TokenProblem.DuringTrip else TokenProblem.NotFound)
            return
        }
        viewModelScope.launch { change("delete", list.slotOf(id)) { store.remove(id) } }
    }

    /** A failed write leaves the stored list as it was, which the screen keeps showing. */
    private suspend fun change(action: String, slot: Int?, block: suspend () -> TokenProblem?) {
        try {
            logAction(action, slot, block())
        } catch (e: IOException) {
            debugLog.error("tokenStore.$action", e)
        }
    }

    /** What the driver did with which entry, by its place in the list; never a token or a name. */
    private fun logAction(action: String, slot: Int?, refused: TokenProblem?) {
        debugLog.log("token_list", mapOf("action" to action, "slot" to slot, "refused" to refused?.logName()).filterValues { it != null })
    }

    private fun TokenProblem.logName(): String = when (this) {
        is TokenProblem.BadToken -> "token_${check.name.lowercase()}"
        is TokenProblem.BadName -> "name_${problem.name.lowercase()}"
        is TokenProblem.Duplicate -> "duplicate"
        TokenProblem.Full -> "full"
        TokenProblem.NotFound -> "not_found"
        TokenProblem.DuringTrip -> "during_trip"
    }
}
