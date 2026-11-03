package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Settings the driver changes on the phone. */
interface AppSettings {
    /** Replay the route instead of following GPS; for testing at home. Still billed as a trip. */
    val simulateDrive: Flow<Boolean>

    suspend fun setSimulateDrive(enabled: Boolean)
}

class DataStoreAppSettings(private val dataStore: DataStore<Preferences>) : AppSettings {

    override val simulateDrive: Flow<Boolean> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> preferences[SIMULATE_DRIVE] ?: false }

    override suspend fun setSimulateDrive(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[SIMULATE_DRIVE] = enabled }
    }

    companion object {
        const val FILE_NAME = "app_settings"

        /** Location of the file relative to the app's files directory, as the backup rules name it. */
        const val RELATIVE_PATH = "datastore/$FILE_NAME.preferences_pb"
        private val SIMULATE_DRIVE = booleanPreferencesKey("simulate_drive")
    }
}
