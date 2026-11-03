package io.github.purplepalm.custommaps.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.ui.guidance.ArrivedCard
import io.github.purplepalm.custommaps.ui.guidance.GuidanceBar
import io.github.purplepalm.custommaps.ui.guidance.ManeuverCard

/** The main screen: the map with search, route choice and guidance drawn over it. */
@Composable
fun MapScreen(engine: MapboxEngine, settings: AppSettings, debugLog: DebugLog, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val viewModel = viewModel { MapViewModel(engine.navigation, engine.search, settings, debugLog) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The navigator lives while this screen does (and for the length of any drive).
    DisposableEffect(lifecycleOwner) {
        engine.attachHost(lifecycleOwner)
        onDispose { engine.detachHost(lifecycleOwner) }
    }

    val phoneMap = remember { engine.createPhoneMap(context) }
    DisposableEffect(phoneMap) { onDispose { phoneMap.release() } }
    val cameraMode by phoneMap.cameraMode.collectAsStateWithLifecycle()

    var locationAccess by remember { mutableStateOf(context.locationAccess()) }
    // Routes and guidance need precise location; approximate is only good for the puck.
    val precise = locationAccess == LocationAccess.PRECISE
    var askedForLocation by rememberSaveable { mutableStateOf(false) }
    // The system stopped showing the permission dialog, so access has to be turned on in its settings.
    var locationBlocked by rememberSaveable { mutableStateOf(false) }
    val refreshLocationAccess = {
        val access = context.locationAccess()
        if (access != LocationAccess.NONE && locationAccess == LocationAccess.NONE) phoneMap.onLocationPermissionGranted()
        if (access != locationAccess) {
            viewModel.onLocationAccessChanged(access != LocationAccess.NONE, access == LocationAccess.PRECISE)
        }
        locationAccess = access
        if (access == LocationAccess.PRECISE) locationBlocked = false
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshLocationAccess()
        // After a second denial Android answers at once without asking; it never does on the first request.
        locationBlocked = locationAccess != LocationAccess.PRECISE && askedForLocation && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        askedForLocation = true
    }
    // Guidance starts whatever the answer: the trip notification is helpful but not required.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onStart()
    }
    // On Android 12 and later, asking again offers to switch approximate location to precise.
    val requestLocation = {
        if (locationBlocked) {
            context.openAppSettings()
        } else {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val startGuidance = {
        when {
            !precise -> requestLocation()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasNotificationPermission() ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.onStart()
        }
    }
    // Asks once when the screen opens; system settings open only when the driver taps the prompt.
    LaunchedEffect(Unit) {
        if (locationAccess == LocationAccess.NONE && !locationBlocked) requestLocation()
    }
    // Access may also be granted in system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        refreshLocationAccess()
        onPauseOrDispose { }
    }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val messageText = message?.let { stringResource(it.textRes()) }
    val settingsLabel = stringResource(R.string.settings_title)
    LaunchedEffect(message) {
        if (messageText != null) {
            // A refused token can only be fixed in Settings, so that message links there.
            val toSettings = message == UserMessage.TOKEN_REJECTED
            val result = snackbar.showSnackbar(
                message = messageText,
                actionLabel = if (toSettings) settingsLabel else null,
                duration = if (toSettings) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            viewModel.onMessageShown()
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }
    val mapAccessDenied by phoneMap.accessDenied.collectAsStateWithLifecycle()

    // The Mapbox logo and attribution keep a fixed strip at the bottom left, just above the navigation
    // bar or the keyboard, and every panel stays above that strip. The floor keeps the logo clear of
    // rounded screen corners when the navigation bar takes no room.
    val ornamentInset = maxOf(
        WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density),
        with(density) { MIN_ORNAMENT_INSET.roundToPx() },
    )
    LaunchedEffect(ornamentInset) { phoneMap.setOrnamentBottomInset(ornamentInset) }
    val ornamentStrip = PhoneMap.ORNAMENT_STRIP_DP.dp
    val bottomPanelsBottom = with(density) { ornamentInset.toDp() } + ornamentStrip

    // Keep the route and the puck clear of the panels.
    var screenHeight by remember { mutableIntStateOf(0) }
    var topPanelBottom by remember { mutableIntStateOf(0) }
    var bottomPanelTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(screenHeight, topPanelBottom, bottomPanelTop) {
        val margin = with(density) { 16.dp.roundToPx() }
        val bottom = if (bottomPanelTop in 1 until screenHeight) screenHeight - bottomPanelTop else 0
        phoneMap.setViewportPadding(ViewportPadding(margin, topPanelBottom + margin, margin, bottom + margin))
    }

    BackHandler(enabled = state.guidance == GuidanceState.PREVIEW) { viewModel.onCancelPreview() }
    BackHandler(enabled = state.guidance == GuidanceState.IDLE && state.searchText.isNotEmpty()) { viewModel.clearSearch() }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height },
    ) {
        AndroidView(factory = { phoneMap.view }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                // Keeps the suggestions above the keyboard and the Mapbox logo on top of it.
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                .padding(bottom = ornamentStrip)
                .padding(12.dp)
                .onGloballyPositioned { topPanelBottom = it.boundsInRoot().bottom.toInt() },
        ) {
            when (state.guidance) {
                GuidanceState.ACTIVE -> state.progress?.let { ManeuverCard(it) }
                GuidanceState.IDLE, GuidanceState.ARRIVED -> {
                    SearchBar(
                        text = state.searchText,
                        // Also while a picked suggestion is being looked up.
                        searching = state.searching || (state.busy && state.selectedPlace == null),
                        onTextChange = viewModel::onSearchTextChange,
                        onClear = viewModel::clearSearch,
                        onOpenSettings = onOpenSettings,
                    )
                    if (state.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SuggestionList(
                            suggestions = state.suggestions,
                            onPick = { suggestion ->
                                focusManager.clearFocus()
                                viewModel.onSuggestionPicked(suggestion)
                            },
                        )
                    }
                }
                GuidanceState.PREVIEW -> Unit
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomPanelsBottom)
                .onGloballyPositioned { bottomPanelTop = it.boundsInRoot().top.toInt() },
        ) {
            if (state.guidance == GuidanceState.IDLE && locationAccess != LocationAccess.NONE) {
                MyLocationButton(
                    onClick = { phoneMap.setCameraMode(CameraMode.FOLLOWING) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 8.dp),
                )
            }
            when {
                state.guidance == GuidanceState.PREVIEW -> RouteSheet(
                    destination = state.destination,
                    routes = state.routes,
                    simulateDrive = state.simulateDrive,
                    onSelect = viewModel::onRouteSelected,
                    onStart = startGuidance,
                    onClose = viewModel::onCancelPreview,
                )
                state.guidance == GuidanceState.ACTIVE -> GuidanceBar(
                    progress = state.progress,
                    following = cameraMode == CameraMode.FOLLOWING,
                    onToggleOverview = {
                        phoneMap.setCameraMode(if (cameraMode == CameraMode.FOLLOWING) CameraMode.OVERVIEW else CameraMode.FOLLOWING)
                    },
                    onEnd = viewModel::onEnd,
                )
                state.guidance == GuidanceState.ARRIVED -> ArrivedCard(
                    destination = state.destination,
                    onDone = viewModel::onDismissArrival,
                )
                state.selectedPlace != null -> PlaceCard(
                    place = requireNotNull(state.selectedPlace),
                    busy = state.busy,
                    onDirections = {
                        if (precise) viewModel.onDirections() else requestLocation()
                    },
                    onClose = viewModel::clearSearch,
                )
                mapAccessDenied -> ActionPrompt(
                    text = stringResource(R.string.token_rejected_prompt),
                    action = settingsLabel,
                    onAction = onOpenSettings,
                )
                !precise -> ActionPrompt(
                    text = stringResource(
                        when {
                            locationBlocked -> R.string.location_prompt_blocked
                            locationAccess == LocationAccess.APPROXIMATE -> R.string.location_prompt_precise
                            else -> R.string.location_prompt
                        },
                    ),
                    action = stringResource(if (locationBlocked) R.string.location_open_settings else R.string.location_allow),
                    onAction = requestLocation,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
        )
    }
}

@Composable
private fun MyLocationButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        IconButton(onClick = onClick) {
            Icon(
                painterResource(R.drawable.ic_my_location),
                contentDescription = stringResource(R.string.map_my_location),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@StringRes
private fun UserMessage.textRes(): Int = when (this) {
    UserMessage.NO_LOCATION -> R.string.message_no_location
    UserMessage.NO_ROUTE -> R.string.message_no_route
    UserMessage.ROUTE_FAILED -> R.string.message_route_failed
    UserMessage.SEARCH_FAILED -> R.string.message_search_failed
    UserMessage.PLACE_NOT_FOUND -> R.string.message_place_not_found
    UserMessage.START_FAILED -> R.string.message_start_failed
    UserMessage.TOKEN_REJECTED -> R.string.message_token_rejected
}

/** Lowest bottom inset for the Mapbox logo, so a rounded screen corner never clips it. */
private val MIN_ORNAMENT_INSET = 16.dp

private enum class LocationAccess { NONE, APPROXIMATE, PRECISE }

private fun Context.locationAccess(): LocationAccess = when {
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
    isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
    else -> LocationAccess.NONE
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** This app's page in the system settings, where blocked permissions are turned back on. */
private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(Manifest.permission.POST_NOTIFICATIONS)
