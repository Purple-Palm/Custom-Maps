package io.github.purplepalm.custommaps.ui.guidance

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

private const val TABULAR_DIGITS = "tnum"

/** Next maneuver: the arrow, a large distance and the road, with lane guidance below. */
@Composable
fun ManeuverCard(progress: RouteProgressSnapshot, modifier: Modifier = Modifier) {
    val maneuver = progress.nextManeuver
    val distance = ImperialFormat.distance(progress.distanceToNextManeuver)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverGlyph(
                    type = maneuver.type,
                    turn = maneuver.modifier,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = distance.value,
                            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = TABULAR_DIGITS),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = distance.unit,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text(
                        text = maneuver.primaryText,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        maneuver.roundaboutExitNumber?.let { stringResource(R.string.guidance_roundabout_exit, it) },
                        maneuver.secondaryText,
                    ).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            progress.lanes?.let { lanes ->
                Spacer(Modifier.height(12.dp))
                LanesRow(lanes)
            }
        }
    }
}

/** Lanes from left to right; the ones to use are amber, the others dim. */
@Composable
fun LanesRow(lanes: LaneInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        lanes.lanes.forEach { lane ->
            LaneGlyph(
                lane = lane,
                highlight = MaterialTheme.colorScheme.primary,
                dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(36.dp),
            )
        }
    }
}

/** Arrival time, time and distance left, and the controls to change view or end guidance. */
@Composable
fun GuidanceBar(
    progress: RouteProgressSnapshot?,
    following: Boolean,
    onToggleOverview: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (progress != null) {
                    Text(
                        text = ImperialFormat.arrivalTime(ZonedDateTime.now(), progress.durationRemaining),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = TABULAR_DIGITS),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.guidance_remaining,
                            ImperialFormat.duration(progress.durationRemaining),
                            ImperialFormat.distance(progress.distanceRemaining).toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.guidance_starting),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onToggleOverview) {
                Text(stringResource(if (following) R.string.guidance_overview else R.string.guidance_recenter))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.guidance_end))
            }
        }
    }
}

@Composable
fun ArrivedCard(destination: Place?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverGlyph(
                type = ManeuverType.ARRIVE,
                turn = null,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.guidance_arrived),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                destination?.let {
                    Text(
                        text = it.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(onClick = onDone) {
                Text(stringResource(R.string.guidance_done))
            }
        }
    }
}

@Preview
@Composable
private fun ManeuverCardPreview() {
    CustomMapsTheme {
        ManeuverCard(
            RouteProgressSnapshot(
                nextManeuver = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: CA-85 S", "Mountain View"),
                distanceToNextManeuver = Distance.miles(0.4),
                lanes = LaneInfo(
                    listOf(
                        Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                        Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                        Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), true, TurnModifier.SLIGHT_RIGHT),
                        Lane(setOf(TurnModifier.SLIGHT_RIGHT), true, TurnModifier.SLIGHT_RIGHT),
                    ),
                ),
                distanceRemaining = Distance.miles(12.3),
                durationRemaining = 18.minutes,
            ),
        )
    }
}
