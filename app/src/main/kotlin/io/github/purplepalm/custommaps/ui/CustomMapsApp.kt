package io.github.purplepalm.custommaps.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.AppGraph
import io.github.purplepalm.custommaps.MapsAccess
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.ui.map.MapScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsScreen
import io.github.purplepalm.custommaps.ui.settings.SettingsViewModel
import io.github.purplepalm.custommaps.ui.settings.crashNow
import io.github.purplepalm.custommaps.ui.settings.isDebuggable
import io.github.purplepalm.custommaps.ui.settings.shareDebugLog
import io.github.purplepalm.custommaps.ui.token.TokenSetupScreen
import io.github.purplepalm.custommaps.ui.token.TokenViewModel
import kotlinx.coroutines.launch

/**
 * Token setup until a token is stored, then the map. Settings opens over the map instead of
 * replacing it, so the map (and a drive in progress) carries on underneath.
 */
@Composable
fun CustomMapsApp(graph: AppGraph) {
    val access by graph.mapsAccess.collectAsStateWithLifecycle()
    val tokenViewModel = viewModel { TokenViewModel(graph.tokenStore, graph.debugLog) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                debugLog = graph.debugLog,
                onOpenSettings = { showSettings = true },
            )
        }

        if (showSettings) {
            BackHandler { showSettings = false }
            val settingsViewModel = viewModel { SettingsViewModel(graph.tripLogStore, graph.settings, graph.debugLog) }
            LaunchedEffect(Unit) { settingsViewModel.refreshLogSize() }
            val tokenState by tokenViewModel.state.collectAsStateWithLifecycle()
            val guidanceSettings by settingsViewModel.guidance.collectAsStateWithLifecycle()
            val debug by settingsViewModel.debug.collectAsStateWithLifecycle()
            val guidance by graph.engine.navigation.guidanceState.collectAsStateWithLifecycle()
            SettingsScreen(
                tokenState = tokenState,
                tokenEditable = guidance != GuidanceState.ACTIVE,
                guidance = guidanceSettings,
                debug = debug,
                onSaveToken = tokenViewModel::save,
                onClearToken = tokenViewModel::clear,
                onCameraStyleChange = settingsViewModel::setCameraStyle,
                onSimulateDriveChange = settingsViewModel::setSimulateDrive,
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
                onClearLog = settingsViewModel::clearLog,
                onBack = { showSettings = false },
                onCrashNow = if (context.isDebuggable()) ::crashNow else null,
            )
        }
    }
}
