package io.github.purplepalm.custommaps.ui.token

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.error
import io.github.purplepalm.custommaps.token.TokenStore
import java.io.IOException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TokenState {
    data object Loading : TokenState

    data object Missing : TokenState

    /** A token is stored; only its masked form ever reaches the UI. */
    data class Present(val masked: String) : TokenState
}

class TokenViewModel(private val store: TokenStore, private val debugLog: DebugLog) : ViewModel() {

    val state: StateFlow<TokenState> = store.token
        .map { token -> if (token == null) TokenState.Missing else TokenState.Present(MapboxAccessTokens.mask(token)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TokenState.Loading)

    /** Validates [input] and stores it when valid. Returns the check so the UI can explain a rejection. */
    fun save(input: String): AccessTokenCheck {
        val check = MapboxAccessTokens.check(input)
        if (check == AccessTokenCheck.VALID) {
            viewModelScope.launch { write("tokenStore.save") { store.save(input) } }
        }
        return check
    }

    fun clear() {
        viewModelScope.launch { write("tokenStore.clear") { store.clear() } }
    }

    /** A failed write leaves the stored token as it was, which the screen keeps showing. */
    private suspend fun write(where: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            debugLog.error(where, e)
        }
    }
}
