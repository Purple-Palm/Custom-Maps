package io.github.purplepalm.custommaps.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transform

/**
 * The Mapbox public tokens entered on this phone, at most [TokenList.MAX_ENTRIES], and the one in
 * use. No build contains a token; this store is the only place they live, and its file is excluded
 * from cloud backup and device transfer.
 */
interface TokenStore {
    /** Every saved token in the driver's order, with the active one; empty when none is saved. */
    val list: Flow<TokenList>

    /** The active entry, or null when no token is saved. A rename changes it, a switch too. */
    val active: Flow<TokenEntry?> get() = list.map { it.active }.distinctUntilChanged()

    /** The token the SDKs use: the active entry's, or null when none is saved. A rename leaves it as it is. */
    val token: Flow<String?> get() = active.map { it?.token }.distinctUntilChanged()

    /** Adds [token] under [name], the default name when null or blank. Null when it was added, else why not. */
    suspend fun add(name: String?, token: String): TokenProblem?

    /** Calls the entry [id] [name]. Null when it was renamed, else why not. */
    suspend fun rename(id: String, name: String): TokenProblem?

    /** Deletes the entry [id]; when it was the active one, the first entry left becomes active. */
    suspend fun remove(id: String): TokenProblem?

    /** Makes the entry [id] the active one. */
    suspend fun activate(id: String): TokenProblem?
}

/**
 * The tokens in the DataStore file [FILE_NAME]: the list in one key and the active entry's id in
 * another, both written in one go. [newId] makes the id of a new entry: random, never derived from
 * the token.
 */
class DataStoreTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val newId: () -> String = ::randomId,
    /** The wait before a failed read is tried again, growing with each attempt. */
    private val readRetryMillis: Long = READ_RETRY_MS,
) : TokenStore {

    /**
     * A read error counts as no token for the moment; reading is tried again, so a token is not
     * lost for good.
     *
     * The first read after an update from a version with one token moves that token into the list
     * ([TokenMigration]): one write adds it while the old key stays, a second one removes the old
     * key, and only then is the list emitted, so every entry shown has the id it keeps. When the
     * second write fails, the list is emitted with the old key still there; the next change of the
     * list removes it, and until then it is read again harmlessly, as its token is in the list.
     */
    override val list: Flow<TokenList> = dataStore.data
        .transform { preferences ->
            val list = stored(preferences)
            when (TokenMigration.of(list, preferences[LEGACY_KEY])) {
                TokenMigration.NONE -> emit(list)
                // Each write brings the next read, one step further.
                TokenMigration.ADD_ENTRY -> dataStore.edit(::migrateOneStep)
                TokenMigration.REMOVE_OLD_KEY -> try {
                    dataStore.edit(::migrateOneStep)
                } catch (e: IOException) {
                    emit(list)
                }
            }
        }
        .retryWhen { error, attempt ->
            if (error !is IOException) return@retryWhen false
            emit(TokenList.EMPTY)
            delay(readRetryMillis * (attempt + 1).coerceAtMost(MAX_RETRY_STEPS))
            true
        }
        .distinctUntilChanged()

    override suspend fun add(name: String?, token: String): TokenProblem? = change { list ->
        list.problemAdding(name, token)?.let(Change::Refused) ?: Change.To(list.add(name, token, freshId(list)))
    }

    override suspend fun rename(id: String, name: String): TokenProblem? = change { list ->
        list.problemRenaming(id, name)?.let(Change::Refused) ?: Change.To(list.rename(id, name))
    }

    override suspend fun remove(id: String): TokenProblem? = change { list ->
        if (list.slotOf(id) == null) Change.Refused(TokenProblem.NotFound) else Change.To(list.remove(id))
    }

    override suspend fun activate(id: String): TokenProblem? = change { list ->
        if (list.slotOf(id) == null) Change.Refused(TokenProblem.NotFound) else Change.To(list.activate(id))
    }

    /**
     * Changes the list in one write. Whatever the migration still has to do comes first in the
     * same write, so a token an older version left under the old key is neither lost nor brought
     * back after the driver deleted it. Returns why [update] refused, or null.
     */
    private suspend fun change(update: (TokenList) -> Change): TokenProblem? {
        var problem: TokenProblem? = null
        dataStore.edit { preferences ->
            repeat(MIGRATION_STEPS) { migrateOneStep(preferences) }
            when (val result = update(stored(preferences))) {
                is Change.To -> write(preferences, result.list)
                is Change.Refused -> problem = result.problem
            }
        }
        return problem
    }

    /** The next step of [TokenMigration] on [preferences], and only that one. */
    private fun migrateOneStep(preferences: MutablePreferences) {
        val list = stored(preferences)
        val oldToken = preferences[LEGACY_KEY]
        when (TokenMigration.of(list, oldToken)) {
            TokenMigration.NONE -> Unit
            TokenMigration.ADD_ENTRY -> write(preferences, list.add(null, checkNotNull(oldToken), freshId(list)))
            TokenMigration.REMOVE_OLD_KEY -> preferences.remove(LEGACY_KEY)
        }
    }

    private fun stored(preferences: Preferences): TokenList = TokenList.decode(preferences[LIST_KEY], preferences[ACTIVE_KEY])

    private fun write(preferences: MutablePreferences, list: TokenList) {
        val active = list.activeId
        if (active == null) {
            preferences.remove(LIST_KEY)
            preferences.remove(ACTIVE_KEY)
        } else {
            preferences[LIST_KEY] = TokenList.encode(list)
            preferences[ACTIVE_KEY] = active
        }
    }

    /** An id no entry of [list] has. */
    private fun freshId(list: TokenList): String {
        repeat(ID_ATTEMPTS) {
            val id = newId()
            if (TokenList.isId(id) && list.slotOf(id) == null) return id
        }
        error("No unused entry id")
    }

    /** What a change of the list comes to: the new list, or why there is none. */
    private sealed interface Change {
        data class To(val list: TokenList) : Change

        data class Refused(val problem: TokenProblem) : Change
    }

    companion object {
        /** DataStore name. Must never be among the files res/xml/backup_rules.xml and data_extraction_rules.xml include. */
        const val FILE_NAME = "mapbox_credentials"

        /** Location of the file relative to the app's files directory. */
        const val RELATIVE_PATH = "datastore/$FILE_NAME.preferences_pb"

        /** The single token of the versions before the list; only ever read, to move it into the list, and removed. */
        private val LEGACY_KEY = stringPreferencesKey("mapbox_access_token")
        private val LIST_KEY = stringPreferencesKey("mapbox_tokens")
        private val ACTIVE_KEY = stringPreferencesKey("mapbox_active_token")

        /** Every key the store writes, all of them in the one file [FILE_NAME]. */
        val KEY_NAMES: Set<String> = listOf(LEGACY_KEY, LIST_KEY, ACTIVE_KEY).map { it.name }.toSet()

        private const val READ_RETRY_MS = 1_000L
        private const val MAX_RETRY_STEPS = 30L
        private const val MIGRATION_STEPS = 2
        private const val ID_ATTEMPTS = 10
        private const val ID_LENGTH = 12

        /** 12 random hex digits. */
        private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)
    }
}
