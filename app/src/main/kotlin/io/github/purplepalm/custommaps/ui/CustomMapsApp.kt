package io.github.purplepalm.custommaps.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.AppGraph
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.MapsAccess
import io.github.purplepalm.custommaps.nav.car.PhoneScreen
import io.github.purplepalm.custommaps.ui.map.MapScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsCatalogue
import io.github.purplepalm.custommaps.ui.settings.SettingsScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsViewModel
import io.github.purplepalm.custommaps.ui.settings.crashNow
import io.github.purplepalm.custommaps.ui.settings.isDebuggable
import io.github.purplepalm.custommaps.ui.settings.shareDebugLog
import io.github.purplepalm.custommaps.ui.settings.signedWithDefaultDebugKey
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.token.TokenSetupScreen
import io.github.purplepalm.custommaps.ui.token.TokenViewModel
import kotlinx.coroutines.launch

/**
 * Token setup until a token is stored, then the map. Settings opens over the map instead of
 * replacing it, so the map (and a drive in progress) carries on underneath. [onListShown] hears
 * whether the list of the maneuvers ahead is on the screen with nothing over it: its black page
 * wants light icons in the system bars, in both looks.
 */
@Composable
fun CustomMapsApp(graph: AppGraph, onListShown: (Boolean) -> Unit = {}) {
    val access by graph.mapsAccess.collectAsStateWithLifecycle()
    // The map is made once the first look is decided, so it draws its first tiles in that look.
    val lookDecided by graph.lookController.decided.collectAsStateWithLifecycle()
    // During a drive while a car's surface shows the map, the phone shows the drive without its map.
    val phoneScreen by graph.phoneScreens.state.collectAsStateWithLifecycle()
    val tokenViewModel = viewModel { TokenViewModel(graph.tokenStore, graph.engine.navigation.guidanceState, graph.debugLog) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Settings over the map hides it: a look that waits for a quiet moment of the drive goes now.
    LaunchedEffect(showSettings) { graph.lookController.onCovered(showSettings) }
    val listOnTop = phoneScreen.screen == PhoneScreen.LIST && access is MapsAccess.Ready && lookDecided && !showSettings
    LaunchedEffect(listOnTop) { onListShown(listOnTop) }

    // The page colour shows until the map has drawn, and behind its fade-in.
    Box(
        Modifier
            .fillMaxSize()
            .background(LookTheme.tokens.screen),
    ) {
        when (access) {
            MapsAccess.Checking -> Unit
            MapsAccess.NeedsToken -> TokenSetupScreen(
                onSaveToken = { tokenViewModel.add(name = null, input = it) },
                onOpenSettings = { showSettings = true },
            )
            is MapsAccess.Ready -> if (lookDecided) {
                MapScreen(
                    engine = graph.engine,
                    settings = graph.settings,
                    looks = graph.lookController,
                    debugLog = graph.debugLog,
                    network = graph.network.status,
                    onOpenSettings = { showSettings = true },
                    phoneScreen = phoneScreen,
                    onMapButton = graph.phoneScreens::onMapButton,
                    onListButton = graph.phoneScreens::onListButton,
                    settingsOpen = showSettings,
                )
            }
        }

        if (showSettings) {
            BackHandler { showSettings = false }
            val settingsViewModel = viewModel {
                SettingsViewModel(
                    graph.tripLogStore,
                    graph.settings,
                    graph.debugLog,
                    installedAt = graph::installedAt,
                    tokens = graph.tokenStore.list,
                    shownLook = graph.lookController.look,
                    nextLook = graph::nextLook,
                    tonesHeard = graph.tones.heard,
                    playTones = graph.tones::playDemo,
                    playToneAt = graph.tones::playSample,
                )
            }
            LaunchedEffect(Unit) {
                settingsViewModel.refreshLogSize()
                // What Play the tones heard the last time Settings was open belongs to that time.
                graph.tones.forgetHeard()
            }
            val tokenState by tokenViewModel.state.collectAsStateWithLifecycle()
            val values by settingsViewModel.values.collectAsStateWithLifecycle()
            // Debug builds only: read once from the app's own signing certificate.
            val debuggable = remember { context.isDebuggable() }
            val temporaryDebugKey = remember { debuggable && context.signedWithDefaultDebugKey() }
            // Every row of the page, from the catalogue: again only when what it shows changed.
            val groups = remember(values, debuggable, temporaryDebugKey) {
                SettingsCatalogue.page(values.copy(crashNow = debuggable, temporaryDebugKey = temporaryDebugKey))
            }
            SettingsScreen(
                groups = groups,
                tokens = tokenState,
                // Each checks again that no trip runs: the active token stays as it is during guidance.
                onAddToken = tokenViewModel::add,
                onRenameToken = tokenViewModel::rename,
                onUseToken = tokenViewModel::use,
                onDeleteToken = tokenViewModel::delete,
                onEvent = settingsViewModel::onEvent,
                onShareLog = {
                    scope.launch {
                        val files = settingsViewModel.exportLog(graph.debugLogHeader())
                        if (files.isEmpty()) {
                            Toast.makeText(context, R.string.debug_log_share_failed, Toast.LENGTH_LONG).show()
                        } else {
                            context.shareDebugLog(files)
                        }
                    }
                },
                onBack = { showSettings = false },
                onCrashNow = { if (debuggable) crashNow() },
            )
        }
    }
}
