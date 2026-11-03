package io.github.purplepalm.custommaps

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens
import io.github.purplepalm.custommaps.token.DataStoreTokenStore
import io.github.purplepalm.custommaps.token.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Creates the app's long-lived objects once per process. */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val tokenStore: TokenStore = DataStoreTokenStore(
        PreferenceDataStoreFactory.create(
            produceFile = { appContext.preferencesDataStoreFile(DataStoreTokenStore.FILE_NAME) },
        ),
    )

    fun start() {
        // Hand the stored token to the SDKs as soon as it is known and whenever it is replaced.
        applicationScope.launch {
            tokenStore.token
                .filterNotNull()
                .filter { MapboxAccessTokens.check(it) == AccessTokenCheck.VALID }
                .collect(MapboxAccessTokens::apply)
        }
    }
}
