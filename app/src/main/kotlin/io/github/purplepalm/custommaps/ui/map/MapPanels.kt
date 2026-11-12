package io.github.purplepalm.custommaps.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.format.ImperialFormat
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.ui.theme.AccentButton
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.theme.PreviewOnMap
import kotlin.time.Duration.Companion.minutes

/** Search field with the settings button next to it. */
@Composable
fun SearchBar(
    text: String,
    searching: Boolean,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val tokens = LookTheme.tokens
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .shadow(tokens.overMapElevation, SEARCH_SHAPE),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = SEARCH_SHAPE,
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = tokens.onCardMuted)
            },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    text.isNotEmpty() -> IconButton(onClick = onClear) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The search key puts the keyboard away, so the suggestions and the map show in full.
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            // The focused border and the caret keep Material's primary, the dark amber by day.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = tokens.card,
                unfocusedContainerColor = tokens.card,
                unfocusedBorderColor = tokens.divider,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Surface(shape = SEARCH_SHAPE, color = tokens.card, shadowElevation = tokens.overMapElevation) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings_title),
                    tint = tokens.onCard,
                )
            }
        }
    }
}

/** The search field and the settings button beside it. */
private val SEARCH_SHAPE = RoundedCornerShape(28.dp)

@Composable
fun SuggestionList(
    suggestions: List<PlaceSuggestion>,
    onPick: (PlaceSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(suggestions, key = { it.id }) { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(suggestion) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = suggestion.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.onCard,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        suggestion.description?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.onCardMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    suggestion.distance?.let {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = ImperialFormat.distance(it).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.onCardMuted,
                        )
                    }
                }
                HorizontalDivider(color = tokens.divider)
            }
        }
    }
}

/** The place picked from search, before routes are requested. */
@Composable
fun PlaceCard(place: Place, busy: Boolean, onDirections: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleLarge, color = tokens.onCard)
            place.address?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.onCardMuted)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                Spacer(Modifier.width(8.dp))
                AccentButton(onClick = onDirections, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.place_directions))
                    }
                }
            }
        }
    }
}

/**
 * Routes to choose from; the first one is selected and drawn in amber on the map. A line under them
 * says how far from the place the selected route ends, when that is worth knowing ([routeEndNote]).
 */
@Composable
fun RouteSheet(
    destination: Place?,
    routes: List<RouteOption>,
    simulateDrive: Boolean,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Column(Modifier.padding(16.dp)) {
            destination?.let {
                Text(
                    text = it.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            routes.forEachIndexed { index, route ->
                val selected = index == 0
                Surface(
                    onClick = { onSelect(route.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.cardRaised,
                    // The selected row's own mark: its colour alone would differ too little.
                    border = if (selected) BorderStroke(2.dp, tokens.accentInk) else null,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        // Read at a glance: a step heavier than Material's, for sunlight.
                        Text(
                            text = ImperialFormat.duration(route.duration),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) tokens.glanceAccent else tokens.onCard,
                        )
                        val via = route.summary.takeIf { it.isNotBlank() }?.let { stringResource(R.string.route_via, it) }
                        Text(
                            text = listOfNotNull(ImperialFormat.distance(route.distance).toString(), via).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.onCardMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            routeEndNote(destination?.stopPoint, routes.firstOrNull()?.endGap)?.let { away ->
                Text(
                    text = stringResource(R.string.route_ends_away, away.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.onCardMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (simulateDrive) {
                Text(
                    text = stringResource(R.string.route_simulated_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.onCardMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                Spacer(Modifier.weight(1f))
                AccentButton(onClick = onStart, enabled = routes.isNotEmpty()) {
                    Text(stringResource(if (simulateDrive) R.string.route_start_simulated else R.string.route_start))
                }
            }
        }
    }
}

/** Explains what the map needs, such as location access or a working token, with the action that fixes it. */
@Composable
fun ActionPrompt(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LookTheme.tokens
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tokens.card,
        shadowElevation = tokens.overMapElevation,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.onCard,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            AccentButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Search before a drive: the field, the settings button and two suggestions, on a narrow phone, also with a large font. */
@Preview(name = "search", widthDp = 360)
@Preview(name = "search, large font", widthDp = 360, fontScale = 1.3f)
@Composable
private fun SearchPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        Column {
            SearchBar(text = "Of", searching = false, onTextChange = {}, onClear = {}, onOpenSettings = {})
            Spacer(Modifier.height(8.dp))
            SuggestionList(
                suggestions = listOf(
                    PlaceSuggestion("1", "Office", "12 Oak Street", Distance.miles(2.4)),
                    PlaceSuggestion("2", "Office supplies", "40 Elm Street", Distance.miles(5.1)),
                ),
                onPick = {},
            )
        }
    }
}

/** The place picked from search, and the prompt that asks for location access. */
@Preview(name = "place and prompt", widthDp = 360)
@Composable
private fun PlaceCardPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        Column {
            PlaceCard(Place("Gym", "8 Pine Street", GeoPoint(0.0, 0.0)), busy = false, onDirections = {}, onClose = {})
            Spacer(Modifier.height(12.dp))
            ActionPrompt(text = stringResource(R.string.location_prompt), action = stringResource(R.string.location_allow), onAction = {})
        }
    }
}

/** Two routes, the first selected, with the note of a route that ends away from the place and of a simulated drive. */
@Preview(name = "routes", widthDp = 360)
@Preview(name = "routes, large font", widthDp = 360, fontScale = 1.3f)
@Preview(name = "routes, largest font", widthDp = 360, fontScale = 2f)
@Composable
private fun RouteSheetPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        RouteSheet(
            destination = Place("Gym", "8 Pine Street", GeoPoint(0.0, 0.0)),
            routes = listOf(
                RouteOption("a", 18.minutes, Distance.miles(9.6), "Oak Street, Elm Street", endGap = Distance.meters(140.0)),
                RouteOption("b", 21.minutes, Distance.miles(8.9), "Pine Street"),
            ),
            simulateDrive = true,
            onSelect = {},
            onStart = {},
            onClose = {},
        )
    }
}
