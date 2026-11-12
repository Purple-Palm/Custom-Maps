package io.github.purplepalm.custommaps.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.nav.network.NetworkStatus
import io.github.purplepalm.custommaps.nav.network.OfflineReason
import io.github.purplepalm.custommaps.nav.network.offlineReason
import io.github.purplepalm.custommaps.ui.guidance.CARD_CORNER
import io.github.purplepalm.custommaps.ui.guidance.ManeuverPanel
import io.github.purplepalm.custommaps.ui.guidance.THEN_CHIP_BAND
import io.github.purplepalm.custommaps.ui.guidance.THEN_CHIP_PADDING
import io.github.purplepalm.custommaps.ui.guidance.THEN_CHIP_SHAPE
import io.github.purplepalm.custommaps.ui.guidance.thenSideMinFont
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.theme.PreviewOnMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * How long the app has to be without internet before the chip says so. A phone that leaves Wi-Fi
 * for mobile data is without a network for a second or two, and that is no news.
 */
internal const val OFFLINE_CHIP_WAIT_MILLIS = 3_000L

/**
 * [status] as the offline chip counts it, while [onScreen] says whether the screen is started.
 *
 * Android keeps an app off the network a few seconds after it leaves the screen (Android 15 does
 * so for every app, Data saver and Battery saver for apps in the background) and lets it back on
 * a moment after the screen is up again. A block that comes while the screen is stopped is
 * therefore left out; only one that has lasted since the screen was last started counts. A lost
 * network, or one without internet, counts wherever the screen is.
 */
internal fun countedStatus(status: Flow<NetworkStatus>, onScreen: Flow<Boolean>): Flow<NetworkStatus> = flow {
    // Whether the app has been blocked since the screen was last started.
    var blockedOnScreen = false
    combine(status, onScreen, ::Pair).collect { (current, started) ->
        if (started) {
            blockedOnScreen = current.blocked
        } else if (!current.blocked) {
            blockedOnScreen = false
        }
        emit(if (started || blockedOnScreen) current else current.copy(blocked = false))
    }
}

/**
 * What the offline chip says, as it changes: why the app has no internet, or [OfflineReason.NONE]
 * while there is no chip. [onScreen] says whether the screen is started; see [countedStatus] for
 * what that changes.
 *
 * The chip comes [waitMillis] after the app lost its internet, if it is still without it, and goes
 * at once when the internet is back. The wait is counted from the loss: a reason that changes on
 * the way does not start it over, and one that changes while the chip is up changes what the chip
 * says, with no new wait. Before Android has said anything the app does not count as offline.
 *
 * The wait runs in whoever collects this, so it starts when the collecting does: a screen made
 * while the app is offline gets its chip [waitMillis] later.
 */
internal fun shownOfflineReason(
    status: Flow<NetworkStatus>,
    onScreen: Flow<Boolean> = flowOf(true),
    waitMillis: Long = OFFLINE_CHIP_WAIT_MILLIS,
): Flow<OfflineReason> =
    channelFlow {
        // Worked out once, so that both collections below see the same reasons.
        val reasons = countedStatus(status, onScreen).map { it.offlineReason() }.stateIn(this)
        // Only going offline and coming back start something new; the reason alone does not.
        reasons.map { it != OfflineReason.NONE }.distinctUntilChanged().collectLatest { offline ->
            if (offline) {
                delay(waitMillis)
                // The chip is up: the reason as it is now, and each one after it. Being online
                // again ends this.
                reasons.filter { it != OfflineReason.NONE }.collect { send(it) }
            } else {
                send(OfflineReason.NONE)
            }
        }
    }.distinctUntilChanged()

/**
 * Whether the chip's sentence for [reason] comes with a Settings button: only when Android keeps
 * the app off the network, which the app's own system pages can change. Nothing there helps
 * without a network or without internet.
 */
internal fun offersSettings(reason: OfflineReason): Boolean = reason == OfflineReason.BLOCKED

/** The chip's sentence for [reason]; null when the app is online and there is nothing to say. */
@StringRes
internal fun offlineSentenceRes(reason: OfflineReason): Int? = when (reason) {
    OfflineReason.NONE -> null
    OfflineReason.NO_NETWORK -> R.string.offline_no_network
    OfflineReason.NO_INTERNET -> R.string.offline_no_internet
    OfflineReason.BLOCKED -> R.string.offline_blocked
}

/**
 * The debug line for a chip that came, went or changed what it says. [guidance] tells which form
 * the driver saw: the sentence under the search bar, the tab under the maneuver card during
 * guidance, or neither, as in the route preview.
 */
internal fun offlineChipLine(reason: OfflineReason, guidance: GuidanceState): Map<String, Any?> = mapOf(
    "shown" to (reason != OfflineReason.NONE),
    "reason" to reason.name,
    "guidance" to guidance.name,
)

/**
 * Follows [network] for the offline chip: the reason the chip gives now, [OfflineReason.NONE]
 * while there is none. Each change is one "offline_chip" line in the debug log, written when the
 * chip changes and not when something is drawn again.
 *
 * Call it once for a screen and pass the result on unread: only what draws the chip reads it, so
 * a change draws the chip again and nothing else. It keeps following while the screen is in the
 * background, so the chip is right the moment the screen is back. A block that Android reports
 * only while the screen is stopped is left out ([countedStatus]): it is lifted a moment after the
 * screen returns, and no one saw it.
 */
@Composable
internal fun rememberOfflineChip(network: StateFlow<NetworkStatus>, debugLog: DebugLog, guidance: () -> GuidanceState): State<OfflineReason> {
    val guidanceNow by rememberUpdatedState(guidance)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(OfflineReason.NONE, network, debugLog, lifecycle) {
        val onScreen = lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()
        try {
            shownOfflineReason(network, onScreen).collect { reason ->
                if (reason != value) {
                    value = reason
                    debugLog.log("offline_chip", offlineChipLine(reason, guidanceNow()))
                }
            }
        } finally {
            // The screen is going, and a chip that was up goes with it.
            if (value != OfflineReason.NONE) debugLog.log("offline_chip", offlineChipLine(OfflineReason.NONE, guidanceNow()))
        }
    }
}

/**
 * The offline chip while no drive runs: the whole sentence, for [reason], and nothing while the
 * app is online. When Android keeps the app off the network, a button leads to the system page
 * where that is changed.
 *
 * It belongs under the search bar, outside the column that is measured there, so that it comes and
 * goes without moving anything else.
 */
@Composable
fun OfflineChip(reason: State<OfflineReason>, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val current = reason.value
    val sentence = offlineSentenceRes(current)
    if (sentence != null) {
        val blocked = offersSettings(current)
        val tokens = LookTheme.tokens
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = tokens.card,
            shadowElevation = tokens.overMapElevation,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = if (blocked) 8.dp else 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_offline),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = tokens.danger,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.onCard,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        // A screen reader says it when it comes, and again when the reason changes.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (blocked) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.offline_settings)) }
                }
            }
        }
    }
}

/**
 * The offline chip during guidance: the icon and one word, as the end tab of
 * [ManeuverPanel], in the band under the maneuver card where the Then chip hangs. It has no
 * button, since nothing on the screen asks for a tap while the car moves; a screen reader gets
 * the whole sentence for [reason]. Nothing while the app is online.
 *
 * The panel gives it its share of the card's width. The word gets smaller before it is cut, on a
 * narrow screen or with a large font, and the tab stays as high as the band whatever the font.
 */
@Composable
fun OfflinePill(reason: State<OfflineReason>, modifier: Modifier = Modifier) {
    val sentence = offlineSentenceRes(reason.value)
    if (sentence != null) {
        val description = stringResource(sentence)
        val tokens = LookTheme.tokens
        Surface(
            modifier = modifier.clearAndSetSemantics { contentDescription = description },
            shape = THEN_CHIP_SHAPE,
            color = tokens.card,
            shadowElevation = tokens.overMapElevation,
        ) {
            Row(
                // The top of the tab stays under the card's rounded bottom edge, like the Then chip's.
                // Narrow at the sides: on a screen 320 dp wide the word has 39 dp left, and needs
                // 36 dp at its smallest.
                modifier = Modifier
                    .padding(start = 10.dp, top = CARD_CORNER + THEN_CHIP_PADDING, end = 10.dp, bottom = THEN_CHIP_PADDING)
                    .height(THEN_CHIP_BAND - THEN_CHIP_PADDING * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_offline),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = tokens.danger,
                )
                Spacer(Modifier.width(6.dp))
                val style = MaterialTheme.typography.labelLarge
                Text(
                    text = stringResource(R.string.offline_short),
                    style = style,
                    color = tokens.onCard,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = LocalDensity.current.thenSideMinFont(), maxFontSize = style.fontSize),
                )
            }
        }
    }
}

/**
 * The system pages where the owner lets the app use the network again, the best one first: the
 * app's own data page, with background data and the Data saver exception, then the app's details
 * page, which every phone has and which leads to the same switches.
 */
internal val DATA_SETTINGS_ACTIONS = listOf(
    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
)

/**
 * Opens the first of [actions] that [start] can open, and returns it; null when it could open
 * none. [start] throws, as `startActivity` does, for a page this phone does not have
 * ([ActivityNotFoundException]) or does not let other apps open ([SecurityException]); the next
 * one is tried then, and nothing else stops.
 */
internal fun openFirst(actions: List<String>, start: (String) -> Unit): String? = actions.firstOrNull { action ->
    try {
        start(action)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * Opens this app's data settings for the chip's Settings button, and notes in the debug log which
 * page opened. A phone without any of the pages opens nothing; that is no reason to stop the app.
 */
internal fun Context.openDataSettings(debugLog: DebugLog) {
    val app = Uri.fromParts("package", packageName, null)
    val opened = openFirst(DATA_SETTINGS_ACTIONS) { action -> startActivity(Intent(action, app)) }
    debugLog.log("offline_settings", mapOf("opened" to opened))
}

/** Each sentence under a search bar, on a narrow phone, also with a large font. */
@Preview(name = "offline chip, each reason", widthDp = 360)
@Preview(name = "offline chip, each reason, large font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun OfflineChipPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchBar(text = "", searching = false, onTextChange = {}, onClear = {}, onOpenSettings = {})
            for (reason in listOf(OfflineReason.NO_NETWORK, OfflineReason.NO_INTERNET, OfflineReason.BLOCKED)) {
                OfflineChip(reason = remember { mutableStateOf(reason) }, onOpenSettings = {})
            }
        }
    }
}

/**
 * The tab beside a Then chip that is as wide as it may be, under a card with lanes, on a narrow
 * phone, also with a large font and on a screen set to show everything larger: the two stay apart.
 */
@Preview(name = "offline tab beside a long Then chip", widthDp = 360)
@Preview(name = "offline tab beside a long Then chip, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "offline tab beside a long Then chip, narrow screen", widthDp = 320, fontScale = 1.3f)
@Composable
private fun OfflinePillBesideThenPreview(@PreviewParameter(LookParameter::class) look: Look) {
    OfflinePillPreview(
        look,
        then = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street Old Mill Extension"),
        lanes = LaneInfo(
            listOf(
                Lane(setOf(TurnModifier.LEFT), isRecommended = true, activeIndication = TurnModifier.LEFT),
                Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), isRecommended = false),
            ),
        ),
    )
}

/** The tab alone, under a card without lanes. */
@Preview(name = "offline tab alone", widthDp = 360)
@Composable
private fun OfflinePillAlonePreview(@PreviewParameter(LookParameter::class) look: Look) {
    OfflinePillPreview(look, then = null, lanes = null)
}

@Composable
private fun OfflinePillPreview(look: Look, then: Maneuver?, lanes: LaneInfo?) {
    PreviewOnMap(look, Modifier.height(300.dp)) {
        val reason = remember { mutableStateOf(OfflineReason.NO_NETWORK) }
        ManeuverPanel(
            progress = RouteProgressSnapshot(
                nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Oak Street"),
                distanceToNextManeuver = Distance.feet(350.0),
                lanes = lanes,
                distanceRemaining = Distance.miles(2.1),
                durationRemaining = 7.minutes,
                following = then?.let { FollowingManeuver(it, distanceAfterNext = Distance.meters(120.0), durationAfterNext = 10.seconds) },
                showThen = then != null,
            ),
            endTab = { OfflinePill(reason) },
        )
    }
}
