package io.github.purplepalm.custommaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.AppGraph
import io.github.purplepalm.custommaps.MapsAccess
import io.github.purplepalm.custommaps.ui.map.MapScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsScreen
import io.github.purplepalm.custommaps.ui.token.TokenSetupScreen
import io.github.purplepalm.custommaps.ui.token.TokenViewModel

/**
 * Token setup until a token is stored, then the map. Settings opens over the map instead of
 * replacing it, so the map (and a drive in progress) carries on underneath.
 */
@Composable
fun CustomMapsApp(graph: AppGraph) {
    val access by graph.mapsAccess.collectAsStateWithLifecycle()
    val tokenViewModel = viewModel { TokenViewModel(graph.tokenStore) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (access) {
            MapsAccess.Checking -> Unit
            MapsAccess.NeedsToken -> TokenSetupScreen(
                onSaveToken = tokenViewModel::save,
                onOpenSettings = { showSettings = true },
            )
            is MapsAccess.Ready -> MapScreen(
                engine = graph.engine,
                settings = graph.settings,
                onOpenSettings = { showSettings = true },
            )
        }

        if (showSettings) {
            BackHandler { showSettings = false }
            val tokenState by tokenViewModel.state.collectAsStateWithLifecycle()
            SettingsScreen(
                tokenState = tokenState,
                onSaveToken = tokenViewModel::save,
                onClearToken = tokenViewModel::clear,
                onBack = { showSettings = false },
            )
        }
    }
}
