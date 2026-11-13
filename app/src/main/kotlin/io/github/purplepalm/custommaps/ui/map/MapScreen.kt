package io.github.purplepalm.custommaps.ui.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.MapboxEngine
import io.github.purplepalm.custommaps.engine.mapbox.map.PhoneMap
import io.github.purplepalm.custommaps.look.LookController
import io.github.purplepalm.custommaps.nav.ViewportPadding
import io.github.purplepalm.custommaps.nav.camera.GuidancePadding
import io.github.purplepalm.custommaps.nav.car.PhoneScreen
import io.github.purplepalm.custommaps.nav.car.PhoneScreenState
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.model.CameraMode
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.network.OfflineReason
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import io.github.purplepalm.custommaps.settings.AppSettings
import io.github.purplepalm.custommaps.ui.guidance.ArrivedCard
import io.github.purplepalm.custommaps.ui.guidance.GuidanceBar
import io.github.purplepalm.custommaps.ui.guidance.ManeuverPanel
import io.github.purplepalm.custommaps.ui.guidance.ReplaySlider
import io.github.purplepalm.custommaps.ui.guidance.THEN_CHIP_BAND
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The main screen: the map with search, route choice and guidance drawn over it. [network] is the
 * app's network as Android reports it, for the chip that says when the app is offline.
 *
 * During a drive while a car's surface shows the map, [phoneScreen] may be the list ([PhoneScreens]):
 * the phone then shows the drive without its map ([ListScreen]), with Map ([onMapButton]) to bring
 * the map back; the map is put away meanwhile, kept as it is but out of the window, and comes back as
 * of now. On the map the guidance bar has List ([onListButton]) while [phoneScreen] offers it. With no
 * car the screen is never the list and the bar has no List button.
 */
@Composable
fun MapScreen(
    engine: MapboxEngine,
    settings: AppSettings,
    looks: LookController,
    debugLog: DebugLog,
    network: StateFlow<NetworkStatus>,
    onOpenSettings: () -> Unit,
    phoneScreen: PhoneScreenState,
    onMapButton: () -> Unit,
    onListButton: () -> Unit,
) {
    val listShown = phoneScreen.screen == PhoneScreen.LIST
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val viewModel = viewModel { MapViewModel(engine.navigation, engine.search, settings, debugLog) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Guidance progress changes about once a second. It is passed on unread, so an update
    // recomposes only the two panels that show it, not this screen.
    val progress = viewModel.progress.collectAsStateWithLifecycle()
    // Where a simulated drive is, once a second; null unless one runs. Passed on unread as well:
    // only the replay slider reads it.
    val replay = viewModel.replay.collectAsStateWithLifecycle()
    // What the offline chip says, if anything. It is passed on unread as well: a change draws the
    // chip again and nothing else, and the wait before the chip comes goes on whatever the screen
    // shows meanwhile. A block Android reports only while the app is in the background is left out.
    val offline = rememberOfflineChip(network, debugLog) { engine.navigation.guidanceState.value }

    // The navigator lives while this screen does (and for the length of any drive).
    DisposableEffect(lifecycleOwner) {
        engine.attachPhoneHost(lifecycleOwner)
        onDispose { engine.detachPhoneHost(lifecycleOwner) }
    }

    // The map is made with the look it is to show, so its first tiles are drawn in that look, and only
    // in the composition that first shows it, whose view goes in the window at once (PhoneMapSlot): a
    // screen that starts on the list, as one made again mid-drive while a car shows the map, has no map
    // until the map is to show. Once made, the map is kept while the list shows in its place: back, it is
    // the same map, with no new style load. Everything below that works on the map waits for it.
    val mapSlot = remember { PhoneMapSlot<PhoneMap>() }
    val phoneMap = mapSlot.take(shown = !listShown) { engine.createPhoneMap(context, looks.lookOnMap.value) }
    if (phoneMap != null) DisposableEffect(phoneMap) { onDispose { phoneMap.release() } }
    // Put away while the list shows: its view leaves the window below, which stops it as a stopped
    // activity does, and its scene draws nothing. It takes no part in the look meanwhile, as a map not
    // made yet takes none: no look is shown on it, and the car map's close view holds a switch in place
    // of its own.
    DisposableEffect(phoneMap, listShown) {
        phoneMap?.setAway(listShown)
        looks.onPhoneMapAway(listShown)
        onDispose { looks.onPhoneMapAway(false) }
    }
    if (phoneMap != null && !listShown) {
        // Every later look, at once, and what the map did with it for the look's line in the debug
        // log. Back from the list, its first one is the look decided meanwhile.
        LaunchedEffect(phoneMap) { looks.showOn(phoneMap::setLook) }
        // Whether the camera shows a close view, which holds a look switch. Taken as the map works it
        // out at each progress update, within that update, so the switch reads the update's own.
        LaunchedEffect(phoneMap) {
            withContext(Dispatchers.Main.immediate) {
                try {
                    phoneMap.closeView.collect { looks.onCloseView(it) }
                } finally {
                    looks.onCloseView(false)
                }
            }
        }
    }
    if (phoneMap != null) {
        // While the activity is stopped nothing is on screen, and the camera draws no frames.
        LifecycleStartEffect(phoneMap) {
            phoneMap.setVisible(true)
            onStopOrDispose { phoneMap.setVisible(false) }
        }
    }
    // What the camera does, for the guidance bar on the map; there is no camera before the map.
    val cameraMode by (if (phoneMap != null) phoneMap.cameraMode.collectAsStateWithLifecycle() else NO_CAMERA)
    // The pin of the place picked from search, of the routes, the drive and the arrival. It goes
    // when the place does: the place card closed, guidance ended.
    val pinnedPlace = state.pinnedPlace
    if (phoneMap != null) {
        // The camera style chosen in Settings, and every later choice at once, also during a drive.
        LaunchedEffect(phoneMap) { settings.cameraStyle.collect(phoneMap::setCameraStyle) }
        // The same for the lane zoom: how close the camera goes before turns and lane choices.
        LaunchedEffect(phoneMap) { settings.laneZoom.collect(phoneMap::setLaneZoom) }
        // And for lane markings on the map, where the map has them.
        LaunchedEffect(phoneMap) { settings.laneDetails.collect(phoneMap::setLaneDetails) }
        // And for the Debug switch that has the app draw the route line itself.
        LaunchedEffect(phoneMap) { settings.ownRouteLine.collect(phoneMap::setOwnRouteLine) }
        // And for the one that has it draw the freeway lanes itself.
        LaunchedEffect(phoneMap) { settings.drawOwnLanes.collect(phoneMap::setDrawOwnLanes) }
        LaunchedEffect(phoneMap, pinnedPlace) { phoneMap.showPlace(pinnedPlace) }
    }

    var locationAccess by remember { mutableStateOf(context.locationAccess()) }
    // Routes and guidance need precise location; approximate is only good for the puck.
    val precise = locationAccess == LocationAccess.PRECISE
    var askedForLocation by rememberSaveable { mutableStateOf(false) }
    // The system stopped showing the permission dialog, so access has to be turned on in its settings.
    var locationBlocked by rememberSaveable { mutableStateOf(false) }
    // Also run on each resume by the effect of the screen's first composition, so it asks the slot for
    // the map, which that composition may not have had yet; a map made later asks for the access then.
    val refreshLocationAccess = {
        val access = context.locationAccess()
        if (access != LocationAccess.NONE && locationAccess == LocationAccess.NONE) mapSlot.map?.onLocationPermissionGranted()
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
    // Only a map that has asked for its style can be refused one.
    val mapAccessDenied = if (phoneMap != null) phoneMap.accessDenied.collectAsStateWithLifecycle().value else false

    // The Mapbox logo and attribution keep a fixed strip at the bottom left, just above the navigation
    // bar or the keyboard, and every panel stays above that strip. The floor keeps the logo clear of
    // rounded screen corners when the navigation bar takes no room.
    val ornamentInset = maxOf(
        WindowInsets.navigationBars.union(WindowInsets.ime).getBottom(density),
        with(density) { MIN_ORNAMENT_INSET.roundToPx() },
    )
    if (phoneMap != null) LaunchedEffect(ornamentInset) { phoneMap.setOrnamentBottomInset(ornamentInset) }
    val ornamentStrip = PhoneMap.ORNAMENT_STRIP_DP.dp
    val bottomPanelsBottom = with(density) { ornamentInset.toDp() } + ornamentStrip

    // Keep the route and the puck clear of the panels. At the top only the maneuver panel counts,
    // and during guidance the inset only grows, so a panel that changes its height, the search bar
    // and its suggestions never make the camera frame the road again. The Then chip hangs under
    // the panel, outside its measured size: the band it hangs in stays out of the camera's frame
    // for the whole of guidance, so the chip never covers what the camera frames, and the camera
    // never moves when the chip comes or goes. The offline chip is no input either: during
    // guidance it is a tab in the same band, and before it lies under the search bar, outside the
    // top column.
    var screenHeight by remember { mutableIntStateOf(0) }
    var topPanelTop by remember { mutableIntStateOf(0) }
    var topPanelBottom by remember { mutableIntStateOf(0) }
    var maneuverPanelBottom by remember { mutableIntStateOf(0) }
    var offlineChipBottom by remember { mutableIntStateOf(0) }
    var bottomPanelTop by remember { mutableIntStateOf(0) }
    val panelMargin = with(density) { 16.dp.roundToPx() }
    // A screen of another size starts over.
    val guidancePadding = remember(screenHeight) { GuidancePadding() }
    val guiding = state.guidance == GuidanceState.ACTIVE
    val thenBand = with(density) { THEN_CHIP_BAND.roundToPx() }
    if (phoneMap != null) {
        LaunchedEffect(screenHeight, guiding, topPanelTop, maneuverPanelBottom, bottomPanelTop, thenBand) {
            val top = guidancePadding.topInset(guiding, panelBottom = maneuverPanelBottom, restTop = topPanelTop, band = thenBand)
            val bottom = if (bottomPanelTop in 1 until screenHeight) screenHeight - bottomPanelTop else 0
            phoneMap.setViewportPadding(ViewportPadding(panelMargin, top + panelMargin, panelMargin, bottom + panelMargin))
        }
    }
    // The compass sits under whatever the top of the screen shows: under the offline chip when
    // that is up, and during guidance under the whole band, where the two tabs hang.
    val compassInset = compassTopInset(guiding, maneuverPanelBottom, thenBand, topPanelBottom, offlineChipBottom)
    if (phoneMap != null) LaunchedEffect(compassInset) { phoneMap.setCompassTopInset(compassInset + panelMargin) }

    BackHandler(enabled = state.guidance == GuidanceState.PREVIEW) { viewModel.onCancelPreview() }
    BackHandler(enabled = state.guidance == GuidanceState.IDLE && state.searchText.isNotEmpty()) { viewModel.clearSearch() }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height },
    ) {
        // The drive without the map, in its place: the list, the map one tap away. Past the list there
        // is always a map: it is made in the composition that first shows it, this one at the latest.
        if (listShown || phoneMap == null) {
            ListScreen(
                progress = progress,
                replay = replay,
                offline = offline,
                onMap = onMapButton,
                onEnd = viewModel::onEnd,
                onSeek = viewModel::onReplaySeek,
                onRunUpWords = { key, shown -> engine.onRunUpWordsShown(key, shown) },
            )
        } else {
            // The map's view is the same one when it comes back from the list. It leaves whatever parent it
            // had first, for a view can be in only one; Mapbox stops it as it leaves the window and starts it
            // as it is back, and nothing here calls its lifecycle.
            AndroidView(factory = { phoneMap.view.also { view -> (view.parent as? ViewGroup)?.removeView(view) } }, modifier = Modifier.fillMaxSize())

            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // Keeps the suggestions above the keyboard and the Mapbox logo on top of it.
                    .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                    .padding(bottom = ornamentStrip)
                    .padding(12.dp)
                    .onGloballyPositioned {
                        val bounds = it.boundsInRoot()
                        topPanelTop = bounds.top.toInt()
                        topPanelBottom = bounds.bottom.toInt()
                    },
            ) {
                when (state.guidance) {
                    GuidanceState.ACTIVE -> ManeuverSlot(
                        progress,
                        offline,
                        onBottomChanged = { maneuverPanelBottom = it },
                        onRunUpWords = { key, shown -> engine.onRunUpWordsShown(key, shown) },
                    )
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

            // The offline chip under the search bar. It is no part of the column above, so nothing
            // that is measured there changes when it comes or goes. It shows only where it ends above
            // the strip of the Mapbox logo.
            if (showsOfflineSentence(state.guidance, suggestionsShown = state.suggestions.isNotEmpty())) {
                val chipTop = topPanelBottom + with(density) { OFFLINE_CHIP_GAP.roundToPx() }
                val chipMaxBottom = screenHeight - ornamentInset - with(density) { ornamentStrip.roundToPx() }
                OfflineChipSlot(
                    reason = offline,
                    onOpenSettings = { context.openDataSettings(debugLog) },
                    onBottomChanged = { offlineChipBottom = it },
                    fits = { height -> offlineChipFits(chipTop, height, chipMaxBottom) },
                    modifier = Modifier
                        // From the search bar's bottom edge, as far below it as its suggestions are.
                        .offset { IntOffset(0, topPanelBottom) }
                        .padding(start = 12.dp, top = OFFLINE_CHIP_GAP, end = 12.dp),
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomPanelsBottom)
                    .onGloballyPositioned { bottomPanelTop = it.boundsInRoot().top.toInt() },
            ) {
                // While suggestions are open the driver is choosing a place; nothing else sits over the list.
                val choosing = state.suggestions.isNotEmpty()
                if (state.guidance == GuidanceState.IDLE && locationAccess != LocationAccess.NONE && !choosing) {
                    MyLocationButton(
                        onClick = { phoneMap.setCameraMode(CameraMode.FOLLOWING) },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(bottom = 8.dp),
                    )
                }
                // Over the guidance bar, clear of the maneuver card and of the Mapbox logo's strip.
                if (state.guidance == GuidanceState.ACTIVE) ReplaySlot(replay, onSeek = viewModel::onReplaySeek)
                when {
                    state.guidance == GuidanceState.PREVIEW -> RouteSheet(
                        destination = state.destination,
                        routes = state.routes,
                        simulateDrive = state.simulateDrive,
                        onSelect = viewModel::onRouteSelected,
                        onStart = startGuidance,
                        onClose = viewModel::onCancelPreview,
                    )
                    state.guidance == GuidanceState.ACTIVE -> GuidanceBarSlot(
                        progress = progress,
                        following = cameraMode == CameraMode.FOLLOWING,
                        onToggleOverview = {
                            phoneMap.setCameraMode(if (cameraMode == CameraMode.FOLLOWING) CameraMode.OVERVIEW else CameraMode.FOLLOWING)
                        },
                        onEnd = viewModel::onEnd,
                        onList = onListButton.takeIf { phoneScreen.listButton },
                    )
                    choosing -> Unit
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
        }

        // Material's snackbar reads the inverse roles: the look's dark card with an amber action.
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
        )
    }
}

/**
 * The maneuver panel once guidance has its first progress. It reads the progress itself, and
 * reports where its bottom edge is, 0 while it is not shown, for the camera's padding. The panel
 * is as large as the maneuver card alone: the Then chip under it is not measured, and neither is
 * the offline chip, here a tab at the other end of the same band, which reads [offline] itself.
 * Whether a run-up's words fitted on the card goes to [onRunUpWords], for the debug log.
 */
@Composable
private fun ManeuverSlot(
    progress: State<RouteProgressSnapshot?>,
    offline: State<OfflineReason>,
    onBottomChanged: (Int) -> Unit,
    onRunUpWords: (key: String, shown: Boolean) -> Unit,
) {
    progress.value?.let { current ->
        ManeuverPanel(
            progress = current,
            modifier = Modifier.onGloballyPositioned { onBottomChanged(it.boundsInRoot().bottom.toInt()) },
            onRunUpWords = onRunUpWords,
            endTab = { OfflinePill(offline) },
        )
        DisposableEffect(Unit) { onDispose { onBottomChanged(0) } }
    }
}

/**
 * The offline chip under the search bar. It reads [reason] itself and reports where its bottom
 * edge is, 0 while there is no chip, so that the compass can keep clear of it. A chip [fits] does
 * not let in at its measured height in pixels is left out, and reported as none. A chip left out
 * is not placed, and Compose calls no positioned callback for it, so its own layout reports the 0.
 */
@Composable
private fun OfflineChipSlot(
    reason: State<OfflineReason>,
    onOpenSettings: () -> Unit,
    onBottomChanged: (Int) -> Unit,
    fits: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (reason.value != OfflineReason.NONE) {
        OfflineChip(
            reason = reason,
            onOpenSettings = onOpenSettings,
            modifier = modifier
                // Called only while the chip is placed, so only for a chip that fits.
                .onGloballyPositioned { onBottomChanged(it.boundsInRoot().bottom.toInt()) }
                .layout { measurable, constraints ->
                    val chip = measurable.measure(constraints)
                    if (fits(chip.height)) layout(chip.width, chip.height) { chip.place(0, 0) } else layout(0, 0) { onBottomChanged(0) }
                },
        )
        DisposableEffect(Unit) { onDispose { onBottomChanged(0) } }
    }
}

/**
 * Whether the offline sentence fits above the strip of the Mapbox logo: from [top], its top edge,
 * [height] tall, it must end no lower than [maxBottom], where the strip begins. In pixels from the
 * top of the screen. A screen not measured yet has room for nothing.
 */
internal fun offlineChipFits(top: Int, height: Int, maxBottom: Int): Boolean = top + height <= maxBottom

/**
 * Whether the offline chip may show as the whole sentence under the search bar: only while the
 * search bar is there, before a drive and at the arrival. Not over an open list of suggestions,
 * which can reach down to the strip of the Mapbox logo, and not in the route preview, where it
 * would lie over the far end of the routes on the map. During guidance the chip is the tab under
 * the maneuver card instead, which only the guidance panel shows.
 */
internal fun showsOfflineSentence(guidance: GuidanceState, suggestionsShown: Boolean): Boolean =
    (guidance == GuidanceState.IDLE || guidance == GuidanceState.ARRIVED) && !suggestionsShown

/**
 * How far down from the top of the map the top of the screen reaches, for the compass to sit
 * under, in pixels. During guidance that is the band under the maneuver panel, whether anything
 * hangs in it or not, so the compass stays where it is when the Then chip or the offline tab
 * comes. Otherwise it is the top column, or the offline chip under it.
 *
 * @param maneuverPanelBottom the bottom edge of the maneuver panel; 0 while it is not shown
 * @param band how far the band under the maneuver panel reaches below it
 * @param topPanelBottom the bottom edge of the top column
 * @param offlineChipBottom the bottom edge of the offline chip under the search bar; 0 without one
 */
internal fun compassTopInset(guiding: Boolean, maneuverPanelBottom: Int, band: Int, topPanelBottom: Int, offlineChipBottom: Int): Int =
    if (guiding && maneuverPanelBottom > 0) {
        maxOf(topPanelBottom, maneuverPanelBottom + band)
    } else {
        maxOf(topPanelBottom, offlineChipBottom)
    }

/**
 * The replay slider while a simulated drive runs, from its start to its end. It asks [replay] only
 * whether there is a simulated drive; the slider reads the position itself, so a new position
 * composes nothing here.
 */
@Composable
private fun ReplaySlot(replay: State<ReplayPosition?>, onSeek: (Double) -> Unit) {
    val simulated by remember(replay) { derivedStateOf { replay.value != null } }
    if (simulated) ReplaySlider(position = replay, onSeek = onSeek, modifier = Modifier.padding(bottom = 4.dp))
}

/** The guidance bar, reading the progress itself; with [onList], while a car's screen shows the map, its List button. */
@Composable
private fun GuidanceBarSlot(
    progress: State<RouteProgressSnapshot?>,
    following: Boolean,
    onToggleOverview: () -> Unit,
    onEnd: () -> Unit,
    onList: (() -> Unit)?,
) {
    GuidanceBar(progress = progress.value, following = following, onToggleOverview = onToggleOverview, onEnd = onEnd, onList = onList)
}

@Composable
private fun MyLocationButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LookTheme.tokens
    Surface(modifier = modifier, shape = CircleShape, color = tokens.card, shadowElevation = tokens.overMapElevation) {
        IconButton(onClick = onClick) {
            Icon(
                painterResource(R.drawable.ic_my_location),
                contentDescription = stringResource(R.string.map_my_location),
                tint = tokens.onCard,
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

/** The camera of a screen that has no map yet, having started on the list: none, so idle. */
private val NO_CAMERA: State<CameraMode> = object : State<CameraMode> {
    override val value: CameraMode get() = CameraMode.IDLE
}

/** Lowest bottom inset for the Mapbox logo, so a rounded screen corner never clips it. */
private val MIN_ORNAMENT_INSET = 16.dp

/** Between the search bar, or its suggestions, and the offline sentence under it. */
private val OFFLINE_CHIP_GAP = 8.dp

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
