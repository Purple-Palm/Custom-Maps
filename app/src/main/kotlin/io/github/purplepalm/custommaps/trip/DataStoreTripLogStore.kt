package io.github.purplepalm.custommaps.trip

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.purplepalm.custommaps.nav.trip.TripLog
import io.github.purplepalm.custommaps.nav.trip.TripLogStore
import io.github.purplepalm.custommaps.nav.trip.TripSessionStart
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The trip session log in its own DataStore file; it is backed up like the settings. */
class DataStoreTripLogStore(private val dataStore: DataStore<Preferences>) : TripLogStore {

    override val entries: Flow<List<TripSessionStart>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> TripLog.decode(preferences[LOG_KEY].orEmpty()) }

    override suspend fun record(start: TripSessionStart) {
        dataStore.edit { preferences ->
            val entries = TripLog.decode(preferences[LOG_KEY].orEmpty())
            preferences[LOG_KEY] = TripLog.encode(TripLog.append(entries, start))
        }
    }

    companion object {
        const val FILE_NAME = "trip_log"

        /** Location of the file relative to the app's files directory, as the backup rules name it. */
        const val RELATIVE_PATH = "datastore/$FILE_NAME.preferences_pb"
        private val LOG_KEY = stringPreferencesKey("trip_session_starts")
    }
}
