package io.github.purplepalm.custommaps.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * The Mapbox public token entered on this phone. No build contains a token; this store is the
 * only place it lives, and its file is excluded from cloud backup and device transfer.
 */
interface TokenStore {
    /** The stored token, or null when none has been entered. */
    val token: Flow<String?>

    /** Stores [token]; only a valid public token is accepted. */
    suspend fun save(token: String)

    suspend fun clear()
}

class DataStoreTokenStore(private val dataStore: DataStore<Preferences>) : TokenStore {

    /** A read error counts as no token for the moment; reading is tried again, so a token is not lost for good. */
    override val token: Flow<String?> = dataStore.data
        .retryWhen { error, attempt ->
            if (error !is IOException) return@retryWhen false
            emit(emptyPreferences())
            delay(READ_RETRY_MS * (attempt + 1).coerceAtMost(MAX_RETRY_STEPS))
            true
        }
        .map { preferences -> preferences[TOKEN_KEY] }
        .distinctUntilChanged()

    override suspend fun save(token: String) {
        val trimmed = token.trim()
        require(MapboxAccessTokens.check(trimmed) == AccessTokenCheck.VALID) { "Only a valid public token can be stored" }
        dataStore.edit { preferences -> preferences[TOKEN_KEY] = trimmed }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(TOKEN_KEY) }
    }

    companion object {
        /** DataStore name. Must never be among the files res/xml/backup_rules.xml and data_extraction_rules.xml include. */
        const val FILE_NAME = "mapbox_credentials"

        /** Location of the file relative to the app's files directory. */
        const val RELATIVE_PATH = "datastore/$FILE_NAME.preferences_pb"

        private val TOKEN_KEY = stringPreferencesKey("mapbox_access_token")
        private const val READ_RETRY_MS = 1_000L
        private const val MAX_RETRY_STEPS = 30L
    }
}
