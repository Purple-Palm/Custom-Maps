package io.github.purplepalm.custommaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.AppGraph
import io.github.purplepalm.custommaps.ui.home.HomeScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsScreen
import io.github.purplepalm.custommaps.ui.token.TokenViewModel

private enum class Screen { HOME, SETTINGS }

@Composable
fun CustomMapsApp(graph: AppGraph) {
    val tokenViewModel = viewModel { TokenViewModel(graph.tokenStore) }
    val tokenState by tokenViewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            tokenState = tokenState,
            onSaveToken = tokenViewModel::save,
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.HOME }
            SettingsScreen(
                tokenState = tokenState,
                onSaveToken = tokenViewModel::save,
                onClearToken = tokenViewModel::clear,
                onBack = { screen = Screen.HOME },
            )
        }
    }
}
